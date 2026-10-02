package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * A client that reaches Tomcat without a trusted proxy in between can't choose its address with {@code
 * X-Forwarded-For} either: from an address that isn't a trusted proxy's, Tomcat ignores the header. Here no address is
 * trusted, so the test's own loopback stands for such a client. In production the api publishes no port; Caddy, on the
 * Docker network {@code edge}, is the only way in (deploy/app/docker-compose.yml).
 * <p>
 * Since D-38 (F6b) a loopback address names no client, so only the per-user limit applies to it. That is how this shows
 * the header ignored: had Tomcat taken the one public address every request names, the eleventh would be refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=no-address-is-trusted")
class ClientAddressUntrustedPeerTests extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void anUntrustedPeerCountsAsItself() throws Exception {
        for (int i = 0; i < ClientAddressTests.PER_MINUTE + 5; i++) {
            assertThat(ClientAddressTests.lookup(port, "203.0.113.200")).isEqualTo(404);
        }
        // The per-user limit still holds.
        String user = UUID.randomUUID().toString();
        for (int i = 0; i < ClientAddressTests.PER_MINUTE; i++) {
            assertThat(ClientAddressTests.lookup(port, "203.0.113.201", user)).isEqualTo(404);
        }
        assertThat(ClientAddressTests.lookup(port, "203.0.113.202", user)).isEqualTo(429);
    }
}
