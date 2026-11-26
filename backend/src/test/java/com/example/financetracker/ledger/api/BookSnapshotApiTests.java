package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.history.Canonical;
import com.example.financetracker.ledger.history.LedgerState;
import com.example.financetracker.ledger.history.RecordType;
import com.example.financetracker.ledger.history.SnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * HIST-4 book snapshots, part a (ADR 0006 section 15, D-1300 to D-1307, D-1334): a snapshot on the personal route
 * covers the member's family budgets, one snapshot_ledger row each at the revision read under its FOR SHARE lock; the
 * cap of 20 budgets; SNAPSHOT_RETRY when his budgets change while it runs; the family state at a revision against the
 * live records; no deadlock (40P01) with the other member's save, accept and own snapshot, nor with his own "Delete all
 * my data"; the erasure and a deleted budget take the rows. {@link BookSnapshotAcceptingApiTests} runs them all with
 * acceptance on.
 */
class BookSnapshotApiTests extends FamilyApiTest {

    @Autowired
    private SnapshotService snapshots;
    @Autowired
    private LedgerState state;
    @Autowired
    private LedgerAccess access;

    /** What POST /accept answers: with acceptance off it doesn't exist (404); {@link BookSnapshotAcceptingApiTests}: 200. */
    HttpStatus acceptAnswers() {
        return HttpStatus.NOT_FOUND;
    }

    @AfterEach
    void noHook() {
        snapshots.stepHook(null);
    }

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

    private JsonNode snapshot(String user) throws IOException {
        return body(post(user, "/api/snapshots", "{}"), HttpStatus.CREATED);
    }

    /** A family budget of the user's own, written with plain SQL, with nothing in it. */
    private long sqlBudget(String user, String name) {
        long ledger = jdbc.sql("""
                INSERT INTO ledger (type, name, base_currency, split_rule, start_date)
                VALUES ('SHARED', ?, 'EUR', 'EQUAL', DATE '2026-09-01') RETURNING id""").param(name)
                .query(Long.class).single();
        join(ledger, user, "Me", "OWNER", LocalDate.of(2026, 9, 1));
        return ledger;
    }

    /** The live family records and categories, each as its canonical content. */
    private Map<RecordType, Map<Long, String>> live(long ledger) {
        Map<RecordType, Map<Long, String>> live = new TreeMap<>();
        for (RecordType type : LedgerState.FAMILY) {
            Map<Long, String> records = new TreeMap<>();
            String sql = type == RecordType.FAMILY_RECORD ? "SELECT id FROM family_record WHERE ledger_id = ?"
                    : "SELECT id FROM category WHERE ledger_id = ? AND user_id IS NULL";
            for (long id : jdbc.sql(sql).param(ledger).query(Long.class).list()) {
                recordContents.load(type, ledger, id).ifPresent(c -> records.put(id, Canonical.text(c)));
            }
            live.put(type, records);
        }
        return live;
    }

    private Map<RecordType, Map<Long, String>> stateAt(LedgerScope scope, long revision) {
        Map<RecordType, Map<Long, String>> read = new TreeMap<>();
        state.at(scope, revision, LedgerState.FAMILY).forEach((type, records) -> {
            Map<Long, String> canonical = new TreeMap<>();
            records.forEach((id, content) -> canonical.put(id, Canonical.canonicalize(content)));
            read.put(type, canonical);
        });
        return read;
    }

    @Test
    void aSnapshotCoversTheFamilyBudgetsAtTheirRevisions() throws IOException {
        JsonNode kept = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30", kid, "")));
        JsonNode gone = created(post(alice, uri + "/records", expense("2026-09-11", rent, "12", kid, "")));
        assertThat(delete(alice, uri + "/records/" + gone.get("id").asLong() + "?version="
                + gone.get("version").asLong())).hasStatus(HttpStatus.NO_CONTENT);
        long familyRevision = revision(family);
        Map<RecordType, Map<Long, String>> liveThen = live(family);

        JsonNode snapshot = snapshot(alice);

        long id = snapshot.get("id").asLong();
        assertThat(snapshot.get("revision").asLong()).isEqualTo(revision(personalLedger(alice)));
        assertThat(snapshot.get("ledgers")).hasSize(1);
        JsonNode part = snapshot.get("ledgers").get(0);
        assertThat(part.get("ledgerId").asLong()).isEqualTo(family);
        assertThat(part.get("name").asText()).isEqualTo("Home");
        assertThat(part.get("revision").asLong()).isEqualTo(familyRevision);
        assertThat(part.get("counts").get("FAMILY_RECORD").asLong()).isEqualTo(2);
        assertThat(part.get("counts").get("FAMILY_CATEGORY").asLong())
                .isEqualTo(count("SELECT count(*) FROM category WHERE ledger_id = ? AND user_id IS NULL", family));
        assertThat(jdbc.sql("SELECT ledger_id, revision, member_id FROM snapshot_ledger WHERE snapshot_id = ?")
                .param(id).query((row, n) -> List.of(row.getLong(1), row.getLong(2), row.getLong(3))).list())
                .containsExactly(List.of(family, familyRevision, mum));
        // The snapshot takes no revision of the budget.
        assertThat(revision(family)).isEqualTo(familyRevision);
        JsonNode event = json.readTree(jdbc.sql("SELECT details::text FROM activity_event WHERE actor_sub = ? "
                + "AND kind = 'SNAPSHOT_CREATED'").param(alice).query(String.class).single());
        assertThat(event.get("ledgers")).hasSize(1);
        assertThat(event.get("ledgers").get(0).get("ledgerId").asLong()).isEqualTo(family);
        assertThat(event.get("ledgers").get(0).get("revision").asLong()).isEqualTo(familyRevision);

        // The state at the revision is the live state then, the deleted record with its deleted flag.
        LedgerScope scope = access.member(alice, family);
        assertThat(stateAt(scope, familyRevision)).isEqualTo(liveThen);
        assertThat(json.readTree(state.at(scope, familyRevision, List.of(RecordType.FAMILY_RECORD))
                .get(RecordType.FAMILY_RECORD).get(gone.get("id").asLong())).get("deleted").asBoolean()).isTrue();
        ok(patch(alice, uri + "/records/" + kept.get("id").asLong() + "?version=" + kept.get("version").asLong(), """
                {"comment": "later"}"""));
        assertThat(stateAt(scope, familyRevision)).isEqualTo(liveThen);
        assertThat(stateAt(scope, revision(family))).isEqualTo(live(family));

        // get and list: additive fields only.
        JsonNode one = ok(get(alice, "/api/snapshots/" + id));
        assertThat(one.get("ledgers").get(0).get("counts").get("FAMILY_RECORD").asLong()).isEqualTo(2);
        assertThat(one.get("counts").get("ENTRY")).isNotNull();
        JsonNode listed = ok(get(alice, "/api/snapshots")).get("content").get(0);
        assertThat(listed.get("counts").isNull()).isTrue();
        assertThat(listed.get("ledgers").get(0).get("revision").asLong()).isEqualTo(familyRevision);
        assertThat(listed.get("ledgers").get(0).get("counts").isNull()).isTrue();

        // A member of no budget: a personal-only book.
        String carol = newUser();
        JsonNode alone = snapshot(carol);
        assertThat(alone.get("ledgers")).isEmpty();
        assertThat(count("SELECT count(*) FROM snapshot_ledger WHERE snapshot_id = ?", alone.get("id").asLong()))
                .isZero();
        assertThat(jdbc.sql("SELECT details -> 'ledgers' IS NOT NULL FROM activity_event WHERE actor_sub = ? "
                + "AND kind = 'SNAPSHOT_CREATED'").param(carol).query(Boolean.class).single()).isFalse();
        // The family route stays refused (D-1327).
        assertThat(body(post(alice, uri + "/snapshots", "{}"), HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("FAMILY_SNAPSHOTS_UNSUPPORTED");
    }

    @Test
    void twentyBudgetsAreCoveredAndTheTwentyFirstIsRefused() throws IOException {
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        for (int i = 0; i < SnapshotService.BUDGET_CAP; i++) {
            sqlBudget(carol, "Budget " + i);
        }
        assertThat(snapshot(carol).get("ledgers")).hasSize(SnapshotService.BUDGET_CAP);
        sqlBudget(carol, "One too many");
        JsonNode refused = body(post(carol, "/api/snapshots", "{}"), HttpStatus.CONFLICT);
        assertThat(refused.get("code").asText()).isEqualTo("SNAPSHOT_TOO_MANY_BUDGETS");
        assertThat(refused.get("detail").asText())
                .isEqualTo("You are in more than 20 family budgets, so a snapshot can't cover them all.");
        assertThat(count("SELECT count(*) FROM snapshot WHERE ledger_id = ?", personalLedger(carol))).isEqualTo(1);
    }

    @Test
    void aBudgetJoinedWhileTheSnapshotRunsAsksForARetry() throws IOException {
        AtomicBoolean once = new AtomicBoolean();
        snapshots.stepHook(step -> {
            if (step.equals(SnapshotService.STEP_LOCKED) && once.compareAndSet(false, true)) {
                // Another transaction, committed at once: he joins a budget meanwhile.
                rawSql("INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, "
                        + "join_date) SELECT id, 'SHARED', ?, 'Me', 'OWNER', 'ACTIVE', DATE '2026-09-01' FROM ledger "
                        + "WHERE id = (SELECT max(id) FROM ledger WHERE name = 'Joined meanwhile')", alice);
            }
        });
        jdbc.sql("""
                INSERT INTO ledger (type, name, base_currency, split_rule, start_date)
                VALUES ('SHARED', 'Joined meanwhile', 'EUR', 'EQUAL', DATE '2026-09-01')""").update();
        JsonNode refused = body(post(alice, "/api/snapshots", "{}"), HttpStatus.CONFLICT);
        assertThat(refused.get("code").asText()).isEqualTo("SNAPSHOT_RETRY");
        assertThat(refused.get("detail").asText())
                .isEqualTo("Your family budgets changed while the snapshot was taken; try again.");
        assertThat(count("SELECT count(*) FROM snapshot WHERE ledger_id = ?", personalLedger(alice))).isZero();
        // Taken again, it covers both budgets.
        snapshots.stepHook(null);
        assertThat(snapshot(alice).get("ledgers")).hasSize(2);
    }

    /** Whether, within 20 seconds, some backend waits on a lock that the given backend holds. */
    private boolean someoneWaitsOn(int pid) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND ? = ANY(pg_blocking_pids(pid))", pid) > 0) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** Holds Alice's snapshot at the step; the pid of its connection, once it is there. */
    private int holdAt(String step, CountDownLatch release) throws InterruptedException {
        CountDownLatch reached = new CountDownLatch(1);
        AtomicInteger pid = new AtomicInteger();
        snapshots.stepHook(at -> {
            if (at.equals(step) && reached.getCount() > 0) {
                pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                reached.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        return reached.await(30, TimeUnit.SECONDS) ? pid.get() : -1;
    }

    private static void noDeadlock(MvcTestResult answer, CapturedOutput output) {
        // A deadlock (40P01) answers 500 without details (ApiExceptionHandler), and is logged.
        assertThat(answer.getResponse().getStatus()).isLessThan(500);
        assertThat(output.getAll()).doesNotContain("40P01").doesNotContain("deadlock detected");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theOtherMembersSaveAcceptAndSnapshotWaitOrPassWhileASnapshotHoldsItsLocks(CapturedOutput output)
            throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<MvcTestResult> mine;
        List<CompletableFuture<MvcTestResult>> his;
        try {
            mine = CompletableFuture.supplyAsync(() -> post(alice, "/api/snapshots", "{}"));
            int pid = holdAt(SnapshotService.STEP_LOCKED, release);
            assertThat(pid).as("the snapshot holds its locks").isPositive();
            CompletableFuture<MvcTestResult> save = CompletableFuture.supplyAsync(() -> post(bob, uri + "/records",
                    expense("2026-09-12", groceries, "8", kid, "")));
            assertThat(someoneWaitsOn(pid)).as("the family save waits for the snapshot").isTrue();
            assertThat(save).isNotDone();
            his = List.of(save, CompletableFuture.supplyAsync(() -> post(bob, uri + "/accept", "{}")),
                    CompletableFuture.supplyAsync(() -> post(bob, "/api/snapshots", "{}")));
        } finally {
            release.countDown();
        }
        assertThat(mine.get(60, TimeUnit.SECONDS)).hasStatus(HttpStatus.CREATED);
        assertThat(his.get(0).get(60, TimeUnit.SECONDS)).hasStatus(HttpStatus.CREATED);
        for (CompletableFuture<MvcTestResult> one : his) {
            noDeadlock(one.get(60, TimeUnit.SECONDS), output);
        }
        assertThat(his.get(1).get()).hasStatus(acceptAnswers());
        assertThat(his.get(2).get()).hasStatus(HttpStatus.CREATED);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void deletingAllMyDataWhileMySnapshotHoldsTheFamilyRowWaitsWithoutADeadlock(CapturedOutput output)
            throws Exception {
        long id;
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<MvcTestResult> mine;
        CompletableFuture<MvcTestResult> erasure;
        try {
            mine = CompletableFuture.supplyAsync(() -> post(alice, "/api/snapshots", "{}"));
            // Between the family rows and the personal row: the old order of delete-all (personal row first) would
            // deadlock here.
            int pid = holdAt(SnapshotService.STEP_FAMILIES_LOCKED, release);
            assertThat(pid).isPositive();
            erasure = CompletableFuture.supplyAsync(() -> delete(alice, "/api/me/data"));
            assertThat(someoneWaitsOn(pid)).as("delete-all waits on the family row").isTrue();
        } finally {
            release.countDown();
        }
        MvcTestResult snapshot = mine.get(60, TimeUnit.SECONDS);
        assertThat(snapshot).hasStatus(HttpStatus.CREATED);
        id = body(snapshot, HttpStatus.CREATED).get("id").asLong();
        MvcTestResult erased = erasure.get(60, TimeUnit.SECONDS);
        noDeadlock(erased, output);
        assertThat(erased).hasStatus(HttpStatus.NO_CONTENT);
        // The erasure leaves no snapshot_ledger row of his.
        assertThat(count("SELECT count(*) FROM snapshot_ledger WHERE snapshot_id = ? OR member_id = ?", id, mum))
                .isZero();
    }

    @Test
    void deletingAllMyDataTakesTheFamilyLockBeforeThePersonalOneAsAFamilySaveDoes() throws Exception {
        long personal = personalLedger(alice);
        CompletableFuture<MvcTestResult> erasure;
        // The other member's family save, as FamilyRecordService and CrossLedgerWriter lock: the family row, then
        // every member's personal row.
        try (Connection save = previousImageConnection()) {
            save.setAutoCommit(false);
            Statement sql = save.createStatement();
            sql.execute("SET LOCAL lock_timeout = '20s'");
            sql.execute("SELECT id FROM ledger WHERE id = " + family + " FOR NO KEY UPDATE");
            int pid;
            try (var rows = sql.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                pid = rows.getInt(1);
            }
            erasure = CompletableFuture.supplyAsync(() -> delete(alice, "/api/me/data"));
            assertThat(someoneWaitsOn(pid)).as("delete-all waits on the family row").isTrue();
            // With delete-all holding the personal row first this would be a deadlock (40P01) here or there.
            sql.execute("SELECT id FROM ledger WHERE id = " + personal + " FOR NO KEY UPDATE");
            save.commit();
        }
        assertThat(erasure.get(60, TimeUnit.SECONDS)).hasStatus(HttpStatus.NO_CONTENT);
        // And a real save of his afterwards works.
        created(post(bob, uri + "/records", expense("2026-09-12", groceries, "8", kid, "",
                "{\"method\": \"ONE_MEMBER\", \"memberId\": %d}".formatted(dad))));
    }

    @Test
    void erasingMyDataLeavesNoRowOfMineAndADeletedBudgetTakesItsRows() throws IOException {
        JsonNode solo = newFamily(alice, """
                {"name": "Solo", "baseCurrency": "EUR", "displayName": "Me", "startDate": "2026-09-01",
                 "categoryIds": []}""");
        long soloId = solo.get("id").asLong();
        long id = snapshot(alice).get("id").asLong();
        assertThat(count("SELECT count(*) FROM snapshot_ledger WHERE snapshot_id = ?", id)).isEqualTo(2);

        assertThat(delete(alice, "/api/family-ledgers/" + soloId + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(count("SELECT count(*) FROM ledger WHERE id = ?", soloId)).isZero();
        assertThat(count("SELECT count(*) FROM snapshot_ledger WHERE ledger_id = ?", soloId)).isZero();
        JsonNode smaller = ok(get(alice, "/api/snapshots/" + id));
        assertThat(smaller.get("ledgers")).hasSize(1);
        assertThat(smaller.get("ledgers").get(0).get("ledgerId").asLong()).isEqualTo(family);

        assertThat(delete(alice, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(count("SELECT count(*) FROM snapshot_ledger WHERE snapshot_id = ? OR member_id = ?", id, mum))
                .isZero();
        assertThat(count("SELECT count(*) FROM snapshot WHERE id = ?", id)).isZero();
    }
}
