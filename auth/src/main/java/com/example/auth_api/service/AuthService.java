package com.example.auth_api.service;

import com.example.auth_api.model.Token;
import com.example.auth_api.repository.TokenRepository;
import com.example.auth_api.model.TokenType;

import org.springframework.stereotype.Service;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.auth_api.model.User;
import com.example.auth_api.model.Role;
import com.example.auth_api.repository.UserRepository;
import com.example.auth_api.config.JwtService;
import com.example.auth_api.model.AuthRequest;
import com.example.auth_api.model.AuthResponse;
import com.example.auth_api.model.RegisterRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

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

    saveUserToken(savedUser, token);
    
    return AuthResponse.builder()
      .token(token)
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
    
    revokeAllUserTokens(user);
    saveUserToken(user, token);
    
    return AuthResponse.builder()
      .token(token)
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
  
}
