package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

/**
 * DELETE /api/me/data: everything the user has in the app, in one transaction, whatever the ledger came from. The
 * next request starts the user again as a new one. {@link DataIsolationApiTests} checks that other users keep theirs.
 */
class UserDataApiTests extends LedgerApiTest {

    @Test
    void deletesADemoLedgerAndTheUserCanLoadTheDemoAgain() throws IOException {
        String user = newUser();
        ok(post(user, "/api/demo-data", null));
        ok(post(user, "/api/rates/manual", """
                {"date": "2026-08-01", "base": "EUR", "quote": "USD", "rate": "1.10"}"""));
        ok(put(user, "/api/settings", """
                {"baseCurrency": "USD", "defaultShareRatio": "0.4"}"""));
        // Rows in every table but import_batch.
        assertThat(rowsOf(user)).allSatisfy((table, rows) -> assertThat(rows > 0).as(table)
                .isEqualTo(!table.equals("import_batch")));

        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(rowsOf(user)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        startsAgainAsANewUser(user);
        ok(post(user, "/api/demo-data", null));
        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
    }

    @Test
    void deletesAnImportedLedgerAndTheSameFilesImportAgain() throws IOException {
        String user = newUser();
        byte[] transactions = withoutRow(fixture("transactions.csv"), 10);
        JsonNode imported = ok(importWorkbook(user, transactions, "false"));
        assertThat(imported.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(rowsOf(user)).containsEntry("import_batch", 1L).containsEntry("journal_entry", 24L);

        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(rowsOf(user)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        startsAgainAsANewUser(user);
        // No row counts as imported before any more.
        JsonNode again = ok(importWorkbook(user, transactions, "false"));
        assertThat(again.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(again.get("entriesByKind")).isEqualTo(imported.get("entriesByKind"));
        assertThat(again.get("skipped")).isEqualTo(imported.get("skipped"));
    }

    @Test
    void anEmptyLedgerCanBeDeletedToo() throws IOException {
        String user = newUser();

        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        startsAgainAsANewUser(user);
    }

    /** The user's next requests find the starter ledger and nothing else, as on a first sign-in. */
    private void startsAgainAsANewUser(String user) throws IOException {
        JsonNode seed = json.readTree(new ClassPathResource("seed/starter-ledger.json").getInputStream());
        assertThat(ok(get(user, "/api/accounts")).findValuesAsText("code"))
                .containsExactlyInAnyOrderElementsOf(seed.get("accounts").findValuesAsText("code"));
        assertThat(ok(get(user, "/api/categories")).findValuesAsText("code"))
                .containsExactlyInAnyOrderElementsOf(seed.get("categories").findValuesAsText("code"));
        assertThat(ok(get(user, "/api/counterparties"))).isEmpty();
        assertThat(ok(get(user, "/api/entries")).get("totalElements").asInt()).isZero();
        assertThat(ok(get(user, "/api/rates/manual"))).isEmpty();
        assertThat(ok(get(user, "/api/settings")).get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(rowsOf(user)).containsAllEntriesOf(Map.of("users", 1L, "user_settings", 1L, "journal_entry", 0L,
                "counterparty", 0L, "import_batch", 0L, "exchange_rate", 0L));
    }
}
