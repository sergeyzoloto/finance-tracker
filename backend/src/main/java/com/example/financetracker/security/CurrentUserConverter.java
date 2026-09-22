package com.example.financetracker.security;

import java.util.List;
import java.util.Optional;

import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;

/** Maps a validated JWT to its local user, creating the row on first sight of the subject. */
@Component
class CurrentUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JdbcClient jdbc;

    CurrentUserConverter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId = findUserId(jwt.getSubject()).orElseGet(() -> {
            // ON CONFLICT covers concurrent first requests of the same user.
            jdbc.sql("""
                    INSERT INTO users (keycloak_id, email, display_name) VALUES (:keycloakId, :email, :displayName)
                    ON CONFLICT (keycloak_id) DO NOTHING""")
                    .param("keycloakId", jwt.getSubject())
                    .param("email", jwt.getClaimAsString("email"))
                    .param("displayName", Optional.ofNullable(jwt.getClaimAsString("name"))
                            .orElse(jwt.getClaimAsString("preferred_username")))
                    .update();
            return findUserId(jwt.getSubject()).orElseThrow();
        });
        return new PreAuthenticatedAuthenticationToken(new CurrentUser(userId), jwt, List.of());
    }

    private Optional<Long> findUserId(String keycloakId) {
        return jdbc.sql("SELECT id FROM users WHERE keycloak_id = :keycloakId")
                .param("keycloakId", keycloakId)
                .query(Long.class)
                .optional();
    }
}
