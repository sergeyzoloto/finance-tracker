package com.example.financetracker.security;

import java.time.LocalDate;

import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.UserSettingsRepository;
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
     * @param timeZone the user's IANA time zone (D-100, D-101), null until it has been set
     * @param today the date in that zone (UTC while it is null), D-53's today, which the frontend uses for every
     *        default date and never works out from the browser's clock
     * @param features which features the app has switched on, so that the frontend shows only those
     */
    record Me(String name, String email, String timeZone, LocalDate today, Features features) {
    }

    /** @param familyLedgers the family budget (D-25): off until F7 in production */
    record Features(boolean familyLedgers) {
    }

    private final FamilySwitch family;
    private final Today today;
    private final UserSettingsRepository settings;

    MeController(FamilySwitch family, Today today, UserSettingsRepository settings) {
        this.family = family;
        this.today = today;
        this.settings = settings;
    }

    @GetMapping("/api/me")
    Me me(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getCredentials();
        String sub = jwt.getSubject();
        return new Me(CurrentUserResolver.displayName(jwt), jwt.getClaimAsString("email"),
                settings.findTimeZone(sub).orElse(null), today.date(sub), new Features(family.enabled()));
    }
}
