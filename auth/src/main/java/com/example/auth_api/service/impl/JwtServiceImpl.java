package com.example.auth_api.service.impl;

import com.example.auth_api.service.JwtService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.ExpiredJwtException;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/**
 * This class is an implementation of the JwtService interface.
 * It provides methods for generating and validating JSON Web Tokens (JWTs).
 */
@Service
public class JwtServiceImpl implements JwtService {

  @Value("${JSON_WEB_TOKEN_SECRET}")
  private String secretKey;
  @Value("${JSON_WEB_TOKEN_EXPIRATION_TIME}")
  private long jwtExpiration;
  @Value("${JSON_WEB_TOKEN_REFRESH_EXPIRATION_TIME}")
  private long refreshExpiration;

  /**
   * Extracts the username from the given token.
   *
   * @param token the JWT token from which to extract the username
   * @return the username extracted from the token
   */
  public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  /**
   * Extracts a claim from the given JWT token using the provided claims
   * resolver function.
   *
   * @param token The JWT token from which to extract the claim.
   * @param claimsResolver The function that resolves the desired claim
   *  from the JWT claims.
   * @param <T> The type of the claim to be extracted.
   * @return The extracted claim.
   */
  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = extractAllClaims(token);
    return claimsResolver.apply(claims);
  }

  /**
   * Generates a token for the given user details.
   *
   * @param userDetails the user details for which the token is generated
   * @return the generated token
   */
  public String generateToken(UserDetails userDetails) {
    return generateToken(new HashMap<>(), userDetails);
  }

  /**
   * Generates a JWT token with the given extra claims and user details.
   *
   * @param extraClaims   the extra claims to include in the token
   * @param userDetails  the user details used to generate the token
   * @return the generated JWT token
   */
  public String generateToken(
      Map<String, Object> extraClaims,
      UserDetails userDetails
  ) {
    return buildToken(extraClaims, userDetails, jwtExpiration);
  }

  /**
   * Generates a refresh token for the given user details.
   *
   * @param userDetails the user details for whom the refresh token is generated
   * @return the generated refresh token
   */
  public String generateRefreshToken(
      UserDetails userDetails
  ) {
    return buildToken(new HashMap<>(), userDetails, refreshExpiration);
  }

  /**
   * Builds a JWT token with the given extra claims, user details, 
   *  and expiration time.
   *
   * @param extraClaims   the extra claims to include in the token
   * @param userDetails  the user details used to set the subject of the token
   * @param expiration    the expiration time of the token in milliseconds
   * @return the generated JWT token
   */
  private String buildToken(
          Map<String, Object> extraClaims,
          UserDetails userDetails,
          long expiration
  ) {
    return Jwts
      .builder()
      .setClaims(extraClaims)
      .setSubject(userDetails.getUsername())
      .setIssuedAt(new Date(System.currentTimeMillis()))
      .setExpiration(new Date(System.currentTimeMillis() + expiration))
      .signWith(getSignInKey(), SignatureAlgorithm.HS256)
      .compact();
  }

  /**
   * Validates the given token against the provided user details.
   *
   * @param token the JWT token to validate
   * @param userDetails the user details to validate against
   * @return true if the token is valid for the user, false otherwise
   */
  public Boolean validateToken(String token, UserDetails userDetails) {
    final String username = extractUsername(token);
    return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
  }

  /**
   * Checks if the given token is expired.
   *
   * @param token the JWT token to check
   * @return true if the token is expired, false otherwise
   */
  public Boolean isTokenExpired(String token) {
    try {
      Date expirationDate = extractExpiration(token);
      return expirationDate.before(new Date());
    } catch (ExpiredJwtException e) {
      return true;  // The token has expired
    }
  }

  /**
   * Extracts the expiration date from the given token.
   *
   * @param token the JWT token from which to extract the expiration date
   * @return the expiration date extracted from the token
   */
  private Date extractExpiration(String token) {
    return extractClaim(token, Claims::getExpiration);
  }

  /**
   * Extracts all claims from the given token.
   *
   * @param token the JWT token from which to extract the claims
   * @return the claims extracted from the token
   */
  private Claims extractAllClaims(String token) {
    try {
      return Jwts.parser()
        .setSigningKey(getSignInKey())
        .build()
        .parseSignedClaims(token)
        .getPayload();
    } catch (ExpiredJwtException e) {
      return e.getClaims();
    }
   }
  
  /**
   * Gets the secret key used to sign the JWTs.
   * 
   * @return the secret key used to sign the JWTs
  */
  private SecretKey getSignInKey() {
    byte[] keyBytes = Decoders.BASE64.decode(secretKey);
    return Keys.hmacShaKeyFor(keyBytes);
  }
}
