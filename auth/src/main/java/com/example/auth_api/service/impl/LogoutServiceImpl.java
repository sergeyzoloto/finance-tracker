package com.example.auth_api.service.impl;

import com.example.auth_api.repository.TokenRepository;
import com.example.auth_api.service.LogoutService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Service;

/**
 * Implementation of the LogoutHandler and LogoutService interfaces.
 * This class is responsible for handling the logout functionality.
 */
@Service
@RequiredArgsConstructor
public class LogoutServiceImpl implements LogoutHandler, LogoutService {

  private final TokenRepository tokenRepository;

  /**
   * Logs out the user by invalidating the token.
   *
   * @param request         the HTTP request
   * @param response        the HTTP response
   * @param authentication  the authentication object
   */
  @Override
  public void logout(
      HttpServletRequest request,
      HttpServletResponse response,
      Authentication authentication
  ) {

    final String authHeader = request.getHeader("Authorization");
    final String jwt;

    if (authHeader == null ||!authHeader.startsWith("Bearer ")) {
      return;
    }

    jwt = authHeader.substring(7);
    
    var storedToken = tokenRepository.findByToken(jwt)
        .orElse(null);

    if (storedToken != null) {
      storedToken.setExpired(true);
      storedToken.setRevoked(true);
      tokenRepository.save(storedToken);
      SecurityContextHolder.clearContext();
    }
  }
}