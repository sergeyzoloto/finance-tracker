package com.example.financetracker.security;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.financetracker.ledger.StarterLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * The one place that works out who the user is: it fills in the {@link CurrentUser} parameter of controller methods
 * with the "sub" claim of the request's access token, which {@link SecurityConfig} has checked. Nothing below the
 * controllers reads the security context; they pass the user id on.
 * <p>
 * The first time it sees a user, it provisions them: a {@code users} row with their email and name, for display and
 * for finding a user's sub by email, and their settings and starter accounts and categories ({@link StarterLedger}).
 * Both are idempotent and safe when a user's first requests run in parallel. Users already provisioned are
 * remembered, so their later requests don't touch the database for it.
 */
@Component
@ConditionalOnWebApplication
class CurrentUserResolver implements HandlerMethodArgumentResolver {

    private final JdbcClient jdbc;
    private final StarterLedger starterLedger;
    private final Set<String> provisioned = ConcurrentHashMap.newKeySet();

    CurrentUserResolver(JdbcClient jdbc, StarterLedger starterLedger) {
        this.jdbc = jdbc;
        this.starterLedger = starterLedger;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentUser.class;
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            // Can't happen behind SecurityConfig, which lets only members with an access token through.
            throw new AuthenticationCredentialsNotFoundException("The request has no access token");
        }
        Jwt jwt = token.getToken();
        if (!provisioned.contains(jwt.getSubject())) {
            provision(jwt);
            provisioned.add(jwt.getSubject());
        }
        return new CurrentUser(jwt.getSubject());
    }

    /** For display only: names can change, data is keyed on the subject. */
    static String displayName(Jwt jwt) {
        return Optional.ofNullable(jwt.getClaimAsString("name")).orElse(jwt.getClaimAsString("preferred_username"));
    }

    private void provision(Jwt jwt) {
        jdbc.sql("""
                INSERT INTO users (keycloak_id, email, display_name) VALUES (:keycloakId, :email, :displayName)
                ON CONFLICT (keycloak_id) DO NOTHING""")
                .param("keycloakId", jwt.getSubject())
                .param("email", jwt.getClaimAsString("email"))
                .param("displayName", displayName(jwt))
                .update();
        starterLedger.seedIfNew(jwt.getSubject());
    }
}
