package com.example.auth_api.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.auth_api.model.User;
import com.example.auth_api.repository.UserRepository;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * The UserRepositoryTest class contains tests for the UserRepository interface.
 */

@DataJpaTest
public class UserRepositoryTest {

  @Autowired
  private UserRepository userRepository;

  @Test
  public void testFindByEmail() {
    // Given
    String email = "test@email.com";
    User user = new User("Test", email, "password");
    userRepository.save(user);

    // When
    Optional<User> foundUser = userRepository.findByEmail(email);

    // Then
    assertTrue(foundUser.isPresent());

    User userFromDb = foundUser.get();

    assertEquals(user.getName(), userFromDb.getName());

    assertEquals(user.getEmail(), userFromDb.getEmail());

    assertEquals(user.getPassword(), userFromDb.getPassword());

    userRepository.delete(user);

  }

  @Test
  public void testExistsByEmail() {
    // Given
    String email = "test@email.com";
    User user = new User("Test", email, "password");
    userRepository.save(user);
    
    // When
    boolean exists = userRepository.existsByEmail(email);

    // Then
    assertTrue(exists);

    userRepository.delete(user);

  }

}

