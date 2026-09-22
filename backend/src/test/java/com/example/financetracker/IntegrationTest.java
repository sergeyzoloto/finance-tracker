package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.TimeZone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
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
 * Full stack against real Postgres and a {@link FakeKeycloak}. The app finds the realm through discovery, exactly as
 * configured in production, and tokens are real RS256 JWTs.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final FakeKeycloak KEYCLOAK = FakeKeycloak.start();

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        // Far from UTC, so a date shifted by a time zone conversion shows up as an off-by-one.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("app.keycloak.issuer-url", KEYCLOAK::issuer);
        registry.add("app.keycloak.client-id", () -> FakeKeycloak.CLIENT_ID);
        registry.add("app.keycloak.client-secret", () -> FakeKeycloak.CLIENT_SECRET);
    }

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected ObjectMapper json;

    /** A member's access token. */
    protected static String token(String subject) {
        return sign(claims(subject));
    }

    /** A member's access token claims, to adjust before {@link #sign}. */
    protected static JWTClaimsSet.Builder claims(String subject) {
        return KEYCLOAK.accessTokenClaims(subject);
    }

    protected static String sign(JWTClaimsSet.Builder claims) {
        return KEYCLOAK.sign(claims.build());
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
}
