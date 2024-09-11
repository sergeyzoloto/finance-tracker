package com.example.auth_api.utils;

public class ValidateEmail {

  private ValidateEmail() {
    // Private constructor to hide the implicit public one
  }

  public static boolean isValid(String email) {
      if (email == null || email.isEmpty()) {
          return false;
      }
      return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
  }
  
}