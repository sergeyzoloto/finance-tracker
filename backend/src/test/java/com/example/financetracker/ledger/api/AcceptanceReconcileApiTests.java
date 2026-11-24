package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;

import com.example.financetracker.ledger.family.FamilyInvariants;
import com.example.financetracker.ledger.history.AcceptanceCounters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reconcile of the acceptance counters (HIST-3 part 2, D-1160), with acceptance off: after an image that took
 * revisions without counters (a rollback to before V21) it restores V21's backfill, and a second run writes nothing;
 * it refuses while a member has a pending record by the counters.
 */
class AcceptanceReconcileApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    @Autowired
    private AcceptanceCounters counters;

    @Autowired
    private PlatformTransactionManager transactions;

    /** The family's counters: records' last revisions, links' posted revisions, members' accepted ones, in id order. */
    private List<String> counters() {
        return jdbc.sql("""
                SELECT 'r' || id || '=' || last_revision FROM family_record WHERE ledger_id = :f
                UNION ALL SELECT 'l' || id || '=' || posted_revision FROM family_entry_link WHERE family_ledger_id = :f
                    AND record_id IS NOT NULL
                UNION ALL SELECT 'm' || id || '=' || coalesce(accepted_revision::text, '-') FROM ledger_member
                    WHERE ledger_id = :f
                ORDER BY 1""").param("f", family).query(String.class).list();
    }

    /** V21's backfill, computed for this family. */
    private List<String> backfill() {
        return jdbc.sql("""
                SELECT 'r' || r.id || '=' || coalesce((SELECT max(revision) FROM record_version v
                    WHERE v.ledger_id = r.ledger_id AND v.record_id = r.id AND v.record_type = 'FAMILY_RECORD'), 0)
                FROM family_record r WHERE r.ledger_id = :f
                UNION ALL SELECT 'l' || l.id || '=' || coalesce((SELECT max(revision) FROM record_version v
                    WHERE v.ledger_id = l.family_ledger_id AND v.record_id = l.record_id
                      AND v.record_type = 'FAMILY_RECORD'), 0)
                FROM family_entry_link l WHERE l.family_ledger_id = :f AND l.record_id IS NOT NULL
                UNION ALL SELECT 'm' || m.id || '=' || CASE WHEN m.status = 'ACTIVE' AND m.user_sub IS NOT NULL
                    THEN l.revision::text ELSE '-' END
                FROM ledger_member m JOIN ledger l ON l.id = m.ledger_id WHERE m.ledger_id = :f
                ORDER BY 1""").param("f", family).query(String.class).list();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    @Test
    void itRestoresTheCountersAfterARollbackAndASecondRunWritesNothing() throws IOException {
        long record = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum, LATER)))
                .get("id").asLong();
        created(post(bob, uri + "/records", expense("2026-09-11", groceries, "9.00", dad, LATER)));
        ok(patch(alice, uri + "/records/" + record + "?version=0", """
                {"amount": "36.00"}"""));
        // What the image before V21 leaves: no counter moved, while revisions were taken.
        inTransaction(() -> {
            jdbc.sql("UPDATE family_record SET last_revision = 0 WHERE ledger_id = ?").param(family).update();
            jdbc.sql("SELECT set_config('app.writer', 'family-posting', true)").query(String.class).single();
            jdbc.sql("UPDATE family_entry_link SET posted_revision = 0 WHERE family_ledger_id = ?").param(family)
                    .update();
            jdbc.sql("SELECT set_config('app.writer', '', true)").query(String.class).single();
            jdbc.sql("UPDATE ledger_member SET accepted_revision = 0 WHERE ledger_id = ? AND accepted_revision > 0")
                    .param(family).update();
        });
        assertThat(counters()).isNotEqualTo(backfill());

        counters.reconcile();
        assertThat(counters()).isEqualTo(backfill());
        FamilyInvariants.checkCounters(jdbc, family);

        List<String> once = counters();
        assertThat(counters.reconcile()).isEqualTo(new AcceptanceCounters.ReconcileView(0, 0, 0));
        assertThat(counters()).isEqualTo(once);
    }

    @Test
    void itRefusesWhileAMemberHasAPendingRecord() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum, LATER)));
        long head = jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(family).query(Long.class).single();
        // Bob lags, as after acceptance was on and off again.
        jdbc.sql("UPDATE ledger_member SET accepted_revision = ? WHERE id = ?").params(head - 1, dad).update();
        try {
            assertThatThrownBy(counters::reconcile).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("pending");
            assertThat(jdbc.sql("SELECT accepted_revision FROM ledger_member WHERE id = ?").param(dad)
                    .query(Long.class).single()).isEqualTo(head - 1);
        } finally {
            jdbc.sql("UPDATE ledger_member SET accepted_revision = ? WHERE id = ?").params(head, dad).update();
        }
    }
}
