package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.example.financetracker.RandomVolume;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.history.Canonical;
import com.example.financetracker.ledger.history.LedgerState;
import com.example.financetracker.ledger.history.RecordType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * The state at a revision (HIST-4 part 1, D-1008) against an oracle: random writes through the API (entries created,
 * changed, deleted and made again; accounts, categories and payees created, renamed and archived), at least 6
 * copies of the live records kept at several revisions, and for each the state engine returns that copy, per type and id. Then the engine on 20,000 versions.
 */
@Tag("random")
class SnapshotStateRandomTests extends LedgerApiTest {

    private static final List<RecordType> TYPES =
            List.of(RecordType.ENTRY, RecordType.ACCOUNT, RecordType.CATEGORY, RecordType.COUNTERPARTY);

    @Autowired
    private LedgerState state;
    @Autowired
    private LedgerAccess access;

    private record Kept(long revision, Map<RecordType, Map<Long, String>> records) {
    }

    @Test
    void theStateAtEachKeptRevisionIsTheCopyTakenThen() {
        // A fresh seed per run, or the one of tests.random.seed to replay a run (TEST-1b).
        long seed = RandomVolume.seedOr(System.nanoTime());
        RandomVolume.run(getClass(), seed, () -> theStateAtEachKeptRevision(seed));
    }

    private void theStateAtEachKeptRevision(long seed) throws IOException {
        Random random = new Random(seed);
        String user = newUser();
        ok(get(user, "/api/accounts"));
        LedgerScope scope = access.personal(user);
        List<long[]> entries = new ArrayList<>();
        List<Long> payees = new ArrayList<>();
        List<Kept> kept = new ArrayList<>();
        List<Long> accounts = new ArrayList<>();
        List<Long> categories = new ArrayList<>();
        // Entries are created, changed and deleted. Accounts, categories and payees are never deleted (rule 12): for
        // them the "delete" is an archive (and a later one may restore). The first 12 steps run each operation once,
        // so every type is created, changed and archived or deleted in every run (not volume); the random tail is 48
        // steps at full volume, fewer at a lower scale.
        for (int step = 0; step < 12 + RandomVolume.count(48); step++) {
            int op = step < 12 ? step : random.nextInt(12);
            if (op == 0 || (op <= 2 && entries.isEmpty())) {
                JsonNode entry = newExpense(user, "2026-09-1" + random.nextInt(10), random.nextInt(1, 99) + ".00",
                        null, "memo " + step);
                entries.add(new long[] {entry.get("id").asLong(), entry.get("version").asLong()});
            } else if (op == 1) {
                long[] entry = entries.get(random.nextInt(entries.size()));
                JsonNode changed = ok(put(user, "/api/entries/" + entry[0] + "?version=" + entry[1], """
                        {"kind": "EXPENSE", "entryDate": "2026-09-20", "memo": "changed %d", "accountId": %d,
                         "currency": "EUR", "amount": "7.00", "categoryId": %d}""".formatted(step,
                        accountId(user, "CASH"), categoryId(user, "GROCERIES"))));
                entry[1] = changed.get("version").asLong();
            } else if (op == 2) {
                long[] entry = entries.remove(random.nextInt(entries.size()));
                assertThat(delete(user, "/api/entries/" + entry[0] + "?version=" + entry[1]))
                        .hasStatus(HttpStatus.NO_CONTENT);
            } else if (op == 3 || (op <= 5 && payees.isEmpty())) {
                payees.add(newCounterparty(user, "Payee " + step));
            } else if (op == 4) {
                ok(patch(user, "/api/counterparties/" + payees.get(random.nextInt(payees.size())), """
                        {"name": "Renamed %d"}""".formatted(step)));
            } else if (op == 5) {
                ok(patch(user, "/api/counterparties/" + payees.get(random.nextInt(payees.size())), """
                        {"archived": %b}""".formatted(random.nextBoolean())));
            } else if (op == 6 || (op <= 8 && accounts.isEmpty())) {
                accounts.add(body(post(user, "/api/accounts", """
                        {"code": "ACC_%d", "name": "Account %d", "type": "ASSET", "defaultCurrency": "EUR"}"""
                        .formatted(step, step)), HttpStatus.CREATED).get("id").asLong());
            } else if (op == 7) {
                ok(patch(user, "/api/accounts/" + accounts.get(random.nextInt(accounts.size())), """
                        {"name": "Account renamed %d"}""".formatted(step)));
            } else if (op == 8) {
                ok(patch(user, "/api/accounts/" + accounts.get(random.nextInt(accounts.size())), """
                        {"archived": %b}""".formatted(random.nextBoolean())));
            } else if (op == 9 || categories.isEmpty()) {
                categories.add(body(post(user, "/api/categories", """
                        {"code": "CAT_%d", "name": "Category %d", "type": "EXPENSE"}""".formatted(step, step)),
                        HttpStatus.CREATED).get("id").asLong());
            } else if (op == 10) {
                ok(patch(user, "/api/categories/" + categories.get(random.nextInt(categories.size())), """
                        {"name": "Category renamed %d"}""".formatted(step)));
            } else {
                ok(patch(user, "/api/categories/" + categories.get(random.nextInt(categories.size())), """
                        {"archived": %b}""".formatted(random.nextBoolean())));
            }
            if (step % 5 == 4) {
                kept.add(copy(scope));
            }
        }
        // 12 copies at full volume (a copy every fifth step), at least 6; the same share of 6 at a lower scale.
        assertThat(kept).hasSizeGreaterThanOrEqualTo(
                RandomVolume.scale() == 1 ? 6 : Math.max(1, (int) Math.round(6 * RandomVolume.scale())));
        for (Kept copy : kept) {
            Map<RecordType, Map<Long, String>> at = state.at(scope, copy.revision());
            Map<RecordType, Map<Long, String>> read = new EnumMap<>(RecordType.class);
            at.forEach((type, records) -> {
                Map<Long, String> canonical = new TreeMap<>();
                records.forEach((id, content) -> canonical.put(id, Canonical.canonicalize(content)));
                read.put(type, canonical);
            });
            assertThat(read).as("seed %d, revision %d", seed, copy.revision()).isEqualTo(copy.records());
        }
    }

    private Kept copy(LedgerScope scope) {
        long revision = jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(scope.ledgerId())
                .query(Long.class).single();
        Map<RecordType, Map<Long, String>> records = new EnumMap<>(RecordType.class);
        for (RecordType type : TYPES) {
            Map<Long, String> live = new TreeMap<>();
            String sql = switch (type) {
                case ENTRY -> "SELECT id FROM journal_entry WHERE ledger_id = ? AND kind NOT LIKE 'FAMILY\\_%'";
                case ACCOUNT -> "SELECT id FROM account WHERE ledger_id = ? AND family_ledger_id IS NULL "
                        + "AND code <> 'UNSPECIFIED_PAYMENTS'";
                case CATEGORY -> "SELECT id FROM category WHERE ledger_id = ? AND user_id IS NOT NULL";
                default -> "SELECT id FROM counterparty WHERE ledger_id = ?";
            };
            for (long id : jdbc.sql(sql).param(scope.ledgerId()).query(Long.class).list()) {
                recordContents.load(type, scope.ledgerId(), id).ifPresent(c -> live.put(id, Canonical.text(c)));
            }
            records.put(type, live);
        }
        return new Kept(revision, records);
    }

    /**
     * HIST-4 book snapshots (D-1326 item 2): the family variant of the state against copies of the live family records
     * and categories, with two members writing at random (records created, commented, deleted; categories created and
     * renamed). A deleted record stays in the state with its deleted flag.
     */
    @Test
    void theFamilyStateAtEachKeptRevisionIsTheCopyTakenThen() {
        long seed = RandomVolume.seedOr(System.nanoTime());
        RandomVolume.run(getClass(), seed, () -> theFamilyStateAtEachKeptRevision(seed));
    }

    private void theFamilyStateAtEachKeptRevision(long seed) throws IOException {
        Random random = new Random(seed);
        String mum = newUser();
        String dad = newUser();
        ok(get(dad, "/api/accounts"));
        JsonNode created = newFamily(mum, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-09-01",
                 "categoryIds": [%d]}""".formatted(categoryId(mum, "GROCERIES")));
        long family = created.get("id").asLong();
        String uri = "/api/family-ledgers/" + family;
        join(family, dad, "Dad", "MEMBER", java.time.LocalDate.of(2026, 9, 1));
        long kidId = body(post(mum, uri + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED).get("id").asLong();
        List<Long> categories = new ArrayList<>(ok(get(mum, uri + "/categories")).findValues("id").stream()
                .map(JsonNode::asLong).toList());
        List<long[]> records = new ArrayList<>();
        List<Kept> kept = new ArrayList<>();
        LedgerScope scope = access.member(mum, family);
        for (int step = 0; step < 5 + RandomVolume.count(35); step++) {
            int op = step < 5 ? step : random.nextInt(5);
            String who = random.nextBoolean() ? mum : dad;
            if (op == 0 || (op <= 2 && records.isEmpty())) {
                // Paid by Kid, who has no account: no payment account to name.
                long payer = kidId;
                JsonNode record = body(post(who, uri + "/records", """
                        {"date": "2026-09-1%d", "categoryId": %d, "amount": "%d.00", "payerMemberId": %d}"""
                        .formatted(random.nextInt(10), categories.get(random.nextInt(categories.size())),
                                random.nextInt(1, 99), payer)), HttpStatus.CREATED);
                records.add(new long[] {record.get("id").asLong(), record.get("version").asLong()});
            } else if (op == 1) {
                long[] record = records.get(random.nextInt(records.size()));
                // The author or an owner (Mum) may change the comment.
                JsonNode changed = ok(patch(mum, uri + "/records/" + record[0] + "?version=" + record[1], """
                        {"comment": "step %d"}""".formatted(step)));
                record[1] = changed.get("version").asLong();
            } else if (op == 2) {
                long[] record = records.remove(random.nextInt(records.size()));
                assertThat(delete(mum, uri + "/records/" + record[0] + "?version=" + record[1]))
                        .hasStatus(HttpStatus.NO_CONTENT);
            } else if (op == 3) {
                categories.add(body(post(mum, uri + "/categories", """
                        {"code": "FAM_%d", "name": "Family %d", "type": "EXPENSE"}""".formatted(step, step)),
                        HttpStatus.CREATED).get("id").asLong());
            } else {
                ok(patch(mum, uri + "/categories/" + categories.get(random.nextInt(categories.size())), """
                        {"name": "Renamed %d"}""".formatted(step)));
            }
            if (step % 4 == 3) {
                kept.add(familyCopy(family));
            }
        }
        assertThat(kept).isNotEmpty();
        for (Kept copy : kept) {
            Map<RecordType, Map<Long, String>> read = new EnumMap<>(RecordType.class);
            state.at(scope, copy.revision(), LedgerState.FAMILY).forEach((type, contents) -> {
                Map<Long, String> canonical = new TreeMap<>();
                contents.forEach((id, content) -> canonical.put(id, Canonical.canonicalize(content)));
                read.put(type, canonical);
            });
            assertThat(read).as("seed %d, family revision %d", seed, copy.revision()).isEqualTo(copy.records());
        }
    }

    private Kept familyCopy(long family) {
        long revision = jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(family).query(Long.class).single();
        Map<RecordType, Map<Long, String>> records = new EnumMap<>(RecordType.class);
        for (RecordType type : LedgerState.FAMILY) {
            Map<Long, String> live = new TreeMap<>();
            String sql = type == RecordType.FAMILY_RECORD ? "SELECT id FROM family_record WHERE ledger_id = ?"
                    : "SELECT id FROM category WHERE ledger_id = ? AND user_id IS NULL";
            for (long id : jdbc.sql(sql).param(family).query(Long.class).list()) {
                recordContents.load(type, family, id).ifPresent(c -> live.put(id, Canonical.text(c)));
            }
            records.put(type, live);
        }
        return new Kept(revision, records);
    }

    /** 2,000 records with 10 versions each; prints the plan and the time of the state at the middle revision. */
    @Test
    void theStateOfTwentyThousandVersions() throws IOException {
        String user = newUser();
        ok(get(user, "/api/accounts"));
        LedgerScope scope = access.personal(user);
        jdbc.sql("""
                INSERT INTO record_version (ledger_id, record_type, record_id, seq, revision, op, content, content_hash,
                                            content_ver, actor_kind, cause)
                SELECT ?, 'ENTRY', 900000000 + r, s, 1000 + (r % 50) * 10 + s,
                       CASE WHEN s = 1 THEN 'CREATE' WHEN s = 10 AND r % 3 = 0 THEN 'DELETE' ELSE 'UPDATE' END,
                       jsonb_build_object('memo', r || '/' || s), sha256((r || '/' || s)::bytea), 1, 'SYSTEM', 'EDIT'
                FROM generate_series(1, 2000) r, generate_series(1, 10) s""").param(scope.ledgerId()).update();
        try {
            jdbc.sql("ANALYZE record_version").update();
            long started = System.nanoTime();
            Map<RecordType, Map<Long, String>> at = state.at(scope, 1255);
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(at.get(RecordType.ENTRY)).isNotEmpty();
            List<String> plan = jdbc.sql("EXPLAIN (ANALYZE, COSTS OFF) SELECT record_type, record_id FROM ("
                            + LedgerState.LATEST + ") v WHERE op NOT IN ('DELETE', 'ARCHIVE')")
                    .param("l", scope.ledgerId()).param("n", 1255L)
                    .param("types", List.of("ENTRY", "ACCOUNT", "CATEGORY", "COUNTERPARTY"))
                    .query(String.class).list();
            System.out.println("STATE-PLAN 20000 versions, state at revision in " + millis + " ms\n"
                    + String.join("\n", plan));
        } finally {
            // The made-up versions name no live record: they go, so that no other class's baseline sees them as gone.
            try (java.sql.Connection db = previousImageConnection()) {
                db.createStatement().execute("BEGIN; SELECT set_config('app.writer', 'delete-all', true); "
                        + "DELETE FROM record_version WHERE ledger_id = " + scope.ledgerId()
                        + " AND record_id > 900000000; COMMIT");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
