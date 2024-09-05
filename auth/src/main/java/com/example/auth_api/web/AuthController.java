package com.example.auth_api.web;

import com.example.auth_api.model.request.AuthRequest;
import com.example.auth_api.model.response.AuthResponse;
import com.example.auth_api.model.request.RegisterRequest;
import com.example.auth_api.service.AuthService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * The AuthController class handles the authentication and registration endpoints for the API.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * Registers a new user.
   *
   * @param request the register request containing user information
   * @return the response entity with the authentication response
   */
  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
    AuthResponse response = authService.register(request);
    return ResponseEntity.ok(response);
  }

    /**
   * Authenticates a user based on the provided authentication request.
   *
   * @param request the authentication request containing user credentials
   * @return a ResponseEntity containing the authentication response
   */
  @PostMapping("/authenticate")
  public ResponseEntity<AuthResponse> authenticate(@RequestBody AuthRequest request) {
    AuthResponse response = authService.authenticate(request);
    return ResponseEntity.ok(response);
  }

  /**
   * Refreshes the authentication token.
   *
   * @param request  the HTTP request
   * @param response the HTTP response
   * @throws IOException if an I/O error occurs
   */
  @PostMapping("/refresh-token")
  public void refreshToken(
      HttpServletRequest request,
      HttpServletResponse response
  ) throws IOException {
    authService.refreshToken(request, response);
  }
}