package com.example.financetracker.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.example.financetracker.WallClock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/**
 * What a refused refresh means for the session, with a stand-in for Keycloak's token endpoint. BrowserLoginTests
 * covers the refresh against FakeKeycloak.
 */
class SessionAccessTokenFilterTests {

    private static final ClientRegistration KEYCLOAK = ClientRegistration.withRegistrationId(SecurityConfig.REGISTRATION_ID)
            .clientId("finance-tracker")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("https://auth.example/auth")
            .tokenUri("https://auth.example/token")
            .build();

    private final HttpSessionOAuth2AuthorizedClientRepository authorizedClients = new HttpSessionOAuth2AuthorizedClientRepository();
    private final MockHttpSession session = new MockHttpSession();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    SessionAccessTokenFilterTests() {
        request.setSession(session);
        authorizedClients.saveAuthorizedClient(client("access-1", "refresh-1", Duration.ofSeconds(30)), null, request, response);
    }

    @Test
    void aRefusedRefreshEndsTheSessionWhileTheRefusedTokenIsStillTheSessions() throws Exception {
        SessionAccessTokenFilter filter = filter(context -> {
            throw invalidGrant();
        });

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
        assertThat(filter.resolve(request)).isNull();
    }

    @Test
    void aRefusedRefreshKeepsTheSessionWhenASignInReplacedTheRefusedToken() throws Exception {
        SessionAccessTokenFilter filter = filter(context -> {
            // While the refresh is on its way: the callback of a new sign-in in the same session stores its tokens.
            authorizedClients.saveAuthorizedClient(client("access-2", "refresh-2", Duration.ofMinutes(5)), null, request, response);
            throw invalidGrant();
        });

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
        assertThat(filter.resolve(request)).isEqualTo("access-2");
    }

    private SessionAccessTokenFilter filter(OAuth2AuthorizedClientProvider refresher) {
        return new SessionAccessTokenFilter(registrationId -> KEYCLOAK, authorizedClients, refresher);
    }

    private static OAuth2AuthorizedClient client(String accessToken, String refreshToken, Duration lifetime) {
        Instant now = WallClock.now();
        return new OAuth2AuthorizedClient(KEYCLOAK, "subject",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, now, now.plus(lifetime)),
                new OAuth2RefreshToken(refreshToken, now));
    }

    private static ClientAuthorizationException invalidGrant() {
        return new ClientAuthorizationException(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, "Maximum allowed refresh token reuse exceeded", null),
                SecurityConfig.REGISTRATION_ID);
    }
}
