package com.example.auth_api.repository;

import com.example.auth_api.model.User;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The UserRepository interface provides methods to interact with the user table.
 */
public interface UserRepository extends JpaRepository<User, Integer>{

  Optional<User> findByEmail(String email);
  boolean existsByEmail(String email);
  
}
