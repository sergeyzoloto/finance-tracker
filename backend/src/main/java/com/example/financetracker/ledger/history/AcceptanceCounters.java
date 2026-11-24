package com.example.financetracker.ledger.history;

import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reconcile of the acceptance counters (HIST-3, D-1160): V21's backfill again, over every family ledger, for after
 * an image that took revisions without keeping the counters (a rollback to before V21) ran. Idempotent: a second run
 * writes nothing.
 */
@Service
public class AcceptanceCounters {

    /** What the reconcile wrote: the rows whose counter it changed. */
    public record ReconcileView(int records, int links, int members) {
    }

    /** The records whose last change set some ACTIVE member with an account has not accepted, by the counters. */
    private static final String PENDING = """
            SELECT count(*) FROM family_record r
            JOIN ledger_member m ON m.ledger_id = r.ledger_id AND m.ledger_type = 'SHARED' AND m.status = 'ACTIVE'
                                AND m.user_sub IS NOT NULL
            WHERE r.last_revision > m.accepted_revision
               OR EXISTS (SELECT FROM family_entry_link l
                          WHERE l.record_id = r.id AND l.family_ledger_id = r.ledger_id AND l.member_id = m.id
                            AND l.detached_at IS NULL AND l.posted_revision < r.last_revision)""";

    private final JdbcClient jdbc;
    private final FamilyAcceptanceSwitch acceptance;

    AcceptanceCounters(JdbcClient jdbc, FamilyAcceptanceSwitch acceptance) {
        this.jdbc = jdbc;
        this.acceptance = acceptance;
    }

    /**
     * Recomputes every family record's last_revision from its versions (0 without one), every link's posted_revision
     * as its record's, and every ACTIVE member with an account at the ledger's head, as V21 does. Holds every family
     * ledger's row lock, as a record change does, so that no change set runs meanwhile.
     *
     * @throws IllegalStateException while acceptance is on, whose counters say what members have accepted; or while
     *         a member has a pending record by the counters as they are, which a reconcile would mark accepted
     *         without posting it
     */
    @Transactional
    public ReconcileView reconcile() {
        if (acceptance.enabled()) {
            throw new IllegalStateException("Family acceptance is on: its counters are kept by the app and are not "
                    + "reconciled; turn it off first");
        }
        jdbc.sql("SELECT id FROM ledger WHERE type = 'SHARED' ORDER BY id FOR NO KEY UPDATE").query(Long.class).list();
        long pending = jdbc.sql(PENDING).query(Long.class).single();
        if (pending > 0) {
            throw new IllegalStateException(("Family members have %d pending record changes: they accept them "
                    + "before the counters are reconciled").formatted(pending));
        }
        int records = jdbc.sql("""
                UPDATE family_record r SET last_revision = coalesce(v.revision, 0)
                FROM family_record x
                LEFT JOIN (SELECT ledger_id, record_id, max(revision) AS revision FROM record_version
                           WHERE record_type = 'FAMILY_RECORD' AND revision > 0 GROUP BY ledger_id, record_id) v
                    ON v.ledger_id = x.ledger_id AND v.record_id = x.id
                WHERE x.id = r.id AND r.last_revision <> coalesce(v.revision, 0)""").update();
        // The link guard wants the posting's writer, as in V21.
        jdbc.sql("SELECT set_config('app.writer', 'family-posting', true)").query(String.class).single();
        int links = jdbc.sql("""
                UPDATE family_entry_link l SET posted_revision = r.last_revision
                FROM family_record r
                WHERE r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                  AND l.posted_revision <> r.last_revision""").update();
        jdbc.sql("SELECT set_config('app.writer', '', true)").query(String.class).single();
        int members = jdbc.sql("""
                UPDATE ledger_member m SET accepted_revision = l.revision
                FROM ledger l
                WHERE l.id = m.ledger_id AND m.ledger_type = 'SHARED' AND m.status = 'ACTIVE'
                  AND m.user_sub IS NOT NULL AND m.accepted_revision IS DISTINCT FROM l.revision""").update();
        return new ReconcileView(records, links, members);
    }
}
