package com.example.financetracker.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user, for the frontend: its first call, and the name in its header. */
@RestController
class MeController {

    record Me(String name) {
    }

    @GetMapping("/api/me")
    Me me(Authentication authentication) {
        return new Me(CurrentUserConverter.displayName((Jwt) authentication.getCredentials()));
    }
}
