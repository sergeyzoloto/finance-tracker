package com.example.auth_api.model.request;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class ChangePasswordRequest {

    private String currentPassword;
    private String newPassword;
    private String confirmationPassword;

    public ChangePasswordRequest(String currentPassword, String newPassword, String confirmationPassword) {
      this.currentPassword = currentPassword;
      this.newPassword = newPassword;
      this.confirmationPassword = confirmationPassword;
  }
    
}