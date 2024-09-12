package com.example.authproxy.controller;

import com.example.authproxy.service.AuthService;
import com.example.authproxy.service.CacheService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class AuthProxyController {

    private final AuthService authService;
    private final CacheService cacheService;
    private final RestTemplate restTemplate;

    @Value("${TRANSACTIONS_SERVER_URL}")
    private String transactionsServiceUrl;

    public AuthProxyController(AuthService authService, CacheService cacheService, RestTemplate restTemplate) {
        this.authService = authService;
        this.cacheService = cacheService;
        this.restTemplate = restTemplate;
    }

    @RequestMapping(value = "/transactions/**", method = {RequestMethod.GET, RequestMethod.POST})
    public Object proxyTransactions(HttpServletRequest request, @RequestBody(required = false) String body) {
        String token = request.getHeader("Authorization");
        System.out.println("Token in Proxy: " + token);  // Log the token for debugging

        // Check if token is in cache
        if (!cacheService.isTokenValid(token)) {
            // Validate token with auth service
            if (!authService.validateToken(token)) {
            System.out.println("Unauthorized in Proxy");  // Log unauthorized attempts
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
          }
        cacheService.cacheToken(token);
      }

      // Determine the URL to forward the request to
      String url = transactionsServiceUrl + request.getRequestURI().substring("/transactions".length());

      // Forward the request to transactions service
    if (request.getMethod().equals(HttpMethod.GET.name())) {
      return restTemplate.getForObject(url, Object.class);
      } else if (request.getMethod().equals(HttpMethod.POST.name())) {
      return restTemplate.postForObject(url, body, Object.class);
    } else {
      return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body("Method not allowed");
    }
  }
}