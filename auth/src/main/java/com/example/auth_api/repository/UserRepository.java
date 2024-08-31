package com.example.auth_api.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.auth_api.model.User;

public interface UserRepository extends JpaRepository<User, Integer>{

  Optional<User> findByEmail(String email);
  
}
