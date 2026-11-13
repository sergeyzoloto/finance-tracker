package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.example.financetracker.FakeKeycloak;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * D-619 (UX-15): {@code /api/me} names the account console of the issuer's realm with this client as its referrer, the
 * same for every signed-in user and nothing of theirs in it; without a session it answers 401 like every endpoint
 * ({@code ApiTests}).
 */
class MeAccountUrlApiTests extends LedgerApiTest {

    private final String alice = newUser();
    private final String bob = newUser();

    @Test
    void theAccountConsoleOfTheIssuer() throws IOException {
        JsonNode me = ok(get(alice, "/api/me"));
        assertThat(me.get("accountUrl").asText())
                .isEqualTo(KEYCLOAK.issuer() + "/account?referrer=" + FakeKeycloak.CLIENT_ID);
        assertThat(ok(get(bob, "/api/me")).get("accountUrl")).isEqualTo(me.get("accountUrl"));
        assertThat(me.get("accountUrl").asText()).doesNotContain(alice);
    }
}
