package com.example.auth_api.service.impl;

import com.example.auth_api.model.Token;
import com.example.auth_api.repository.TokenRepository;
import com.example.auth_api.model.TokenType;

import com.example.auth_api.service.AuthService;

import com.example.auth_api.model.User;
import com.example.auth_api.repository.UserRepository;
import com.example.auth_api.model.Role;

import com.example.auth_api.model.AuthRequest;
import com.example.auth_api.model.AuthResponse;
import com.example.auth_api.model.RegisterRequest;

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

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  private final UserRepository userRepository;
  private final TokenRepository tokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuthenticationManager authenticationManager;
  

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
