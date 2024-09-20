package com.example.auth_api.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidateEmailTest {

  @Test
  public void testValidEmail() {
    assertTrue(ValidateEmail.isValid("test@test.com"));
  }

  @Test
  public void testInvalidEmail() {
    assertTrue(!ValidateEmail.isValid("invalid-email"));
  }

  @Test
  public void testNullEmail() {
    assertTrue(!ValidateEmail.isValid(null));
  }

  @Test
  public void testEmptyEmail() {
    assertTrue(!ValidateEmail.isValid(""));
  }

  @Test
  public void testEmptyEmailWithSpaces() {
    assertTrue(!ValidateEmail.isValid(" "));
  }

  @Test
  public void testEmailWithoutAt() {
    assertTrue(!ValidateEmail.isValid("test.com"));
  }

  @Test
  public void testEmailWithoutDot() {
    assertTrue(!ValidateEmail.isValid("test@test"));
  }

  @Test
  public void testEmailWithoutDomain() {
    assertTrue(!ValidateEmail.isValid("test@.com"));
  }

  @Test
  public void testEmailWithoutUsername() {
    assertTrue(!ValidateEmail.isValid("@test.com"));
  }

  @Test
  public void testEmailWithoutTLD() {
    assertTrue(!ValidateEmail.isValid("test@test."));
  }

  @Test
  public void testEmailWithoutTLDAndDomain() {
    assertTrue(!ValidateEmail.isValid("test@"));
  }

  @Test
  public void testEmailWithoutUsernameAndDomain() {
    assertTrue(!ValidateEmail.isValid("@"));
  }
  
}
