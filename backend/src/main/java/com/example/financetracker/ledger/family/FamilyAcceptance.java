package com.example.financetracker.ledger.family;

import java.util.List;
import java.util.Set;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.posting.FamilyPostingService;
import com.example.financetracker.ledger.history.Notifications;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Family acceptance (HIST-3, ADR 0006 sections 7.1 and 7.2): which records have changes a member has not accepted, and
 * the author rule that refuses a record change by a member who has some, while acceptance is on.
 */
@Service
public class FamilyAcceptance {

    /**
     * What an accept did.
     *
     * @param accepted how many records' changes it brought into the member's books; 0 for a member who was caught up
     * @param revision the family budget's revision the member has accepted up to now
     */
    public record AcceptView(int accepted, long revision) {
    }

    /** The pending records of the member (D-1159); {@code %s} narrows them further. */
    private static final String PENDING = """
            SELECT r.id FROM family_record r
            JOIN ledger_member m ON m.id = :memberId AND m.ledger_id = r.ledger_id
            WHERE r.ledger_id = :ledgerId AND m.accepted_revision IS NOT NULL%s
              AND (r.last_revision > m.accepted_revision
                   OR EXISTS (SELECT FROM family_entry_link l
                              WHERE l.record_id = r.id AND l.member_id = m.id AND l.family_ledger_id = r.ledger_id
                                AND l.detached_at IS NULL AND l.posted_revision < r.last_revision))
            ORDER BY r.id""";

    private final JdbcClient jdbc;
    private final FamilyAcceptanceSwitch acceptance;
    private final FamilyPostingService posting;
    private final Notifications notifications;

    FamilyAcceptance(JdbcClient jdbc, FamilyAcceptanceSwitch acceptance, FamilyPostingService posting,
            Notifications notifications) {
        this.jdbc = jdbc;
        this.acceptance = acceptance;
        this.posting = posting;
        this.notifications = notifications;
    }

    /**
     * The catch-up accept (ADR 0006, 7.3; D-1012, D-1168) of the member the scope stands for: under the family
     * ledger's row lock, taken first as the record paths take it, it posts every record pending for them into their
     * books alone, by the record paths' own posting (a deleted record's entries go, a settlement's side follows the
     * record), re-runs their opening balance, and moves their counter to the ledger's head. It takes no revision of
     * the family ledger. Their unread change notifications of the ledger up to that head are read (D-1167).
     */
    @Transactional
    public AcceptView accept(LedgerScope family) {
        long head = jdbc.sql("SELECT revision FROM ledger WHERE id = :ledgerId FOR NO KEY UPDATE")
                .param("ledgerId", family.ledgerId()).query(Long.class).single();
        List<Long> pending = pendingRecords(family, family.memberId());
        if (!pending.isEmpty()) {
            posting.accept(family, pending);
        }
        jdbc.sql("""
                UPDATE ledger_member SET accepted_revision = :head
                WHERE id = :memberId AND ledger_id = :ledgerId AND accepted_revision IS DISTINCT FROM :head
                  AND accepted_revision IS NOT NULL""")
                .param("head", head).param("memberId", family.memberId()).param("ledgerId", family.ledgerId())
                .update();
        notifications.accepted(family, head);
        return new AcceptView(pending.size(), head);
    }

    /** The member's pending records (D-1159), for the pending summary; empty with acceptance off. */
    @Transactional(readOnly = true)
    public List<Long> pendingForMe(LedgerScope family) {
        return acceptance.enabled() ? pendingRecords(family, family.memberId()) : List.of();
    }

    /**
     * Which of these records are pending for the member the scope stands for (D-1159), by one statement over them all;
     * with acceptance off none, and no statement runs.
     */
    @Transactional(readOnly = true)
    public Set<Long> pendingAmong(LedgerScope family, List<Long> recordIds) {
        if (!acceptance.enabled() || recordIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(PENDING.formatted(" AND r.id IN (:recordIds)"))
                .param("memberId", family.memberId()).param("ledgerId", family.ledgerId())
                .param("recordIds", recordIds)
                .query(Long.class).list());
    }

    /**
     * The member's pending records, by id: those whose last change set the member has not accepted (D-1159), that is
     * whose last_revision is above the member's accepted_revision and which no attached link of the member holds at
     * that revision. Detached links count for nothing; a member without a counter (no account, not ACTIVE) has none.
     * A deleted record is one too: its soft delete is its last change.
     */
    @Transactional(readOnly = true)
    public List<Long> pendingRecords(LedgerScope family, long memberId) {
        return jdbc.sql(PENDING.formatted(""))
                .param("memberId", memberId).param("ledgerId", family.ledgerId())
                .query(Long.class).list();
    }

    /**
     * The author rule (D-1162) for an import commit (D-1164), taking the family ledger's row lock first, as the record
     * paths do, before any row or category is written. With acceptance off it changes nothing, not even the lock.
     * {@code family} is the uploader's own member scope.
     *
     * @throws PendingChangesException with the count of their pending records
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCaughtUpLocked(LedgerScope family) {
        if (!acceptance.enabled()) {
            return;
        }
        jdbc.sql("SELECT id FROM ledger WHERE id = :ledgerId FOR NO KEY UPDATE")
                .param("ledgerId", family.ledgerId()).query(Long.class).single();
        requireCaughtUp(family);
    }

    /**
     * The author rule (D-1162): with acceptance on, the member the scope stands for changes a record only when nothing
     * is pending for them. The caller must already hold the family ledger's row lock ({@code lockLedger}).
     *
     * @throws PendingChangesException with the count of their pending records
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCaughtUp(LedgerScope family) {
        if (!acceptance.enabled()) {
            return;
        }
        int pending = pendingRecords(family, family.memberId()).size();
        if (pending > 0) {
            throw new PendingChangesException(pending);
        }
    }
}
