package com.example.financetracker;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.example.financetracker.ledger.family.FamilySwitch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Full stack against real Postgres and a {@link FakeKeycloak}. The app finds the realm through discovery, exactly as
 * configured in production, and tokens are real RS256 JWTs. The family budget's feature switch is on (D-25);
 * FamilySwitchOffApiTests turns it off.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = FamilySwitch.PROPERTY + "=true")
@Import(IntegrationTest.Clocks.class)
public abstract class IntegrationTest {

    /** The clock of the application's dates ({@code ledger.Today}), which a test may set ({@link TestClock}). */
    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {

        @Bean
        TestClock testClock() {
            return new TestClock();
        }
    }

    protected static final FakeKeycloak KEYCLOAK = FakeKeycloak.start();

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        // The JVM runs in Pacific/Kiritimati from its start (pom.xml, user.timezone), far from UTC, so a date shifted by
        // a time zone conversion shows up as an off-by-one. Setting it here, as before OPS-2b, changed it for the rest
        // of the run at whichever class came first (JvmDefaultsGuard).
        POSTGRES.start();
    }

    /**
     * The ECB's rates ({@code user_id} NULL) are every user's: a class that writes them removes them, or each class
     * after it reads them (OPS-2b: LedgerRepositoryTests' RUB rate of 2026-09-25 became RateApiTests' latest RUB rate,
     * in the class order of Ubuntu 26.04's runner).
     */
    @AfterAll
    static void noSharedRateLeft() throws SQLException {
        try (Connection db = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
                ResultSet rows = db.createStatement().executeQuery("""
                        SELECT string_agg(rate_date || ' ' || quote_currency || ' ' || source, ', ' ORDER BY rate_date)
                        FROM app.exchange_rate WHERE user_id IS NULL""")) {
            rows.next();
            assertThat(rows.getString(1)).as("the ECB's rates this class left in the shared database").isNull();
        }
    }

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("app.keycloak.issuer-url", KEYCLOAK::issuer);
        registry.add("app.keycloak.client-id", () -> FakeKeycloak.CLIENT_ID);
        registry.add("app.keycloak.client-secret", () -> FakeKeycloak.CLIENT_SECRET);
        // No downloads from the ECB: EcbRateLoaderTests load from a stand-in.
        registry.add("app.rates.ecb.enabled", () -> "false");
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

    /**
     * A member's request as spring-security-test's jwt() makes it: authenticated as the subject with the client role
     * user, without a signed token. The tests of AccessTokenTests and BrowserLoginTests check how real tokens get
     * there.
     */
    protected static RequestPostProcessor member(String subject) {
        return jwt()
                .jwt(token -> token.subject(subject).claim("email", subject + "@example.com").claim("name", "User " + subject))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
