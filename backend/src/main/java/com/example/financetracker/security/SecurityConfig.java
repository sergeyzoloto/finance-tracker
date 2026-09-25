package com.example.financetracker.security;

import java.util.List;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Login and access control through the shared Keycloak, realm "myapps", client {@code app.keycloak.client-id}
 * (docs/auth.md).
 * <ul>
 * <li>Browsers use this backend as their backend-for-frontend: it runs the authorization code flow with PKCE as a
 * confidential client and keeps the tokens in the server-side session; the browser only gets the session cookie.
 * <li>Every API request, from a browser session or with a bearer token, is authorized by an access token checked
 * locally against the realm's keys: signature, issuer, expiry, audience ({@code application.yml}) and subject, then
 * this client's {@code user} role ({@link ClientRoles}).
 * <li>The token's subject is the user id that all data is keyed by. Controllers get it as a {@link CurrentUser}
 * parameter ({@link CurrentUserResolver}).
 * </ul>
 * Keycloak is contacted lazily, so the app starts while it's down; API requests then get 401.
 * <p>
 * Only in a web application: the command-line importer (profile "import") serves no requests.
 */
@Configuration
@ConditionalOnWebApplication
@EnableConfigurationProperties(KeycloakProperties.class)
class SecurityConfig {

    static final String REGISTRATION_ID = "keycloak";
    /** Where the browser lands after a login; the frontend then reopens the page the user was on. */
    private static final String LOGIN_DONE_URL = "/?login=done";
    /** Where the browser lands when a login can't be completed; the frontend explains and offers a retry. */
    private static final String LOGIN_FAILED_URL = "/?login=failed";

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, KeycloakProperties keycloak, JwtDecoder jwtDecoder,
            ClientRegistrationRepository clientRegistrations, OAuth2AuthorizedClientRepository authorizedClients)
            throws Exception {
        SessionAccessTokenFilter sessionAccessTokens = new SessionAccessTokenFilter(clientRegistrations, authorizedClients);
        AuthenticationFailureHandler loginFailed = loginFailedHandler();
        return http
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().hasRole("USER"))
                .oauth2ResourceServer(server -> server
                        .bearerTokenResolver(sessionAccessTokens)
                        .jwt(jwt -> jwt
                                .decoder(unauthorizedWhileKeycloakIsDown(jwtDecoder))
                                .jwtAuthenticationConverter(members(keycloak))))
                .addFilterBefore(sessionAccessTokens, BearerTokenAuthenticationFilter.class)
                .oauth2Login(login -> login
                        // Also switches off Spring's generated login and logout pages.
                        .loginPage("/oauth2/authorization/" + REGISTRATION_ID)
                        .defaultSuccessUrl(LOGIN_DONE_URL, true)
                        .failureHandler(loginFailed)
                        .withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                            @Override
                            public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(loginFailed); // e.g. Keycloak unreachable
                                return filter;
                            }
                        }))
                .logout(logout -> logout.logoutSuccessHandler(keycloakLogout(clientRegistrations)))
                // API callers get 401 and never a redirect; the frontend starts the login itself.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfCookie())
                        .csrfTokenRequestHandler(csrfTokenOnEveryResponse())
                        // Only the session cookie is a credential a cross-site page can make the browser send.
                        // Requests without a session, or with a bearer token, can't be forged that way (and a
                        // write without any credentials gets 401, not 403).
                        .ignoringRequestMatchers(request -> request.getSession(false) == null
                                || request.getHeader(HttpHeaders.AUTHORIZATION) != null))
                .build();
    }

    /**
     * Every row a user owns is keyed by the token's subject (rule 11), so a token without one is refused like any
     * invalid token (401). Spring Boot adds this check to the decoder's own.
     */
    @Bean
    OAuth2TokenValidator<Jwt> subjectRequired() {
        return new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank());
    }

    /** Controllers take the user as a {@link CurrentUser} parameter. */
    @Bean
    WebMvcConfigurer currentUserParameter(CurrentUserResolver currentUser) {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(currentUser);
            }
        };
    }

    /**
     * The client registration, from the realm's discovery document on first use rather than at startup, so the app
     * starts while Keycloak is unreachable. A failed lookup is retried on the next login.
     */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(KeycloakProperties keycloak) {
        SingletonSupplier<ClientRegistrationRepository> registrations = SingletonSupplier.of(
                () -> new InMemoryClientRegistrationRepository(ClientRegistrations.fromIssuerLocation(keycloak.issuerUrl())
                        .registrationId(REGISTRATION_ID)
                        .clientId(keycloak.clientId())
                        .clientSecret(keycloak.clientSecret())
                        .scope("openid", "profile", "email")
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                        .build()));
        return registrationId -> registrations.obtain().findByRegistrationId(registrationId);
    }

    /** Tokens live in the HTTP session and end with it, at logout or expiry. */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** A token's authorities are this client's roles; its name is its subject. */
    private static JwtAuthenticationConverter members(KeycloakProperties keycloak) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new ClientRoles(keycloak.clientId()));
        return converter;
    }

    /** Ends the Keycloak session too (end-session endpoint with id_token_hint), then returns to the app. */
    private static OidcClientInitiatedLogoutSuccessHandler keycloakLogout(ClientRegistrationRepository clientRegistrations) {
        OidcClientInitiatedLogoutSuccessHandler handler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }

    private static AuthenticationFailureHandler loginFailedHandler() {
        SimpleUrlAuthenticationFailureHandler redirect = new SimpleUrlAuthenticationFailureHandler(LOGIN_FAILED_URL);
        return (request, response, exception) -> {
            log.warn("Login failed: {}", NestedExceptionUtils.getMostSpecificCause(exception).getMessage());
            redirect.onAuthenticationFailure(request, response, exception);
        };
    }

    /** The token in a cookie that the frontend's script reads and sends back in the X-XSRF-TOKEN header. */
    private static CookieCsrfTokenRepository csrfCookie() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
        return repository;
    }

    /**
     * Compares the plain token, as the frontend reads it from the cookie, and loads it on every request so the cookie
     * is always set. The token never appears in a response body, so it needs no BREACH masking.
     */
    private static CsrfTokenRequestAttributeHandler csrfTokenOnEveryResponse() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /**
     * A token that can't be checked because Keycloak is unreachable (discovery or keys) gets 401 like any other
     * unverifiable token, instead of the 500 Spring Security answers by default.
     */
    private static JwtDecoder unauthorizedWhileKeycloakIsDown(JwtDecoder decoder) {
        return token -> {
            try {
                return decoder.decode(token);
            } catch (BadJwtException e) {
                throw e;
            } catch (JwtException | JwtDecoderInitializationException e) {
                log.warn("Cannot check access tokens, Keycloak is unreachable: {}",
                        NestedExceptionUtils.getMostSpecificCause(e).getMessage());
                throw new BadJwtException("The token can't be checked right now", e);
            }
        };
    }
}
