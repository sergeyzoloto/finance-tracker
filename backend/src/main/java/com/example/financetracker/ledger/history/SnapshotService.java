package com.example.financetracker.ledger.history;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.InvalidRequestException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshots of a personal ledger (ADR 0006, HIST-4 part 1): a named revision. Creating one takes no revision and writes
 * no version; it writes one SNAPSHOT_CREATED event. A family budget has none of its own (D-1193, HIST-5); with the
 * family switch on, a snapshot covers the member's whole book (ADR 0006 section 15, D-1290, D-1300 to D-1307): the
 * personal ledger on the snapshot row and one {@code snapshot_ledger} row per family budget he is an ACTIVE member of.
 */
@Service
public class SnapshotService {

    /** A ledger holds at most this many (D-1195). */
    public static final int CAP = 1000;
    public static final int NAME_MAX = 80;
    public static final String KIND_MANUAL = "MANUAL";
    public static final String KIND_BEFORE_RESTORE = "BEFORE_RESTORE";
    public static final String CREATED = "SNAPSHOT_CREATED";

    public static final String NAME_INVALID = "SNAPSHOT_NAME_INVALID";
    public static final String LIMIT_REACHED = "SNAPSHOT_LIMIT_REACHED";
    public static final String HISTORY_INCONSISTENT = "HISTORY_INCONSISTENT";
    public static final String FAMILY_UNSUPPORTED = "FAMILY_SNAPSHOTS_UNSUPPORTED";
    public static final String RETRY = "SNAPSHOT_RETRY";
    public static final String TOO_MANY_BUDGETS = "SNAPSHOT_TOO_MANY_BUDGETS";
    /** A book snapshot covers at most this many family budgets (D-1302). */
    public static final int BUDGET_CAP = 20;
    /** The test seam's step after the family rows are locked, before the personal row (D-1340). */
    public static final String STEP_FAMILIES_LOCKED = "families-locked";
    /** The test seam's step after every lock, before the memberships are read again (D-1340). */
    public static final String STEP_LOCKED = "locked";
    private static final Consumer<String> NO_HOOK = step -> { };

    private static final DateTimeFormatter DEFAULT_NAME = DateTimeFormatter.ofPattern("'Snapshot 'yyyy-MM-dd HH:mm");

    /**
     * @param counts per record type, how many records the state at the revision holds; null in a list
     * @param ledgers the family budgets the snapshot covers (D-1290), possibly none; absent with the family switch off
     */
    public record SnapshotView(long id, String name, String kind, long revision, Instant createdAt,
            RecordHistory.Actor createdBy, Map<RecordType, Long> counts,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<SnapshotLedger> ledgers) {
    }

    /**
     * One family budget of a book snapshot.
     *
     * @param counts per family record type at the revision (soft-deleted records included); null in a list and for a
     *        budget the user is no longer an ACTIVE member of
     */
    public record SnapshotLedger(long ledgerId, String name, long revision, Map<RecordType, Long> counts) {
    }

    public record SnapshotPage(List<SnapshotView> content, int page, int size, long total, int totalPages) {
    }

    private final JdbcClient jdbc;
    private final LedgerState state;
    private final HistoryJobs jobs;
    private final ActivityLog activity;
    private final Today today;
    private final Clock clock;
    private final LedgerAccess access;
    private final FamilySwitch family;
    private volatile Consumer<String> hook = NO_HOOK;

    SnapshotService(JdbcClient jdbc, LedgerState state, HistoryJobs jobs, ActivityLog activity, Today today,
            ObjectProvider<Clock> clock, LedgerAccess access, FamilySwitch family) {
        this.access = access;
        this.family = family;
        this.jdbc = jdbc;
        this.state = state;
        this.jobs = jobs;
        this.activity = activity;
        this.today = today;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** For tests only (D-1340); null puts back the hook that does nothing. */
    public void stepHook(Consumer<String> stepHook) {
        this.hook = stepHook == null ? NO_HOOK : stepHook;
    }

    /**
     * Read committed on purpose: after the lock each statement sees what the writer it waited for committed (D-1196).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SnapshotView create(LedgerScope scope, String requestedName) {
        personalOnly(scope);
        String name = name(scope, requestedName);
        // D-1305 (1): his budgets, without a lock; none with the family switch off (the snapshot is as before HIST-4's
        // family part).
        List<LedgerScope> families = family.enabled() ? access.families(scope.userId()) : List.of();
        if (families.size() > BUDGET_CAP) {
            throw new ConflictException("You are in more than " + BUDGET_CAP + " family budgets, so a snapshot can't "
                    + "cover them all", TOO_MANY_BUDGETS);
        }
        // (2) each family row FOR SHARE by ascending id (access.families orders by id), its revision after its lock:
        // every writer that reaches his personal ledger because of a budget holds that budget's row (D-1305, D-1306).
        Map<LedgerScope, Long> revisions = new LinkedHashMap<>();
        for (LedgerScope budget : families) {
            revisions.put(budget, shareLock(budget));
        }
        hook.accept(STEP_FAMILIES_LOCKED);
        // (3) FOR SHARE waits for a change set that holds the ledger's row (it took its revision), blocks no reader and
        // no other snapshot (D-1196); the revision is read after the lock.
        long revision = shareLock(scope);
        hook.accept(STEP_LOCKED);
        // (4) he joined, left or was removed meanwhile: the cut would miss or hold a budget.
        // By ledger and membership, not by role: an owner change is no change of the book.
        if (family.enabled() && !memberships(access.families(scope.userId())).equals(memberships(families))) {
            throw new ConflictException("Your family budgets changed while the snapshot was taken; try again", RETRY);
        }
        // (5) the consistency checks, the cap, the inserts.
        Map<RecordType, Long> counts = state.counts(scope, revision);
        for (RecordType type : LedgerState.PERSONAL) {
            if (jobs.liveIds(type, scope.ledgerId()).size() != counts.get(type)) {
                throw new ConflictException("The history of your data doesn't match it yet, so no snapshot can be "
                        + "made now; try again later", HISTORY_INCONSISTENT);
            }
        }
        Map<Long, Map<RecordType, Long>> familyCounts = new LinkedHashMap<>();
        for (var budget : revisions.entrySet()) {
            Map<RecordType, Long> at = state.counts(budget.getKey(), budget.getValue(), LedgerState.FAMILY);
            // D-1307: a soft-deleted family record keeps its row, so every row counts.
            for (RecordType type : LedgerState.FAMILY) {
                if (jobs.liveIds(type, budget.getKey().ledgerId()).size() != at.get(type)) {
                    throw new ConflictException("The history of '" + budgetName(budget.getKey()) + "' doesn't match "
                            + "it yet, so no snapshot can be made now; try again later", HISTORY_INCONSISTENT);
                }
            }
            familyCounts.put(budget.getKey().ledgerId(), at);
        }
        // A safety limit, not an invariant: two snapshots at once may both pass it (D-1197).
        long existing = jdbc.sql("SELECT count(*) FROM snapshot WHERE ledger_id = :l").param("l", scope.ledgerId())
                .query(Long.class).single();
        if (existing >= CAP) {
            throw new ConflictException("You have " + CAP + " snapshots, the most there can be", LIMIT_REACHED);
        }
        long id = insert(scope, name, KIND_MANUAL, revision, revisions);
        return find(scope, id, counts, family.enabled() ? familyCounts : null);
    }

    private static List<List<Long>> memberships(List<LedgerScope> families) {
        return families.stream().map(f -> List.of(f.ledgerId(), f.memberId())).toList();
    }

    /** The ledger's row FOR SHARE (D-1196, D-1306) and its revision, read after the lock. */
    private long shareLock(LedgerScope ledger) {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = :l FOR SHARE").param("l", ledger.ledgerId())
                .query(Long.class).single();
    }

    private String budgetName(LedgerScope budget) {
        return jdbc.sql("SELECT name FROM ledger WHERE id = :l").param("l", budget.ledgerId()).query(String.class)
                .single();
    }

    /**
     * The snapshot row, one snapshot_ledger row per family budget (D-1300, D-1301) and one SNAPSHOT_CREATED event,
     * with {@code ledgers} in its details when it covers a budget (D-1341).
     */
    private long insert(LedgerScope scope, String name, String kind, long revision, Map<LedgerScope, Long> families) {
        long id = jdbc.sql("""
                INSERT INTO snapshot (ledger_id, revision, name, kind, created_by)
                VALUES (:l, :revision, :name, :kind, :member) RETURNING id""")
                .param("l", scope.ledgerId()).param("revision", revision).param("name", name)
                .param("kind", kind).param("member", scope.memberId()).query(Long.class).single();
        List<Map<String, Object>> ledgers = new ArrayList<>();
        for (var budget : families.entrySet()) {
            LedgerScope f = budget.getKey();
            if (f.type() != LedgerType.SHARED || !f.userId().equals(scope.userId())) {
                throw new IllegalArgumentException("a book snapshot holds the user's own family budgets only");
            }
            jdbc.sql("""
                    INSERT INTO snapshot_ledger (snapshot_id, ledger_id, revision, member_id)
                    VALUES (:s, :l, :revision, :member)""")
                    .param("s", id).param("l", f.ledgerId()).param("revision", budget.getValue())
                    .param("member", f.memberId()).update();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("ledgerId", f.ledgerId());
            one.put("revision", budget.getValue());
            ledgers.add(one);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("snapshotId", id);
        details.put("name", name);
        details.put("revision", revision);
        details.put("kind", kind);
        if (!ledgers.isEmpty()) {
            details.put("ledgers", ledgers);
        }
        // About the person's own book, as a manual rate is: no ledger id, so the Activity page shows it always.
        activity.log(scope.userId(), null, CREATED, "SNAPSHOT", id, details);
        return id;
    }

    /**
     * The BEFORE_RESTORE snapshot of a restore (HIST-4 part 2b, D-1010, D-1229) at {@code revision}, in the restore's
     * transaction, which already holds the ledgers' rows and checked the cap and the history (no FOR SHARE here).
     * {@code families} maps each family budget of the restore to its revision read under the restore's locks
     * (D-1318); one snapshot_ledger row each. The personal restore of part 2b passes none until part c. Writes one
     * SNAPSHOT_CREATED event, as {@link #create} does. Returns its id.
     */
    long createBeforeRestore(LedgerScope scope, String restoredName, long revision, Map<LedgerScope, Long> families) {
        String full = "Before restoring '" + restoredName + "'";
        String name = full.length() > NAME_MAX ? full.substring(0, NAME_MAX) : full;
        return insert(scope, name, KIND_BEFORE_RESTORE, revision, families);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SnapshotPage list(LedgerScope scope, int page, int size) {
        personalOnly(scope);
        long total = jdbc.sql("SELECT count(*) FROM snapshot WHERE ledger_id = :l").param("l", scope.ledgerId())
                .query(Long.class).single();
        List<SnapshotView> content = select(scope, "s.ledger_id = :l ORDER BY s.created_at DESC, s.id DESC "
                + "LIMIT :limit OFFSET :offset", Map.of("limit", size, "offset", (long) page * size), null);
        if (family.enabled()) {
            Map<Long, List<SnapshotLedger>> parts = new LinkedHashMap<>();
            content.forEach(view -> parts.put(view.id(), new ArrayList<>()));
            if (!parts.isEmpty()) {
                ledgerRows(scope, List.copyOf(parts.keySet()))
                        .forEach(row -> parts.get(row.snapshotId()).add(row.ledger()));
            }
            content = content.stream().map(view -> withLedgers(view, parts.get(view.id()))).toList();
        }
        return new SnapshotPage(content, page, size, total, Math.toIntExact((total + size - 1) / size));
    }

    /** One snapshot with the number of records of each type at its revision, all read in one database snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SnapshotView get(LedgerScope scope, long id) {
        personalOnly(scope);
        SnapshotView found = find(scope, id, null, null);
        if (!family.enabled()) {
            return find(scope, id, state.counts(scope, found.revision()), null);
        }
        // Counts of a budget he is still an ACTIVE member of; a budget he left is listed without them (D-1303).
        Map<Long, Map<RecordType, Long>> familyCounts = new LinkedHashMap<>();
        Map<Long, LedgerScope> active = new LinkedHashMap<>();
        access.families(scope.userId()).forEach(f -> active.put(f.ledgerId(), f));
        for (LedgerRow row : ledgerRows(scope, List.of(id))) {
            LedgerScope budget = active.get(row.ledger().ledgerId());
            if (budget != null) {
                familyCounts.put(budget.ledgerId(), state.counts(budget, row.ledger().revision(), LedgerState.FAMILY));
            }
        }
        return find(scope, id, state.counts(scope, found.revision()), familyCounts);
    }

    /**
     * @param familyCounts per family budget, its counts; null with the family switch off (no {@code ledgers} field)
     */
    private SnapshotView find(LedgerScope scope, long id, Map<RecordType, Long> counts,
            Map<Long, Map<RecordType, Long>> familyCounts) {
        SnapshotView view = select(scope, "s.ledger_id = :l AND s.id = :id", Map.of("id", id), counts).stream()
                .findFirst().orElseThrow(() -> new NotFoundException("Snapshot " + id + " not found"));
        if (familyCounts == null) {
            return view;
        }
        List<SnapshotLedger> ledgers = ledgerRows(scope, List.of(id)).stream().map(LedgerRow::ledger)
                .map(l -> new SnapshotLedger(l.ledgerId(), l.name(), l.revision(), familyCounts.get(l.ledgerId())))
                .toList();
        return withLedgers(view, ledgers);
    }

    private static SnapshotView withLedgers(SnapshotView view, List<SnapshotLedger> ledgers) {
        return new SnapshotView(view.id(), view.name(), view.kind(), view.revision(), view.createdAt(),
                view.createdBy(), view.counts(), ledgers);
    }

    private record LedgerRow(long snapshotId, SnapshotLedger ledger) {
    }

    /** The family rows of the scope's snapshots, by snapshot and ledger id; no counts. */
    private List<LedgerRow> ledgerRows(LedgerScope scope, List<Long> ids) {
        return jdbc.sql("""
                SELECT sl.snapshot_id, sl.ledger_id, sl.revision, l.name
                FROM snapshot s
                JOIN snapshot_ledger sl ON sl.snapshot_id = s.id
                JOIN ledger l ON l.id = sl.ledger_id
                WHERE s.ledger_id = :l AND s.id IN (:ids)
                ORDER BY sl.snapshot_id, sl.ledger_id""")
                .param("l", scope.ledgerId()).param("ids", ids)
                .query((row, n) -> new LedgerRow(row.getLong("snapshot_id"), new SnapshotLedger(
                        row.getLong("ledger_id"), row.getString("name"), row.getLong("revision"), null)))
                .list();
    }

    private List<SnapshotView> select(LedgerScope scope, String where, Map<String, Object> params,
            Map<RecordType, Long> counts) {
        var sql = jdbc.sql("""
                SELECT s.id, s.name, s.kind, s.revision, s.created_at, s.created_by,
                       CASE WHEN s.created_by IS NULL THEN NULL
                            WHEN m.id IS NULL OR m.status = 'FORMER' THEN 'Former member'
                            ELSE m.display_name END AS actor_name
                FROM snapshot s
                LEFT JOIN ledger_member m ON m.id = s.created_by AND m.ledger_id = s.ledger_id
                WHERE\s""" + where).param("l", scope.ledgerId());
        for (var param : params.entrySet()) {
            sql = sql.param(param.getKey(), param.getValue());
        }
        return sql.query((row, n) -> {
            Long member = row.getObject("created_by", Long.class);
            RecordHistory.Actor actor = member == null ? new RecordHistory.Actor("SYSTEM", null, null)
                    : new RecordHistory.Actor("USER", member, row.getString("actor_name"));
            return new SnapshotView(row.getLong("id"), row.getString("name"), row.getString("kind"),
                    row.getLong("revision"), row.getTimestamp("created_at").toInstant(), actor, counts, null);
        }).list();
    }

    private static void personalOnly(LedgerScope scope) {
        if (scope.type() != LedgerType.PERSONAL) {
            throw new ConflictException("A family budget has no snapshots yet", FAMILY_UNSUPPORTED);
        }
    }

    /** Trimmed, 1 to 80 characters, no control character; absent or blank is the date and time in the user's zone. */
    private String name(LedgerScope scope, String requested) {
        String name = requested == null ? "" : requested.strip();
        if (name.isEmpty()) {
            return Instant.now(clock).atZone(today.zone(scope.userId())).format(DEFAULT_NAME);
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidRequestException("A snapshot's name can't hold a control character", NAME_INVALID);
        }
        if (name.length() > NAME_MAX) {
            throw new InvalidRequestException("A snapshot's name has at most " + NAME_MAX + " characters", NAME_INVALID);
        }
        return name;
    }
}
