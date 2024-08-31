package com.example.auth_api.auth;

import org.springframework.stereotype.Service;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.auth_api.model.User;
import com.example.auth_api.model.Role;
import com.example.auth_api.repository.UserRepository;
import com.example.auth_api.config.JwtService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuthenticationManager authenticationManager;

  public AuthResponse register(RegisterRequest request) {
    var user = User.builder()
      .firstname(request.getFirstname())
      .lastname(request.getLastname())
      .email(request.getEmail())
      .password(passwordEncoder.encode(request.getPassword()))
      .role(Role.USER)
      .build();

    userRepository.save(user);

    var token = jwtService.generateToken(user);
    return AuthResponse.builder()
      .token(token)
      .build();
  }

  public AuthResponse authenticate(AuthRequest request) {
    var authentication = new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword());
    authenticationManager.authenticate(authentication);
    var user = userRepository.findByEmail(request.getEmail()).orElseThrow();
    var token = jwtService.generateToken(user);
    return AuthResponse.builder()
      .token(token)
      .build();
  }
  
}
