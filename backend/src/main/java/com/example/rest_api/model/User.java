package com.example.rest_api.model;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Data // Lombok annotation to create all the getters, setters, equals, hash, and toString methods
@Builder // Lombok annotation: builder pattern
@AllArgsConstructor // Lombok annotation: constructor with all arguments
@NoArgsConstructor // Lombok annotation: default constructor
@Entity // JPA annotation to make this class ready for storage in a JPA-based data store
@Table(name = "USERS") // JPA annotation to specify the table that this entity is mapped to
public class User {

  @Id // JPA annotation to specify the primary key of an entity
  @GeneratedValue() // JPA annotation to specify the primary key generation strategy
  private Integer id;

  private String firstname;

  private String lastname;

  private String email;

  private String password;

}
