package com.example.rest_api.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.rest_api.model.User;

public interface UserRepository extends JpaRepository<User, Integer>{

  Optional<User> findByEmail(String email);
  
}
