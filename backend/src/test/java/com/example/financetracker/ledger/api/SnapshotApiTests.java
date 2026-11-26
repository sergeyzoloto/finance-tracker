package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * HIST-4 part 1 (ADR 0006, D-1008, D-1193 to D-1201) through the API: a snapshot of the personal ledger names its
 * revision, takes none and writes no version but one activity event; its name's rules, the cap, the consistency
 * refusal, the family budget's refusal, 404 for an unknown or another user's snapshot, the wait for an open change set,
 * two snapshots at once that both succeed, and the erasure with the user's data. The book's family part (HIST-4 section
 * 15) is in {@link BookSnapshotApiTests}; here only its additive {@code ledgers} field.
 */
class SnapshotApiTests extends FamilyApiTest {

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

    /** Per table of the ledger, its row count and an md5 of all its rows in id order (journal_entry with version). */
    private Map<String, String> live(long ledger) {
        Map<String, String> live = new java.util.TreeMap<>();
        for (String table : List.of("journal_entry", "account", "category", "counterparty")) {
            live.put(table, jdbc.sql("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t.id)), '') "
                    + "FROM " + table + " t WHERE t.ledger_id = ?").param(ledger).query(String.class).single());
        }
        return live;
    }

    private JsonNode create(String user, String body) throws IOException {
        return body(post(user, "/api/snapshots", body), HttpStatus.CREATED);
    }

    private String code(MvcTestResult result, HttpStatus status) throws IOException {
        return body(result, status).get("code").asText();
    }

    @Test
    void aSnapshotNamesTheRevisionAndTakesNone() throws IOException {
        long ledger = personalLedger(alice);
        newExpense(alice, "2026-09-10", "5.00", null, "bread");
        long before = revision(ledger);
        long versions = count("SELECT count(*) FROM record_version WHERE ledger_id = ?", ledger);
        Map<String, String> liveBefore = live(ledger);

        JsonNode snapshot = create(alice, """
                {"name": "  Before the move  "}""");

        assertThat(snapshot.get("name").asText()).isEqualTo("Before the move");
        assertThat(snapshot.get("kind").asText()).isEqualTo("MANUAL");
        assertThat(snapshot.get("revision").asLong()).isEqualTo(before);
        assertThat(snapshot.get("createdBy").get("kind").asText()).isEqualTo("USER");
        assertThat(snapshot.get("counts").get("ENTRY").asLong()).isEqualTo(1);
        // HIST-4 book snapshots (D-1290): Alice's family budget is in it, as an additive field.
        assertThat(snapshot.get("ledgers")).hasSize(1);
        assertThat(snapshot.get("ledgers").get(0).get("ledgerId").asLong()).isEqualTo(family);
        assertThat(snapshot.get("ledgers").get(0).get("revision").asLong()).isEqualTo(revision(family));
        assertThat(revision(ledger)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM record_version WHERE ledger_id = ?", ledger)).isEqualTo(versions);
        // No live row changed: per table, the same count and the same hash of every row (with its version column).
        assertThat(live(ledger)).isEqualTo(liveBefore);
        assertThat(liveBefore.keySet()).containsExactlyInAnyOrder("journal_entry", "account", "category", "counterparty");
        assertThat(jdbc.sql("SELECT kind FROM activity_event WHERE actor_sub = ? AND kind LIKE 'SNAPSHOT%'")
                .param(alice).query(String.class).list()).containsExactly("SNAPSHOT_CREATED");
        assertThat(jdbc.sql("SELECT details ->> 'name' FROM activity_event WHERE actor_sub = ? "
                + "AND kind = 'SNAPSHOT_CREATED'").param(alice).query(String.class).single()).isEqualTo("Before the move");

        long id = snapshot.get("id").asLong();
        JsonNode one = ok(get(alice, "/api/snapshots/" + id));
        assertThat(one.get("revision").asLong()).isEqualTo(before);
        assertThat(one.get("counts").get("ACCOUNT").asLong())
                .isEqualTo(count("SELECT count(*) FROM account WHERE ledger_id = ? AND family_ledger_id IS NULL "
                        + "AND code <> 'UNSPECIFIED_PAYMENTS'", ledger));
        JsonNode list = ok(get(alice, "/api/snapshots"));
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("content").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(list.get("content").get(0).get("counts").isNull()).isTrue();
        assertThat(list.get("content").get(0).get("ledgers").get(0).get("ledgerId").asLong()).isEqualTo(family);
        assertThat(one.get("ledgers").get(0).get("counts").get("FAMILY_CATEGORY").asLong()).isPositive();
    }

    @Test
    void theNameIsTrimmedAtMostEightyCharactersWithoutControlCharacters() throws IOException {
        assertThat(create(alice, null).get("name").asText()).matches("Snapshot \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(create(alice, """
                {"name": "   "}""").get("name").asText()).startsWith("Snapshot ");
        assertThat(create(alice, """
                {"name": "%s"}""".formatted("x".repeat(80))).get("name").asText()).hasSize(80);
        assertThat(code(post(alice, "/api/snapshots", """
                {"name": "%s"}""".formatted("x".repeat(81))), HttpStatus.BAD_REQUEST))
                .isEqualTo("SNAPSHOT_NAME_INVALID");
        assertThat(code(post(alice, "/api/snapshots", """
                {"name": "a\\u0007b"}"""), HttpStatus.BAD_REQUEST)).isEqualTo("SNAPSHOT_NAME_INVALID");
        assertThat(count("SELECT count(*) FROM snapshot WHERE ledger_id = ?", personalLedger(alice))).isEqualTo(3);
    }

    @Test
    void theThousandAndFirstIsRefused() throws IOException {
        long ledger = personalLedger(alice);
        jdbc.sql("INSERT INTO snapshot (ledger_id, revision, name, kind) "
                + "SELECT ?, 0, 'Old', 'MANUAL' FROM generate_series(1, 1000)").param(ledger).update();
        assertThat(code(post(alice, "/api/snapshots", "{}"), HttpStatus.CONFLICT)).isEqualTo("SNAPSHOT_LIMIT_REACHED");
        JsonNode page = ok(get(alice, "/api/snapshots?size=200"));
        assertThat(page.get("content").size()).isEqualTo(200);
        assertThat(page.get("totalPages").asInt()).isEqualTo(5);
        assertThat(get(alice, "/api/snapshots?size=201")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aLedgerWhoseRecordsDontMatchTheirHistoryIsRefused() throws IOException {
        long ledger = personalLedger(alice);
        // A row the previous image wrote, with no version: the live count differs from the state's.
        rawSql("INSERT INTO counterparty (user_id, ledger_id, name) VALUES (?, ?, 'Unversioned')", alice, ledger);
        assertThat(code(post(alice, "/api/snapshots", "{}"), HttpStatus.CONFLICT)).isEqualTo("HISTORY_INCONSISTENT");
        assertThat(count("SELECT count(*) FROM snapshot WHERE ledger_id = ?", ledger)).isZero();
    }

    @Test
    void aFamilyBudgetHasNoSnapshotsAndOthersSnapshotsAreUnknown() throws IOException {
        assertThat(code(post(alice, uri + "/snapshots", "{}"), HttpStatus.CONFLICT))
                .isEqualTo("FAMILY_SNAPSHOTS_UNSUPPORTED");
        assertThat(code(get(bob, uri + "/snapshots"), HttpStatus.CONFLICT)).isEqualTo("FAMILY_SNAPSHOTS_UNSUPPORTED");
        assertThat(post(newUser(), uri + "/snapshots", "{}")).hasStatus(HttpStatus.NOT_FOUND);

        long id = create(alice, "{}").get("id").asLong();
        assertThat(get(bob, "/api/snapshots/" + id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(alice, "/api/snapshots/" + (id + 1_000_000))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ok(get(bob, "/api/snapshots")).get("total").asLong()).isZero();
    }

    @Test
    void aSnapshotWaitsForAnOpenChangeSetAndTwoAtOnceBothSucceed() throws Exception {
        long ledger = personalLedger(alice);
        long before = revision(ledger);
        CompletableFuture<MvcTestResult> waiting;
        try (Connection changeSet = previousImageConnection()) {
            changeSet.setAutoCommit(false);
            changeSet.createStatement().execute("UPDATE ledger SET revision = revision + 1 WHERE id = " + ledger);
            waiting = CompletableFuture.supplyAsync(() -> post(alice, "/api/snapshots", "{}"));
            await("the snapshot waits for the change set").atMost(20, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofMillis(20)).until(() -> count(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                                    + "AND query LIKE '%FOR SHARE%'") > 0);
            assertThat(waiting).isNotDone();
            changeSet.commit();
        }
        assertThat(body(waiting.get(20, TimeUnit.SECONDS), HttpStatus.CREATED).get("revision").asLong())
                .isEqualTo(before + 1);

        List<CompletableFuture<MvcTestResult>> both = List.of(
                CompletableFuture.supplyAsync(() -> post(alice, "/api/snapshots", "{}")),
                CompletableFuture.supplyAsync(() -> post(alice, "/api/snapshots", "{}")));
        for (CompletableFuture<MvcTestResult> one : both) {
            assertThat(one.get(20, TimeUnit.SECONDS)).hasStatus(HttpStatus.CREATED);
        }
        assertThat(count("SELECT count(*) FROM snapshot WHERE ledger_id = ?", ledger)).isEqualTo(3);
    }

    @Test
    void deletingAllMyDataTakesTheSnapshots() throws IOException {
        String carol = newUser();
        create(carol, "{}");
        long ledger = personalLedger(carol);
        assertThat(delete(carol, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(count("SELECT count(*) FROM snapshot WHERE ledger_id = ?", ledger)).isZero();
        assertThat(count("SELECT count(*) FROM activity_event WHERE actor_sub = ?", carol)).isZero();
    }
}
