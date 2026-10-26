package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * D4b's round trip over the demo (D-238, D-256, D-259), as the end-to-end smoke test runs it: the demo's export, of the
 * whole period and of its last month, imported back as a dry run, with no family budget named, changes nothing, and
 * the ledger checks out against the file. Every row of it is the app's: compared, never written.
 */
class DemoRoundTripApiTests extends LedgerApiTest {

    @Test
    void theDemosExportImportedBackChangesNothing() throws IOException {
        String user = newUser();
        JsonNode demo = ok(post(user, "/api/demo-data", null));
        for (String query : new String[] {"", "?from=" + demo.get("to").asText().substring(0, 8) + "01&to="
                + demo.get("to").asText()}) {
            byte[] file = Answers.download(get(user, "/api/export/transactions" + query));
            JsonNode report = ok(importWorkbook(user, file, "true"));
            assertThat(report.get("errors")).as(query).isEmpty();
            assertThat(report.get("totals").get("created").asInt()).as(query).isZero();
            assertThat(report.get("totals").get("updated").asInt()).as(query).isZero();
            assertThat(report.get("totals").get("conflicts").asInt()).as(query).isZero();
            assertThat(report.get("totals").get("missing").asInt()).as(query).isZero();
            assertThat(report.get("sync").get("appRows")).as(query).isEmpty();
            assertThat(report.get("reconciliation").get("rows").get("failed").asInt()).as(query).isZero();
            assertThat(report.get("reconciliation").get("failures")).as(query).isEmpty();
            assertThat(report.get("reconciliation").get("passed").asBoolean()).as(query).isTrue();
        }
    }
}
