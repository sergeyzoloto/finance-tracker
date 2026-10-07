package com.example.financetracker.security;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import com.example.financetracker.WallClock;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The backend-for-frontend half of the API. A browser request carries only the session cookie, so this filter takes
 * the access token that the login stored in the session, refreshes it when it expires within a minute, and hands it
 * to the resource server as if it had come in an Authorization header. Browser and bearer requests are then checked
 * the same way; the session's login by itself grants nothing.
 * <p>
 * The realm accepts each refresh token once, and a second use also ends this client's session at Keycloak, including
 * the token the first use brought. So one request of a session refreshes at a time, and the requests that waited use
 * its new token. The lock lives in this JVM, which is enough while one api instance holds the sessions in its memory.
 * Several instances that share sessions would need a shared lock too (docs/auth.md).
 */
final class SessionAccessTokenFilter extends OncePerRequestFilter implements BearerTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionAccessTokenFilter.class);
    private static final String ACCESS_TOKEN = SessionAccessTokenFilter.class.getName() + ".accessToken";
    private static final String REFRESH_LOCK = SessionAccessTokenFilter.class.getName() + ".refreshLock";
    /** An access token is refreshed once it expires within this margin. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(1);

    private final BearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final DefaultOAuth2AuthorizedClientManager clientManager;

    SessionAccessTokenFilter(ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientRepository authorizedClients) {
        this(clientRegistrations, authorizedClients, OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(refresh -> refresh.clockSkew(REFRESH_MARGIN))
                .build());
    }

    /** With another way to refresh, for tests. */
    SessionAccessTokenFilter(ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientRepository authorizedClients, OAuth2AuthorizedClientProvider refresher) {
        this.authorizedClients = authorizedClients;
        this.clientManager = new DefaultOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        this.clientManager.setAuthorizedClientProvider(refresher);
        // A refused refresh leaves the stored tokens alone; refresh() decides what it means for the session.
        this.clientManager.setAuthorizationFailureHandler((failure, principal, attributes) -> { });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            OAuth2AuthorizedClient client = storedClient(request);
            if (client != null && expiring(client)) {
                client = refresh(request, response);
                if (client == null) {
                    // Refresh refused: the Keycloak session is over (logged out, expired, user disabled), so is ours.
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                }
            }
            if (client != null) {
                request.setAttribute(ACCESS_TOKEN, client.getAccessToken().getTokenValue());
            }
            // Without a token the request is anonymous (401), even if the session still remembers a login: the
            // login's own authentication grants nothing.
            if (SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    /** The bearer token from the Authorization header, or else the session's access token. */
    @Override
    public String resolve(HttpServletRequest request) {
        String header = headerResolver.resolve(request);
        return header != null ? header : (String) request.getAttribute(ACCESS_TOKEN);
    }

    /**
     * The session's client with a fresh access token, or null once Keycloak refuses to refresh it. Holds the session's
     * refresh lock, and reads the client again under it: a request that waited finds the token that the request
     * before it got, and refreshes nothing.
     */
    private OAuth2AuthorizedClient refresh(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        ReentrantLock lock = refreshLock(session);
        lock.lock();
        try {
            OAuth2AuthorizedClient current = storedClient(request);
            if (current == null || !expiring(current)) {
                return current;
            }
            try {
                return clientManager.authorize(OAuth2AuthorizeRequest.withAuthorizedClient(current)
                        .principal(current.getPrincipalName())
                        .attribute(HttpServletRequest.class.getName(), request)
                        .attribute(HttpServletResponse.class.getName(), response)
                        .build());
            } catch (OAuth2AuthorizationException e) {
                if (!OAuth2ErrorCodes.INVALID_GRANT.equals(e.getError().getErrorCode())) {
                    // Keycloak unreachable: keep the current token, which is still accepted until it expires.
                    log.warn("Could not refresh the access token: {}", e.getMessage());
                    return current;
                }
                // The refusal ends the session only while the refused token is still the session's. A sign-in in
                // this session meanwhile, whose callback doesn't take the lock, stored tokens of its own.
                OAuth2AuthorizedClient stored = storedClient(request);
                if (stored != null && !Objects.equals(refreshToken(stored), refreshToken(current))) {
                    return stored;
                }
                log.info("Keycloak refused to refresh the session's access token ({}), so the session ends",
                        e.getError().getDescription());
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    private OAuth2AuthorizedClient storedClient(HttpServletRequest request) {
        return authorizedClients.loadAuthorizedClient(SecurityConfig.REGISTRATION_ID, null, request);
    }

    /** The session's refresh lock, made on first use; it ends with the session. */
    private synchronized ReentrantLock refreshLock(HttpSession session) {
        ReentrantLock lock = (ReentrantLock) session.getAttribute(REFRESH_LOCK);
        if (lock == null) {
            lock = new ReentrantLock();
            session.setAttribute(REFRESH_LOCK, lock);
        }
        return lock;
    }

    /** Whether the access token expires within the margin, as the refresh token provider decides it. */
    private static boolean expiring(OAuth2AuthorizedClient client) {
        Instant expiresAt = client.getAccessToken().getExpiresAt();
        return expiresAt != null && WallClock.now().isAfter(expiresAt.minus(REFRESH_MARGIN));
    }

    private static String refreshToken(OAuth2AuthorizedClient client) {
        OAuth2RefreshToken token = client.getRefreshToken();
        return token == null ? null : token.getTokenValue();
    }
}
