package com.example.auth_api.service;

import com.example.auth_api.model.Token;
import com.example.auth_api.model.TokenType;
import com.example.auth_api.model.User;
import com.example.auth_api.model.Role;
import com.example.auth_api.model.request.RegisterRequest;
import com.example.auth_api.model.request.AuthRequest;
import com.example.auth_api.model.response.AuthResponse;
import com.example.auth_api.repository.TokenRepository;
import com.example.auth_api.repository.UserRepository;
import com.example.auth_api.service.JwtService;

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

  public boolean checkIfEmailExists(String email);
}
