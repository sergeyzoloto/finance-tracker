package com.example.auth_api.service;

import com.example.auth_api.model.User;
import com.example.auth_api.model.request.ChangePasswordRequest;
import com.example.auth_api.repository.UserRepository;
import com.example.auth_api.service.impl.UserServiceImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

public class UserServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  private AutoCloseable autoCloseable;
  private UserServiceImpl underTest;
  static final String PASSWORD = "password";
  static final String NEW_PASSWORD = "newPassword";
  static final String ENCODED_NEW_PASSWORD = "encodedNewPassword";

  @BeforeEach
  public void setUp() {
    autoCloseable = MockitoAnnotations.openMocks(this);
    underTest = new UserServiceImpl(passwordEncoder, userRepository);
  }

  @AfterEach
  public void tearDown() throws Exception {
    autoCloseable.close();
  }

  @Test
  public void changePassword() {
    // Given
    ChangePasswordRequest request = new ChangePasswordRequest(
      PASSWORD,
      NEW_PASSWORD,
      NEW_PASSWORD
    );

    User user = new User();
    user.setPassword(PASSWORD);

    Principal principal = new UsernamePasswordAuthenticationToken(user, null);

    // Mock password encoder behavior
    when(passwordEncoder.matches(PASSWORD, PASSWORD)).thenReturn(true);
    when(passwordEncoder.matches(NEW_PASSWORD, ENCODED_NEW_PASSWORD)).thenReturn(true);
    when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(ENCODED_NEW_PASSWORD);

    // When
    underTest.changePassword(request, principal);

    // Then
    assertEquals(ENCODED_NEW_PASSWORD, user.getPassword());
    assertTrue(passwordEncoder.matches(NEW_PASSWORD, user.getPassword()));
    verify(userRepository).save(user);
  }

  @Test
  public void changePasswordWithInvalidCurrentPasswordShouldThrowException() {
    ChangePasswordRequest request = new ChangePasswordRequest(
      "wrongPassword",
      NEW_PASSWORD,
      NEW_PASSWORD
    );

    User user = new User();
    user.setPassword(PASSWORD);

    Principal principal = new UsernamePasswordAuthenticationToken(user, null);

    // Mock password encoder behavior
    when(passwordEncoder.matches("wrongPassword", PASSWORD)).thenReturn(false);

    // When & Then
    assertThrows(IllegalStateException.class, () -> {
      underTest.changePassword(request, principal);
    });
  }

  @Test
  public void changePasswordWithNonMatchingNewPasswordsShouldThrowException() {
    // Given
    ChangePasswordRequest request = new ChangePasswordRequest(
      PASSWORD,
      NEW_PASSWORD,
      "differentNewPassword"
    );

    User user = new User();
    user.setPassword(PASSWORD);

    Principal principal = new UsernamePasswordAuthenticationToken(user, null);

    // Mock password encoder behavior
    when(passwordEncoder.matches(PASSWORD, PASSWORD)).thenReturn(true);

    // When & Then
    assertThrows(IllegalStateException.class, () -> {
        underTest.changePassword(request, principal);
    });
  }

  @Test
  public void changePasswordWithInvalidPrincipalTypeShouldThrowException() {
    // Given
    ChangePasswordRequest request = new ChangePasswordRequest(
      PASSWORD,
      NEW_PASSWORD,
      NEW_PASSWORD
    );

    Principal principal = mock(Principal.class);

    // When & Then
    assertThrows(IllegalArgumentException.class, () -> {
      underTest.changePassword(request, principal);
    });
  }

  @Test
  public void changePasswordWithNullOrEmptyPasswordsShouldThrowException() {
    // Given
    ChangePasswordRequest request1 = new ChangePasswordRequest(
      null,
      NEW_PASSWORD,
      NEW_PASSWORD
    );

    ChangePasswordRequest request2 = new ChangePasswordRequest(
      PASSWORD,
      null,
      NEW_PASSWORD
    );

    ChangePasswordRequest request3 = new ChangePasswordRequest(
      PASSWORD,
      NEW_PASSWORD,
      null
    );

    User user = new User();
    user.setPassword(PASSWORD);

    Principal principal = new UsernamePasswordAuthenticationToken(user, null);

    // When & Then
    assertThrows(IllegalStateException.class, () -> {
        underTest.changePassword(request1, principal);
    });

    assertThrows(IllegalStateException.class, () -> {
        underTest.changePassword(request2, principal);
    });

    assertThrows(IllegalStateException.class, () -> {
        underTest.changePassword(request3, principal);
    });
  }

}
