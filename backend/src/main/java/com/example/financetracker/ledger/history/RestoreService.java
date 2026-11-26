package com.example.financetracker.ledger.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.history.HistoryService.RestoreWrite;
import com.example.financetracker.ledger.history.RestorePlanner.Change;
import com.example.financetracker.ledger.history.RestorePlanner.Outcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A snapshot's restore, all or nothing (ADR 0006, 6.3, 6.8, HIST-4 part 2b, D-1009 to D-1011, D-1226 to D-1232): in one
 * READ COMMITTED transaction, the ledger's row FOR UPDATE, the stale check, the plan of {@link RestorePlanner} again,
 * the BEFORE_RESTORE snapshot, one revision, the writes of the plan with their versions, the sync stamps, the run, its
 * items and one RESTORE event. KEPT and UNCHANGED records and every derived row are never written.
 */
@Service
public class RestoreService {

    public static final String STALE = "RESTORE_STALE";
    public static final String EVENT = "RESTORE";

    /** The answer of a restore: the plan's count per outcome. */
    public record Result(long runId, long revision, long beforeSnapshotId, Map<String, Integer> counts) {
    }

    /** The plan has blockers: nothing was written. */
    public static final class BlockedException extends RuntimeException {
        private final transient RestorePlanner.Preview preview;

        BlockedException(RestorePlanner.Preview preview) {
            super("The restore is blocked");
            this.preview = preview;
        }

        public RestorePlanner.Preview preview() {
            return preview;
        }
    }

    /**
     * The import hash of an entry a restore brings back (D-1219, D-1233): the SHA-256 hex of "restored", which V14's
     * CHECK accepts and no file row's hash matches, so the next import counts the entry as untouched since its import.
     */
    public static final String RESTORED_IMPORT_HASH = sha256Hex("restored");

    private static String sha256Hex(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Consumer<String> NO_HOOK = step -> { };

    private final JdbcClient jdbc;
    private final RestorePlanner planner;
    private final SnapshotService snapshots;
    private final HistoryService history;
    private final ActivityLog activity;
    /** The test seam (D-1226): called after each row write with its step name; inert unless a test sets it. */
    private volatile Consumer<String> hook = NO_HOOK;

    RestoreService(JdbcClient jdbc, RestorePlanner planner, SnapshotService snapshots, HistoryService history,
            ActivityLog activity) {
        this.jdbc = jdbc;
        this.planner = planner;
        this.snapshots = snapshots;
        this.history = history;
        this.activity = activity;
    }

    /** For tests only (D-1226); null puts back the hook that does nothing. */
    public void stepHook(Consumer<String> stepHook) {
        this.hook = stepHook == null ? NO_HOOK : stepHook;
    }

    private record Item(RecordType type, long id, Outcome outcome, RestoreWrite write) {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result restore(LedgerScope scope, long snapshotId, long atRevision) {
        if (scope.type() != LedgerType.PERSONAL) {
            throw new ConflictException("A family budget has no restores yet", SnapshotService.FAMILY_UNSUPPORTED);
        }
        long ledger = scope.ledgerId();
        long revision = jdbc.sql("SELECT revision FROM ledger WHERE id = :l FOR UPDATE").param("l", ledger)
                .query(Long.class).single();
        String name = jdbc.sql("SELECT name FROM snapshot WHERE id = :id AND ledger_id = :l")
                .param("id", snapshotId).param("l", ledger).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("Snapshot " + snapshotId + " not found"));
        if (revision != atRevision) {
            throw new ConflictException("The ledger changed since the preview; preview again", STALE);
        }
        RestorePlanner.Plan plan = planner.ofSnapshot(scope, snapshotId);
        if (!plan.preview().restorable()) {
            throw new BlockedException(plan.preview());
        }
        long before = snapshots.createBeforeRestore(scope, name, revision);
        List<Item> items = new ArrayList<>();
        long taken;
        try (HistoryContext.Frame frame = HistoryContext.open(Cause.RESTORE, scope.userId())) {
            taken = history.takeRevision(ledger);
            apply(scope, plan.changes(), items);
        }
        long run = jdbc.sql("""
                INSERT INTO restore_run (ledger_id, kind, snapshot_id, target_revision, revision, before_snapshot_id,
                                         actor_member, counts)
                VALUES (:l, 'SNAPSHOT', :s, :target, :r, :before, :member, CAST(:counts AS jsonb)) RETURNING id""")
                .param("l", ledger).param("s", snapshotId).param("target", plan.preview().targetRevision())
                .param("r", taken).param("before", before).param("member", scope.memberId())
                .param("counts", json(plan.preview().counts())).query(Long.class).single();
        for (Item item : items) {
            jdbc.sql("""
                    INSERT INTO restore_item (run_id, record_type, record_id, outcome, replaced_version_id,
                                              written_version_id)
                    VALUES (:run, :t, :id, :o, :replaced, :written)""")
                    .param("run", run).param("t", item.type().name()).param("id", item.id())
                    .param("o", item.outcome().name()).param("replaced", item.write().replacedVersionId())
                    .param("written", item.write().versionId()).update();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("snapshotId", snapshotId);
        details.put("name", name);
        details.put("beforeSnapshotId", before);
        details.put("runId", run);
        details.put("counts", plan.preview().counts());
        activity.log(scope.userId(), null, EVENT, "SNAPSHOT", snapshotId, taken, details);
        return new Result(run, taken, before, plan.preview().counts());
    }

    /** The plan's writes, in an order that keeps every key and reference valid at each statement. */
    private void apply(LedgerScope scope, Map<RecordType, Map<Long, Change>> changes, List<Item> items) {
        long l = scope.ledgerId();
        Map<Long, Change> entries = changes.get(RecordType.ENTRY);
        List<Long> restoredEntries = ids(entries, Outcome.RESTORED);

        // 1. Removals: entries (ARCHIVED ones without an imported_entry_deleted row, D-1038, D-1224), then what the
        // restored entries hold now (postings, payee, external ref), then the removed reference records.
        writer(scope, "restore-archive");
        for (long id : ids(entries, Outcome.ARCHIVED)) {
            removeEntry(scope, id, Outcome.ARCHIVED, items);
        }
        writer(scope, "");
        for (long id : ids(entries, Outcome.DELETED)) {
            removeEntry(scope, id, Outcome.DELETED, items);
        }
        if (!restoredEntries.isEmpty()) {
            jdbc.sql("DELETE FROM posting WHERE entry_id IN (SELECT id FROM journal_entry WHERE ledger_id = :l "
                    + "AND id IN (:ids))").param("l", l).param("ids", restoredEntries).update();
            jdbc.sql("""
                    UPDATE journal_entry SET payee_id = NULL,
                           external_ref = CASE WHEN external_ref IS NULL THEN NULL ELSE '~restore~' || id END
                    WHERE ledger_id = :l AND id IN (:ids)""").param("l", l).param("ids", restoredEntries).update();
        }
        for (RecordType type : List.of(RecordType.COUNTERPARTY, RecordType.CATEGORY, RecordType.ACCOUNT)) {
            for (Outcome outcome : List.of(Outcome.DELETED, Outcome.ARCHIVED)) {
                for (long id : ids(changes.get(type), outcome)) {
                    Map<String, Object> last = history.capture(type, l, id).orElseThrow();
                    jdbc.sql("DELETE FROM " + table(type) + " WHERE ledger_id = :l AND id = :id")
                            .param("l", l).param("id", id).update();
                    items.add(new Item(type, id, outcome, history.restored(l, type, id,
                            outcome == Outcome.DELETED ? "DELETE" : "ARCHIVE", last, null)));
                    hook.accept(step(type, id));
                }
            }
        }

        // 2. Reference records: every restored one's key is freed first, then each takes its target.
        for (RecordType type : List.of(RecordType.COUNTERPARTY, RecordType.CATEGORY, RecordType.ACCOUNT)) {
            List<Long> restored = ids(changes.get(type), Outcome.RESTORED);
            if (!restored.isEmpty()) {
                jdbc.sql("UPDATE " + table(type) + " SET " + (type == RecordType.COUNTERPARTY ? "name" : "code")
                        + " = '~restore~' || id WHERE ledger_id = :l AND id IN (:ids)")
                        .param("l", l).param("ids", restored).update();
            }
            for (Outcome outcome : List.of(Outcome.RESTORED, Outcome.RETURNED)) {
                for (long id : ids(changes.get(type), outcome)) {
                    Change change = changes.get(type).get(id);
                    writeReference(scope, type, id, change.content(), outcome == Outcome.RETURNED);
                    items.add(new Item(type, id, outcome, history.restored(l, type, id, "RESTORE", change.content(),
                            change.targetVersionId())));
                    hook.accept(step(type, id));
                }
            }
        }

        // 3. Entries, postings in line order; then the sync stamps (D-1219, D-1231), which are no content.
        for (Outcome outcome : List.of(Outcome.RESTORED, Outcome.RETURNED)) {
            for (long id : ids(entries, outcome)) {
                Change change = entries.get(id);
                writeEntry(scope, id, change.content(), outcome == Outcome.RETURNED);
                items.add(new Item(RecordType.ENTRY, id, outcome, history.restored(l, RecordType.ENTRY, id, "RESTORE",
                        change.content(), change.targetVersionId())));
                hook.accept(step(RecordType.ENTRY, id));
            }
        }
        // The importer compares import_version with the entry's version (ImportSync): equal is untouched. A RETURNED
        // entry, and a RESTORED one that has no import hash (its ref is new to it), take the marker hash, since V14
        // pairs the two columns (D-1219, D-1233).
        List<Long> written = new ArrayList<>(restoredEntries);
        written.addAll(ids(entries, Outcome.RETURNED));
        if (!written.isEmpty()) {
            jdbc.sql("""
                    UPDATE journal_entry SET import_version = version,
                           import_hash = CASE WHEN id IN (:returned) THEN :hash ELSE coalesce(import_hash, :hash) END
                    WHERE ledger_id = :l AND id IN (:ids) AND external_ref IS NOT NULL""")
                    .param("hash", RESTORED_IMPORT_HASH).param("l", l).param("ids", written)
                    .param("returned", ids(entries, Outcome.RETURNED).isEmpty() ? List.of(-1L)
                            : ids(entries, Outcome.RETURNED)).update();
        }
    }

    private void removeEntry(LedgerScope scope, long id, Outcome outcome, List<Item> items) {
        long l = scope.ledgerId();
        Map<String, Object> last = history.capture(RecordType.ENTRY, l, id).orElseThrow();
        jdbc.sql("DELETE FROM journal_entry WHERE ledger_id = :l AND id = :id").param("l", l).param("id", id).update();
        items.add(new Item(RecordType.ENTRY, id, outcome, history.restored(l, RecordType.ENTRY, id,
                outcome == Outcome.DELETED ? "DELETE" : "ARCHIVE", last, null)));
        hook.accept(step(RecordType.ENTRY, id));
    }

    private void writeReference(LedgerScope scope, RecordType type, long id, Map<String, Object> c, boolean insert) {
        boolean archived = Boolean.TRUE.equals(c.get("archived"));
        var sql = switch (type) {
            case ACCOUNT -> jdbc.sql(insert ? """
                    INSERT INTO account (id, user_id, ledger_id, code, name, type, default_currency,
                                         requires_counterparty, is_system, archived_at)
                    OVERRIDING SYSTEM VALUE
                    VALUES (:id, :u, :l, :code, :name, :type, :currency, :rc, :system,
                            CASE WHEN :archived THEN now() END)""" : """
                    UPDATE account SET code = :code, name = :name, type = :type, default_currency = :currency,
                           requires_counterparty = :rc, is_system = :system,
                           archived_at = CASE WHEN :archived THEN coalesce(archived_at, now()) END
                    WHERE ledger_id = :l AND id = :id""")
                    .param("code", c.get("code")).param("name", c.get("name")).param("type", c.get("type"))
                    .param("currency", c.get("defaultCurrency"))
                    .param("rc", Boolean.TRUE.equals(c.get("requiresCounterparty")))
                    .param("system", Boolean.TRUE.equals(c.get("system")));
            case CATEGORY -> jdbc.sql(insert ? """
                    INSERT INTO category (id, user_id, ledger_id, code, name, type, archived_at)
                    OVERRIDING SYSTEM VALUE
                    VALUES (:id, :u, :l, :code, :name, :type, CASE WHEN :archived THEN now() END)""" : """
                    UPDATE category SET code = :code, name = :name, type = :type,
                           archived_at = CASE WHEN :archived THEN coalesce(archived_at, now()) END
                    WHERE ledger_id = :l AND id = :id""")
                    .param("code", c.get("code")).param("name", c.get("name")).param("type", c.get("type"));
            default -> jdbc.sql(insert ? """
                    INSERT INTO counterparty (id, user_id, ledger_id, name, kind, archived_at)
                    OVERRIDING SYSTEM VALUE
                    VALUES (:id, :u, :l, :name, :kind, CASE WHEN :archived THEN now() END)""" : """
                    UPDATE counterparty SET name = :name, kind = :kind,
                           archived_at = CASE WHEN :archived THEN coalesce(archived_at, now()) END
                    WHERE ledger_id = :l AND id = :id""")
                    .param("name", c.get("name")).param("kind", c.get("kind"));
        };
        sql = sql.param("id", id).param("l", scope.ledgerId()).param("archived", archived);
        if (insert) {
            sql = sql.param("u", scope.userId());
        }
        sql.update();
    }

    private void writeEntry(LedgerScope scope, long id, Map<String, Object> c, boolean insert) {
        long l = scope.ledgerId();
        String ref = (String) c.get("externalRef");
        var sql = jdbc.sql(insert ? """
                INSERT INTO journal_entry (id, user_id, ledger_id, entry_date, kind, payee_id, memo, external_ref)
                OVERRIDING SYSTEM VALUE
                VALUES (:id, :u, :l, :date, :kind, :payee, :memo, :ref)""" : """
                UPDATE journal_entry SET entry_date = :date, kind = :kind, payee_id = :payee, memo = :memo,
                       external_ref = :ref,
                       import_hash = CASE WHEN CAST(:ref AS text) IS NULL THEN NULL ELSE import_hash END,
                       import_version = CASE WHEN CAST(:ref AS text) IS NULL THEN NULL ELSE import_version END,
                       version = version + 1, updated_at = now()
                WHERE ledger_id = :l AND id = :id""")
                .param("id", id).param("l", l).param("date", LocalDate.parse(String.valueOf(c.get("date"))))
                .param("kind", c.get("kind")).param("payee", asLong(c.get("payee"))).param("memo", c.get("memo"))
                .param("ref", ref);
        if (insert) {
            sql = sql.param("u", scope.userId());
        }
        sql.update();
        int line = 0;
        for (Object posting : c.get("postings") instanceof List<?> list ? list : List.of()) {
            Map<?, ?> p = (Map<?, ?>) posting;
            jdbc.sql("""
                    INSERT INTO posting (entry_id, line_no, account_id, currency, amount, category_id, counterparty_id)
                    VALUES (:e, :n, :a, :c, :amount, :cat, :cp)""")
                    .param("e", id).param("n", line++).param("a", asLong(p.get("account")))
                    .param("c", p.get("currency")).param("amount", new BigDecimal(String.valueOf(p.get("amount"))))
                    .param("cat", asLong(p.get("category"))).param("cp", asLong(p.get("counterparty"))).update();
        }
        if (ref != null) {
            // A RETURNED entry, or a RESTORED one whose ref is back: the import's memory of its deletion goes (D-1038).
            jdbc.sql("DELETE FROM imported_entry_deleted WHERE ledger_id = :l AND external_ref = :ref")
                    .param("l", l).param("ref", ref).update();
        }
    }

    private void writer(LedgerScope scope, String writer) {
        jdbc.sql("SELECT set_config('app.writer', :w, true)").param("w", writer).query(String.class).single();
    }

    private static List<Long> ids(Map<Long, Change> changes, Outcome outcome) {
        return changes.entrySet().stream().filter(e -> e.getValue().outcome() == outcome).map(Map.Entry::getKey)
                .toList();
    }

    private static String table(RecordType type) {
        return switch (type) {
            case ACCOUNT -> "account";
            case CATEGORY -> "category";
            case COUNTERPARTY -> "counterparty";
            default -> throw new IllegalArgumentException(type.name());
        };
    }

    private static String step(RecordType type, long id) {
        return type.name().toLowerCase(java.util.Locale.ROOT) + ":" + id;
    }

    private static Long asLong(Object value) {
        return value == null ? null : value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
    }

    private static String json(Map<String, Integer> counts) {
        StringBuilder text = new StringBuilder("{");
        counts.forEach((k, v) -> text.append(text.length() > 1 ? "," : "").append('"').append(k).append("\":")
                .append(Objects.requireNonNull(v)));
        return text.append('}').toString();
    }
}
