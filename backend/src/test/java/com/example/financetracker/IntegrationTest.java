package com.example.financetracker;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.family.posting.FamilyPostingService;
import com.example.financetracker.ledger.history.HistoryService;
import com.example.financetracker.ledger.history.Notifications;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
// One set of spies for every class, so that they share a context (TEST-1a; D-1203). A spy does nothing until a test stubs
// it, and is reset after each test.
@MockitoSpyBean(types = { EntryService.class, FamilyPostingService.class, Notifications.class })
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            // Every cached Spring context keeps its own Hikari pool of idle connections (about ten each); 100, the
            // default, is not enough for the suite in every class order (CI, seed 195: "too many clients already").
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300")
            // Reused between JVMs only where reuse is enabled locally (TESTCONTAINERS_REUSE_ENABLE=true or
            // testcontainers.reuse.enable=true in ~/.testcontainers.properties), and only in fork 1: forks share nothing,
            // each fork has its own container, and a reused container may be used by one fork only, because the static
            // block of each fork empties the tables of the container it uses. A reused container is also shared by every
            // JVM that uses it, so only one local test run at a time; CI doesn't enable it, and the container is new and
            // removed as before (TEST-1a, TEST-1b, D-1203).
            .withReuse("1".equals(System.getProperty("tests.fork", "1")));

    static {
        // The JVM runs in Pacific/Kiritimati from its start (pom.xml, user.timezone), far from UTC, so a date shifted by
        // a time zone conversion shows up as an off-by-one. Setting it here, as before OPS-2b, changed it for the rest
        // of the run at whichever class came first (JvmDefaultsGuard).
        POSTGRES.start();
        try {
            emptyTheApplicationTables();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A reused container keeps the previous JVM's rows: every application table is emptied once per JVM, except Flyway's
     * history, so that the run starts as on a new database. (The migrations insert no reference data of their own; the
     * application's seed is written per user.) A new container has no schema yet, and nothing happens.
     */
    private static void emptyTheApplicationTables() throws SQLException {
        try (Connection db = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            db.createStatement().execute("""
                    DO $$
                    DECLARE tables text;
                    BEGIN
                        SELECT string_agg(format('%I.%I', schemaname, tablename), ', ') INTO tables
                        FROM pg_tables WHERE schemaname = 'app' AND tablename <> 'flyway_schema_history';
                        IF tables IS NOT NULL THEN
                            EXECUTE 'TRUNCATE ' || tables || ' RESTART IDENTITY CASCADE';
                        END IF;
                    END $$""");
        }
    }

    /**
     * A database of a class's own, new: one that a reused container has from the previous JVM is dropped first, with
     * its connections.
     */
    static void recreateDatabase(Connection admin, String name) throws SQLException {
        if (!name.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("not a plain database name: " + name);
        }
        admin.createStatement().execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        admin.createStatement().execute("CREATE DATABASE " + name);
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

    /**
     * A connection as the image before HIST-1 (d44df66) has them: it never says it is version-aware, so the database's
     * backstop lets its writes through (D-1040). Tables are in schema app. The caller closes it.
     */
    protected static Connection previousImageConnection() throws SQLException {
        Connection db = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        db.createStatement().execute("SET search_path TO app");
        return db;
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

    @Autowired
    private HistoryService lockingHistory;

    @Autowired
    private PlatformTransactionManager lockingTransactions;

    /**
     * A test's own write of a ledger's rows through a repository, in a transaction that locks the ledger's row first,
     * as every writer of the application does (D-1234; the guard of D-1235 is on in the tests).
     */
    protected <T> T lockedWrite(long ledgerId, java.util.function.Supplier<T> write) {
        return new TransactionTemplate(lockingTransactions).execute(status -> {
            lockingHistory.lockLedger(ledgerId);
            return write.get();
        });
    }

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
