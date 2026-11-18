package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.ledger.history.ActivityLog;
import com.example.financetracker.ledger.history.HistoryIntegrity;
import com.example.financetracker.ledger.history.HistoryJobs;
import com.example.financetracker.ledger.history.RecordContents;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HIST-1 (ADR 0006, D-990 to D-1042) through the API: a version for every change of a record and none for a save that
 * changes nothing, the revision per change set, what a family record's version holds and never holds, the backstop,
 * the baseline and the reconciling of what the previous image wrote (D-1040), the sign-in events' deletion after 13
 * months, and the state-at-revision oracle.
 */
class HistoryApiTests extends FamilyApiTest {

    @Autowired
    private RecordContents contents;
    @Autowired
    private HistoryJobs jobs;
    @Autowired
    private HistoryIntegrity integrity;
    @Autowired
    private ActivityLog activity;
    @Autowired
    private TransactionTemplate transaction;

    private long personalLedger(String user) {
        return jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(user).query(Long.class).single();
    }

    private long revision(long ledger) {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(ledger).query(Long.class).single();
    }

    private List<String> versions(long ledger, String type, long id) {
        return jdbc.sql("""
                SELECT seq || ' ' || op || ' r' || revision || ' ' || cause || ' ' || actor_kind
                FROM record_version WHERE ledger_id = ? AND record_type = ? AND record_id = ? ORDER BY seq""")
                .param(ledger).param(type).param(id).query(String.class).list();
    }

    private long countVersions(long ledger) {
        return jdbc.sql("SELECT count(*) FROM record_version WHERE ledger_id = ?").param(ledger)
                .query(Long.class).single();
    }

    private String command(long account, long category, String amount, String memo) {
        return """
                {"kind": "EXPENSE", "entryDate": "2026-09-10", "memo": %s, "accountId": %d, "currency": "EUR",
                 "amount": "%s", "categoryId": %d}""".formatted(memo == null ? null : '"' + memo + '"', account, amount,
                category);
    }

    @Test
    void anEntryHasAVersionPerChangeAndNoneForASaveThatChangesNothing() throws IOException {
        long ledger = personalLedger(alice);
        long cash = accountId(alice, "CASH");
        long groceries = categoryId(alice, "GROCERIES");
        JsonNode created = newEntry(alice, command(cash, groceries, "12.50", "first"));
        long id = created.get("id").asLong();

        assertThat(versions(ledger, "ENTRY", id)).containsExactly("1 CREATE r" + revision(ledger) + " EDIT USER");
        long afterCreate = revision(ledger);
        // Ids, never names: the content names the account and the category by id.
        String content = jdbc.sql("SELECT content::text FROM record_version WHERE record_type = 'ENTRY' AND record_id = ?")
                .param(id).query(String.class).single();
        assertThat(content).contains("\"account\": " + cash).doesNotContain("Cash").doesNotContain("Groceries");
        assertThat(jdbc.sql("""
                SELECT m.user_sub FROM record_version v JOIN ledger_member m ON m.id = v.actor_member
                WHERE v.record_type = 'ENTRY' AND v.record_id = ?""").param(id).query(String.class).single())
                .isEqualTo(alice);

        // The same fields again, with 12.5 for 12.50: no version, no revision, and the entry's own version stays.
        JsonNode same = ok(put(alice, "/api/entries/" + id + "?version=" + created.get("version").asInt(),
                command(cash, groceries, "12.5", "first")));
        assertThat(same.get("version").asInt()).isEqualTo(created.get("version").asInt());
        assertThat(versions(ledger, "ENTRY", id)).hasSize(1);
        assertThat(revision(ledger)).isEqualTo(afterCreate);

        JsonNode changed = ok(put(alice, "/api/entries/" + id + "?version=" + created.get("version").asInt(),
                command(cash, groceries, "12.50", "second")));
        assertThat(versions(ledger, "ENTRY", id)).hasSize(2).last().isEqualTo("2 UPDATE r" + (afterCreate + 1)
                + " EDIT USER");

        assertThat(delete(alice, "/api/entries/" + id + "?version=" + changed.get("version").asInt()))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(versions(ledger, "ENTRY", id)).hasSize(3).last().isEqualTo("3 DELETE r" + (afterCreate + 2)
                + " EDIT USER");
        // The delete keeps the last content, so that it can come back (D-1002).
        assertThat(jdbc.sql("SELECT content ->> 'memo' FROM record_version WHERE record_type = 'ENTRY' "
                + "AND record_id = ? AND seq = 3").param(id).query(String.class).single()).isEqualTo("second");
        assertThat(integrity.check(ledger).problems()).isEmpty();
    }

    @Test
    void accountsCategoriesAndPayeesHaveVersionsToo() throws IOException {
        long ledger = personalLedger(alice);
        long payee = newCounterparty(alice, "Corner shop");
        assertThat(versions(ledger, "COUNTERPARTY", payee)).hasSize(1).first().asString().startsWith("1 CREATE");
        ok(patch(alice, "/api/counterparties/" + payee, """
                {"name": "Corner store"}"""));
        assertThat(versions(ledger, "COUNTERPARTY", payee)).hasSize(2).last().asString().startsWith("2 UPDATE");
        ok(patch(alice, "/api/counterparties/" + payee, """
                {"name": "Corner store"}"""));
        assertThat(versions(ledger, "COUNTERPARTY", payee)).hasSize(2);

        long account = accountId(alice, "CASH");
        // The starter ledger's rows were versioned when the user was provisioned, by the system (D-1020).
        assertThat(versions(ledger, "ACCOUNT", account)).hasSize(1).first().asString()
                .contains("CREATE").contains("PROVISION").contains("SYSTEM");
        ok(patch(alice, "/api/accounts/" + account, """
                {"name": "Wallet"}"""));
        assertThat(versions(ledger, "ACCOUNT", account)).hasSize(2).last().asString()
                .contains("UPDATE").contains("EDIT").contains("USER");
        assertThat(jdbc.sql("SELECT content ->> 'name' FROM record_version WHERE record_type = 'ACCOUNT' "
                + "AND record_id = ? AND seq = 2").param(account).query(String.class).single()).isEqualTo("Wallet");

        long category = categoryId(alice, "CLOTHING");
        ok(patch(alice, "/api/categories/" + category, """
                {"archived": true}"""));
        assertThat(jdbc.sql("SELECT content ->> 'archived' FROM record_version WHERE record_type = 'CATEGORY' "
                + "AND record_id = ? ORDER BY seq DESC LIMIT 1").param(category).query(String.class).single())
                .isEqualTo("true");
        assertThat(integrity.check(ledger).problems()).isEmpty();
    }

    @Test
    void aFamilyRecordHasAVersionBesideItsJournalAndNeverTheCallersPaymentSide() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        JsonNode record = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "100", "comment": "Weekly shop",
                 "payerMemberId": %d, "paymentAccountId": %d}""".formatted(groceries, mum, current)));
        long id = record.get("id").asLong();
        assertThat(versions(family, "FAMILY_RECORD", id)).hasSize(1).first().asString()
                .startsWith("1 CREATE").endsWith("EDIT USER");
        String content = jdbc.sql("SELECT content::text FROM record_version WHERE record_type = 'FAMILY_RECORD' "
                + "AND record_id = ?").param(id).query(String.class).single();
        // The family fields and the shares only: no account, no entry, no private side (D-16, D-1022).
        assertThat(content).contains("\"comment\": \"Weekly shop\"").contains("\"shares\"")
                .doesNotContain("account").doesNotContain("entry").doesNotContain("Current");
        assertThat(jdbc.sql("SELECT count(*) FROM family_record_change WHERE record_id = ?").param(id)
                .query(Long.class).single()).isEqualTo(1);

        ok(patch(alice, uri + "/records/" + id + "?version=0", """
                {"comment": "Mine"}"""));
        assertThat(versions(family, "FAMILY_RECORD", id)).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM family_record_change WHERE record_id = ?").param(id)
                .query(Long.class).single()).isEqualTo(2);
        // The same comment again changes nothing.
        ok(patch(alice, uri + "/records/" + id + "?version=1", """
                {"comment": "Mine"}"""));
        assertThat(versions(family, "FAMILY_RECORD", id)).hasSize(2);

        assertThat(delete(alice, uri + "/records/" + id + "?version=1")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(versions(family, "FAMILY_RECORD", id)).hasSize(3).last().asString().startsWith("3 DELETE");
        assertThat(jdbc.sql("SELECT content ->> 'deleted' FROM record_version WHERE record_type = 'FAMILY_RECORD' "
                + "AND record_id = ? AND seq = 3").param(id).query(String.class).single()).isEqualTo("true");
        assertThat(integrity.check(family).problems()).isEmpty();
    }

    @Test
    void aLeaversCommentsAreErasedFromTheVersionsAndOthersStay() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        long cash = accountId(bob, "CASH");
        long first = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "40", "comment": "Alice's words",
                 "payerMemberId": %d, "paymentAccountId": %d}""".formatted(groceries, mum, current))).get("id").asLong();
        long second = created(post(bob, uri + "/records", """
                {"date": "2026-09-11", "categoryId": %d, "amount": "20", "comment": "Bob's words",
                 "payerMemberId": %d, "paymentAccountId": %d}""".formatted(groceries, dad, cash))).get("id").asLong();
        // Alice, an owner, changes Bob's category and then writes a comment of her own on his record.
        ok(patch(alice, uri + "/records/" + second + "?version=0", """
                {"categoryId": %d}""".formatted(rent)));
        ok(patch(alice, uri + "/records/" + second + "?version=1", """
                {"comment": "Alice's note on Bob's"}"""));
        assertThat(jdbc.sql("SELECT count(*) FROM record_version WHERE ledger_id = ? AND content::text LIKE '%Alice%'")
                .param(family).query(Long.class).single()).isEqualTo(2);

        assertThat(delete(alice, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        // What Alice wrote is gone from every version (the record's own and journal's erasure is D-20's); Bob's own
        // comment stays in the versions where it was the record's, and nothing else in them changes.
        assertThat(jdbc.sql("SELECT count(*) FROM record_version WHERE ledger_id = ? AND content::text LIKE '%Alice%'")
                .param(family).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT seq || ' ' || coalesce(content ->> 'comment', '-') FROM record_version "
                + "WHERE ledger_id = ? AND record_id = ? ORDER BY seq").param(family).param(second).query(String.class)
                .list()).containsExactly("1 Bob's words", "2 Bob's words", "3 -");
        assertThat(jdbc.sql("SELECT coalesce(content ->> 'comment', '-') FROM record_version "
                + "WHERE ledger_id = ? AND record_id = ?").param(family).param(first).query(String.class).list())
                .containsExactly("-");
        assertThat(integrity.check(family).problems()).isEmpty();
    }

    @Test
    void aWritePathWithoutAVersionFailsAtCommitButTheImageBeforeHist1IsNotRefused() throws Exception {
        long ledger = personalLedger(alice);
        // A connection of the application says it writes versions, so a raw insert without one fails at commit.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                jdbc.sql("INSERT INTO counterparty (user_id, ledger_id, name) VALUES (?, ?, 'No version')")
                        .param(alice).param(ledger).update()))
                .hasMessageContaining("was changed without a version");
        assertThat(jdbc.sql("SELECT count(*) FROM counterparty WHERE ledger_id = ? AND name = 'No version'")
                .param(ledger).query(Long.class).single()).isZero();
        // The same insert as the previous image makes it (D-1040): nothing refuses it.
        try (Connection old = previousImageConnection()) {
            old.createStatement().execute("INSERT INTO counterparty (user_id, ledger_id, name) VALUES ('" + alice + "', "
                    + ledger + ", 'Old image')");
            old.createStatement().execute("UPDATE counterparty SET name = 'Old image, renamed' WHERE name = 'Old image'");
            old.createStatement().execute("DELETE FROM counterparty WHERE name = 'Old image, renamed'");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM counterparty WHERE ledger_id = ? AND name LIKE 'Old image%'")
                .param(ledger).query(Long.class).single()).isZero();
        // A write that comes with its version passes.
        long payee = newCounterparty(alice, "With a version");
        assertThat(versions(ledger, "COUNTERPARTY", payee)).hasSize(1);
    }

    @Test
    void whatThePreviousImageWroteIsBaselinedAndReconciledAndASecondRunWritesNothing() throws Exception {
        long ledger = personalLedger(alice);
        long cash = accountId(alice, "CASH");
        long groceries = categoryId(alice, "GROCERIES");
        JsonNode versioned = newEntry(alice, command(cash, groceries, "5.00", "versioned"));
        long versionedId = versioned.get("id").asLong();
        long old;
        try (Connection db = previousImageConnection()) {
            // A record the image before HIST-1 made, and a change it made to a versioned one.
            var rows = db.createStatement().executeQuery("INSERT INTO counterparty (user_id, ledger_id, name) VALUES ('"
                    + alice + "', " + ledger + ", 'Written by the old image') RETURNING id");
            rows.next();
            old = rows.getLong(1);
            db.createStatement().execute("UPDATE journal_entry SET memo = 'changed by the old image', version = version + 1 "
                    + "WHERE id = " + versionedId);
        }
        assertThat(versions(ledger, "COUNTERPARTY", old)).isEmpty();
        var found = integrity.check(ledger);
        assertThat(found.unversioned()).isGreaterThanOrEqualTo(1);
        assertThat(found.problems()).extracting(p -> p.type() + " " + p.recordId())
                .containsExactly("ENTRY " + versionedId);

        long before = revision(ledger);
        HistoryJobs.BaselineResult first = jobs.baseline();
        assertThat(first.reconciled()).isGreaterThanOrEqualTo(1);
        assertThat(first.baselines()).isGreaterThanOrEqualTo(1);
        assertThat(versions(ledger, "COUNTERPARTY", old)).hasSize(1).first().asString().contains("BASELINE");
        assertThat(versions(ledger, "ENTRY", versionedId)).hasSize(2).last().asString().contains("BASELINE");
        assertThat(jdbc.sql("SELECT cause FROM record_version WHERE record_type = 'ENTRY' AND record_id = ? AND seq = 2")
                .param(versionedId).query(String.class).single()).isEqualTo("RECONCILE");
        assertThat(revision(ledger)).isEqualTo(before);
        assertThat(integrity.check(ledger).problems()).isEmpty();

        long versionsNow = countVersions(ledger);
        HistoryJobs.BaselineResult second = jobs.baseline();
        assertThat(second.baselines() + second.reconciled() + second.deletes()).isZero();
        assertThat(countVersions(ledger)).isEqualTo(versionsNow);

        // The lazy baseline: the old image changes the record again, and the next change through the application
        // first writes what the old image left (a RECONCILE) and then its own version.
        try (Connection db = previousImageConnection()) {
            db.createStatement().execute("UPDATE counterparty SET name = 'Renamed by the old image' WHERE id = " + old);
        }
        ok(patch(alice, "/api/counterparties/" + old, """
                {"name": "Renamed by the application"}"""));
        assertThat(jdbc.sql("SELECT seq || ' ' || cause || ' ' || (content ->> 'name') FROM record_version "
                + "WHERE record_type = 'COUNTERPARTY' AND record_id = ? ORDER BY seq").param(old).query(String.class)
                .list()).containsExactly("1 BASELINE Written by the old image",
                        "2 RECONCILE Renamed by the old image", "3 EDIT Renamed by the application");
        assertThat(integrity.check(ledger).problems()).isEmpty();
    }

    @Test
    void aDeleteByThePreviousImageIsAGhostThatTheBaselineJobCloses() throws Exception {
        long ledger = personalLedger(alice);
        long payee = newCounterparty(alice, "Soon gone");
        try (Connection db = previousImageConnection()) {
            db.createStatement().execute("DELETE FROM counterparty WHERE id = " + payee);
        }
        assertThat(integrity.check(ledger).problems()).extracting(p -> p.type() + " " + p.recordId())
                .containsExactly("COUNTERPARTY " + payee);
        assertThat(jobs.baseline().deletes()).isEqualTo(1);
        assertThat(versions(ledger, "COUNTERPARTY", payee)).hasSize(2).last().asString().contains("DELETE");
        assertThat(integrity.check(ledger).problems()).isEmpty();
    }

    @Test
    void theStateAtEveryRevisionIsWhatTheVersionsSayItWas() throws IOException {
        long ledger = personalLedger(alice);
        long cash = accountId(alice, "CASH");
        long groceries = categoryId(alice, "GROCERIES");
        List<HistoryOracle.Snapshot> snapshots = new ArrayList<>();
        snapshots.add(HistoryOracle.capture(jdbc, contents, ledger));
        JsonNode one = newEntry(alice, command(cash, groceries, "1.00", "one"));
        snapshots.add(HistoryOracle.capture(jdbc, contents, ledger));
        long payee = newCounterparty(alice, "Oracle shop");
        snapshots.add(HistoryOracle.capture(jdbc, contents, ledger));
        JsonNode two = newEntry(alice, command(cash, groceries, "2.00", "two"));
        snapshots.add(HistoryOracle.capture(jdbc, contents, ledger));
        ok(put(alice, "/api/entries/" + one.get("id").asLong() + "?version=" + one.get("version").asInt(),
                command(cash, groceries, "1.50", "one, changed")));
        snapshots.add(HistoryOracle.capture(jdbc, contents, ledger));
        ok(patch(alice, "/api/counterparties/" + payee, """
                {"name": "Oracle shop, renamed"}"""));
        snapshots.add(HistoryOracle.capture(jdbc, contents, ledger));
        assertThat(delete(alice, "/api/entries/" + two.get("id").asLong() + "?version=" + two.get("version").asInt()))
                .hasStatus(HttpStatus.NO_CONTENT);
        snapshots.add(HistoryOracle.capture(jdbc, contents, ledger));
        ok(patch(alice, "/api/accounts/" + accountId(alice, "SAVINGS_ACCOUNT"), """
                {"archived": true}"""));
        snapshots.add(HistoryOracle.capture(jdbc, contents, ledger));

        assertThat(snapshots).extracting(HistoryOracle.Snapshot::revision).isSorted();
        snapshots.forEach(snapshot -> HistoryOracle.verify(jdbc, snapshot));
    }

    @Test
    void theFamilyLedgersRevisionsAndStatesFollowItsRecords() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        List<HistoryOracle.Snapshot> snapshots = new ArrayList<>();
        snapshots.add(HistoryOracle.capture(jdbc, contents, family));
        long a = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "100", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(groceries, mum, current))).get("id").asLong();
        snapshots.add(HistoryOracle.capture(jdbc, contents, family));
        ok(patch(alice, uri + "/records/" + a + "?version=0", """
                {"comment": "changed"}"""));
        snapshots.add(HistoryOracle.capture(jdbc, contents, family));
        long b = created(post(alice, uri + "/records", """
                {"date": "2026-09-12", "categoryId": %d, "amount": "30", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(groceries, mum, current))).get("id").asLong();
        snapshots.add(HistoryOracle.capture(jdbc, contents, family));
        assertThat(delete(alice, uri + "/records/" + a + "?version=1")).hasStatus(HttpStatus.NO_CONTENT);
        snapshots.add(HistoryOracle.capture(jdbc, contents, family));
        created(post(alice, uri + "/categories", """
                {"code": "TRAVEL", "name": "Travel", "type": "EXPENSE"}"""));
        snapshots.add(HistoryOracle.capture(jdbc, contents, family));
        assertThat(b).isNotEqualTo(a);

        assertThat(snapshots).extracting(HistoryOracle.Snapshot::revision).isSorted();
        snapshots.forEach(snapshot -> HistoryOracle.verify(jdbc, snapshot));
        // The posting into the members' books wrote no version: a derived row is no record (D-998).
        assertThat(jdbc.sql("""
                SELECT count(*) FROM record_version v JOIN journal_entry e
                    ON v.record_type = 'ENTRY' AND v.record_id = e.id
                WHERE e.kind LIKE 'FAMILY\\_%' AND v.ledger_id IN (?, ?)""")
                .param(personalLedger(alice)).param(personalLedger(bob)).query(Long.class).single()).isZero();
    }

    @Test
    void signInEventsOlderThanThirteenMonthsAreDeletedAndNothingElseIs() {
        String sub = newUser();
        activity.logAlone(sub, "SIGN_IN", null);
        activity.logAlone(sub, "SESSION_EXPIRED", java.util.Map.of("reason", "x"));
        // Rows of every age, written as the application would; their age is set with the guard's own writer.
        jdbc.sql("INSERT INTO activity_event (actor_sub, kind, at) VALUES (?, 'SIGN_IN', now() - interval '14 months')")
                .param(sub).update();
        jdbc.sql("INSERT INTO activity_event (actor_sub, kind, at) VALUES (?, 'SIGN_OUT', now() - interval '12 months')")
                .param(sub).update();
        jdbc.sql("INSERT INTO activity_event (actor_sub, kind, at, ledger_id) "
                + "VALUES (?, 'MEMBER_LEFT', now() - interval '20 months', ?)").param(sub).param(family).update();

        assertThat(jobs.purgeSignInEvents()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT kind || ' ' || (at < now() - interval '13 months') FROM activity_event "
                + "WHERE actor_sub = ? ORDER BY id").param(sub).query(String.class).list())
                .containsExactlyInAnyOrder("SIGN_IN false", "SESSION_EXPIRED false", "SIGN_OUT false",
                        "MEMBER_LEFT true");
        assertThat(jobs.purgeSignInEvents()).isZero();
        // An event is immutable: no update, and a delete only by the jobs and the erasure.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                "UPDATE activity_event SET kind = 'X' WHERE actor_sub = ?").param(sub).update())
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                "DELETE FROM activity_event WHERE actor_sub = ?").param(sub).update())
                .hasMessageContaining("immutable");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                "UPDATE record_version SET op = 'UPDATE' WHERE ledger_id = ?").param(family).update())
                .hasMessageContaining("immutable");
    }

    @Test
    void deletingAllMyDataRemovesTheUsersHistoryAndEventsAndALedgersGoWithTheirs() throws IOException {
        String user = newUser();
        ok(get(user, "/api/accounts"));
        long ledger = personalLedger(user);
        newExpense(user, "2026-09-10", "3.00", null, "gone soon");
        activity.logAlone(user, "SIGN_IN", null);
        assertThat(countVersions(ledger)).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM activity_event WHERE actor_sub = ?").param(user)
                .query(Long.class).single()).isEqualTo(1);

        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(countVersions(ledger)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM activity_event WHERE actor_sub = ?").param(user)
                .query(Long.class).single()).isZero();
        assertThat(rowsOf(user)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
    }

    @Test
    void anImportsVersionsNameItsBatchAndTheSameFileAgainWritesNoVersionAndNoRevision() throws IOException {
        String user = newUser();
        byte[] transactions = personalRows();
        long ledger;

        // A dry run writes, reports and rolls back: no version and no revision are left.
        assertThat(ok(importWorkbook(user, transactions, "true")).get("outcome").asText()).isEqualTo("DRY_RUN");
        ledger = personalLedger(user);
        assertThat(jdbc.sql("SELECT count(*) FROM record_version WHERE ledger_id = ? AND cause = 'IMPORT'")
                .param(ledger).query(Long.class).single()).isZero();

        assertThat(ok(importWorkbook(user, transactions, "false")).get("outcome").asText()).isEqualTo("COMMITTED");
        long batch = jdbc.sql("SELECT id FROM import_batch WHERE ledger_id = ?").param(ledger).query(Long.class)
                .single();
        // Every imported entry has its version, by the import, naming the batch, in one change set.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM record_version
                WHERE ledger_id = ? AND record_type = 'ENTRY' AND cause = 'IMPORT' AND cause_batch = ?
                  AND actor_kind = 'IMPORT' AND op = 'CREATE'""").param(ledger).param(batch).query(Long.class).single())
                .isEqualTo(9);
        assertThat(jdbc.sql("""
                SELECT count(DISTINCT revision) FROM record_version
                WHERE ledger_id = ? AND cause = 'IMPORT' AND record_type = 'ENTRY'""").param(ledger)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(integrity.check(ledger).problems()).isEmpty();

        // D-995: the same file again recognises every row, writes no version, takes no revision.
        long versions = countVersions(ledger);
        long revision = revision(ledger);
        assertThat(ok(importWorkbook(user, transactions, "false")).get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(countVersions(ledger)).isEqualTo(versions);
        assertThat(revision(ledger)).isEqualTo(revision);
        assertThat(integrity.check(ledger).problems()).isEmpty();
    }

    @Test
    void theDemoAndAMembersDepartureWriteTheirVersionsWithTheirCauses() throws IOException {
        String user = newUser();
        ok(get(user, "/api/accounts"));
        long personal = personalLedger(user);
        ok(post(user, "/api/demo-data", null));
        assertThat(jdbc.sql("SELECT cause || ' ' || actor_kind FROM record_version WHERE ledger_id = ? "
                + "GROUP BY cause, actor_kind ORDER BY 1").param(personal).query(String.class).list())
                .contains("DEMO USER", "PROVISION SYSTEM");
        assertThat(integrity.check(personal).problems()).isEmpty();

        // Bob leaves the family: the entries that the family posted become his own and move to categories of his own (Bob
        // had one with the code already, so no category changes), and his debt account an ordinary liability: all
        // written by the system (ADR 0006, 2.2).
        long bobsLedger = personalLedger(bob);
        long cash = accountId(bob, "CASH");
        newEntry(bob, command(cash, categoryId(bob, "GROCERIES"), "7.00", "bob's own"));
        created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "10", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(groceries, mum, accountId(alice, "CASH"))));
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT record_type || ' ' || cause || ' ' || actor_kind FROM record_version "
                + "WHERE ledger_id = ? AND cause = 'LEAVE' GROUP BY 1 ORDER BY 1").param(bobsLedger)
                .query(String.class).list()).contains("ACCOUNT LEAVE SYSTEM", "ENTRY LEAVE SYSTEM");
        assertThat(integrity.check(bobsLedger).problems()).isEmpty();
    }

    @Test
    void theBaselineOfALedgerThatHasNeverChangedTakesRevisionZero() {
        // A ledger and its account as the image before HIST-1 makes them: no version, and the ledger's revision is 0.
        String sub = newUser();
        rawSql("INSERT INTO account (user_id, code, name, type) VALUES (?, 'CASH', 'Cash', 'ASSET')", sub);
        long ledger = personalLedger(sub);
        long account = jdbc.sql("SELECT id FROM account WHERE ledger_id = ?").param(ledger).query(Long.class).single();
        assertThat(revision(ledger)).isZero();

        assertThat(jobs.baseline().baselines()).isGreaterThanOrEqualTo(1);

        assertThat(versions(ledger, "ACCOUNT", account)).containsExactly("1 BASELINE r0 BASELINE SYSTEM");
        assertThat(revision(ledger)).isZero();
        assertThat(integrity.check(ledger).problems()).isEmpty();
    }

    @Test
    void manyFirstChangesAtOnceDoNotStarveTheConnectionPool() throws Exception {
        // Records the previous image made have no version, so each first change reads the committed state. Read through
        // the application's own pool, as a nested transaction would, that deadlocks once more requests than the pool
        // holds do it together (every one holds a connection and asks for a second).
        String user = newUser();
        ok(get(user, "/api/accounts"));
        long ledger = personalLedger(user);
        int n = 30;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rawSql("INSERT INTO counterparty (user_id, ledger_id, name) VALUES (?, ?, ?)", user, ledger, "Old " + i);
        }
        ids.addAll(jdbc.sql("SELECT id FROM counterparty WHERE ledger_id = ? AND name LIKE 'Old %' ORDER BY id")
                .param(ledger).query(Long.class).list());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<HttpStatus>> answers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                long id = ids.get(i);
                int number = i;
                answers.add(pool.submit(() -> HttpStatus.valueOf(patch(user, "/api/counterparties/" + id, """
                        {"name": "New %d"}""".formatted(number)).getResponse().getStatus())));
            }
            for (var answer : answers) {
                assertThat(answer.get(25, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM record_version WHERE ledger_id = ? AND record_type = 'COUNTERPARTY'")
                .param(ledger).query(Long.class).single()).isEqualTo(2L * n);
        assertThat(integrity.check(ledger).problems()).isEmpty();
    }
}
