package com.example.auth_api.service;

import com.example.auth_api.model.request.ChangePasswordRequest;
import com.example.auth_api.model.request.DeleteUserRequest;

import java.security.Principal;

public interface UserService {

  public void changePassword(ChangePasswordRequest request, Principal connectedUser);

  public void deleteUser(DeleteUserRequest request, Principal connectedUser);

}