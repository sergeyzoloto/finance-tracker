package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.example.financetracker.ledger.family.ImportSync;
import com.example.financetracker.ledger.history.Canonical;
import com.example.financetracker.ledger.history.HistoryService;
import com.example.financetracker.ledger.history.RecordType;
import com.example.financetracker.ledger.history.RestoreService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HIST-4 part 2b through the API (ADR 0006, 6.3, 6.8, D-1009 to D-1011, D-1219, D-1226 to D-1231): a snapshot's restore
 * applied all or nothing, its undo, the refusals (stale, blocked, a failure in the middle), unique keys that swap, the
 * import's rows and stamps, a save and a read during a restore, the erasure and the time on 3,000 entries.
 */
class RestoreApplyApiTests extends FamilyApiTest {

    private static final List<RecordType> TYPES =
            List.of(RecordType.ENTRY, RecordType.ACCOUNT, RecordType.CATEGORY, RecordType.COUNTERPARTY);

    @Autowired
    private RestoreService restoring;
    @Autowired
    private HistoryService history;
    @Autowired
    private PlatformTransactionManager transactions;

    @AfterEach
    void resetTheHook() {
        restoring.stepHook(null);
    }

    private long ledgerOf(String user) {
        return jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(user).query(Long.class).single();
    }

    private long revision(long ledger) {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(ledger).query(Long.class).single();
    }

    private String fresh() throws IOException {
        String user = newUser();
        ok(get(user, "/api/accounts"));
        return user;
    }

    private long snapshot(String user, String name) throws IOException {
        return body(post(user, "/api/snapshots", "{\"name\": \"" + name + "\"}"), HttpStatus.CREATED).get("id")
                .asLong();
    }

    private MvcTestResult restoreAt(String user, long snapshot, long atRevision) {
        return post(user, "/api/snapshots/" + snapshot + "/restore", "{\"atRevision\": " + atRevision + "}");
    }

    private JsonNode restore(String user, long snapshot) throws IOException {
        long at = ok(post(user, "/api/snapshots/" + snapshot + "/restore-preview", "{}")).get("atRevision").asLong();
        return ok(restoreAt(user, snapshot, at));
    }

    /** Every record of the four types, as canonical content by id. */
    private Map<RecordType, Map<Long, String>> live(long ledger) {
        Map<RecordType, Map<Long, String>> state = new EnumMap<>(RecordType.class);
        for (RecordType type : TYPES) {
            Map<Long, String> rows = new TreeMap<>();
            recordContents.loadAll(type, ledger).forEach((id, c) -> rows.put(id, Canonical.text(c)));
            state.put(type, rows);
        }
        return state;
    }

    /** The rows of every table a restore writes, for the user's ledger. */
    private Map<String, List<String>> dump(String user, long ledger) {
        Map<String, String> queries = new LinkedHashMap<>();
        for (String table : List.of("snapshot", "record_version", "restore_run", "journal_entry", "account", "category",
                "counterparty", "imported_entry_deleted")) {
            queries.put(table, "SELECT to_jsonb(t)::text FROM " + table + " t WHERE ledger_id = ? ORDER BY 1");
        }
        queries.put("restore_item", "SELECT to_jsonb(i)::text FROM restore_item i JOIN restore_run r ON r.id = i.run_id "
                + "WHERE r.ledger_id = ? ORDER BY 1");
        queries.put("posting", "SELECT to_jsonb(p)::text FROM posting p JOIN journal_entry e ON e.id = p.entry_id "
                + "WHERE e.ledger_id = ? ORDER BY 1");
        queries.put("ledger", "SELECT to_jsonb(l)::text FROM ledger l WHERE id = ?");
        Map<String, List<String>> rows = new LinkedHashMap<>();
        queries.forEach((table, sql) -> rows.put(table, jdbc.sql(sql).param(ledger).query(String.class).list()));
        rows.put("activity_event", jdbc.sql("SELECT to_jsonb(a)::text FROM activity_event a WHERE actor_sub = ? "
                + "ORDER BY id").param(user).query(String.class).list());
        return rows;
    }

    private long count(String sql, Object... params) {
        var statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }

    private JsonNode editEntry(String user, JsonNode entry, String memo) throws IOException {
        return ok(put(user, "/api/entries/" + entry.get("id").asLong() + "?version=" + entry.get("version").asLong(), """
                {"kind": "EXPENSE", "entryDate": "2026-09-20", "memo": "%s", "accountId": %d,
                 "currency": "EUR", "amount": "7.25", "categoryId": %d}""".formatted(memo, accountId(user, "CASH"),
                categoryId(user, "GROCERIES"))));
    }

    @Test
    void aRestoreWritesThePlanAndItsUndoGivesBackTheLiveState() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        long shop = newCounterparty(user, "Shop");
        long account = body(post(user, "/api/accounts", """
                {"code": "SAVINGS", "name": "Savings", "type": "ASSET", "defaultCurrency": "EUR"}"""),
                HttpStatus.CREATED).get("id").asLong();
        long category = body(post(user, "/api/categories", """
                {"code": "BOOKS", "name": "Books", "type": "EXPENSE"}"""), HttpStatus.CREATED).get("id").asLong();
        JsonNode edited = newExpense(user, "2026-09-10", "5.00", shop, "edited later");
        JsonNode deleted = newExpense(user, "2026-09-11", "6.00", null, "deleted later");
        long snapshot = snapshot(user, "Start");

        editEntry(user, edited, "changed");
        assertThat(delete(user, "/api/entries/" + deleted.get("id").asLong() + "?version="
                + deleted.get("version").asLong())).hasStatus(HttpStatus.NO_CONTENT);
        long later = newCounterparty(user, "Later");
        newExpense(user, "2026-09-12", "8.00", later, "created later");
        ok(patch(user, "/api/counterparties/" + shop, "{\"name\": \"Shop renamed\"}"));
        ok(patch(user, "/api/accounts/" + account, "{\"name\": \"Savings renamed\"}"));
        ok(patch(user, "/api/categories/" + category, "{\"archived\": true}"));
        body(post(user, "/api/accounts", """
                {"code": "LATER", "name": "Later", "type": "ASSET", "defaultCurrency": "EUR"}"""), HttpStatus.CREATED);
        Map<RecordType, Map<Long, String>> before = live(ledger);

        JsonNode result = restore(user, snapshot);

        long r = result.get("revision").asLong();
        long run = result.get("runId").asLong();
        assertThat(revision(ledger)).isEqualTo(r);
        assertThat(result.get("counts").get("RESTORED").asInt()).isEqualTo(4);
        assertThat(result.get("counts").get("RETURNED").asInt()).isEqualTo(1);
        assertThat(result.get("counts").get("ARCHIVED").asInt()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT memo FROM journal_entry WHERE id = ?").param(edited.get("id").asLong())
                .query(String.class).single()).isEqualTo("edited later");
        assertThat(count("SELECT count(*) FROM restore_item WHERE run_id = ?", run)).isEqualTo(8);
        assertThat(count("SELECT count(*) FROM restore_item WHERE run_id = ? AND written_version_id IS NULL", run))
                .isZero();
        assertThat(count("SELECT count(*) FROM record_version WHERE ledger_id = ? AND revision = ? AND cause = 'RESTORE' "
                + "AND (op = 'RESTORE') = (restored_from IS NOT NULL)", ledger, r)).isEqualTo(8);
        assertThat(count("SELECT count(*) FROM record_version WHERE ledger_id = ? AND revision = ? AND op = 'ARCHIVE'",
                ledger, r)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM restore_run WHERE id = ? AND snapshot_id = ? AND revision = ? "
                + "AND before_snapshot_id = ?", run, snapshot, r, result.get("beforeSnapshotId").asLong())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT name FROM snapshot WHERE id = ? AND kind = 'BEFORE_RESTORE'")
                .param(result.get("beforeSnapshotId").asLong()).query(String.class).single())
                .isEqualTo("Before restoring 'Start'");
        assertThat(count("SELECT count(*) FROM activity_event WHERE actor_sub = ? AND kind = 'RESTORE' AND revision = ? "
                + "AND subject_id = ? AND (details->>'runId')::bigint = ?", user, r, snapshot, run)).isEqualTo(1);
        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
        checkHistory(ledger);

        restore(user, result.get("beforeSnapshotId").asLong());

        assertThat(live(ledger)).isEqualTo(before);
        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
        checkHistory(ledger);
    }

    @Test
    void aRestoreThatChangesNothingStillRunsInFull() throws IOException {
        String user = fresh();
        long snapshot = snapshot(user, "Nothing after");

        JsonNode result = restore(user, snapshot);

        assertThat(count("SELECT count(*) FROM restore_run WHERE id = ?", result.get("runId").asLong())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM snapshot WHERE id = ?", result.get("beforeSnapshotId").asLong()))
                .isEqualTo(1);
    }

    @Test
    void aChangedRevisionIsStaleAndWritesNothing() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        JsonNode entry = newExpense(user, "2026-09-10", "5.00", null, "one");
        long snapshot = snapshot(user, "S");
        editEntry(user, entry, "two");
        long at = ok(post(user, "/api/snapshots/" + snapshot + "/restore-preview", "{}")).get("atRevision").asLong();
        newCounterparty(user, "After the preview");
        Map<String, List<String>> before = dump(user, ledger);

        JsonNode problem = body(restoreAt(user, snapshot, at), HttpStatus.CONFLICT);

        assertThat(problem.get("code").asText()).isEqualTo("RESTORE_STALE");
        assertThat(problem.get("detail").asText()).isEqualTo("The ledger changed since the preview; preview again.");
        assertThat(dump(user, ledger)).isEqualTo(before);
    }

    @Test
    void aBlockerThatAppearsAfterThePreviewRefusesTheRestore() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        JsonNode entry = newExpense(user, "2026-09-10", "5.00", null, "one");
        long snapshot = snapshot(user, "S");
        editEntry(user, entry, "two");
        long at = ok(post(user, "/api/snapshots/" + snapshot + "/restore-preview", "{}")).get("atRevision").asLong();
        // Another transaction, as the image before HIST-1 writes: no version and no revision.
        rawSql("INSERT INTO counterparty (user_id, ledger_id, name) VALUES (?, ?, 'Unversioned')", user, ledger);
        Map<String, List<String>> before = dump(user, ledger);

        JsonNode problem = body(restoreAt(user, snapshot, at), HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(problem.get("restorable").asBoolean()).isFalse();
        assertThat(problem.get("blockers").get(0).get("reason").asText()).isEqualTo("HISTORY_INCONSISTENT");
        assertThat(problem.get("violations")).isNotEmpty();
        assertThat(dump(user, ledger)).isEqualTo(before);
    }

    @Test
    void aFailureInTheMiddleRollsEverythingBack() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        JsonNode first = newExpense(user, "2026-09-10", "5.00", null, "one");
        JsonNode second = newExpense(user, "2026-09-11", "6.00", null, "two");
        long snapshot = snapshot(user, "S");
        editEntry(user, first, "one changed");
        editEntry(user, second, "two changed");
        newCounterparty(user, "Created after");
        long at = ok(post(user, "/api/snapshots/" + snapshot + "/restore-preview", "{}")).get("atRevision").asLong();
        Map<String, List<String>> before = dump(user, ledger);
        restoring.stepHook(step -> {
            if (step.startsWith("entry:")) {
                throw new IllegalStateException("forced failure after " + step);
            }
        });

        assertThat(restoreAt(user, snapshot, at)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(dump(user, ledger)).isEqualTo(before);
    }

    @Test
    void namesAndCodesThatSwapAfterTheSnapshotAreRestored() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        long alpha = newCounterparty(user, "Alpha");
        long beta = newCounterparty(user, "Beta");
        long first = body(post(user, "/api/categories", """
                {"code": "FIRST", "name": "First", "type": "EXPENSE"}"""), HttpStatus.CREATED).get("id").asLong();
        long second = body(post(user, "/api/categories", """
                {"code": "SECOND", "name": "Second", "type": "EXPENSE"}"""), HttpStatus.CREATED).get("id").asLong();
        long snapshot = snapshot(user, "S");
        ok(patch(user, "/api/counterparties/" + alpha, "{\"name\": \"Gamma\"}"));
        ok(patch(user, "/api/counterparties/" + beta, "{\"name\": \"Alpha\"}"));
        ok(patch(user, "/api/counterparties/" + alpha, "{\"name\": \"Beta\"}"));
        // No endpoint changes a category's code; a writer that versions its change swaps them.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("UPDATE category SET code = 'SWAP_TMP' WHERE id = ?").param(first).update();
            jdbc.sql("UPDATE category SET code = 'FIRST' WHERE id = ?").param(second).update();
            jdbc.sql("UPDATE category SET code = 'SECOND' WHERE id = ?").param(first).update();
            history.changed(ledger, RecordType.CATEGORY, first);
            history.changed(ledger, RecordType.CATEGORY, second);
        });

        restore(user, snapshot);

        assertThat(jdbc.sql("SELECT name FROM counterparty WHERE id IN (?, ?) ORDER BY id").param(alpha).param(beta)
                .query(String.class).list()).containsExactly("Alpha", "Beta");
        assertThat(jdbc.sql("SELECT code FROM category WHERE id IN (?, ?) ORDER BY id").param(first).param(second)
                .query(String.class).list()).containsExactly("FIRST", "SECOND");
        checkHistory(ledger);
    }

    private long deletedRows(long ledger) {
        return count("SELECT count(*) FROM imported_entry_deleted WHERE ledger_id = ?", ledger);
    }

    private long anImportedEntry(long ledger) {
        return jdbc.sql("SELECT min(id) FROM journal_entry WHERE ledger_id = ? AND import_hash IS NOT NULL")
                .param(ledger).query(Long.class).single();
    }

    @Test
    void anArchivedImportedEntryLeavesNoDeletedRow() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        long snapshot = snapshot(user, "Before the import");
        assertThat(importWorkbook(user, personalRows(), "false")).hasStatus(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM journal_entry WHERE ledger_id = ? AND import_hash IS NOT NULL", ledger))
                .isPositive();

        JsonNode result = restore(user, snapshot);

        assertThat(result.get("counts").get("ARCHIVED").asInt()).isPositive();
        assertThat(count("SELECT count(*) FROM journal_entry WHERE ledger_id = ? AND external_ref IS NOT NULL", ledger))
                .isZero();
        assertThat(deletedRows(ledger)).isZero();
        checkHistory(ledger);
    }

    @Test
    void aReturnedImportedEntryRemovesItsDeletedRow() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        assertThat(importWorkbook(user, personalRows(), "false")).hasStatus(HttpStatus.OK);
        long id = anImportedEntry(ledger);
        long snapshot = snapshot(user, "After the import");
        long version = count("SELECT version FROM journal_entry WHERE id = ?", id);
        assertThat(delete(user, "/api/entries/" + id + "?version=" + version)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(deletedRows(ledger)).isEqualTo(1);

        JsonNode result = restore(user, snapshot);

        assertThat(result.get("counts").get("RETURNED").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM journal_entry WHERE id = ? AND ledger_id = ?", id, ledger)).isEqualTo(1);
        assertThat(deletedRows(ledger)).isZero();
        // D-1219, D-1233: the marker hash, and the entry counts as untouched since its import.
        assertThat(jdbc.sql("SELECT import_hash FROM journal_entry WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo(RestoreService.RESTORED_IMPORT_HASH).hasSize(64);
        assertThat(sync(id, "another row")).isEqualTo(ImportSync.Outcome.EDITED);
        checkHistory(ledger);
    }

    @Test
    void aDeletedImportedEntryAddsItsDeletedRow() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        assertThat(importWorkbook(user, personalRows(), "false")).hasStatus(HttpStatus.OK);
        long id = anImportedEntry(ledger);
        Map<String, Object> content = recordContents.load(RecordType.ENTRY, ledger, id).orElseThrow();
        // The entry's history says it was gone at the snapshot's revision and came back later.
        long at = revision(ledger);
        seedVersion(ledger, id, at, "DELETE", content);
        long snapshot = jdbc.sql("INSERT INTO snapshot (ledger_id, revision, name, kind) VALUES (?, ?, 'Seeded', "
                + "'MANUAL') RETURNING id").param(ledger).param(at).query(Long.class).single();
        newCounterparty(user, "Moves the revision");
        seedVersion(ledger, id, revision(ledger), "UPDATE", content);

        JsonNode result = restore(user, snapshot);

        assertThat(result.get("counts").get("DELETED").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM journal_entry WHERE id = ?", id)).isZero();
        assertThat(deletedRows(ledger)).isEqualTo(1);
    }

    private void seedVersion(long ledger, long id, long revision, String op, Map<String, Object> content) {
        int seq = (int) count("SELECT max(seq) FROM record_version WHERE ledger_id = ? AND record_type = 'ENTRY' "
                + "AND record_id = ?", ledger, id) + 1;
        jdbc.sql("""
                INSERT INTO record_version (ledger_id, record_type, record_id, seq, revision, op, content, content_hash,
                                            content_ver, actor_kind, cause)
                VALUES (?, 'ENTRY', ?, ?, ?, ?, CAST(? AS jsonb), ?, 1, 'SYSTEM', 'EDIT')""")
                .param(ledger).param(id).param(seq).param(revision).param(op).param(Canonical.text(content))
                .param(Canonical.hash(content)).update();
    }

    @Test
    void aRestoredImportedEntryCountsAsUntouchedSinceItsImport() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        assertThat(importWorkbook(user, personalRows(), "false")).hasStatus(HttpStatus.OK);
        long id = anImportedEntry(ledger);
        long snapshot = snapshot(user, "After the import");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("UPDATE journal_entry SET memo = 'edited in the app', version = version + 1 WHERE id = ?")
                    .param(id).update();
            history.changed(ledger, RecordType.ENTRY, id);
        });
        assertThat(sync(id, "another row")).isEqualTo(ImportSync.Outcome.CONFLICT);

        restore(user, snapshot);

        assertThat(count("SELECT import_version FROM journal_entry WHERE id = ?", id))
                .isEqualTo(count("SELECT version FROM journal_entry WHERE id = ?", id));
        assertThat(sync(id, "another row")).isEqualTo(ImportSync.Outcome.EDITED);
        // The stamp is no content: it wrote no version after the restore's.
        assertThat(jdbc.sql("SELECT op FROM record_version WHERE ledger_id = ? AND record_type = 'ENTRY' AND record_id = ? "
                + "ORDER BY seq DESC LIMIT 1").param(ledger).param(id).query(String.class).single()).isEqualTo("RESTORE");
        checkHistory(ledger);
    }

    @Test
    void aRestoredEntryWhoseRefComesBackIsStampedAndForgetsItsDeletion() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        assertThat(importWorkbook(user, personalRows(), "false")).hasStatus(HttpStatus.OK);
        long id = anImportedEntry(ledger);
        String ref = jdbc.sql("SELECT external_ref FROM journal_entry WHERE id = ?").param(id).query(String.class)
                .single();
        long snapshot = snapshot(user, "With the ref");
        // After the snapshot the entry loses its ref (and stamps) in a versioned write, and the import remembers the
        // ref as deleted.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("UPDATE journal_entry SET external_ref = NULL, import_hash = NULL, import_version = NULL, "
                    + "version = version + 1 WHERE id = ?").param(id).update();
            jdbc.sql("INSERT INTO imported_entry_deleted (ledger_id, external_ref) VALUES (?, ?)").param(ledger)
                    .param(ref).update();
            history.changed(ledger, RecordType.ENTRY, id);
        });

        JsonNode result = restore(user, snapshot);

        assertThat(result.get("counts").get("RESTORED").asInt()).isEqualTo(1);
        assertThat(count("SELECT import_version FROM journal_entry WHERE id = ? AND external_ref = ?", id, ref))
                .isEqualTo(count("SELECT version FROM journal_entry WHERE id = ?", id));
        assertThat(jdbc.sql("SELECT import_hash FROM journal_entry WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo(RestoreService.RESTORED_IMPORT_HASH);
        assertThat(deletedRows(ledger)).isZero();
        checkHistory(ledger);
    }

    private ImportSync.Outcome sync(long id, String fileRow) {
        return jdbc.sql("SELECT import_hash, import_version, version FROM journal_entry WHERE id = ?").param(id)
                .query((row, n) -> ImportSync.recognise(new ImportSync.Known(id, row.getString("import_hash"),
                        row.getInt("import_version"), row.getInt("version"), false),
                        ImportSync.contentHash(fileRow), false))
                .single();
    }

    @Test
    void aSaveDuringARestoreWaitsAndAReadDoesNot() throws Exception {
        String user = fresh();
        JsonNode first = newExpense(user, "2026-09-09", "4.00", null, "written first");
        JsonNode entry = newExpense(user, "2026-09-10", "5.00", null, "one");
        long snapshot = snapshot(user, "S");
        editEntry(user, first, "first changed");
        JsonNode edited = editEntry(user, entry, "two");
        long at = ok(post(user, "/api/snapshots/" + snapshot + "/restore-preview", "{}")).get("atRevision").asLong();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        restoring.stepHook(step -> {
            if (step.startsWith("entry:") && reached.getCount() > 0) {
                reached.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        CompletableFuture<MvcTestResult> restore = CompletableFuture.supplyAsync(() -> restoreAt(user, snapshot, at));
        CompletableFuture<MvcTestResult> save = null;
        try {
            assertThat(reached.await(30, TimeUnit.SECONDS)).isTrue();
            // (b) A read returns while the restore is still blocked.
            CompletableFuture<MvcTestResult> read = CompletableFuture.supplyAsync(() -> get(user, "/api/entries"));
            assertThat(read.get(10, TimeUnit.SECONDS)).hasStatus(HttpStatus.OK);
            assertThat(restore).isNotDone();
            // (a) A save of an entry the restore writes after the one it is blocked on waits for the restore (the
            // ledger's row first, so no deadlock).
            save = CompletableFuture.supplyAsync(() -> put(user, "/api/entries/" + entry.get("id").asLong()
                    + "?version=" + edited.get("version").asLong(), """
                    {"kind": "EXPENSE", "entryDate": "2026-09-21", "memo": "three", "accountId": %d,
                     "currency": "EUR", "amount": "9.00", "categoryId": %d}""".formatted(accountIdQuiet(user, "CASH"),
                    categoryIdQuiet(user, "GROCERIES"))));
            Thread.sleep(1000);
            assertThat(save).as("the save waits for the restore").isNotDone();
        } finally {
            release.countDown();
        }
        assertThat(restore.get(30, TimeUnit.SECONDS)).hasStatus(HttpStatus.OK);
        MvcTestResult saved = save.get(30, TimeUnit.SECONDS);
        assertThat(saved.getResponse().getStatus()).isIn(200, 409);
    }

    private long accountIdQuiet(String user, String code) {
        try {
            return accountId(user, code);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long categoryIdQuiet(String user, String code) {
        try {
            return categoryId(user, code);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anEntryAFamilyBudgetPostedBlocksTheRestore() throws IOException {
        long ledger = ledgerOf(alice);
        long snapshot = snapshot(alice, "Before the family expense");
        // A personal entry made after the snapshot that a live system-owned link names: journal_entry_family_guard
        // refuses its removal. The posting service writes such links only on FAMILY_* entries (no records), so the
        // link is written here as that writer would.
        long entry = newExpense(alice, "2026-09-10", "5.00", null, "guarded").get("id").asLong();
        rawSql("DO $$ BEGIN PERFORM set_config('app.writer', 'family-posting', true); "
                + "INSERT INTO family_entry_link (entry_id, family_ledger_id, member_id, link_type, system_owned) "
                + "VALUES (" + entry + ", " + family + ", " + mum + ", 'CORRECTION', true); END $$");
        JsonNode preview = ok(post(alice, "/api/snapshots/" + snapshot + "/restore-preview", "{}"));
        assertThat(preview.get("restorable").asBoolean()).isFalse();
        List<String> reasons = new ArrayList<>();
        preview.get("blockers").forEach(b -> reasons.add(b.get("reason").asText()));
        assertThat(reasons).contains("FAMILY_GUARDED");
        Map<String, List<String>> before = dump(alice, ledger);

        JsonNode problem = body(restoreAt(alice, snapshot, preview.get("atRevision").asLong()),
                HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(problem.get("restorable").asBoolean()).isFalse();
        assertThat(problem.get("blockers").get(0).get("recordType").asText()).isEqualTo("ENTRY");
        assertThat(problem.get("blockers").get(0).get("recordId").asLong()).isEqualTo(entry);
        assertThat(dump(alice, ledger)).isEqualTo(before);
    }

    @Test
    void deletingAllMyDataAfterARestoreLeavesNoSnapshotsOrRuns() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        JsonNode entry = newExpense(user, "2026-09-10", "5.00", null, "one");
        long snapshot = snapshot(user, "S");
        editEntry(user, entry, "two");
        long run = restore(user, snapshot).get("runId").asLong();

        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(count("SELECT count(*) FROM snapshot WHERE ledger_id = ?", ledger)).isZero();
        assertThat(count("SELECT count(*) FROM restore_run WHERE ledger_id = ?", ledger)).isZero();
        assertThat(count("SELECT count(*) FROM restore_item WHERE run_id = ?", run)).isZero();
    }

    @Test
    void aFamilySnapshotIsRefusedAndAnotherUsersIsUnknown() throws IOException {
        long snapshot = snapshot(alice, "Mine");
        assertThat(body(post(alice, uri + "/snapshots/" + snapshot + "/restore", "{\"atRevision\": 1}"),
                HttpStatus.CONFLICT).get("code").asText()).isEqualTo("FAMILY_SNAPSHOTS_UNSUPPORTED");
        assertThat(restoreAt(bob, snapshot, revision(ledgerOf(bob)))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(post(alice, "/api/snapshots/" + snapshot + "/restore", "{}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    /** 2,000 entries before the snapshot; after it 1,000 changed, 1,000 deleted and 1,000 created. */
    @Test
    void aRestoreOfThreeThousandEntriesTakesLessThanAMinute() throws IOException {
        String user = fresh();
        long ledger = ledgerOf(user);
        List<JsonNode> entries = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            entries.add(newExpense(user, LocalDate.of(2026, 9, 1).plusDays(i % 28).toString(), "5.00", null,
                    "entry " + i));
        }
        long snapshot = snapshot(user, "Big");
        for (int i = 0; i < 1000; i++) {
            editEntry(user, entries.get(i), "changed " + i);
            JsonNode gone = entries.get(1000 + i);
            assertThat(delete(user, "/api/entries/" + gone.get("id").asLong() + "?version="
                    + gone.get("version").asLong())).hasStatus(HttpStatus.NO_CONTENT);
            newExpense(user, "2026-09-15", "3.00", null, "created " + i);
        }
        long at = ok(post(user, "/api/snapshots/" + snapshot + "/restore-preview", "{}")).get("atRevision").asLong();

        long started = System.nanoTime();
        JsonNode result = ok(restoreAt(user, snapshot, at));
        long millis = (System.nanoTime() - started) / 1_000_000;

        System.out.println("RESTORE-APPLY 3000 entries (1000 restored, 1000 returned, 1000 archived): " + millis + " ms");
        assertThat(result.get("counts").get("RESTORED").asInt()).isEqualTo(1000);
        assertThat(result.get("counts").get("RETURNED").asInt()).isEqualTo(1000);
        assertThat(result.get("counts").get("ARCHIVED").asInt()).isEqualTo(1000);
        assertThat(millis).isLessThan(60_000);
        assertThat(count("SELECT count(*) FROM journal_entry WHERE ledger_id = ?", ledger)).isEqualTo(2000);
    }
}
