package com.example.rest_api.model;

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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.userdetails.UserDetails;

@Data // Lombok annotation to create all the getters, setters, equals, hash, and toString methods
@Builder // Lombok annotation: builder pattern
@AllArgsConstructor // Lombok annotation: constructor with all arguments
@NoArgsConstructor // Lombok annotation: default constructor
@Entity // JPA annotation to make this class ready for storage in a JPA-based data store
@Table(name = "USERS") // JPA annotation to specify the table that this entity is mapped to
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
