package com.example.auth_api.model;

import com.example.auth_api.model.Token;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.userdetails.UserDetails;

/**
 * The User class represents a user entity.
 */
@Data // Lombok annotation to create all the getters, setters, equals, hash, and toString methods
@Builder // Lombok annotation: builder pattern
@AllArgsConstructor // Lombok annotation: constructor with all arguments
@NoArgsConstructor // Lombok annotation: default constructor
@Entity // JPA annotation to make this class ready for storage in a JPA-based data store
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class User implements UserDetails {

  @Id // JPA annotation to specify the primary key of an entity
  @GeneratedValue() // JPA annotation to specify the primary key generation strategy
  private Integer id;

  private String firstname;

  private String lastname;

  private String email;

  private String password;

  @Enumerated(EnumType.STRING) // JPA annotation to specify the enum type
  private Role role;

  @OneToMany(mappedBy = "user", cascade = CascadeType.REMOVE, orphanRemoval = true)
  private List<Token> tokens;

  public User(String email, String password) {
    this.email = email;
    this.password = password;
  }

  public User(String firstname, String lastname, String email, String password) {
    this.firstname = firstname;
    this.lastname = lastname;
    this.email = email;
    this.password = password;
  }

  public User(String firstname, String email, String password) {
    this.firstname = firstname;
    this.email = email;
    this.password = password;
  }

  public String getName() {
    return this.firstname + " " + this.lastname;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority(role.name()));
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public String getPassword() {
    return password;
  }

}
