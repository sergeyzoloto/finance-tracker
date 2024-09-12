package com.example.authproxy.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AuthService {

    private final RestTemplate restTemplate;

    @Value("${AUTH_SERVER_URL}")
    private String authServiceUrl;

    public AuthService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean validateToken(String token) {
        String validationUrl = authServiceUrl + "/validate-token";
        return restTemplate.postForObject(validationUrl, token, Boolean.class);
    }
}
