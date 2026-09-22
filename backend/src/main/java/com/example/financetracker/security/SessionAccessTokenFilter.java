package com.example.financetracker.security;

import java.io.IOException;

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
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The backend-for-frontend half of the API. A browser request carries only the session cookie, so this filter takes
 * the access token that the login stored in the session, refreshes it when it expires within a minute, and hands it
 * to the resource server as if it had come in an Authorization header. Browser and bearer requests are then checked
 * the same way; the session's login by itself grants nothing.
 */
final class SessionAccessTokenFilter extends OncePerRequestFilter implements BearerTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionAccessTokenFilter.class);
    private static final String ACCESS_TOKEN = SessionAccessTokenFilter.class.getName() + ".accessToken";

    private final BearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final DefaultOAuth2AuthorizedClientManager clientManager;

    SessionAccessTokenFilter(ClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientRepository authorizedClients) {
        this.authorizedClients = authorizedClients;
        this.clientManager = new DefaultOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        this.clientManager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().refreshToken().build());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(SecurityConfig.REGISTRATION_ID, null, request);
            if (client != null) {
                client = refreshIfExpiring(client, request, response);
                if (client != null) {
                    request.setAttribute(ACCESS_TOKEN, client.getAccessToken().getTokenValue());
                } else {
                    // Refresh refused: the Keycloak session is over (logged out, expired, user disabled), so is ours.
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                }
            }
            // Without a token the request is anonymous (401), even if the session still remembers a login.
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    /** The bearer token from the Authorization header, or else the session's access token. */
    @Override
    public String resolve(HttpServletRequest request) {
        String header = headerResolver.resolve(request);
        return header != null ? header : (String) request.getAttribute(ACCESS_TOKEN);
    }

    /** The client with a fresh access token, or null once Keycloak refuses to refresh it. */
    private OAuth2AuthorizedClient refreshIfExpiring(OAuth2AuthorizedClient client, HttpServletRequest request,
            HttpServletResponse response) {
        try {
            return clientManager.authorize(OAuth2AuthorizeRequest.withAuthorizedClient(client)
                    .principal(client.getPrincipalName())
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build());
        } catch (OAuth2AuthorizationException e) {
            if (OAuth2ErrorCodes.INVALID_GRANT.equals(e.getError().getErrorCode())) {
                return null;
            }
            // Keycloak unreachable: keep the current token, which is still accepted until it expires.
            log.warn("Could not refresh the access token: {}", e.getMessage());
            return client;
        }
    }
}
