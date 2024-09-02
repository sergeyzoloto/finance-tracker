package com.example.auth_api.service;

import com.example.auth_api.model.Token;
import com.example.auth_api.repository.TokenRepository;
import com.example.auth_api.model.TokenType;

import org.springframework.stereotype.Service;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import com.example.auth_api.model.User;
import com.example.auth_api.model.Role;
import com.example.auth_api.repository.UserRepository;
import com.example.auth_api.config.JwtService;
import com.example.auth_api.model.AuthRequest;
import com.example.auth_api.model.AuthResponse;
import com.example.auth_api.model.RegisterRequest;

import lombok.RequiredArgsConstructor;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public interface AuthService {

  public AuthResponse register(RegisterRequest request);

  public AuthResponse authenticate(AuthRequest request);

  public void refreshToken(
          HttpServletRequest request,
          HttpServletResponse response
  ) throws IOException;
}
