package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.PostgreSQLContainer;

/** Keycloak down: the app starts anyway, answers 401 rather than 500, and recovers on its own. */
@SpringBootTest
@AutoConfigureMockMvc
class KeycloakOutageTests {

    private static final FakeKeycloak KEYCLOAK = FakeKeycloak.start();

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;

    static {
        KEYCLOAK.setDown(true); // before the application context starts
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
    private MockMvcTester mvc;

    @Test
    void startsWhileKeycloakIsDownAndRecoversOnItsOwn() {
        String token = KEYCLOAK.sign(KEYCLOAK.accessTokenClaims(UUID.randomUUID().toString()).build());

        assertThat(me(token)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/oauth2/authorization/keycloak")).hasRedirectedUrl("/?login=failed");

        KEYCLOAK.setDown(false);
        assertThat(me(token)).as("discovery and keys load once Keycloak is back").hasStatusOk();

        KEYCLOAK.setDown(true);
        assertThat(me(token)).as("keys are cached, so issued tokens keep working").hasStatusOk();
    }

    private MvcTestResult me(String token) {
        return mvc.get().uri("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }
}
