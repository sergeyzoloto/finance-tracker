package com.example.auth_api.service.impl;

import com.example.auth_api.model.Token;
import com.example.auth_api.repository.TokenRepository;
import com.example.auth_api.model.TokenType;

import com.example.auth_api.service.AuthService;

import com.example.auth_api.model.User;
import com.example.auth_api.repository.UserRepository;
import com.example.auth_api.model.Role;

import com.example.auth_api.model.request.AuthRequest;
import com.example.auth_api.model.response.AuthResponse;
import com.example.auth_api.model.request.RegisterRequest;

import com.example.auth_api.service.JwtService;

import org.springframework.stereotype.Service;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Service implementation for authentication operations.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private final UserRepository userRepository;
  private final TokenRepository tokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuthenticationManager authenticationManager;
  

  /**
   * Registers a new user with the provided registration request.
   * 
   * @param request The registration request containing user details.
   * @return The authentication response containing access and refresh tokens.
   * @throws IllegalArgumentException If a user with the same email already exists.
   */
  public AuthResponse register(RegisterRequest request) {

    if (userRepository.findByEmail(request.getEmail()).isPresent()) {
      throw new IllegalArgumentException("User with this email already exists");
    }

    var user = User.builder()
      .firstname(request.getFirstname())
      .lastname(request.getLastname())
      .email(request.getEmail())
      .password(passwordEncoder.encode(request.getPassword()))
      .role(Role.USER)
      .build();

    var savedUser = userRepository.save(user);
    var token = jwtService.generateToken(user);
    var refreshToken = jwtService.generateRefreshToken(user);

    saveUserToken(savedUser, token);
    
    return AuthResponse.builder()
      .accessToken(token)
      .refreshToken(refreshToken)
      .build();
  }

  /**
   * Authenticates the user with the provided credentials.
   * 
   * @param request the authentication request containing the user's email and password
   * @return an AuthResponse object containing the access token and refresh token
   */
  public AuthResponse authenticate(AuthRequest request) {
    
    var authentication = new UsernamePasswordAuthenticationToken(
      request.getEmail(),
      request.getPassword()
    );
    
    authenticationManager.authenticate(authentication);
    
    var user = userRepository.findByEmail(request.getEmail()).orElseThrow();
    var token = jwtService.generateToken(user);
    var refreshToken = jwtService.generateRefreshToken(user);
    
    revokeAllUserTokens(user);
    saveUserToken(user, token);
    
    return AuthResponse.builder()
    .accessToken(token)
    .refreshToken(refreshToken)
      .build();
  }

  /**
   * Saves the user token in the token repository.
   *
   * @param user The user for whom the token is being saved.
   * @param token The token to be saved.
   */
  private void saveUserToken(User user, String token) {
  
    var newToken = Token.builder()
        .user(user)
        .token(token)
        .tokenType(TokenType.BEARER)
        .expired(false)
        .revoked(false)
        .build();
    
    tokenRepository.save(newToken);
  }

  /**
   * Revokes all tokens associated with the given user.
   *
   * @param user the user for whom to revoke tokens
   */
  private void revokeAllUserTokens(User user) {
  
    var validUserTokens = tokenRepository.findAllValidTokenByUser(user.getId());
  
    if (validUserTokens.isEmpty())
      return;
    
    validUserTokens.forEach(token -> {
      token.setExpired(true);
      token.setRevoked(true);
    });
    
    tokenRepository.saveAll(validUserTokens);
  }

  /**
   * Refreshes the access token using the provided refresh token.
   * 
   * @param request The HttpServletRequest object representing the incoming request.
   * @param response The HttpServletResponse object representing the outgoing response.
   * @throws IOException If an I/O error occurs while writing the response.
   */
  public void refreshToken(
          HttpServletRequest request,
          HttpServletResponse response
  ) throws IOException {

    final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    final String refreshToken;
    final String userEmail;

    if (authHeader == null ||!authHeader.startsWith("Bearer ")) {
      return;
    }

    refreshToken = authHeader.substring(7);
    userEmail = jwtService.extractUsername(refreshToken);

    if (userEmail != null) {
      var user = userRepository.findByEmail(userEmail)
        .orElseThrow();
      if (jwtService.validateToken(refreshToken, user)) {

        var accessToken = jwtService.generateToken(user);

        revokeAllUserTokens(user);
        saveUserToken(user, accessToken);

        var authenticationResponse = AuthResponse.builder()
          .accessToken(accessToken)
          .refreshToken(refreshToken)
          .build();
        new ObjectMapper().writeValue(response.getOutputStream(), authenticationResponse);
      }
    }
  }
  
}
