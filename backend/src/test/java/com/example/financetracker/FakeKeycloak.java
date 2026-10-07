package com.example.financetracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Stand-in for the auth server's realm "myapps" on a local port: discovery, signing keys, and the token endpoint for
 * the authorization code grant with PKCE and for refreshes. Its tokens are RS256 JWTs signed with a key generated per
 * run and carry Keycloak's claims, so the app checks them exactly as it checks Keycloak's. Like Keycloak, it answers
 * requests in parallel, and it rotates refresh tokens as the realm does (see {@link #refresh}).
 */
public final class FakeKeycloak {

    public static final String CLIENT_ID = "finance-tracker";
    public static final String CLIENT_SECRET = "finance-tracker-secret";

    private final RSAKey signingKey = newRsaKey("test-key");
    private final HttpServer server;
    private final String issuer;
    private final Map<String, Login> codes = new ConcurrentHashMap<>();
    /** Every refresh token issued, current or used, and the client session it belongs to. */
    private final Map<String, ClientSession> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> refreshes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> refusedRefreshes = new ConcurrentHashMap<>();
    private final Map<String, Duration> refreshDelays = new ConcurrentHashMap<>();
    private final Set<String> endedSessions = ConcurrentHashMap.newKeySet();
    private volatile boolean down;

    /**
     * A user's login: what their access tokens carry, and how long the first one lasts and each one after a refresh.
     */
    private record Login(JWTClaimsSet accessClaims, Duration firstLifetime, Duration refreshedLifetime, String nonce,
            String codeChallenge) {
    }

    /** What Keycloak keeps for one login of this client: the one refresh token still valid, until detached. */
    private static final class ClientSession {
        private final Login login;
        private String refreshToken;
        private boolean detached;

        private ClientSession(Login login) {
            this.login = login;
        }
    }

    private FakeKeycloak() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        issuer = "http://localhost:" + server.getAddress().getPort() + "/realms/myapps";
        String path = "/realms/myapps";
        server.createContext(path + "/.well-known/openid-configuration", exchange -> respond(exchange, 200, discovery()));
        server.createContext(path + "/protocol/openid-connect/certs",
                exchange -> respond(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toJSONObject()));
        server.createContext(path + "/protocol/openid-connect/token", this::token);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public static FakeKeycloak start() {
        return new FakeKeycloak();
    }

    public String issuer() {
        return issuer;
    }

    /** While down, every endpoint answers 503. */
    public void setDown(boolean down) {
        this.down = down;
    }

    /** A member's access token, as Keycloak issues it to this client; tests adjust claims from here. */
    public JWTClaimsSet.Builder accessTokenClaims(String subject) {
        Instant now = WallClock.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .audience(List.of(CLIENT_ID, "shop-api"))
                .claim("azp", CLIENT_ID)
                .claim("typ", "Bearer")
                .claim("realm_access", Map.of("roles", List.of("user")))
                .claim("resource_access", Map.of(CLIENT_ID, Map.of("roles", List.of("user")), "shop-api", Map.of("roles", List.of("user"))))
                .claim("preferred_username", "user-" + subject)
                .claim("email", subject + "@example.com")
                .claim("name", "User " + subject);
    }

    public String sign(JWTClaimsSet claims) {
        return sign(claims, signingKey);
    }

    public static String sign(JWTClaimsSet claims, RSAKey key) {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    /**
     * Plays the user signing in on the login page for the app's authorization request (the URL the app redirected
     * the browser to), and returns the code that Keycloak would send back to the app's callback.
     */
    public String approve(String authorizationRequestUrl, JWTClaimsSet accessClaims, Duration accessTokenLifetime) {
        return approve(authorizationRequestUrl, accessClaims, accessTokenLifetime, accessTokenLifetime);
    }

    /**
     * {@link #approve(String, JWTClaimsSet, Duration)}, with a first access token that lasts {@code firstLifetime} and
     * later ones, from refreshes, that last {@code refreshedLifetime}.
     */
    public String approve(String authorizationRequestUrl, JWTClaimsSet accessClaims, Duration firstLifetime,
            Duration refreshedLifetime) {
        Map<String, String> params = new LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(authorizationRequestUrl).build().getQueryParams()
                .forEach((name, values) -> params.put(name, URLDecoder.decode(values.getFirst(), StandardCharsets.UTF_8)));
        if (!authorizationRequestUrl.startsWith(issuer + "/protocol/openid-connect/auth?")
                || !"code".equals(params.get("response_type")) || !CLIENT_ID.equals(params.get("client_id"))
                || !"S256".equals(params.get("code_challenge_method")) || params.get("code_challenge") == null) {
            throw new IllegalArgumentException("Not an authorization code request with PKCE S256: " + authorizationRequestUrl);
        }
        String code = UUID.randomUUID().toString();
        codes.put(code, new Login(accessClaims, firstLifetime, refreshedLifetime, params.get("nonce"),
                params.get("code_challenge")));
        return code;
    }

    /** Ends the subject's Keycloak session (logout elsewhere, expiry, disabled user): refreshes now fail. */
    public void endSession(String subject) {
        endedSessions.add(subject);
    }

    /** Makes the token endpoint take this long to answer the subject's refreshes, so parallel requests overlap. */
    public void delayRefreshes(String subject, Duration delay) {
        refreshDelays.put(subject, delay);
    }

    /**
     * The refresh requests that reached the token endpoint with one of the subject's refresh tokens, granted or
     * refused: Keycloak's events {@code REFRESH_TOKEN} and {@code REFRESH_TOKEN_ERROR} together.
     */
    public int refreshCount(String subject) {
        return refreshes.getOrDefault(subject, new AtomicInteger()).get();
    }

    /** The refused ones among {@link #refreshCount}: Keycloak's {@code REFRESH_TOKEN_ERROR}. */
    public int refusedRefreshCount(String subject) {
        return refusedRefreshes.getOrDefault(subject, new AtomicInteger()).get();
    }

    private Map<String, Object> discovery() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer);
        metadata.put("authorization_endpoint", issuer + "/protocol/openid-connect/auth");
        metadata.put("token_endpoint", issuer + "/protocol/openid-connect/token");
        metadata.put("jwks_uri", issuer + "/protocol/openid-connect/certs");
        metadata.put("end_session_endpoint", issuer + "/protocol/openid-connect/logout");
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("subject_types_supported", List.of("public"));
        metadata.put("id_token_signing_alg_values_supported", List.of("RS256"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        return metadata;
    }

    private void token(HttpExchange exchange) throws IOException {
        if (down) {
            respond(exchange, 503, Map.of());
            return;
        }
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!expectedAuth.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            String[] nameValue = pair.split("=", 2);
            form.put(URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8),
                    nameValue.length > 1 ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : "");
        }
        Map<String, Object> answer = switch (form.getOrDefault("grant_type", "")) {
            case "authorization_code" -> {
                Login pending = codes.remove(form.getOrDefault("code", ""));
                yield pending != null && s256(form.getOrDefault("code_verifier", "")).equals(pending.codeChallenge())
                        ? tokenResponse(new ClientSession(pending), pending.firstLifetime())
                        : invalidGrant("Code not valid");
            }
            case "refresh_token" -> refresh(form.getOrDefault("refresh_token", ""));
            default -> invalidGrant("Unsupported grant type");
        };
        respond(exchange, answer.containsKey("error") ? 400 : 200, answer);
    }

    /**
     * A refresh as the realm answers it (revokeRefreshToken on, refreshTokenMaxReuse 0): each refresh token works
     * once, and the answer brings the next one. Using a refresh token again is refused with Keycloak's message, and,
     * as Keycloak 26 does, it also detaches the client session, so the token that replaced it is refused from then on.
     */
    private Map<String, Object> refresh(String refreshToken) {
        ClientSession clientSession = refreshTokens.get(refreshToken);
        if (clientSession == null) {
            return invalidGrant("Invalid refresh token");
        }
        String subject = clientSession.login.accessClaims().getSubject();
        refreshes.computeIfAbsent(subject, s -> new AtomicInteger()).incrementAndGet();
        pause(refreshDelays.getOrDefault(subject, Duration.ZERO));
        synchronized (clientSession) {
            String refused = endedSessions.contains(subject) ? "Session not active"
                    : clientSession.detached ? "Client session not active"
                    : !refreshToken.equals(clientSession.refreshToken) ? "Maximum allowed refresh token reuse exceeded"
                    : null;
            if (refused != null) {
                clientSession.detached = true;
                refusedRefreshes.computeIfAbsent(subject, s -> new AtomicInteger()).incrementAndGet();
                return invalidGrant(refused);
            }
            return tokenResponse(clientSession, clientSession.login.refreshedLifetime());
        }
    }

    private static Map<String, Object> invalidGrant(String description) {
        return Map.of("error", "invalid_grant", "error_description", description);
    }

    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** New tokens for the client session; the new refresh token replaces the session's current one. */
    private Map<String, Object> tokenResponse(ClientSession clientSession, Duration accessTokenLifetime) {
        Instant now = WallClock.now();
        Login login = clientSession.login;
        String subject = login.accessClaims().getSubject();
        String accessToken = sign(new JWTClaimsSet.Builder(login.accessClaims())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTokenLifetime)))
                .build());
        String idToken = sign(new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(CLIENT_ID)
                .claim("azp", CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", login.nonce())
                .claim("name", login.accessClaims().getClaim("name"))
                .build());
        String refreshToken = UUID.randomUUID().toString();
        clientSession.refreshToken = refreshToken;
        refreshTokens.put(refreshToken, clientSession);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", accessTokenLifetime.toSeconds());
        response.put("refresh_token", refreshToken);
        response.put("id_token", idToken);
        response.put("scope", "openid profile email");
        return response;
    }

    private void respond(HttpExchange exchange, int status, Map<String, Object> json) throws IOException {
        if (down) {
            status = 503;
            json = Map.of();
        }
        byte[] body = JSONObjectUtils.toJSONString(json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RSAKey newRsaKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
