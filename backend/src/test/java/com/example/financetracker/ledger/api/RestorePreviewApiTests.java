package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * HIST-4 part 2a through the API (ADR 0006, 6.3, D-1009, D-1220 to D-1225): the read-only restore preview of a snapshot
 * and of one record's version. Each outcome, each blocker, KEPT for a reference a posting still uses (a derived family
 * entry, and a version that archives an account an entry uses), 404 and 409 answers, a preview that writes nothing,
 * the erasure of restore runs, and the time of a preview on 3,000 entries.
 */
class RestorePreviewApiTests extends FamilyApiTest {

    private long personalLedger(String user) {
        return jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(user).query(Long.class).single();
    }

    private long revision(long ledger) {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(ledger).query(Long.class).single();
    }

    private long count(String sql, Object... params) {
        var statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }

    private long snapshot(String user) throws IOException {
        return body(post(user, "/api/snapshots", "{}"), HttpStatus.CREATED).get("id").asLong();
    }

    private JsonNode snapshotPreview(String user, long id) throws IOException {
        return ok(post(user, "/api/snapshots/" + id + "/restore-preview", "{}"));
    }

    private JsonNode versionPreview(String user, String type, long id, int seq) throws IOException {
        return ok(post(user, "/api/history/" + type + "/" + id + "/versions/" + seq + "/restore-preview", "{}"));
    }

    private static int outcome(JsonNode preview, String outcome) {
        return preview.get("counts").get(outcome).asInt();
    }

    private static List<String> reasons(JsonNode preview) {
        List<String> reasons = new ArrayList<>();
        preview.get("blockers").forEach(b -> reasons.add(b.get("reason").asText()));
        return reasons;
    }

    private static List<String> messages(JsonNode preview) {
        List<String> messages = new ArrayList<>();
        preview.get("blockers").forEach(b -> messages.add(b.get("message").asText()));
        return messages;
    }

    private static List<String> items(JsonNode preview, String outcome) {
        List<String> items = new ArrayList<>();
        preview.get("items").get(outcome).forEach(i -> items.add(i.get("recordType").asText() + " "
                + i.get("recordId").asLong()));
        return items;
    }

    private JsonNode edit(String user, long entry, long version, String memo) throws IOException {
        return editWith(user, entry, version, "2026-09-20", "7.00", memo);
    }

    private JsonNode editWith(String user, long entry, long version, String date, String amount, String memo)
            throws IOException {
        return ok(put(user, "/api/entries/" + entry + "?version=" + version, """
                {"kind": "EXPENSE", "entryDate": "%s", "memo": "%s", "accountId": %d,
                 "currency": "EUR", "amount": "%s", "categoryId": %d}""".formatted(date, memo,
                accountId(user, "CASH"), amount, categoryId(user, "GROCERIES"))));
    }

    /** A removal written with plain SQL, then a later live version of the same content: the state stays consistent. */
    private void seedRemovalThenLive(long ledger, String type, long id, int seq, String removal, String live) {
        seedVersion(ledger, type, id, seq, removal, live);
        seedVersion(ledger, type, id, seq + 1, "UPDATE", live);
    }

    /** A version written with plain SQL: the content a test chooses, its hash arbitrary, its revision the ledger's. */
    private void seedVersion(long ledger, String type, long id, int seq, String op, String content) {
        jdbc.sql("""
                INSERT INTO record_version (ledger_id, record_type, record_id, seq, revision, op, content, content_hash,
                                            content_ver, actor_kind, cause)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), sha256(convert_to(?, 'UTF8')), 1, 'SYSTEM', 'EDIT')""")
                .param(ledger).param(type).param(id).param(seq).param(revision(ledger)).param(op).param(content)
                .param("seeded " + type + id + seq + op).update();
    }

    @Test
    void theOutcomesOfASnapshotPreviewAreCountedAndListed() throws IOException {
        long ledger = personalLedger(alice);
        JsonNode kept = newExpense(alice, "2026-09-10", "5.00", null, "one");
        JsonNode unchanged = newExpense(alice, "2026-09-11", "6.00", null, "four");
        JsonNode gone = newExpense(alice, "2026-09-12", "8.00", null, "three");
        long snapshot = snapshot(alice);
        edit(alice, kept.get("id").asLong(), kept.get("version").asLong(), "one changed");
        JsonNode changed = editWith(alice, unchanged.get("id").asLong(), unchanged.get("version").asLong(),
                "2026-09-11", "6.00", "four x");
        editWith(alice, unchanged.get("id").asLong(), changed.get("version").asLong(), "2026-09-11", "6.00", "four");
        assertThat(delete(alice, "/api/entries/" + gone.get("id").asLong() + "?version=" + gone.get("version").asLong())).hasStatus(HttpStatus.NO_CONTENT);
        newExpense(alice, "2026-09-13", "9.00", null, "after the snapshot");

        JsonNode preview = snapshotPreview(alice, snapshot);

        assertThat(preview.get("kind").asText()).isEqualTo("SNAPSHOT");
        assertThat(preview.get("targetRevision").asLong()).isLessThan(preview.get("atRevision").asLong());
        assertThat(preview.get("atRevision").asLong()).isEqualTo(revision(ledger));
        assertThat(outcome(preview, "RESTORED")).isEqualTo(1);
        assertThat(outcome(preview, "RETURNED")).isEqualTo(1);
        assertThat(outcome(preview, "ARCHIVED")).isEqualTo(1);
        assertThat(outcome(preview, "UNCHANGED")).isEqualTo(1);
        assertThat(outcome(preview, "DELETED")).isZero();
        assertThat(outcome(preview, "KEPT")).isZero();
        assertThat(items(preview, "RESTORED")).containsExactly("ENTRY " + kept.get("id").asLong());
        assertThat(items(preview, "RETURNED")).containsExactly("ENTRY " + gone.get("id").asLong());
        assertThat(preview.get("restorable").asBoolean()).isTrue();
        assertThat(reasons(preview)).isEmpty();
    }

    @Test
    void aPreviewWritesNothing() throws IOException {
        long ledger = personalLedger(alice);
        JsonNode entry = newExpense(alice, "2026-09-10", "5.00", null, "one");
        long snapshot = snapshot(alice);
        edit(alice, entry.get("id").asLong(), entry.get("version").asLong(), "changed");
        String[] tables = {"record_version", "snapshot", "restore_run", "imported_entry_deleted", "activity_event"};
        long before = revision(ledger);
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (String table : tables) {
            counts.put(table, count("SELECT count(*) FROM " + table + " WHERE ledger_id = ?", ledger));
        }
        long events = count("SELECT count(*) FROM activity_event WHERE actor_sub = ?", alice);

        snapshotPreview(alice, snapshot);
        versionPreview(alice, "ENTRY", entry.get("id").asLong(), 1);

        assertThat(revision(ledger)).isEqualTo(before);
        for (String table : tables) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE ledger_id = ?", ledger))
                    .as(table).isEqualTo(counts.get(table));
        }
        assertThat(count("SELECT count(*) FROM activity_event WHERE actor_sub = ?", alice)).isEqualTo(events);
    }

    @Test
    void aVersionPreviewRestoresOnlyItsRecord() throws IOException {
        JsonNode entry = newExpense(alice, "2026-09-10", "5.00", null, "one");
        long id = entry.get("id").asLong();
        JsonNode edited = edit(alice, id, entry.get("version").asLong(), "two");
        assertThat(delete(alice, "/api/entries/" + id + "?version=" + edited.get("version").asLong())).hasStatus(HttpStatus.NO_CONTENT);

        // Version 1 (the creation) comes back: the record is gone now (RETURNED).
        assertThat(outcome(versionPreview(alice, "ENTRY", id, 1), "RETURNED")).isEqualTo(1);
        // Version 2 is the edit, and the record is gone now as well.
        assertThat(outcome(versionPreview(alice, "ENTRY", id, 2), "RETURNED")).isEqualTo(1);
        assertThat(outcome(versionPreview(alice, "ENTRY", id, 1), "UNCHANGED")).isZero();
    }

    @Test
    void aVersionThatDeletesARecordIsDeletedWhileTheRecordIsLive() throws IOException {
        long ledger = personalLedger(alice);
        JsonNode entry = newExpense(alice, "2026-09-10", "5.00", null, "one");
        long id = entry.get("id").asLong();
        seedRemovalThenLive(ledger, "ENTRY", id, 2, "DELETE", """
                {"date": "2026-09-10", "kind": "EXPENSE", "memo": "one", "postings": []}""");

        JsonNode preview = versionPreview(alice, "ENTRY", id, 2);

        assertThat(outcome(preview, "DELETED")).isEqualTo(1);
        assertThat(items(preview, "DELETED")).containsExactly("ENTRY " + id);
    }

    @Test
    void aVersionOfAnAccountThatAnEntryStillUsesIsKept() throws IOException {
        long ledger = personalLedger(alice);
        long account = body(post(alice, "/api/accounts", """
                {"code": "SAVINGS_K", "name": "Savings", "type": "ASSET", "defaultCurrency": "EUR"}"""),
                HttpStatus.CREATED).get("id").asLong();
        newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "2026-09-10", "memo": "uses it", "accountId": %d,
                 "currency": "EUR", "amount": "5.00", "categoryId": %d}""".formatted(account,
                categoryId(alice, "GROCERIES")));
        seedRemovalThenLive(ledger, "ACCOUNT", account, 2, "ARCHIVE", """
                {"code": "SAVINGS_K", "name": "Savings", "type": "ASSET", "defaultCurrency": "EUR",
                 "requiresCounterparty": false, "system": false, "archived": false}""");

        JsonNode preview = versionPreview(alice, "ACCOUNT", account, 2);

        assertThat(outcome(preview, "KEPT")).isEqualTo(1);
        assertThat(outcome(preview, "DELETED")).isZero();
        assertThat(reasons(preview)).isEmpty();
        assertThat(preview.get("items").get("KEPT").get(0).get("referenceCount").asInt()).isEqualTo(1);
        assertThat(preview.get("restorable").asBoolean()).isTrue();
    }

    @Test
    void aRemovedAccountNoEntryUsesIsDeleted() throws IOException {
        long ledger = personalLedger(alice);
        long account = body(post(alice, "/api/accounts", """
                {"code": "UNUSED_K", "name": "Unused", "type": "ASSET", "defaultCurrency": "EUR"}"""),
                HttpStatus.CREATED).get("id").asLong();
        seedRemovalThenLive(ledger, "ACCOUNT", account, 2, "ARCHIVE", """
                {"code": "UNUSED_K", "name": "Unused", "type": "ASSET", "defaultCurrency": "EUR",
                 "requiresCounterparty": false, "system": false, "archived": false}""");

        assertThat(outcome(versionPreview(alice, "ACCOUNT", account, 2), "DELETED")).isEqualTo(1);
    }

    @Test
    void aFamilyPostedEntryKeepsItsAccount() throws IOException {
        long snapshot = snapshot(alice);
        long account = body(post(alice, "/api/accounts", """
                {"code": "FAMILY_PAY", "name": "Family pay", "type": "ASSET", "defaultCurrency": "EUR"}"""),
                HttpStatus.CREATED).get("id").asLong();
        // A family record paid from the account: the derived FAMILY_* entry in the personal ledger still uses it.
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "12", mum,
                "\"paymentAccountId\": " + account + ",")));

        JsonNode preview = snapshotPreview(alice, snapshot);

        assertThat(items(preview, "KEPT")).containsExactly("ACCOUNT " + account);
        assertThat(preview.get("items").get("KEPT").get(0).get("referenceCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(preview.get("restorable").asBoolean()).isTrue();
    }

    @Test
    void aReturnedEntryWhoseAccountWasArchivedIsBlocked() throws IOException {
        long account = body(post(alice, "/api/accounts", """
                {"code": "ARCHIVED_LATER", "name": "Later", "type": "ASSET", "defaultCurrency": "EUR"}"""),
                HttpStatus.CREATED).get("id").asLong();
        JsonNode entry = newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "2026-09-10", "memo": "uses later", "accountId": %d,
                 "currency": "EUR", "amount": "5.00", "categoryId": %d}""".formatted(account,
                categoryId(alice, "GROCERIES")));
        long id = entry.get("id").asLong();
        edit(alice, id, entry.get("version").asLong(), "moved");
        ok(patch(alice, "/api/accounts/" + account, """
                {"archived": true}"""));

        JsonNode preview = versionPreview(alice, "ENTRY", id, 1);

        assertThat(reasons(preview)).containsExactly("REFERENCE_MISSING");
        assertThat(preview.get("blockers").get(0).get("recordType").asText()).isEqualTo("ACCOUNT");
        assertThat(preview.get("blockers").get(0).get("recordId").asLong()).isEqualTo(account);
        assertThat(preview.get("restorable").asBoolean()).isFalse();
    }

    @Test
    void aTargetTheValidatorRefusesIsBlocked() throws IOException {
        long ledger = personalLedger(alice);
        JsonNode entry = newExpense(alice, "2026-09-10", "5.00", null, "one");
        long id = entry.get("id").asLong();
        edit(alice, id, entry.get("version").asLong(), "two");
        seedVersion(ledger, "ENTRY", id, 3, "UPDATE", """
                {"date": "2026-09-10", "kind": "EXPENSE", "memo": "bad", "payee": null, "externalRef": null,
                 "postings": [{"account": %d, "currency": "EUR", "amount": "5.00", "category": null,
                               "counterparty": null},
                              {"account": %d, "currency": "EUR", "amount": "-4.00",
                               "category": %d, "counterparty": null}]}""".formatted(accountId(alice, "CASH"),
                accountId(alice, "UNALLOCATED"), categoryId(alice, "GROCERIES")));

        JsonNode preview = versionPreview(alice, "ENTRY", id, 3);

        assertThat(reasons(preview)).contains("INVALID_TARGET");
        assertThat(messages(preview)).anyMatch(m -> m.contains("do not balance"));
        assertThat(preview.get("restorable").asBoolean()).isFalse();
    }

    @Test
    void aRestoredNameThatAnotherCounterpartyHoldsIsBlocked() throws IOException {
        long first = newCounterparty(alice, "Shop");
        ok(patch(alice, "/api/counterparties/" + first, """
                {"name": "Other"}"""));
        newCounterparty(alice, "Shop");

        JsonNode preview = versionPreview(alice, "COUNTERPARTY", first, 1);

        assertThat(reasons(preview)).containsExactly("KEY_COLLISION");
        assertThat(preview.get("blockers").get(0).get("recordId").asLong()).isEqualTo(first);
        assertThat(messages(preview)).anyMatch(m -> m.contains("counterparty"));
    }

    @Test
    void aLedgerWhoseRecordsDontMatchItsHistoryIsBlocked() throws IOException {
        long snapshot = snapshot(alice);
        rawSql("INSERT INTO counterparty (user_id, ledger_id, name) VALUES (?, ?, 'Unversioned')", alice,
                personalLedger(alice));

        JsonNode preview = snapshotPreview(alice, snapshot);

        assertThat(reasons(preview)).containsExactly("HISTORY_INCONSISTENT");
        assertThat(preview.get("blockers").get(0).get("recordType").isNull()).isTrue();
    }

    @Test
    void theSnapshotCapBlocksTheRestore() throws IOException {
        long ledger = personalLedger(alice);
        long snapshot = snapshot(alice);
        jdbc.sql("INSERT INTO snapshot (ledger_id, revision, name, kind) "
                + "SELECT ?, 0, 'Old', 'MANUAL' FROM generate_series(1, 999)").param(ledger).update();

        JsonNode preview = snapshotPreview(alice, snapshot);

        assertThat(reasons(preview)).containsExactly("SNAPSHOT_LIMIT_REACHED");
        assertThat(preview.get("restorable").asBoolean()).isFalse();
    }

    @Test
    void theAnswersAreTheOwnersOnlyAndFamilyRefusalsAreCoded() throws IOException {
        long snapshot = snapshot(alice);
        assertThat(post(bob, "/api/snapshots/" + snapshot + "/restore-preview", "{}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(alice, "/api/snapshots/" + (snapshot + 1_000_000) + "/restore-preview", "{}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(alice, "/api/history/ENTRY/" + (snapshot + 1_000_000) + "/versions/1/restore-preview", "{}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(alice, "/api/history/ENTRY/" + newExpense(alice, "2026-09-10", "5.00", null, "x")
                .get("id").asLong() + "/versions/99/restore-preview", "{}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(alice, "/api/history/NOPE/1/versions/1/restore-preview", "{}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(code(post(alice, uri + "/snapshots/" + snapshot + "/restore-preview", "{}"),
                HttpStatus.CONFLICT)).isEqualTo("FAMILY_SNAPSHOTS_UNSUPPORTED");
        assertThat(code(post(alice, "/api/history/FAMILY_RECORD/1/versions/1/restore-preview", "{}"),
                HttpStatus.CONFLICT)).isEqualTo("FAMILY_SNAPSHOTS_UNSUPPORTED");
        assertThat(post(newUser(), uri + "/snapshots/" + snapshot + "/restore-preview", "{}"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private String code(org.springframework.test.web.servlet.assertj.MvcTestResult result, HttpStatus status)
            throws IOException {
        return body(result, status).get("code").asText();
    }

    @Test
    void deletingAllMyDataTakesTheRestoreRuns() throws IOException {
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        long ledger = personalLedger(carol);
        long snapshot = body(post(carol, "/api/snapshots", "{}"), HttpStatus.CREATED).get("id").asLong();
        jdbc.sql("INSERT INTO restore_run (ledger_id, kind, snapshot_id, target_revision, revision, before_snapshot_id) "
                + "VALUES (?, 'SNAPSHOT', ?, 0, 1, ?)").param(ledger).param(snapshot).param(snapshot).update();
        long run = count("SELECT id FROM restore_run WHERE ledger_id = ?", ledger);
        jdbc.sql("INSERT INTO restore_item (run_id, record_type, record_id, outcome) VALUES (?, 'ACCOUNT', 1, 'DELETED')")
                .param(run).update();

        assertThat(delete(carol, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(count("SELECT count(*) FROM restore_run WHERE ledger_id = ?", ledger)).isZero();
        assertThat(count("SELECT count(*) FROM restore_item WHERE run_id = ?", run)).isZero();
    }

    /** About 3,000 entries (created through the API) and the edit of a third of them after the snapshot. */
    @Test
    void aPreviewOfThreeThousandEntriesIsQuick() throws IOException {
        String user = newUser();
        ok(get(user, "/api/accounts"));
        List<long[]> entries = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            JsonNode entry = newExpense(user, LocalDate.of(2026, 9, 1).plusDays(i % 28).toString(), "5.00", null,
                    "entry " + i);
            entries.add(new long[] {entry.get("id").asLong(), entry.get("version").asLong()});
        }
        long snapshot = snapshot(user);
        for (int i = 0; i < 1000; i++) {
            long[] entry = entries.get(i * 3);
            ok(put(user, "/api/entries/" + entry[0] + "?version=" + entry[1], """
                    {"kind": "EXPENSE", "entryDate": "2026-09-20", "memo": "changed %d", "accountId": %d,
                     "currency": "EUR", "amount": "7.00", "categoryId": %d}""".formatted(i,
                    accountId(user, "CASH"), categoryId(user, "GROCERIES"))));
        }

        long started = System.nanoTime();
        JsonNode preview = snapshotPreview(user, snapshot);
        long millis = (System.nanoTime() - started) / 1_000_000;

        System.out.println("RESTORE-PREVIEW 3000 entries, 1000 changed after the snapshot: " + millis + " ms");
        assertThat(outcome(preview, "RESTORED")).isEqualTo(1000);
        assertThat(reasons(preview)).isEmpty();
        assertThat(millis).isLessThan(2000);
    }
}
