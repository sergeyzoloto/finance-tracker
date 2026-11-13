package com.example.financetracker.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
     * @param dateFormat how the user has the app show and take day dates (D-304): DD.MM.YYYY, DD/MM/YYYY, MM/DD/YYYY
     *        or YYYY-MM-DD, null for as the browser shows them
     * @param features which features the app has switched on, so that the frontend shows only those
     * @param accountUrl Keycloak's account console for this realm, with {@code referrer} this app's client, so that
     *        its "Back to Finance Tracker" returns to the client's base URL (D-619, UX-15, additive): the user menu's
     *        Account link, built here because only the backend knows the issuer
     */
    record Me(String name, String email, String timeZone, LocalDate today, String dateFormat, Features features,
            String accountUrl) {
    }

    /** @param familyLedgers the family budget (D-25): off until F7 in production */
    record Features(boolean familyLedgers) {
    }

    private final FamilySwitch family;
    private final Today today;
    private final UserSettingsRepository settings;
    private final String accountUrl;

    MeController(FamilySwitch family, Today today, UserSettingsRepository settings, KeycloakProperties keycloak) {
        this.family = family;
        this.today = today;
        this.settings = settings;
        this.accountUrl = accountUrl(keycloak.issuerUrl(), keycloak.clientId());
    }

    /**
     * The account console of the realm whose issuer is given (D-619): {@code <issuer>/account?referrer=<client id>},
     * the client id URL-encoded. Keycloak 26.7 turns {@code referrer} alone into the client's base URL for its "Back to"
     * link (AccountConsole#getReferrer), so no {@code referrer_uri} is sent and no redirect URI is needed.
     */
    static String accountUrl(String issuerUrl, String clientId) {
        String realm = issuerUrl.endsWith("/") ? issuerUrl.substring(0, issuerUrl.length() - 1) : issuerUrl;
        return realm + "/account?referrer=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
    }

    @GetMapping("/api/me")
    Me me(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getCredentials();
        String sub = jwt.getSubject();
        return new Me(CurrentUserResolver.displayName(jwt), jwt.getClaimAsString("email"),
                settings.findTimeZone(sub).orElse(null), today.date(sub), settings.findDateFormat(sub).orElse(null),
                new Features(family.enabled()), accountUrl);
    }
}
