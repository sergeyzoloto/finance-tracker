package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.financetracker.ledger.family.FamilyInvariants;
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

    /**
     * "Delete all my data" with postings on family categories (ADR 0003, topic J): the user's entries go first, those
     * the family budget posted and their payments included, so that the family ledger, which has nobody else with an
     * account, can go with its categories, records and journal.
     */
    @Test
    void deletesPostingsOnFamilyCategoriesAndAFamilyWithOnlyGuests() throws IOException {
        String user = newUser();
        JsonNode created = newFamily(user, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna", "categoryIds": [%d]}"""
                .formatted(categoryId(user, "GROCERIES")));
        long family = created.get("id").asLong();
        String uri = "/api/family-ledgers/" + family;
        body(post(user, uri + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED);
        long groceries = ok(get(user, uri + "/categories")).get(0).get("id").asLong();
        body(post(user, uri + "/records", """
                {"date": "%s", "categoryId": %d, "amount": "30", "payerMemberId": %d, "paymentAccountId": %d}"""
                .formatted(LocalDate.now(), groceries, created.get("memberId").asLong(), accountId(user, "CASH"))),
                HttpStatus.CREATED);
        body(post(user, uri + "/records", """
                {"date": "%s", "categoryId": %d, "amount": "4", "payerMemberId": %d, "paymentLater": true}"""
                .formatted(LocalDate.now(), groceries, created.get("memberId").asLong())), HttpStatus.CREATED);
        assertThat(jdbc.sql("SELECT count(*) FROM posting p JOIN journal_entry e ON e.id = p.entry_id "
                + "WHERE e.user_id = ? AND p.category_id = ?").params(user, groceries).query(Long.class).single())
                .isEqualTo(2);
        assertThat(rowsOf(user)).containsEntry("family family_record", 2L).containsEntry("family family_share", 4L);

        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(familyRows(family)).isEqualTo("ledgers 0, members 0, categories 0");
        assertThat(jdbc.sql("SELECT (SELECT count(*) FROM family_record WHERE ledger_id = :family) "
                + "+ (SELECT count(*) FROM family_record_change WHERE ledger_id = :family) "
                + "+ (SELECT count(*) FROM family_entry_link WHERE family_ledger_id = :family)")
                .param("family", family).query(Long.class).single()).isZero();
        assertThat(rowsOf(user)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        startsAgainAsANewUser(user);
    }

    /**
     * D-20 with records: the user's personal entries go, their posted shares and payments included, and they become a
     * FORMER member. Their records stay, frozen; the others' balances, and their posted entries, stay as they were;
     * and the journal shows the split rule's reset, a system change about the user.
     */
    @Test
    void aMemberWhoDeletesTheirDataLeavesTheirRecordsFrozenAndTheOthersBalances() throws IOException {
        Scenario scenario = scenario();
        String uri = "/api/family-ledgers/" + scenario.family();
        JsonNode balancesBefore = ok(get(scenario.bob(), uri + "/balances"));
        Map<String, String> bobsPersonalRows = personal(digestOf(scenario.bob()));
        FamilyInvariants.check(jdbc, scenario.family());

        assertThat(delete(scenario.alice(), "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(rowsOf(scenario.alice())).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        JsonNode balancesAfter = ok(get(scenario.bob(), uri + "/balances"));
        assertThat(balancesAfter.get("members").findValuesAsText("balance"))
                .isEqualTo(balancesBefore.get("members").findValuesAsText("balance"));
        assertThat(balancesAfter.get("members").findValuesAsText("displayName")).contains("Former member")
                .doesNotContain("Alice");
        assertThat(personal(digestOf(scenario.bob()))).isEqualTo(bobsPersonalRows);
        FamilyInvariants.check(jdbc, scenario.family());
        JsonNode records = ok(get(scenario.bob(), uri + "/records")).get("content");
        assertThat(records).hasSize(2).allSatisfy(record -> assertThat(record.get("frozen").asBoolean()).isTrue());
        assertThat(patch(scenario.bob(), uri + "/records/" + records.get(0).get("id").asLong() + "?version=0", """
                {"comment": "Mine now"}""")).hasStatus(HttpStatus.CONFLICT);
        JsonNode reset = ok(get(scenario.bob(), uri + "/journal")).get("content").get(0);
        assertThat(reset.get("action").asText()).isEqualTo("SPLIT_RULE_RESET");
        assertThat(reset.get("author").isNull()).isTrue();
        assertThat(reset.get("recordId").isNull()).isTrue();
        assertThat(reset.get("record").isNull()).isTrue();
        assertThat(reset.get("about").get("displayName").asText()).isEqualTo("Former member");
        assertThat(reset.get("changes")).isEqualTo(json.readTree("""
                [{"field": "splitRule", "member": null, "old": "CUSTOM", "new": "EQUAL"}]"""));
        // Her links stay as the family's history, detached and without her entries.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM family_entry_link l JOIN ledger_member m ON m.id = l.member_id
                WHERE l.family_ledger_id = ? AND m.status = 'FORMER' AND l.detached_at IS NOT NULL
                  AND l.entry_id IS NULL""").param(scenario.family()).query(Long.class).single()).isEqualTo(3);
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

    /**
     * "Delete all my data" revokes the user's invites that are still pending (D-20; F5), through the API and through
     * the runbook's "Delete a user"; an accepted one stays as it was, and a family ledger that goes takes its invites.
     */
    @Test
    void deletingAllMyDataRevokesMyPendingInvites() throws IOException {
        Scenario byApi = scenario();
        Scenario byRunbook = scenario();
        String soleOwner = newUser();
        long soleFamily = newFamily(soleOwner, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna"}""").get("id").asLong();
        for (Scenario scenario : List.of(byApi, byRunbook)) {
            String path = "/api/family-ledgers/" + scenario.family() + "/invites";
            body(post(scenario.alice(), path, """
                    {"kind": "NEW_MEMBER"}"""), HttpStatus.CREATED);
            String link = body(post(scenario.alice(), path, """
                    {"kind": "NEW_MEMBER"}"""), HttpStatus.CREATED).get("link").asText();
            assertThat(mvc.post().uri("/api/invites/accept").with(member(newUser())).with(request -> {
                request.setRemoteAddr("198.18.1." + (scenario.family() % 250 + 1));
                return request;
            }).contentType("application/json").content("{\"token\": \"%s\", \"displayName\": \"Erin\"}"
                    .formatted(link.substring(link.indexOf('#') + 1)))).hasStatus(HttpStatus.OK);
            body(post(scenario.alice(), path, """
                    {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "%s"}""".formatted(jdbc.sql(
                    "SELECT id FROM ledger_member WHERE ledger_id = ? AND display_name = 'Kid'")
                    .param(scenario.family()).query(Long.class).single(),
                    jdbc.sql("SELECT current_date").query(LocalDate.class).single())), HttpStatus.CREATED);
        }
        body(post(soleOwner, "/api/family-ledgers/" + soleFamily + "/invites", """
                {"kind": "NEW_MEMBER"}"""), HttpStatus.CREATED);

        assertThat(delete(byApi.alice(), "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        runbooksDeleteAUser(byRunbook.alice());
        runbooksDeleteAUser(soleOwner);

        for (Scenario scenario : List.of(byApi, byRunbook)) {
            JsonNode invites = ok(get(scenario.bob(), "/api/family-ledgers/" + scenario.family() + "/invites"));
            assertThat(invites.findValuesAsText("status")).containsExactly("REVOKED", "ACCEPTED", "REVOKED");
            assertThat(invites.findValuesAsText("displayName")).contains("Former member", "Erin");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_invite WHERE ledger_id = ?").param(soleFamily)
                .query(Long.class).single()).isZero();
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
        assertThat(records(byRunbook.family())).isEqualTo(records(byApi.family()));
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
        // A record Alice paid with her cash, and one Bob paid and will specify later, both split by the rule.
        long groceries = ok(get(alice, uri + "/categories")).get(0).get("id").asLong();
        body(post(alice, uri + "/records", """
                {"date": "%s", "categoryId": %d, "amount": "30", "comment": "Alice's shop", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(LocalDate.now(), groceries, created.get("memberId").asLong(),
                accountId(alice, "CASH"))), HttpStatus.CREATED);
        body(post(bob, uri + "/records", """
                {"date": "%s", "categoryId": %d, "amount": "12", "payerMemberId": %d, "paymentLater": true}"""
                .formatted(LocalDate.now(), groceries, bobs)), HttpStatus.CREATED);
        return new Scenario(alice, bob, family);
    }

    /** The family ledger's records, shares and journal, without ids and times, to compare two ledgers. */
    private List<String> records(long family) {
        return jdbc.sql("""
                SELECT concat_ws(' ', r.type, r.record_date, r.base_amount, r.comment, r.deleted_at IS NULL,
                    (SELECT string_agg(s.amount::text, ',' ORDER BY s.amount) FROM family_share s WHERE s.record_id = r.id))
                FROM family_record r WHERE r.ledger_id = :family
                UNION ALL
                SELECT concat_ws(' ', c.action, c.changes::text) FROM family_record_change c
                WHERE c.ledger_id = :family AND c.record_id IS NULL
                ORDER BY 1""").param("family", family).query(String.class).list();
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
                    "SELECT 'writer ' || set_config('app.writer', 'delete-all', true)",
                    "DELETE FROM user_settings WHERE user_id = ?",
                    "DELETE FROM journal_entry WHERE user_id = ?",
                    "DELETE FROM import_batch WHERE user_id = ?",
                    "DELETE FROM account WHERE user_id = ?",
                    "DELETE FROM category WHERE user_id = ?",
                    "DELETE FROM counterparty WHERE user_id = ?",
                    "DELETE FROM exchange_rate WHERE user_id = ?",
                    "DELETE FROM transactions WHERE user_id IN (SELECT id FROM users WHERE keycloak_id = ?)",
                    "DELETE FROM categories WHERE user_id IN (SELECT id FROM users WHERE keycloak_id = ?)",
                    "SELECT release_family_memberships(?)",
                    "DELETE FROM ledger WHERE id IN (SELECT ledger_id FROM ledger_member WHERE user_sub = ? "
                            + "AND ledger_type = 'PERSONAL')",
                    "DELETE FROM users WHERE keycloak_id = ?")) {
                var sql = statement.contains("?") ? jdbc.sql(statement).param(sub) : jdbc.sql(statement);
                if (statement.startsWith("SELECT")) {
                    sql.query().singleValue();
                } else {
                    sql.update();
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
