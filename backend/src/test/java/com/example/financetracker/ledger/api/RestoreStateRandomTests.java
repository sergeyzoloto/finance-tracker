package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import com.example.financetracker.RandomVolume;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.history.Canonical;
import com.example.financetracker.ledger.history.LedgerState;
import com.example.financetracker.ledger.history.RecordType;
import com.example.financetracker.ledger.history.RestorePlanner;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * The restore plan of a snapshot against the state at its revision (HIST-4 part 2a, D-1220, D-1221): random writes
 * through the API, snapshots kept at several points, and for each snapshot the plan's live state after the restore
 * equals {@link LedgerState#at} at that revision, for every record, except the KEPT records (rule 12). The plan has no
 * blocker in these ledgers: every restore of them is valid.
 */
@Tag("random")
class RestoreStateRandomTests extends LedgerApiTest {

    private static final List<RecordType> TYPES =
            List.of(RecordType.ENTRY, RecordType.ACCOUNT, RecordType.CATEGORY, RecordType.COUNTERPARTY);

    @Autowired
    private LedgerState state;
    @Autowired
    private LedgerAccess access;
    @Autowired
    private RestorePlanner planner;

    @Test
    void thePlanOfEachSnapshotIsTheStateAtItsRevision() {
        // A fresh seed per run, or the one of tests.random.seed to replay a run (TEST-1b).
        long seed = RandomVolume.seedOr(System.nanoTime());
        RandomVolume.run(getClass(), seed, () -> thePlanOfEachSnapshot(seed));
    }

    private void thePlanOfEachSnapshot(long seed) throws IOException {
        Random random = new Random(seed);
        String user = newUser();
        ok(get(user, "/api/accounts"));
        LedgerScope scope = access.personal(user);
        List<long[]> entries = new ArrayList<>();
        List<Long> payees = new ArrayList<>();
        List<Long> accounts = new ArrayList<>();
        List<Long> categories = new ArrayList<>();
        List<Long> snapshots = new ArrayList<>();
        // The same operations as SnapshotStateRandomTests: the first 12 steps run each once, the tail is random.
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
                snapshots.add(body(post(user, "/api/snapshots", "{}"), HttpStatus.CREATED).get("id").asLong());
            }
        }
        assertThat(snapshots).hasSizeGreaterThanOrEqualTo(
                RandomVolume.scale() == 1 ? 6 : Math.max(1, (int) Math.round(6 * RandomVolume.scale())));

        for (long snapshot : snapshots) {
            JsonNode preview = ok(post(user, "/api/snapshots/" + snapshot + "/restore-preview", "{}"));
            long revision = preview.get("targetRevision").asLong();
            assertThat(preview.get("blockers").size()).as("seed %d, snapshot %d", seed, snapshot).isZero();

            RestorePlanner.Plan plan = planner.ofSnapshot(scope, snapshot);
            Map<RecordType, Map<Long, String>> at = state.at(scope, revision);
            Map<RecordType, Set<Long>> kept = plan.kept();
            for (RecordType type : TYPES) {
                Set<Long> ids = new TreeSet<>(at.get(type).keySet());
                ids.addAll(plan.afterState().get(type).keySet());
                for (long id : ids) {
                    if (kept.get(type).contains(id)) {
                        continue;
                    }
                    String expected = at.get(type).containsKey(id) ? Canonical.canonicalize(at.get(type).get(id)) : null;
                    String actual = plan.afterState().get(type).get(id);
                    assertThat(actual).as("seed %d, snapshot %d (revision %d), %s %d", seed, snapshot, revision, type, id)
                            .isEqualTo(expected);
                }
            }
        }
    }
}
