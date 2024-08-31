package com.example.auth_api.auth;

import com.example.auth_api.auth.AuthRequest;
import com.example.auth_api.auth.AuthResponse;
import com.example.auth_api.auth.RegisterRequest;
import com.example.auth_api.auth.AuthService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
    AuthResponse response = authService.register(request);
    return ResponseEntity.ok(response);
  }

  @PostMapping("/authenticate")
  public ResponseEntity<AuthResponse> authenticate(@RequestBody AuthRequest request) {
    AuthResponse response = authService.authenticate(request);
    return ResponseEntity.ok(response);
  }
}