package com.example.auth_api.service.impl;

import com.example.auth_api.model.ChangePasswordRequest;
import com.example.auth_api.model.User;
import com.example.auth_api.repository.UserRepository;
import com.example.auth_api.service.UserService;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.Principal;

/**
 * Service implementation for user operations.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

  private final PasswordEncoder passwordEncoder;
  private final UserRepository repository;
  
  /**
   * Changes the password for the connected user.
   *
   * @param request        the change password request containing the new password
   * @param connectedUser  the principal representing the connected user
   */
  public void changePassword(ChangePasswordRequest request, Principal connectedUser) {

    var user = (User) ((UsernamePasswordAuthenticationToken) connectedUser).getPrincipal();

    // check if the current password is correct
    if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
        throw new IllegalStateException("Wrong password");
    }
    // check if the two new passwords are the same
    if (!request.getNewPassword().equals(request.getConfirmationPassword())) {
        throw new IllegalStateException("Password are not the same");
    }

    // update the password
    user.setPassword(passwordEncoder.encode(request.getNewPassword()));

    // save the new password
    repository.save(user);
  }
}