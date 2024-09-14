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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

public class UserServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  private AutoCloseable autoCloseable;
  private UserServiceImpl underTest;

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
    final String PASSWORD = "password";
    final String NEW_PASSWORD = "newPassword";
    final String ENCODED_NEW_PASSWORD = "encodedNewPassword";
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

}
