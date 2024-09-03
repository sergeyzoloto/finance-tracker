package com.example.auth_api.service;

import com.example.auth_api.repository.TokenRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

public interface LogoutService {

  public void logout(
      HttpServletRequest request,
      HttpServletResponse response,
      Authentication authentication
  );
}