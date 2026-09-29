package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DELETE /api/me/data: everything the user has in the app, in one transaction, whatever the ledger came from. The
 * next request starts the user again as a new one. {@link DataIsolationApiTests} checks that other users keep theirs.
 */
class UserDataApiTests extends LedgerApiTest {

    @Autowired
    private TransactionTemplate inTransaction;

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

    /**
     * The runbook's "Delete a user" (deploy/RUNBOOK.md) deletes a user by hand with the statements of its deleting
     * block, in one transaction, like {@link com.example.financetracker.ledger.UserDataService#deleteAll}.
     */
    @Test
    void theRunbooksDeleteAUserLeavesNothingOfTheUserAndChangesNobodyElses() throws IOException {
        String user = newUser();
        String other = newUser();
        ok(post(user, "/api/demo-data", null));
        ok(post(other, "/api/demo-data", null));
        assertThat(rowsOf(user)).containsEntry("ledger", 1L).containsEntry("ledger_member", 1L);
        Map<String, String> othersRows = digestOf(other);

        runbooksDeleteAUser(user);

        assertThat(rowsOf(user)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(digestOf(other)).isEqualTo(othersRows);
    }

    /**
     * The command-line importer writes rows for a sub that has no users row, so the users row's trigger never removes
     * its ledger. The runbook deletes the ledger by the member's sub.
     */
    @Test
    void theRunbooksDeleteAUserAlsoRemovesTheLedgerOfASubWithoutAUsersRow() {
        String sub = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO account (user_id, code, name, type) VALUES (?, 'CASH', 'Cash', 'ASSET')").param(sub)
                .update();
        assertThat(rowsOf(sub)).containsEntry("users", 0L).containsEntry("account", 1L).containsEntry("ledger", 1L)
                .containsEntry("ledger_member", 1L);

        runbooksDeleteAUser(sub);

        assertThat(rowsOf(sub)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
    }

    private void runbooksDeleteAUser(String sub) {
        inTransaction.executeWithoutResult(status -> {
            for (String statement : List.of(
                    "DELETE FROM user_settings WHERE user_id = ?",
                    "DELETE FROM journal_entry WHERE user_id = ?",
                    "DELETE FROM import_batch WHERE user_id = ?",
                    "DELETE FROM account WHERE user_id = ?",
                    "DELETE FROM category WHERE user_id = ?",
                    "DELETE FROM counterparty WHERE user_id = ?",
                    "DELETE FROM exchange_rate WHERE user_id = ?",
                    "DELETE FROM transactions WHERE user_id IN (SELECT id FROM users WHERE keycloak_id = ?)",
                    "DELETE FROM categories WHERE user_id IN (SELECT id FROM users WHERE keycloak_id = ?)",
                    "DELETE FROM ledger WHERE id IN (SELECT ledger_id FROM ledger_member WHERE user_sub = ? "
                            + "AND ledger_type = 'PERSONAL')",
                    "DELETE FROM users WHERE keycloak_id = ?")) {
                jdbc.sql(statement).param(sub).update();
            }
        });
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
                "counterparty", 0L, "import_batch", 0L, "exchange_rate", 0L, "ledger", 1L, "ledger_member", 1L));
    }
}
