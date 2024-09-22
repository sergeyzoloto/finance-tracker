package com.example.auth_api.config;

import com.example.auth_api.repository.TokenRepository;
import com.example.auth_api.service.JwtService;
import com.example.auth_api.model.response.StandardResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final UserDetailsService userDetailsService;
  private final TokenRepository tokenRepository;
  private final ObjectMapper objectMapper = new ObjectMapper();  // To convert StandardResponse to JSON

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain
  ) throws ServletException, IOException {

    final String authorizationHeader = request.getHeader("Authorization");
    final String jwt;
    final String userEmail;

    // Check if the Authorization header contains a valid Bearer token
    if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
        filterChain.doFilter(request, response);
        return;
    }

    // Extract the JWT and email from the token
    jwt = authorizationHeader.substring(7);  // Remove "Bearer " prefix
    if (jwt == null || jwt.isEmpty()) {
      writeErrorResponse(response, HttpServletResponse.SC_BAD_REQUEST, "JWT cannot be null or empty");
      return;
    }

    try {
      userEmail = jwtService.extractUsername(jwt);

      // Proceed if the token contains a username and there is no authenticated user in the context
      if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);

        // Find the token in the database
        var token = tokenRepository.findByToken(jwt).orElse(null);

        if (token == null) {
          writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token not found in the database");
          return;
        }

        // Validate if the JWT is valid
        boolean isJwtValid = jwtService.validateToken(jwt, userDetails);
        if (!isJwtValid) {
          writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid JWT token");
          return;
        }

        // Check if the JWT is expired
        boolean isJwtExpired = jwtService.isTokenExpired(jwt);
        if (isJwtExpired) {
          writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "JWT token is expired");
          return;
        }

        // Check if the token is revoked
        boolean isTokenRevoked = token.isRevoked();
        if (isTokenRevoked) {
          writeErrorResponse(
            response, 
            HttpServletResponse.SC_UNAUTHORIZED, 
            "JWT token is revoked"
            );
          return;
        }

        // If all checks pass (JWT is valid, not expired, and not revoked), set up authentication
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
            userDetails, null, userDetails.getAuthorities()
        );
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
      }
    } catch (UsernameNotFoundException e) {
      // Handle the case where the user has been deleted or not found
      writeErrorResponse(
        response, 
        HttpServletResponse.SC_UNAUTHORIZED, 
        "User not found: " + e.getMessage()
        );
      return;
    }

    // Continue with the filter chain
    filterChain.doFilter(request, response);
  }

  // Helper method to write JSON error responses
  private void writeErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    StandardResponse<Void> errorResponse = StandardResponse.<Void>builder()
      .success(false)
      .msg(message)
      .payload(null)
      .build();
    response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
  }
}
