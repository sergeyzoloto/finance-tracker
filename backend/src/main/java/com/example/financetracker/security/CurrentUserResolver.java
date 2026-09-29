package com.example.financetracker.security;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.financetracker.ledger.StarterLedger;
import com.example.financetracker.ledger.UserDataDeleted;
import com.example.financetracker.ledger.UserDataService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * The one place that works out who the user is: it fills in the {@link CurrentUser} parameter of controller methods
 * with the "sub" claim of the request's access token, which {@link SecurityConfig} has checked. Nothing below the
 * controllers reads the security context; they pass the user id on, or the ledger that {@link PersonalLedgerResolver}
 * resolves for it.
 * <p>
 * The first time it sees a user, it provisions them ({@link UserDataService#provision}): a {@code users} row with
 * their email and name, and their settings, personal ledger and starter accounts and categories
 * ({@link StarterLedger}). Both are idempotent and safe when a user's first requests run in parallel. Users already
 * provisioned are remembered, so their later requests don't touch the database for it, until they delete all their
 * data.
 */
@Component
@ConditionalOnWebApplication
class CurrentUserResolver implements HandlerMethodArgumentResolver {

    private final UserDataService userData;
    private final Set<String> provisioned = ConcurrentHashMap.newKeySet();

    CurrentUserResolver(UserDataService userData) {
        this.userData = userData;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentUser.class;
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return currentUser();
    }

    /** The request's user, provisioned on first sight. */
    CurrentUser currentUser() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            // Can't happen behind SecurityConfig, which lets only members with an access token through.
            throw new AuthenticationCredentialsNotFoundException("The request has no access token");
        }
        Jwt jwt = token.getToken();
        if (!provisioned.contains(jwt.getSubject())) {
            userData.provision(jwt.getSubject(), jwt.getClaimAsString("email"), displayName(jwt));
            provisioned.add(jwt.getSubject());
        }
        return new CurrentUser(jwt.getSubject());
    }

    /** The user deleted all their data: their next request provisions them again, as on their first. */
    @TransactionalEventListener
    public void forget(UserDataDeleted event) {
        provisioned.remove(event.userId());
    }

    /** For display only: names can change, data is keyed on the subject. */
    static String displayName(Jwt jwt) {
        return Optional.ofNullable(jwt.getClaimAsString("name")).orElse(jwt.getClaimAsString("preferred_username"));
    }
}
