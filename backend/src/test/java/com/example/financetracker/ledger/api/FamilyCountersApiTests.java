package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import com.example.financetracker.ledger.family.FamilyInvariants;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The acceptance counters (HIST-3 part 1; D-1157, D-1158) after each family change set, with acceptance off: a record
 * change takes revision R, the record's last_revision and its attached links' posted_revision are R, and every ACTIVE
 * member with an account has accepted R; a membership change takes no revision, and joining, claiming and returning
 * set the member's counter to the ledger's head, leaving clears it. Members without an account have none.
 */
class FamilyCountersApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    private final String carol = newUser();

    private long revision() {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(family).query(Long.class).single();
    }

    private Long accepted(long member) {
        return jdbc.sql("SELECT accepted_revision FROM ledger_member WHERE id = ?").param(member)
                .query(Long.class).optional().orElse(null);
    }

    private long lastRevision(long record) {
        return jdbc.sql("SELECT last_revision FROM family_record WHERE id = ?").param(record).query(Long.class)
                .single();
    }

    private List<Long> postedRevisions(long record) {
        return jdbc.sql("SELECT posted_revision FROM family_entry_link WHERE record_id = ? AND detached_at IS NULL")
                .param(record).query(Long.class).list();
    }

    /** The record's counters and the members' at R, the revision its change set just took. */
    private void atRevision(long record, long before) {
        long r = revision();
        assertThat(r).isGreaterThan(before);
        assertThat(lastRevision(record)).isEqualTo(r);
        assertThat(postedRevisions(record)).isNotEmpty().containsOnly(r);
        assertThat(accepted(mum)).isEqualTo(r);
        assertThat(accepted(dad)).isEqualTo(r);
        assertThat(accepted(kid)).isNull();
    }

    @Test
    void createEditDeleteAndSettleMoveEveryCounterToTheirRevision() throws IOException {
        long before = revision();
        long record = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum, LATER)))
                .get("id").asLong();
        atRevision(record, before);
        long other = created(post(alice, uri + "/records", expense("2026-09-11", groceries, "9.00", mum, LATER)))
                .get("id").asLong();
        long otherAt = lastRevision(other);

        before = revision();
        ok(patch(alice, uri + "/records/" + record + "?version=0", """
                {"amount": "36.00"}"""));
        atRevision(record, before);
        // Another record's counters stay.
        assertThat(lastRevision(other)).isEqualTo(otherAt);
        assertThat(postedRevisions(other)).containsOnly(otherAt);

        before = revision();
        long settlement = created(post(bob, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "12.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(dad, mum))).get("id").asLong();
        atRevision(settlement, before);

        before = revision();
        assertThat(delete(alice, uri + "/records/" + record + "?version=1")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(revision()).isGreaterThan(before);
        assertThat(lastRevision(record)).isEqualTo(revision());
        assertThat(postedRevisions(record)).isEmpty();
        assertThat(accepted(mum)).isEqualTo(revision());
        assertThat(accepted(dad)).isEqualTo(revision());
    }

    /**
     * D-1181: a member's links are detached when he leaves and never stamped again (CrossLedgerWriter stamps attached
     * links only), so the stage check HIST-3.sql and the pre-check of deploy.sh acceptance count attached links only.
     * A record the leaver is in is frozen (no member can edit it), so the remaining members' next change sets are on
     * other records; the stage check's query, as HIST-3.sql has it, still answers 0 with his detached links present.
     */
    @Test
    void aDetachedLinkIsNotCountedByTheStageCheck() throws IOException {
        long record = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum, LATER)))
                .get("id").asLong();
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT count(*) FROM family_entry_link WHERE record_id = ? AND detached_at IS NOT NULL")
                .param(record).query(Long.class).single()).isPositive();
        long recordAt = lastRevision(record);

        // The remaining member's change sets: a new record and an edit of it.
        long other = created(post(alice, uri + "/records", expense("2026-09-11", groceries, "9.00", mum, LATER, """
                {"method": "ONE_MEMBER", "memberId": %d}""".formatted(mum)))).get("id").asLong();
        ok(patch(alice, uri + "/records/" + other + "?version=0", """
                {"amount": "12.00"}"""));
        assertThat(revision()).isGreaterThan(recordAt);
        // The last statement of deploy/checks/HIST-3.sql, narrowed to this family budget (the database is shared).
        assertThat(jdbc.sql("""
                SELECT 'attached family links not posted at their record''s last revision ' || count(*)
                FROM app.family_entry_link k
                JOIN app.family_record r ON r.id = k.record_id AND r.ledger_id = k.family_ledger_id
                WHERE k.detached_at IS NULL AND k.posted_revision <> r.last_revision AND r.ledger_id = ?""")
                .param(family).query(String.class).single())
                .isEqualTo("attached family links not posted at their record's last revision 0");
    }

    @Test
    void membershipTakesNoRevisionAndSetsTheMembersOwnCounter() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", kid, "")));
        long head = revision();

        // Claim: Carol takes Kid's place at the head; nobody's counter moves.
        accept(carol, kidsPlace("2026-09-15"), "Carol");
        assertThat(revision()).isEqualTo(head);
        assertThat(accepted(kid)).isEqualTo(head);
        assertThat(accepted(mum)).isEqualTo(head);

        // Leave: no revision, the counter goes.
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(revision()).isEqualTo(head);
        assertThat(accepted(dad)).isNull();

        // A record change while he is away: everybody ACTIVE with an account follows, he doesn't.
        created(post(alice, uri + "/records", expense("2026-09-20", groceries, "8.00", mum, LATER, """
                {"method": "ONE_MEMBER", "memberId": %d}""".formatted(kid))));
        long moved = revision();
        assertThat(moved).isGreaterThan(head);
        assertThat(accepted(dad)).isNull();
        assertThat(accepted(kid)).isEqualTo(moved);

        // Return: at the head.
        accept(bob, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Dad");
        assertThat(revision()).isEqualTo(moved);
        assertThat(accepted(dad)).isEqualTo(moved);

        // Join: a new member without a seat, at the head.
        String dora = newUser();
        ok(get(dora, "/api/accounts"));
        accept(dora, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Dora");
        long doraMember = jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub = ?")
                .params(family, dora).query(Long.class).single();
        assertThat(revision()).isEqualTo(moved);
        assertThat(accepted(doraMember)).isEqualTo(moved);

        // A seat without an account has none.
        long gran = body(post(alice, uri + "/members", """
                {"displayName": "Gran"}"""), HttpStatus.CREATED).get("id").asLong();
        assertThat(accepted(gran)).isNull();
    }

    @Test
    void theDemoFamilyIsCaughtUp() throws IOException {
        String user = newUser();
        long demoFamily = ok(post(user, "/api/demo-data", null)).get("familyLedgerId").asLong();
        long head = jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(demoFamily).query(Long.class).single();
        assertThat(head).isPositive();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM family_record WHERE ledger_id = ? AND last_revision > 0""").param(demoFamily)
                .query(Long.class).single()).isPositive();
        assertThat(jdbc.sql("""
                SELECT DISTINCT m.accepted_revision FROM ledger_member m
                WHERE m.ledger_id = ? AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL""").param(demoFamily)
                .query(Long.class).list()).containsExactly(head);
        FamilyInvariants.checkCounters(jdbc, demoFamily);
    }
}
