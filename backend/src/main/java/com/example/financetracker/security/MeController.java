package com.example.financetracker.security;

import com.example.financetracker.ledger.family.FamilySwitch;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user, for the frontend: its first call, and the name in its header. */
@RestController
class MeController {

    /**
     * @param email the signed-in account's email, the token's {@code email} claim; null if it has none (D-54, F8b,
     *        additive), which the end-to-end suite's identity check compares
     * @param features which features the app has switched on, so that the frontend shows only those
     */
    record Me(String name, String email, Features features) {
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
        Jwt jwt = (Jwt) authentication.getCredentials();
        return new Me(CurrentUserResolver.displayName(jwt), jwt.getClaimAsString("email"),
                new Features(family.enabled()));
    }
}
