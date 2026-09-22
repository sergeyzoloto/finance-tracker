package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jwt.JWTClaimsSet;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Bearer tokens, as services and curl send them: what a token needs to get in under the members-only model. */
class AccessTokenTests extends IntegrationTest {

    private final String subject = UUID.randomUUID().toString();

    @Autowired
    private JdbcClient jdbc;

    @Test
    void acceptsAMembersToken() throws Exception {
        MvcTestResult result = me(token(subject));

        assertThat(result).hasStatusOk();
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("name").asText()).isEqualTo("User " + subject);
    }

    @Test
    void allowsAMinuteOfClockSkewOnExpiryAndNotBefore() {
        Instant now = Instant.now();

        assertThat(me(sign(expiredAgo(now, 30)))).hasStatusOk();
        assertThat(me(sign(expiredAgo(now, 90)))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(me(sign(claims(subject).notBeforeTime(Date.from(now.plusSeconds(30)))))).hasStatusOk();
        assertThat(me(sign(claims(subject).notBeforeTime(Date.from(now.plusSeconds(90)))))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requiresThisClientInTheAudience() {
        // Keycloak names this client in "aud" only for users and services that hold one of its roles.
        assertThat(me(sign(claims(subject).audience("shop-api")))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(me(sign(claims(subject).audience((String) null)))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(me(sign(claims(subject).audience(FakeKeycloak.CLIENT_ID)))).hasStatusOk();
    }

    @Test
    void requiresThisClientsUserRole() {
        Map<String, Map<String, Object>> withoutUserRole = new LinkedHashMap<>();
        withoutUserRole.put("no roles", Map.of());
        withoutUserRole.put("realm role user", Map.of("realm_access", Map.of("roles", List.of("user"))));
        withoutUserRole.put("another client's admin and user roles",
                Map.of("resource_access", Map.of("shop-api", Map.of("roles", List.of("admin", "user")))));
        withoutUserRole.put("this client's admin role only",
                Map.of("resource_access", Map.of(FakeKeycloak.CLIENT_ID, Map.of("roles", List.of("admin")))));

        SoftAssertions softly = new SoftAssertions();
        withoutUserRole.forEach((kind, roleClaims) -> {
            var claims = claims(subject).claim("realm_access", null).claim("resource_access", null);
            roleClaims.forEach(claims::claim);
            softly.assertThat(me(sign(claims)).getResponse().getStatus()).as(kind).isEqualTo(403);
        });
        softly.assertAll();
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE keycloak_id = ?").param(subject).query(Long.class).single())
                .as("non-members must not get a users row").isZero();
    }

    @Test
    void answers401WithoutRedirectingToTheLoginPage() {
        MvcTestResult result = request(GET, "/api/categories", null, null);

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).doesNotContainHeader(HttpHeaders.LOCATION);
        assertThat(result).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void bearerRequestsAreStatelessAndNeedNoCsrfToken() {
        MvcTestResult write = request(POST, "/api/categories", token(subject), """
                {"name": "Rent", "type": "EXPENSE"}""");

        assertThat(write).hasStatus(HttpStatus.CREATED);
        assertThat(write.getRequest().getSession(false)).isNull();
    }

    /** A five-minute token, like Keycloak's, that expired {@code seconds} ago. */
    private JWTClaimsSet.Builder expiredAgo(Instant now, long seconds) {
        return claims(subject)
                .issueTime(Date.from(now.minusSeconds(seconds + 300)))
                .expirationTime(Date.from(now.minusSeconds(seconds)));
    }

    private MvcTestResult me(String token) {
        return request(GET, "/api/me", token, null);
    }
}
