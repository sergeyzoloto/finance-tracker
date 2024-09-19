package com.example.auth_api.web;

import com.example.auth_api.model.request.AuthRequest;
import com.example.auth_api.model.response.AuthResponse;
import com.example.auth_api.model.request.RegisterRequest;
import com.example.auth_api.service.AuthService;
import com.example.auth_api.model.response.StandardResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import io.jsonwebtoken.ExpiredJwtException;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * The AuthController class handles the authentication and registration endpoints for the API.
 */
@RestController
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
  public ResponseEntity<StandardResponse<AuthResponse>> register(@RequestBody RegisterRequest request) {
    try {
      AuthResponse response = authService.register(request);
      return ResponseEntity.ok(
        StandardResponse.<AuthResponse>builder()
          .success(true)
          .msg("User registered successfully.")
          .payload(response)
          .build()
      );
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(
        StandardResponse.<AuthResponse>builder()
          .success(false)
          .msg(e.getMessage())  // Capture the specific error message here
          .build()
      );
    }
  }

  /**
   * Authenticates a user based on the provided authentication request.
   *
   * @param request the authentication request containing user credentials
   * @return a ResponseEntity containing the authentication response
   */
  @PostMapping("/authenticate")
  public ResponseEntity<StandardResponse<AuthResponse>> authenticate(@RequestBody AuthRequest request) {
    AuthResponse response = authService.authenticate(request);
    return ResponseEntity.ok(
      StandardResponse.<AuthResponse>builder()
        .success(true)
        .msg("Authentication successful.")
        .payload(response)
        .build()
    );
  }

  /**
   * Refreshes the authentication token.
   *
   * @param request the refresh token request containing the refresh token
   * @return a ResponseEntity containing the authentication response
   */
  @PostMapping("/refresh-token")
  public ResponseEntity<StandardResponse<AuthResponse>> refreshToken(@RequestBody Map<String, String> request) {
    String refreshToken = request.get("refreshToken");
    if (refreshToken == null || refreshToken.isEmpty()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
        StandardResponse.<AuthResponse>builder()
          .success(false)
          .msg("Refresh token cannot be null or empty.")
          .payload(null)
          .build()
      );
    }

    AuthResponse response = authService.refreshToken(refreshToken);
    return ResponseEntity.ok(
      StandardResponse.<AuthResponse>builder()
        .success(true)
        .msg("Token refreshed successfully.")
        .payload(response)
        .build()
    );
  }

  /**
   * Checks if an email exists in the database.
   *
   * @param email the email to check
   * @return a ResponseEntity containing a boolean indicating if the email exists
   */
  @GetMapping("/email/{email}")
  public ResponseEntity<StandardResponse<Boolean>> checkIfEmailExists(@PathVariable String email) {
    boolean exists = authService.checkIfEmailExists(email);
    return ResponseEntity.ok(
      StandardResponse.<Boolean>builder()
        .success(true)
        .msg(exists ? "Email exists." : "Email does not exist.")
        .payload(exists)
        .build()
    );
  }
}
