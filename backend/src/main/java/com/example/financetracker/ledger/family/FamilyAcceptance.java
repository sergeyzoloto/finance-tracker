package com.example.financetracker.ledger.family;

import java.util.List;

import com.example.financetracker.ledger.access.LedgerScope;
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

    private final JdbcClient jdbc;
    private final FamilyAcceptanceSwitch acceptance;

    FamilyAcceptance(JdbcClient jdbc, FamilyAcceptanceSwitch acceptance) {
        this.jdbc = jdbc;
        this.acceptance = acceptance;
    }

    /**
     * The member's pending records, by id: those whose last change set the member has not accepted (D-1159), that is
     * whose last_revision is above the member's accepted_revision and which no attached link of the member holds at
     * that revision. Detached links count for nothing; a member without a counter (no account, not ACTIVE) has none.
     * A deleted record is one too: its soft delete is its last change.
     */
    @Transactional(readOnly = true)
    public List<Long> pendingRecords(LedgerScope family, long memberId) {
        return jdbc.sql("""
                SELECT r.id FROM family_record r
                JOIN ledger_member m ON m.id = :memberId AND m.ledger_id = r.ledger_id
                WHERE r.ledger_id = :ledgerId AND m.accepted_revision IS NOT NULL
                  AND (r.last_revision > m.accepted_revision
                       OR EXISTS (SELECT FROM family_entry_link l
                                  WHERE l.record_id = r.id AND l.member_id = m.id AND l.family_ledger_id = r.ledger_id
                                    AND l.detached_at IS NULL AND l.posted_revision < r.last_revision))
                ORDER BY r.id""")
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
