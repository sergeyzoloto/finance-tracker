package com.example.auth_api.repository;

import com.example.auth_api.model.Token;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The TokenRepository interface provides methods to interact with the token table.
 */
public interface TokenRepository extends JpaRepository<Token, Integer> {

  final String FIND_ALL_VALID_TOKEN_BY_USER_REQUEST = """
    SELECT token FROM Token token\s
    INNER JOIN User users\s
      ON token.user.id = users.id\s
    WHERE\s
      users.id = :id\s
      AND (token.expired = FALSE\s
      OR token.revoked = FALSE)\s
    """;

  @Query(value = FIND_ALL_VALID_TOKEN_BY_USER_REQUEST)
  List<Token> findAllValidTokenByUser(@Param("id") Integer id);

  Optional<Token> findByToken(String token);
}