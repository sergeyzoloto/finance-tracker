package com.example.auth_api.repository;

import com.example.auth_api.model.Token;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * The TokenRepository interface provides methods to interact with the token table.
 */
public interface TokenRepository extends JpaRepository<Token, Integer> {

  @Query(value = """
    select t from Token t inner join User u\s
    on t.user.id = u.id\s
    where u.id = :id and (t.expired = false or t.revoked = false)\s
    """)
  List<Token> findAllValidTokenByUser(Integer id);

  Optional<Token> findByToken(String token);
}