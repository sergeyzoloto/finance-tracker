package com.example.financetracker.security;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Maps a validated access token to this client's roles and, for members, to their local user, creating the row on
 * first sight of the subject. Part of {@link SecurityConfig}, and like it only in a web application.
 */
@Component
@ConditionalOnWebApplication
class CurrentUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final GrantedAuthority MEMBER = new SimpleGrantedAuthority("ROLE_USER");

    private final JdbcClient jdbc;
    private final String clientId;

    CurrentUserConverter(JdbcClient jdbc, KeycloakProperties keycloak) {
        this.jdbc = jdbc;
        this.clientId = keycloak.clientId();
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = authorities(jwt, clientId);
        // Non-members are turned away with 403 and leave no row behind.
        Object principal = authorities.contains(MEMBER) ? new CurrentUser(userId(jwt)) : jwt.getSubject();
        return new PreAuthenticatedAuthenticationToken(principal, jwt, authorities);
    }

    /**
     * The roles of client {@code clientId} as {@code ROLE_<ROLE>}. Unlike the auth server's reference SecurityConfig,
     * realm roles are left out: the realm role "user" is held by every user of every myapps project, so mapping it
     * would let them all in. Other clients' roles are ignored too.
     */
    static Set<GrantedAuthority> authorities(Jwt jwt, String clientId) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null || !(resourceAccess.get(clientId) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }

    private long userId(Jwt jwt) {
        return findUserId(jwt.getSubject()).orElseGet(() -> {
            // ON CONFLICT covers concurrent first requests of the same user.
            jdbc.sql("""
                    INSERT INTO users (keycloak_id, email, display_name) VALUES (:keycloakId, :email, :displayName)
                    ON CONFLICT (keycloak_id) DO NOTHING""")
                    .param("keycloakId", jwt.getSubject())
                    .param("email", jwt.getClaimAsString("email"))
                    .param("displayName", displayName(jwt))
                    .update();
            return findUserId(jwt.getSubject()).orElseThrow();
        });
    }

    /** For display only: names can change, data is keyed on the subject. */
    static String displayName(Jwt jwt) {
        return Optional.ofNullable(jwt.getClaimAsString("name")).orElse(jwt.getClaimAsString("preferred_username"));
    }

    private Optional<Long> findUserId(String keycloakId) {
        return jdbc.sql("SELECT id FROM users WHERE keycloak_id = :keycloakId")
                .param("keycloakId", keycloakId)
                .query(Long.class)
                .optional();
    }
}
