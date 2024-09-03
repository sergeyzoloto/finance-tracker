package com.example.auth_api.web;

import com.example.auth_api.model.ChangePasswordRequest;
import com.example.auth_api.service.UserService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * The UserController class handles HTTP requests related to user operations.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

  private final UserService service;

  /**
   * Changes the password for the connected user.
   *
   * @param request        the change password request containing the new password
   * @param connectedUser  the principal representing the connected user
   * @return               a ResponseEntity indicating the success of the password change
   */
  @PatchMapping
  public ResponseEntity<?> changePassword(
    @RequestBody ChangePasswordRequest request,
    Principal connectedUser
  ) {
    service.changePassword(request, connectedUser);
    return ResponseEntity.ok().build();
  }
}