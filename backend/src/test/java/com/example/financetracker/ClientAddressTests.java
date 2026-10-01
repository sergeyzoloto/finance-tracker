package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The client address that the invites' rate limit counts per (D-29; F6a, the F5 follow-up), through a real Tomcat on
 * a port, as production runs behind the auth server's Caddy: Caddy (2.11, no {@code trusted_proxies}) sets {@code
 * X-Forwarded-For} to the address its connection came from, and Tomcat's {@code RemoteIpValve}
 * ({@code server.forward-headers-strategy: native}) trusts it from a private address, such as Caddy's on the Docker
 * network {@code edge}, and from loopback, which this test's requests come from. Two failures are ruled out here: every
 * user counted as the proxy's one address, and a client choosing its own address with a header of its own.
 * {@link ClientAddressUntrustedPeerTests} covers a client that reaches Tomcat without a trusted proxy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientAddressTests extends IntegrationTest {

    /** The most lookups an address makes in a minute (InviteRateLimit's default). */
    static final int PER_MINUTE = 10;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    /** Each client behind the proxy counts on its own, although every request comes from the proxy's one address. */
    @Test
    void eachClientBehindTheProxyCountsOnItsOwn() throws Exception {
        for (int i = 0; i < PER_MINUTE; i++) {
            assertThat(lookup(port, "203.0.113.10")).isEqualTo(404);
        }
        assertThat(lookup(port, "203.0.113.10")).isEqualTo(429);
        assertThat(lookup(port, "203.0.113.11")).isEqualTo(404);
    }

    /**
     * A client can't choose its address: a value it sends itself comes before what the proxy adds, and Tomcat takes
     * the right-most address that isn't a trusted proxy's, the one the proxy saw. (Caddy 2.11 replaces a client's
     * header altogether, which ends the same way.) An address of a private network the client names is no way around
     * it either.
     */
    @Test
    void aClientCantChooseItsAddress() throws Exception {
        for (int i = 0; i < PER_MINUTE; i++) {
            assertThat(lookup(port, "203.0.113.20")).isEqualTo(404);
        }
        assertThat(lookup(port, "198.51.100.7, 203.0.113.20")).isEqualTo(429);
        assertThat(lookup(port, "10.0.0.1, 203.0.113.20")).isEqualTo(429);
        assertThat(lookup(port, "203.0.113.21, 203.0.113.20")).isEqualTo(429);
    }

    /**
     * A lookup of a token that lets nobody in, by a new user each time, so that only the address's count can say 429.
     *
     * @param forwardedFor the X-Forwarded-For header, as a proxy in front sends it; null for none
     * @return the answer's status
     */
    static int lookup(int port, String forwardedFor) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/invites/lookup"))
                .header("Authorization", "Bearer " + token(UUID.randomUUID().toString()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\": \"AAAA\"}"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
