package com.example.auth_api.web;

import com.example.auth_api.model.request.ChangePasswordRequest;
import com.example.auth_api.model.request.DeleteUserRequest;
import com.example.auth_api.model.response.StandardResponse;
import com.example.auth_api.service.UserService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * The UserController class handles HTTP requests related to user operations.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

  private final UserService service;

  /**
   * Deletes the user with the given ID.
   * @param request       the delete user request containing the user ID
   * @param connectedUser the principal representing the connected user
   * @return            a ResponseEntity indicating the success of the user deletion
   */
  @DeleteMapping("/delete")
  public ResponseEntity<StandardResponse<Void>> deleteUser(
    @RequestBody DeleteUserRequest request,
    Principal connectedUser
  ) {
    try {
      if (connectedUser == null) {
        return ResponseEntity.status(401).body(
          StandardResponse.<Void>builder()
            .success(false)
            .msg("Unauthorized: Principal is null")
            .build()
        );
      }
      service.deleteUser(request, connectedUser);
      return ResponseEntity.ok(
        StandardResponse.<Void>builder()
          .success(true)
          .msg("User deleted successfully.")
          .build()
      );
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(
        StandardResponse.<Void>builder()
          .success(false)
          .msg("An error occurred: " + e.getMessage())
          .build()
      );
    }
  }

  /**
   * Changes the password for the connected user.
   *
   * @param request        the change password request containing the new password
   * @param connectedUser  the principal representing the connected user
   * @return               a ResponseEntity indicating the success of the password change
   */
  @PatchMapping("/password")
  public ResponseEntity<StandardResponse<Void>> changePassword(
    @RequestBody ChangePasswordRequest request,
    Principal connectedUser
  ) {
    try {
    if (connectedUser == null) {
      return ResponseEntity.status(401).body(
        StandardResponse.<Void>builder()
          .success(false)
          .msg("Unauthorized: Principal is null")
          .build()
      );
    }
    service.changePassword(request, connectedUser);
    return ResponseEntity.ok(
      StandardResponse.<Void>builder()
        .success(true)
        .msg("Password changed successfully.")
        .build()
    );
    } catch (Exception e) {
      return ResponseEntity.badRequest().body(
        StandardResponse.<Void>builder()
          .success(false)
          .msg("An error occurred: " + e.getMessage())
          .build()
      );
    }
  }
}
