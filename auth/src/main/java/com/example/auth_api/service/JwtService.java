package com.example.auth_api.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;


@Service
public interface JwtService {

  public String extractUsername(String token);  

  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

  public String generateToken(UserDetails userDetails);

  public String generateToken(
      Map<String, Object> extraClaims,
      UserDetails userDetails
  );

  public String generateRefreshToken(
      UserDetails userDetails
  );

  public Boolean validateToken(String token, UserDetails userDetails);
  
}
