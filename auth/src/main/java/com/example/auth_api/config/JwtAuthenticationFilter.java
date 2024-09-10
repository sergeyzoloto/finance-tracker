package com.example.auth_api.config;

import com.example.auth_api.repository.TokenRepository;
import com.example.auth_api.service.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.TransactionScoped;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.security.Security;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import lombok.RequiredArgsConstructor;

/**
 * This class is responsible for filtering and authenticating JWT tokens in the request.
 * It extends the OncePerRequestFilter class from the Spring Security framework.
 * 
 * The doFilterInternal method is overridden to perform the actual filtering logic.
 * It passes the request and response objects along with the filter chain to the next filter in the chain.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final UserDetailsService userDetailsService;
  private final TokenRepository tokenRepository;

  /**
   * Filters the incoming HTTP request and authenticates the JWT token.
   * 
   * @param request The HTTP request object.
   * @param response The HTTP response object.
   * @param filterChain The filter chain object.
   * @throws ServletException If an error occurs during the filter operation.
   * @throws IOException If an I/O error occurs during the filter operation.
   */
  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain
  ) throws ServletException, IOException {

    if (request.getServletPath().contains("/auth")) {
      filterChain.doFilter(request, response);
      return;
    }
    final String authorizationHeader = request.getHeader("Authorization");
    final String jwt;
    final String username;

    if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }

    jwt = authorizationHeader.substring(7);
    username = jwtService.extractUsername(jwt);

    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

      UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

      var checkTokenValid = tokenRepository.findByToken(jwt)
          .map(t -> !t.isExpired() && !t.isRevoked())
          .orElse(false);
      
      if (jwtService.validateToken(jwt, userDetails) && checkTokenValid) {

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
            userDetails,
            null,
            userDetails.getAuthorities()
        );

        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authToken);
        
      }
    }
    filterChain.doFilter(request, response);
  }
}