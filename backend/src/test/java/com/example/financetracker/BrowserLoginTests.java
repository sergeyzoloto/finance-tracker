package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The browser's login through this backend (backend-for-frontend): the authorization code flow with PKCE, tokens
 * kept in the server-side session, the session's access token refreshed and checked on every API call.
 */
class BrowserLoginTests extends IntegrationTest {

    private static final Duration LONG_LIVED = Duration.ofMinutes(5);
    /** Inside the one-minute margin, so every request refreshes it first. */
    private static final Duration EXPIRING = Duration.ofSeconds(30);
    /** The requests of one page that reach the backend together, like the dashboard's reports. */
    private static final int PARALLEL = 6;

    private final String subject = UUID.randomUUID().toString();

    @Autowired
    private JdbcClient jdbc;

    @Test
    void loginRunsTheCodeFlowWithPkceAndTheSessionThenCarriesTheApi() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcTestResult start = mvc.get().uri("/oauth2/authorization/keycloak").session(session).exchange();
        assertThat(start).hasStatus(HttpStatus.FOUND);
        String authorizationUrl = start.getResponse().getRedirectedUrl();
        assertThat(param(authorizationUrl, "redirect_uri")).isEqualTo("http://localhost/login/oauth2/code/keycloak");
        assertThat(param(authorizationUrl, "scope")).isEqualTo("openid profile email");

        // approve() insists on a S256 code challenge; the token endpoint checks the verifier and the client secret.
        String code = KEYCLOAK.approve(authorizationUrl, claims(subject).build(), LONG_LIVED);
        assertThat(mvc.get().uri("/login/oauth2/code/keycloak?code={code}&state={state}", code, param(authorizationUrl, "state"))
                .session(session)).hasRedirectedUrl("/?login=done");

        assertThat(Answers.of(get("/api/me", session)).get("name").asText()).isEqualTo("User " + subject);
        assertThat(write("/api/categories", session, """
                {"code": "RENT", "name": "Rent", "type": "EXPENSE"}""", csrfToken(session))).hasStatus(HttpStatus.CREATED);
        assertThat(Answers.of(get("/api/categories", session)).findValuesAsText("name")).contains("Rent");
    }

    @Test
    void writesFromTheBrowserNeedTheCsrfToken() {
        MockHttpSession session = login(claims(subject), LONG_LIVED);
        String body = """
                {"code": "RENT", "name": "Rent", "type": "EXPENSE"}""";

        String csrfToken = csrfToken(session);

        assertThat(write("/api/categories", session, body, null)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/api/categories").session(session).contentType(MediaType.APPLICATION_JSON).content(body)
                .cookie(new Cookie("XSRF-TOKEN", csrfToken)).header("X-XSRF-TOKEN", "not-the-cookie"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(write("/api/categories", session, body, csrfToken)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refreshesTheAccessTokenWhenItExpiresWithinAMinute() {
        MockHttpSession expiring = login(claims(subject), EXPIRING);
        assertThat(get("/api/me", expiring)).hasStatusOk();
        assertThat(get("/api/me", expiring)).hasStatusOk();
        assertThat(KEYCLOAK.refreshCount(subject)).isEqualTo(2);

        String other = UUID.randomUUID().toString();
        MockHttpSession longLived = login(claims(other), LONG_LIVED);
        assertThat(get("/api/me", longLived)).hasStatusOk();
        assertThat(KEYCLOAK.refreshCount(other)).isZero();
    }

    @Test
    void parallelRequestsWithAnExpiredAccessTokenKeepTheSession() throws Exception {
        // As in D3: the access token expires within the minute, and a page sends several requests at once.
        MockHttpSession session = login(claims(subject), EXPIRING, LONG_LIVED);
        KEYCLOAK.delayRefreshes(subject, Duration.ofMillis(300));

        List<MvcTestResult> results = inParallel(() -> get("/api/me", session));

        assertThat(results).allSatisfy(result -> assertThat(result).hasStatusOk());
        assertThat(session.isInvalid()).isFalse();
        assertThat(get("/api/me", session)).hasStatusOk();
        assertThat(KEYCLOAK.refusedRefreshCount(subject)).isZero();
    }

    @Test
    void parallelRequestsRefreshTheAccessTokenOnce() throws Exception {
        MockHttpSession session = login(claims(subject), EXPIRING, LONG_LIVED);
        KEYCLOAK.delayRefreshes(subject, Duration.ofMillis(300));

        inParallel(() -> get("/api/me", session));

        // One went to Keycloak; the others waited for it and used its token.
        assertThat(KEYCLOAK.refreshCount(subject)).isEqualTo(1);
    }

    @Test
    void endingTheKeycloakSessionEndsTheAppSession() {
        MockHttpSession session = login(claims(subject), EXPIRING);
        assertThat(get("/api/me", session)).hasStatusOk();

        KEYCLOAK.endSession(subject); // e.g. logged out in another myapps app, or disabled by an admin

        assertThat(get("/api/me", session)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aSignedInNonMemberGets403AndNoUsersRow() {
        // Tokens requested by this client carry it in "aud" (audience mapper), roles or not.
        MockHttpSession session = login(claims(subject)
                .claim("resource_access", Map.of("shop-api", Map.of("roles", List.of("admin", "user")))), LONG_LIVED);

        assertThat(get("/api/me", session)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE keycloak_id = ?").param(subject).query(Long.class).single()).isZero();
    }

    @Test
    void logoutEndsTheKeycloakSessionTooAndNeedsTheCsrfToken() {
        MockHttpSession session = login(claims(subject), LONG_LIVED);
        String csrfToken = csrfToken(session);
        assertThat(mvc.post().uri("/logout").session(session)).hasStatus(HttpStatus.FORBIDDEN);

        // As the frontend does it: a form post with the token from the cookie.
        MvcTestResult logout = mvc.post().uri("/logout").session(session).cookie(new Cookie("XSRF-TOKEN", csrfToken))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("_csrf", csrfToken).exchange();

        String endSessionUrl = logout.getResponse().getRedirectedUrl();
        assertThat(endSessionUrl).startsWith(KEYCLOAK.issuer() + "/protocol/openid-connect/logout?");
        assertThat(param(endSessionUrl, "id_token_hint")).isNotBlank();
        assertThat(param(endSessionUrl, "post_logout_redirect_uri")).isEqualTo("http://localhost/");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aFailedLoginReturnsToTheAppWithAnError() {
        MockHttpSession session = new MockHttpSession();
        String authorizationUrl = mvc.get().uri("/oauth2/authorization/keycloak").session(session).exchange()
                .getResponse().getRedirectedUrl();

        assertThat(mvc.get().uri("/login/oauth2/code/keycloak?error=access_denied&state={state}", param(authorizationUrl, "state"))
                .session(session)).hasRedirectedUrl("/?login=failed");
    }

    @Test
    void aLateSecondCallbackOfAFinishedLoginLandsInTheApp() {
        MockHttpSession session = new MockHttpSession();
        String authorizationUrl = mvc.get().uri("/oauth2/authorization/keycloak").session(session).exchange()
                .getResponse().getRedirectedUrl();
        String code = KEYCLOAK.approve(authorizationUrl, claims(subject).build(), LONG_LIVED);
        String callback = "/login/oauth2/code/keycloak?code=" + code + "&state=" + param(authorizationUrl, "state");
        assertThat(mvc.get().uri(callback).session(session)).hasRedirectedUrl("/?login=done");

        // The first callback used up the session's authorization request: authorization_request_not_found.
        assertThat(mvc.get().uri(callback).session(session)).hasRedirectedUrl("/?login=done");
        assertThat(get("/api/me", session)).hasStatusOk();
    }

    @Test
    void aCallbackWithoutAnAuthorizationRequestFailsInASessionThatIsNotSignedIn() {
        assertThat(mvc.get().uri("/login/oauth2/code/keycloak?code=some-code&state=some-state").session(new MockHttpSession()))
                .hasRedirectedUrl("/?login=failed");
    }

    /** Signs in through the whole code flow and returns the browser's session. */
    private MockHttpSession login(JWTClaimsSet.Builder accessClaims, Duration accessTokenLifetime) {
        return login(accessClaims, accessTokenLifetime, accessTokenLifetime);
    }

    /** {@link #login(JWTClaimsSet.Builder, Duration)} with a first access token that lasts less than later ones. */
    private MockHttpSession login(JWTClaimsSet.Builder accessClaims, Duration firstLifetime, Duration refreshedLifetime) {
        MockHttpSession session = new SharedSession();
        String authorizationUrl = mvc.get().uri("/oauth2/authorization/keycloak").session(session).exchange()
                .getResponse().getRedirectedUrl();
        String code = KEYCLOAK.approve(authorizationUrl, accessClaims.build(), firstLifetime, refreshedLifetime);
        assertThat(mvc.get().uri("/login/oauth2/code/keycloak?code={code}&state={state}", code, param(authorizationUrl, "state"))
                .session(session)).hasRedirectedUrl("/?login=done");
        return session;
    }

    private MvcTestResult get(String uri, MockHttpSession session) {
        return mvc.get().uri(uri).session(session).exchange();
    }

    /** Sends {@value #PARALLEL} requests at the same moment and returns their results. */
    private static List<MvcTestResult> inParallel(Callable<MvcTestResult> request) throws Exception {
        CyclicBarrier start = new CyclicBarrier(PARALLEL);
        List<Future<MvcTestResult>> pending = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(PARALLEL)) {
            for (int i = 0; i < PARALLEL; i++) {
                pending.add(threads.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            List<MvcTestResult> results = new ArrayList<>();
            for (Future<MvcTestResult> result : pending) {
                results.add(result.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    /** A JSON write with the session cookie, and the CSRF token in cookie and header as the frontend sends it. */
    private MvcTestResult write(String uri, MockHttpSession session, String body, String csrfToken) {
        var request = mvc.post().uri(uri).session(session).contentType(MediaType.APPLICATION_JSON).content(body);
        if (csrfToken != null) {
            request.cookie(new Cookie("XSRF-TOKEN", csrfToken)).header("X-XSRF-TOKEN", csrfToken);
        }
        return request.exchange();
    }

    /** The token from the XSRF-TOKEN cookie, which every response to a browser request without one sets. */
    private String csrfToken(MockHttpSession session) {
        Cookie cookie = get("/api/me", session).getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private static String param(String url, String name) {
        String value = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** A session that parallel requests can share, as they share Tomcat's: MockHttpSession's attributes are not. */
    private static final class SharedSession extends MockHttpSession {

        @Override
        public synchronized Object getAttribute(String name) {
            return super.getAttribute(name);
        }

        @Override
        public synchronized Enumeration<String> getAttributeNames() {
            return super.getAttributeNames();
        }

        @Override
        public synchronized void setAttribute(String name, Object value) {
            super.setAttribute(name, value);
        }

        @Override
        public synchronized void removeAttribute(String name) {
            super.removeAttribute(name);
        }

        @Override
        public synchronized void invalidate() {
            super.invalidate();
        }

        @Override
        public synchronized boolean isInvalid() {
            return super.isInvalid();
        }
    }
}
