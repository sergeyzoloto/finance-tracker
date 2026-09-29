package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
        // Rows in every table but import_batch, and those of family ledgers, which the user has none of.
        assertThat(rowsOf(user)).allSatisfy((table, rows) -> assertThat(rows > 0).as(table)
                .isEqualTo(!table.equals("import_batch") && !FAMILY_ROWS.contains(table)));

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

    /**
     * D-20: a sole owner whose other members have no account takes the family ledger with them: its categories and
     * members go too. Nothing else of anybody's changes.
     */
    @Test
    void aSoleOwnersFamilyLedgerGoesWithTheirData() throws IOException {
        String user = newUser();
        String other = newUser();
        ok(get(other, "/api/accounts"));
        Map<String, String> othersRows = digestOf(other);
        long family = newFamily(user, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna", "categoryIds": [%d]}"""
                .formatted(categoryId(user, "GROCERIES"))).get("id").asLong();
        body(post(user, "/api/family-ledgers/" + family + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED);
        assertThat(rowsOf(user)).containsEntry("family ledger_member", 2L).containsEntry("family category", 1L);

        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(familyRows(family)).isEqualTo("ledgers 0, members 0, categories 0");
        assertThat(rowsOf(user)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(digestOf(other)).isEqualTo(othersRows);
        startsAgainAsANewUser(user);
        assertThat(ok(get(user, "/api/family-ledgers"))).isEmpty();
    }

    /**
     * D-20 with other members with an account: the user becomes a FORMER member without a sub, "Former member", and
     * the ACTIVE member with an account who joined earliest becomes owner; a LEFT member, who joined even earlier,
     * never does (D-19). The user's custom share of 5000 can't go to anybody by itself, so the rule becomes EQUAL.
     * The others' personal rows don't change.
     */
    @Test
    void theEarliestActiveMemberWithAnAccountBecomesOwner() throws IOException {
        Scenario scenario = scenario();
        Map<String, String> bobsPersonalRows = personal(digestOf(scenario.bob()));

        assertThat(delete(scenario.alice(), "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(members(scenario.family())).containsExactly(
                "Dave MEMBER LEFT account",
                "Bob OWNER ACTIVE account",
                "Carol MEMBER ACTIVE account",
                "Former member MEMBER FORMER no account",
                "Kid MEMBER ACTIVE no account");
        assertThat(familyRows(scenario.family())).isEqualTo("ledgers 1, members 5, categories 1");
        assertThat(jdbc.sql("SELECT split_rule FROM ledger WHERE id = ?").param(scenario.family())
                .query(String.class).single()).isEqualTo("EQUAL");
        assertThat(jdbc.sql("SELECT left_date FROM ledger_member WHERE ledger_id = ? AND status = 'FORMER'")
                .param(scenario.family()).query(LocalDate.class).single()).isEqualTo(LocalDate.now());
        assertThat(rowsOf(scenario.alice())).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(personal(digestOf(scenario.bob()))).isEqualTo(bobsPersonalRows);
        // Bob, the new owner, may do what owners do; Alice, back as a new user, isn't in the ledger.
        assertThat(ok(patch(scenario.bob(), "/api/family-ledgers/" + scenario.family(), """
                {"name": "Bob's home"}""")).get("role").asText()).isEqualTo("OWNER");
        assertThat(get(scenario.alice(), "/api/family-ledgers/" + scenario.family())).hasStatus(HttpStatus.NOT_FOUND);
    }

    /** A LEFT member never counts as a member with an account (D-19): with only them left, the ledger goes. */
    @Test
    void aLeftMemberNeverKeepsTheFamilyLedger() throws IOException {
        String user = newUser();
        String left = newUser();
        long family = newFamily(user, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna"}""").get("id").asLong();
        long dave = join(family, left, "Dave", "MEMBER", LocalDate.of(2025, 1, 1));
        jdbc.sql("UPDATE ledger_member SET status = 'LEFT', left_date = DATE '2026-06-01' WHERE id = ?").param(dave)
                .update();

        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(familyRows(family)).isEqualTo("ledgers 0, members 0, categories 0");
    }

    /** The runbook's "Delete a user" ends where "Delete all my data" does, for the family ledgers too. */
    @Test
    void theRunbooksDeleteAUserReleasesFamilyMembershipsLikeDeleteAll() throws IOException {
        Scenario byApi = scenario();
        Scenario byRunbook = scenario();
        String soleOwner = newUser();
        long soleFamily = newFamily(soleOwner, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna"}""").get("id").asLong();

        assertThat(delete(byApi.alice(), "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        runbooksDeleteAUser(byRunbook.alice());
        runbooksDeleteAUser(soleOwner);

        assertThat(members(byRunbook.family())).isEqualTo(members(byApi.family()));
        assertThat(familyRows(byRunbook.family())).isEqualTo(familyRows(byApi.family()));
        assertThat(jdbc.sql("SELECT split_rule FROM ledger WHERE id IN (?, ?)")
                .params(byApi.family(), byRunbook.family()).query(String.class).list()).containsExactly("EQUAL", "EQUAL");
        assertThat(rowsOf(byRunbook.alice())).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(familyRows(soleFamily)).isEqualTo("ledgers 0, members 0, categories 0");
        assertThat(rowsOf(soleOwner)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
    }

    /**
     * Alice owns a family ledger, created today, with a custom split: Alice 5000, Bob 5000, Carol 0, Kid (no account)
     * 0. Dave joined first and left; Bob joined before Carol, whose membership is older.
     */
    private Scenario scenario() throws IOException {
        String alice = newUser();
        String bob = newUser();
        String carol = newUser();
        ok(get(bob, "/api/accounts"));
        JsonNode created = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Alice", "categoryIds": [%d]}"""
                .formatted(categoryId(alice, "GROCERIES")));
        long family = created.get("id").asLong();
        String uri = "/api/family-ledgers/" + family;
        long dave = join(family, newUser(), "Dave", "MEMBER", LocalDate.of(2025, 1, 1));
        jdbc.sql("UPDATE ledger_member SET status = 'LEFT', left_date = DATE '2026-06-01' WHERE id = ?").param(dave)
                .update();
        // Carol's membership comes first, Bob's join date: the join date decides.
        long carols = join(family, carol, "Carol", "MEMBER", LocalDate.of(2026, 3, 1));
        long bobs = join(family, bob, "Bob", "MEMBER", LocalDate.of(2026, 2, 1));
        long kid = body(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED).get("id").asLong();
        ok(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 5000}, {"memberId": %d, "share": 5000},
                 {"memberId": %d, "share": 0}, {"memberId": %d, "share": 0}]}"""
                .formatted(created.get("memberId").asLong(), bobs, carols, kid)));
        return new Scenario(alice, bob, family);
    }

    private record Scenario(String alice, String bob, long family) {
    }

    /** Each member of the family ledger as "name role status account", by join date. */
    private List<String> members(long family) {
        return jdbc.sql("""
                SELECT concat_ws(' ', display_name, role, status,
                    CASE WHEN user_sub IS NULL THEN 'no account' ELSE 'account' END)
                FROM ledger_member WHERE ledger_id = ? ORDER BY join_date, id""").param(family)
                .query(String.class).list();
    }

    private String familyRows(long family) {
        return jdbc.sql("""
                SELECT 'ledgers ' || (SELECT count(*) FROM ledger WHERE id = :family)
                    || ', members ' || (SELECT count(*) FROM ledger_member WHERE ledger_id = :family)
                    || ', categories ' || (SELECT count(*) FROM category WHERE ledger_id = :family)""")
                .param("family", family).query(String.class).single();
    }

    /**
     * The digests of the user's personal rows: without the ledgers and memberships, which include their family ones,
     * and without their family ledgers' rows.
     */
    private static Map<String, String> personal(Map<String, String> digests) {
        Map<String, String> personal = new LinkedHashMap<>(digests);
        personal.keySet().removeAll(FAMILY_ROWS);
        personal.keySet().removeAll(List.of("ledger", "ledger_member"));
        return personal;
    }

    private void runbooksDeleteAUser(String sub) {
        inTransaction.executeWithoutResult(status -> {
            for (String statement : List.of(
                    "SELECT release_family_memberships(?)",
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
                if (statement.startsWith("SELECT")) {
                    jdbc.sql(statement).param(sub).query().singleValue();
                } else {
                    jdbc.sql(statement).param(sub).update();
                }
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
