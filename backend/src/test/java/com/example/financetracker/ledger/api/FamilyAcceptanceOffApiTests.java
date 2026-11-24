package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Family acceptance off, the default (HIST-3, D-1166): its endpoints answer as unknown paths do, no record is pending
 * for anyone, and the integrity check is as before, whatever the counters say.
 */
class FamilyAcceptanceOffApiTests extends FamilyApiTest {

    @Test
    void theEndpointsAreUnknownAndNothingIsPending() throws IOException {
        long record = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum,
                "\"paymentLater\": true,"))).get("id").asLong();
        assertThat(post(bob, uri + "/accept", "{}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(bob, uri + "/pending")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ok(get(bob, uri + "/records/" + record)).get("pendingForMe").asBoolean()).isFalse();
        assertThat(ok(get(bob, uri + "/records")).get("content").get(0).get("pendingForMe").asBoolean()).isFalse();
    }

    /** Counters that would make Bob lagging change nothing of the integrity check with acceptance off. */
    @Test
    void theIntegrityCheckIsUnchangedByTheCounters() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum,
                "\"paymentLater\": true,")));
        JsonNode before = ok(get(bob, "/api/reports/integrity"));
        long head = jdbc.sql("SELECT accepted_revision FROM ledger_member WHERE id = ?").param(dad)
                .query(Long.class).single();
        jdbc.sql("UPDATE ledger_member SET accepted_revision = 0 WHERE id = ?").param(dad).update();
        try {
            JsonNode lagging = ok(get(bob, "/api/reports/integrity"));
            assertThat(lagging).isEqualTo(before);
            lagging.forEach(violation -> assertThat(violation.has("pendingChanges")).isFalse());
        } finally {
            jdbc.sql("UPDATE ledger_member SET accepted_revision = ? WHERE id = ?").params(head, dad).update();
        }
    }
}
