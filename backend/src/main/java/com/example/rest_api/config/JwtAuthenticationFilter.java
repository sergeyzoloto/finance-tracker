package com.example.rest_api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
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
  
  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
      final String authorizationHeader = request.getHeader("Authorization");
      final String jwt;
      final String username;

      if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
        filterChain.doFilter(request, response);
        return;
      }

      jwt = authorizationHeader.substring(7);
      username = jwtService.extractUsername(jwt);
  }
}