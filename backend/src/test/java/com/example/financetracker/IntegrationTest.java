package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Full stack against real Postgres. Tokens are real RS256 JWTs whose key is served from a local JWK
 * Set endpoint, so validation runs exactly as configured in production (issuer + JWK Set URI).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final String ISSUER = "https://keycloak.test/realms/finance-tracker";
    protected static final RSAKey SIGNING_KEY = newRsaKey("test-key");

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final HttpServer JWKS = startJwksServer();

    static {
        // Far from UTC, so a date shifted by a time zone conversion shows up as an off-by-one.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + JWKS.getAddress().getPort() + "/certs");
    }

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected ObjectMapper json;

    protected static String token(String subject) {
        return token(subject, SIGNING_KEY, ISSUER, Instant.now().plusSeconds(300));
    }

    protected static String token(String subject, RSAKey key, String issuer, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .expirationTime(Date.from(expiresAt))
                .claim("email", subject + "@example.com")
                .claim("name", "User " + subject)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    protected MvcTestResult request(HttpMethod method, String uri, String token, String body) {
        var request = mvc.method(method).uri(uri).contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request.exchange();
    }

    protected <T> T read(MvcTestResult result, Class<T> type) throws IOException {
        return json.readValue(result.getResponse().getContentAsString(), type);
    }

    protected long createCategory(String token, String name, String type) throws IOException {
        var result = request(HttpMethod.POST, "/api/categories", token, """
                {"name": "%s", "type": "%s"}""".formatted(name, type));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    protected long createTransaction(String token, long categoryId, String amount, String occurredOn) throws IOException {
        var result = request(HttpMethod.POST, "/api/transactions", token, """
                {"categoryId": %d, "amount": %s, "occurredOn": "%s"}""".formatted(categoryId, amount, occurredOn));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    protected static RSAKey newRsaKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksServer() {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
