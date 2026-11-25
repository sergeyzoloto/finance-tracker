package com.example.financetracker.ledger.history;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.InvalidRequestException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshots of a personal ledger (ADR 0006, HIST-4 part 1): a named revision. Creating one takes no revision and writes
 * no version; it writes one SNAPSHOT_CREATED event. A family budget has none yet (D-1193).
 */
@Service
public class SnapshotService {

    /** A ledger holds at most this many (D-1195). */
    public static final int CAP = 1000;
    public static final int NAME_MAX = 80;
    public static final String KIND_MANUAL = "MANUAL";
    public static final String CREATED = "SNAPSHOT_CREATED";

    public static final String NAME_INVALID = "SNAPSHOT_NAME_INVALID";
    public static final String LIMIT_REACHED = "SNAPSHOT_LIMIT_REACHED";
    public static final String HISTORY_INCONSISTENT = "HISTORY_INCONSISTENT";
    public static final String FAMILY_UNSUPPORTED = "FAMILY_SNAPSHOTS_UNSUPPORTED";

    private static final DateTimeFormatter DEFAULT_NAME = DateTimeFormatter.ofPattern("'Snapshot 'yyyy-MM-dd HH:mm");

    /** @param counts per record type, how many records the state at the revision holds; null in a list */
    public record SnapshotView(long id, String name, String kind, long revision, Instant createdAt,
            RecordHistory.Actor createdBy, Map<RecordType, Long> counts) {
    }

    public record SnapshotPage(List<SnapshotView> content, int page, int size, long total, int totalPages) {
    }

    private final JdbcClient jdbc;
    private final LedgerState state;
    private final HistoryJobs jobs;
    private final ActivityLog activity;
    private final Today today;
    private final Clock clock;

    SnapshotService(JdbcClient jdbc, LedgerState state, HistoryJobs jobs, ActivityLog activity, Today today,
            ObjectProvider<Clock> clock) {
        this.jdbc = jdbc;
        this.state = state;
        this.jobs = jobs;
        this.activity = activity;
        this.today = today;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Read committed on purpose: after the lock each statement sees what the writer it waited for committed (D-1196).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SnapshotView create(LedgerScope scope, String requestedName) {
        personalOnly(scope);
        String name = name(scope, requestedName);
        // FOR SHARE waits for a change set that holds the ledger's row (it took its revision), blocks no reader and no
        // other snapshot (D-1196); the revision is read after the lock.
        long revision = jdbc.sql("SELECT revision FROM ledger WHERE id = :l FOR SHARE").param("l", scope.ledgerId())
                .query(Long.class).single();
        Map<RecordType, Long> counts = state.counts(scope, revision);
        for (RecordType type : LedgerState.PERSONAL) {
            if (jobs.liveIds(type, scope.ledgerId()).size() != counts.get(type)) {
                throw new ConflictException("The history of your data doesn't match it yet, so no snapshot can be "
                        + "made now; try again later", HISTORY_INCONSISTENT);
            }
        }
        // A safety limit, not an invariant: two snapshots at once may both pass it (D-1197).
        long existing = jdbc.sql("SELECT count(*) FROM snapshot WHERE ledger_id = :l").param("l", scope.ledgerId())
                .query(Long.class).single();
        if (existing >= CAP) {
            throw new ConflictException("You have " + CAP + " snapshots, the most there can be", LIMIT_REACHED);
        }
        long id = jdbc.sql("""
                INSERT INTO snapshot (ledger_id, revision, name, kind, created_by)
                VALUES (:l, :revision, :name, :kind, :member) RETURNING id""")
                .param("l", scope.ledgerId()).param("revision", revision).param("name", name)
                .param("kind", KIND_MANUAL).param("member", scope.memberId()).query(Long.class).single();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("snapshotId", id);
        details.put("name", name);
        details.put("revision", revision);
        details.put("kind", KIND_MANUAL);
        // About the person's own ledger, as a manual rate is: no ledger id, so the Activity page shows it always.
        activity.log(scope.userId(), null, CREATED, "SNAPSHOT", id, details);
        return find(scope, id, counts);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SnapshotPage list(LedgerScope scope, int page, int size) {
        personalOnly(scope);
        long total = jdbc.sql("SELECT count(*) FROM snapshot WHERE ledger_id = :l").param("l", scope.ledgerId())
                .query(Long.class).single();
        List<SnapshotView> content = select(scope, "s.ledger_id = :l ORDER BY s.created_at DESC, s.id DESC "
                + "LIMIT :limit OFFSET :offset", Map.of("limit", size, "offset", (long) page * size), null);
        return new SnapshotPage(content, page, size, total, Math.toIntExact((total + size - 1) / size));
    }

    /** One snapshot with the number of records of each type at its revision, all read in one database snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SnapshotView get(LedgerScope scope, long id) {
        personalOnly(scope);
        SnapshotView found = find(scope, id, null);
        return find(scope, id, state.counts(scope, found.revision()));
    }

    private SnapshotView find(LedgerScope scope, long id, Map<RecordType, Long> counts) {
        return select(scope, "s.ledger_id = :l AND s.id = :id", Map.of("id", id), counts).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Snapshot " + id + " not found"));
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
                    row.getLong("revision"), row.getTimestamp("created_at").toInstant(), actor, counts);
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
