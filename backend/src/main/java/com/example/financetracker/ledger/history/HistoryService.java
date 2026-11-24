package com.example.financetracker.ledger.history;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Record versions (ADR 0006, D-990, D-1000, D-1001, D-1003): after a write, the service reads the live record, makes
 * its canonical content and hash and, when the hash differs from the latest version's, writes the next version. No
 * change, no version and no revision (D-995). The first change to a record that has no version writes its state before
 * the change as a BASELINE version, read from the committed database through a pool of its own (the outer transaction hasn't
 * committed, so that is the state before it); so does a change to a record whose live state no longer equals its latest
 * version, which only a write by the image before HIST-1 can cause (D-1040), as a RECONCILE version.
 * <p>
 * The revision is the ledger's change-set counter: taken once per transaction and ledger, at the first version
 * written (an UPDATE of the ledger's row, held until the transaction ends), so that revisions follow commit order.
 */
@Service
public class HistoryService {

    /** The shape of {@code content}, for a later change of it. */
    public static final short CONTENT_VERSION = 1;

    private static final Object STATE_KEY = new Object();

    private final JdbcClient jdbc;
    private final RecordContents contents;
    private final ObjectProvider<ActorSource> actors;
    /**
     * What the database had committed is read through a pool of its own, never the application's: a request that holds
     * a connection for its transaction and asks the same pool for a second one, as a nested transaction does, starves
     * the pool when enough requests do it at once (every one holds one and waits for another). These connections are
     * held for one read and release it without asking for anything else, so they can only wait, never deadlock.
     */
    private final HikariDataSource committedReads;
    private final RecordContents committedContents;
    private final FamilyAcceptanceSwitch acceptance;

    HistoryService(JdbcClient jdbc, RecordContents contents, JdbcConnectionDetails connection,
            ObjectProvider<ActorSource> actors, @Value("${spring.datasource.hikari.schema:app}") String schema,
            FamilyAcceptanceSwitch acceptance) {
        this.jdbc = jdbc;
        this.acceptance = acceptance;
        this.contents = contents;
        this.actors = actors;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connection.getJdbcUrl());
        config.setUsername(connection.getUsername());
        config.setPassword(connection.getPassword());
        config.setSchema(schema);
        config.setPoolName("history-committed-reads");
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setConnectionTimeout(10_000);
        config.setInitializationFailTimeout(-1);
        this.committedReads = new HikariDataSource(config);
        this.committedContents = new RecordContents(JdbcClient.create(committedReads));
    }

    @PreDestroy
    void close() {
        committedReads.close();
    }

    /** What one transaction has done so far. */
    private static final class State {
        final Map<Long, Long> revisions = new HashMap<>();
        final Set<String> versioned = new HashSet<>();
        final Map<Long, Optional<Long>> members = new HashMap<>();
        /** The ledgers whose revision a label-only write took, with acceptance on, until a record changes in it. */
        final Set<Long> labelOnly = new HashSet<>();
    }

    private record Latest(int seq, byte[] hash, String op) {
    }

    /** The record's live content, for a caller that is about to delete it (and then calls {@link #deleted}). */
    public Optional<Map<String, Object>> capture(RecordType type, long ledgerId, long id) {
        return contents.load(type, ledgerId, id);
    }

    /** After the record was created or changed: writes a version if its content differs from the latest one's. */
    public void changed(long ledgerId, RecordType type, long id) {
        changed(ledgerId, type, id, false);
    }

    /**
     * As {@link #changed(long, RecordType, long)}; {@code fresh} says the row was inserted by this very write, so it has
     * no version and no committed state to read (the import writes thousands of them: no query is spent on that).
     */
    public void changed(long ledgerId, RecordType type, long id, boolean fresh) {
        Optional<Map<String, Object>> live = contents.load(type, ledgerId, id);
        if (live.isEmpty()) {
            return;
        }
        write(ledgerId, type, id, live.get(), false, fresh);
    }

    /** A record inserted by this write, whose content the caller has: no read at all, one insert. */
    public void created(long ledgerId, RecordType type, long id, Map<String, Object> content) {
        write(ledgerId, type, id, content, false, true);
    }

    /** After the record was deleted: a DELETE version holding its last content, which {@link #capture} read. */
    public void deleted(long ledgerId, RecordType type, long id, Map<String, Object> last) {
        write(ledgerId, type, id, last, true, false);
    }

    /**
     * Versions every account, category and payee of the ledger that a raw INSERT or UPDATE wrote (the starter ledger,
     * the demo): a version for each one whose content differs from its latest. Derived accounts are no records.
     */
    public void changedAllReference(long ledgerId) {
        jdbc.sql("""
                SELECT id FROM account WHERE ledger_id = :l AND family_ledger_id IS NULL AND code <> 'UNSPECIFIED_PAYMENTS'
                ORDER BY id""").param("l", ledgerId).query(Long.class).list()
                .forEach(id -> changed(ledgerId, RecordType.ACCOUNT, id));
        jdbc.sql("SELECT id, user_id IS NULL AS family FROM category WHERE ledger_id = :l ORDER BY id")
                .param("l", ledgerId)
                .query((row, n) -> new Object[] {row.getLong("id"), row.getBoolean("family")}).list()
                .forEach(r -> changed(ledgerId, (boolean) r[1] ? RecordType.FAMILY_CATEGORY : RecordType.CATEGORY,
                        (long) r[0]));
        jdbc.sql("SELECT id FROM counterparty WHERE ledger_id = :l ORDER BY id").param("l", ledgerId)
                .query(Long.class).list().forEach(id -> changed(ledgerId, RecordType.COUNTERPARTY, id));
    }

    /**
     * The baseline job's write (ADR 0006, 5.6): a BASELINE version of the live content at the ledger's current revision
     * (no revision of its own: nothing changed), or a RECONCILE one when versions exist and differ. False if the record
     * has a version equal to it by now.
     */
    boolean baseline(long ledgerId, RecordType type, long id, Map<String, Object> live, boolean reconcile) {
        State state = state();
        Latest latest = latest(ledgerId, type, id);
        if (latest != null && Arrays.equals(latest.hash(), Canonical.hash(live))) {
            return false;
        }
        long revision = jdbc.sql("SELECT revision FROM ledger WHERE id = :id").param("id", ledgerId)
                .query(Long.class).single();
        insert(ledgerId, type, id, latest == null ? 1 : latest.seq() + 1, revision, "BASELINE", live,
                reconcile ? Cause.RECONCILE : Cause.BASELINE, state);
        return true;
    }

    /** The baseline job's DELETE version for a record whose live row is gone: its last version's content. */
    boolean baselineGone(long ledgerId, RecordType type, long id) {
        State state = state();
        Latest latest = latest(ledgerId, type, id);
        if (latest == null || "DELETE".equals(latest.op())) {
            return false;
        }
        String last = jdbc.sql("""
                SELECT content::text FROM record_version
                WHERE ledger_id = :l AND record_type = :t AND record_id = :id ORDER BY seq DESC LIMIT 1""")
                .param("l", ledgerId).param("t", type.name()).param("id", id).query(String.class).single();
        long revision = jdbc.sql("SELECT revision FROM ledger WHERE id = :id").param("id", ledgerId)
                .query(Long.class).single();
        Map<String, Object> content = contentOf(last);
        insert(ledgerId, type, id, latest.seq() + 1, revision, "DELETE", content, Cause.RECONCILE, state);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contentOf(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the content equals the live record's, for a save that would change nothing (D-995). */
    public boolean unchanged(long ledgerId, RecordType type, long id, Map<String, Object> content) {
        return contents.load(type, ledgerId, id)
                .map(live -> Arrays.equals(Canonical.hash(live), Canonical.hash(content)))
                .orElse(false);
    }

    private void write(long ledgerId, RecordType type, long id, Map<String, Object> content, boolean deleting,
            boolean fresh) {
        State state = state();
        String key = type + ":" + id;
        byte[] hash = Canonical.hash(content);
        Latest latest = fresh ? null : latest(ledgerId, type, id);
        boolean isDelete = deleting || Boolean.TRUE.equals(content.get("deleted"));
        boolean sameAsLatest = latest != null && Arrays.equals(latest.hash(), hash)
                && "DELETE".equals(latest.op()) == isDelete;
        // What the database had committed before this transaction, read only when it matters: the first version of the
        // record in this transaction.
        Optional<Map<String, Object>> before = fresh || state.versioned.contains(key) ? Optional.empty()
                : committed(type, ledgerId, id);
        boolean drifted = before.isPresent()
                && (latest == null || !Arrays.equals(latest.hash(), Canonical.hash(before.get())));
        if (sameAsLatest && !drifted) {
            return;
        }
        long revision = revision(state, ledgerId, type);
        if (drifted) {
            // The latest version isn't what the database held: nothing wrote a version for the difference (the image
            // before HIST-1, D-1040, or a row that wasn't a record until now, such as a debt account when its member
            // left). That state is written first, one revision before this change.
            Cause cause = latest == null ? Cause.BASELINE : Cause.RECONCILE;
            latest = insert(ledgerId, type, id, latest == null ? 1 : latest.seq() + 1, revision - 1, "BASELINE",
                    before.get(), cause, state);
        }
        String op = isDelete ? "DELETE" : latest == null ? "CREATE" : "UPDATE";
        insert(ledgerId, type, id, latest == null ? 1 : latest.seq() + 1, revision, op, content, HistoryContext.cause(),
                state);
        state.versioned.add(key);
    }

    private Latest insert(long ledgerId, RecordType type, long id, int seq, long revision, String op,
            Map<String, Object> content, Cause cause, State state) {
        String text = Canonical.text(content);
        byte[] hash = Canonical.sha256(text);
        Long member = member(state, ledgerId);
        jdbc.sql("""
                INSERT INTO record_version (ledger_id, record_type, record_id, seq, revision, op, content, content_hash,
                    content_ver, actor_kind, actor_member, cause, cause_batch)
                VALUES (:ledgerId, :type, :id, :seq, :revision, :op, CAST(:content AS jsonb), :hash, :ver, :kind,
                    :member, :cause, :batch)""")
                .param("ledgerId", ledgerId).param("type", type.name()).param("id", id).param("seq", seq)
                .param("revision", revision).param("op", op).param("content", text).param("hash", hash)
                .param("ver", CONTENT_VERSION).param("kind", cause.actorKind(member != null))
                .param("member", cause == Cause.BASELINE || cause == Cause.RECONCILE ? null : member)
                .param("cause", cause.name())
                .param("batch", cause == Cause.IMPORT ? HistoryContext.batchId() : null)
                .update();
        return new Latest(seq, hash, op);
    }

    private Latest latest(long ledgerId, RecordType type, long id) {
        return jdbc.sql("""
                SELECT seq, content_hash, op FROM record_version
                WHERE ledger_id = :ledgerId AND record_type = :type AND record_id = :id
                ORDER BY seq DESC LIMIT 1""")
                .param("ledgerId", ledgerId).param("type", type.name()).param("id", id)
                .query((row, n) -> new Latest(row.getInt("seq"), row.getBytes("content_hash"), row.getString("op")))
                .optional().orElse(null);
    }

    /** The state before this transaction: what the database has committed. */
    private Optional<Map<String, Object>> committed(RecordType type, long ledgerId, long id) {
        return committedContents.load(type, ledgerId, id);
    }

    /** The ledger's revision this transaction took, if it changed a record of it (for its notification, D-1105). */
    public java.util.OptionalLong revisionTaken(long ledgerId) {
        State state = TransactionSynchronizationManager.isSynchronizationActive()
                ? (State) TransactionSynchronizationManager.getResource(STATE_KEY) : null;
        Long revision = state == null ? null : state.revisions.get(ledgerId);
        return revision == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(revision);
    }

    private long revision(State state, long ledgerId, RecordType type) {
        if (acceptance.enabled()) {
            return acceptingRevision(state, ledgerId, type);
        }
        // With the revision, every ACTIVE member with an account of a family ledger has accepted it (HIST-3, D-1157):
        // with acceptance off, each change set is posted to everybody at once.
        return state.revisions.computeIfAbsent(ledgerId, l -> jdbc.sql("""
                WITH taken AS (UPDATE ledger SET revision = revision + 1 WHERE id = :id RETURNING id, type, revision),
                accepted AS (UPDATE ledger_member m SET accepted_revision = t.revision FROM taken t
                             WHERE t.type = 'SHARED' AND m.ledger_id = t.id AND m.status = 'ACTIVE'
                               AND m.user_sub IS NOT NULL)
                SELECT revision FROM taken""")
                .param("id", l).query(Long.class).single());
    }

    /**
     * The revision with acceptance on (HIST-3, D-1159): a change set of records (the type that takes it, or any later
     * write of the transaction, is a family record) is accepted by nobody here, its actor by the posting
     * (D-1161); one that changes no record, such as a family category's rename, is accepted by the members who had
     * accepted everything before it, and the lagging ones keep their counter.
     */
    private long acceptingRevision(State state, long ledgerId, RecordType type) {
        boolean record = type == RecordType.FAMILY_RECORD;
        Long taken = state.revisions.get(ledgerId);
        if (taken == null) {
            taken = jdbc.sql("""
                    WITH taken AS (UPDATE ledger SET revision = revision + 1 WHERE id = :id RETURNING id, type, revision),
                    accepted AS (UPDATE ledger_member m SET accepted_revision = t.revision FROM taken t
                                 WHERE NOT :record AND t.type = 'SHARED' AND m.ledger_id = t.id
                                   AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL
                                   AND m.accepted_revision = t.revision - 1)
                    SELECT revision FROM taken""")
                    .param("id", ledgerId).param("record", record).query(Long.class).single();
            state.revisions.put(ledgerId, taken);
            if (!record) {
                state.labelOnly.add(ledgerId);
            }
        } else if (record && state.labelOnly.remove(ledgerId)) {
            // A record changes after all: who the label-only start moved to it goes back (only they can be at it).
            jdbc.sql("""
                    UPDATE ledger_member SET accepted_revision = :revision - 1
                    WHERE ledger_id = :id AND accepted_revision = :revision""")
                    .param("id", ledgerId).param("revision", taken).update();
        }
        return taken;
    }

    private Long member(State state, long ledgerId) {
        return state.members.computeIfAbsent(ledgerId, l -> {
            String sub = HistoryContext.actorSub();
            if (sub == null) {
                ActorSource source = actors.getIfAvailable();
                sub = source == null ? null : source.currentSub().orElse(null);
            }
            if (sub == null) {
                return Optional.empty();
            }
            return jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = :ledgerId AND user_sub = :sub")
                    .param("ledgerId", l).param("sub", sub).query(Long.class).optional();
        }).orElse(null);
    }

    private State state() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return new State();
        }
        State state = (State) TransactionSynchronizationManager.getResource(STATE_KEY);
        if (state == null) {
            state = new State();
            TransactionSynchronizationManager.bindResource(STATE_KEY, state);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(STATE_KEY);
                }
            });
        }
        return state;
    }
}
