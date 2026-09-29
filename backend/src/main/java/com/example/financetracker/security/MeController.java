package com.example.financetracker.security;

import com.example.financetracker.ledger.family.FamilySwitch;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user, for the frontend: its first call, and the name in its header. */
@RestController
class MeController {

    /** @param features which features the app has switched on, so that the frontend shows only those */
    record Me(String name, Features features) {
    }

    /** @param familyLedgers the family budget (D-25): off until F7 in production */
    record Features(boolean familyLedgers) {
    }

    private final FamilySwitch family;

    MeController(FamilySwitch family) {
        this.family = family;
    }

    @GetMapping("/api/me")
    Me me(Authentication authentication) {
        return new Me(CurrentUserResolver.displayName((Jwt) authentication.getCredentials()),
                new Features(family.enabled()));
    }
}
