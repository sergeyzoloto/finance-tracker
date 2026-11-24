package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.family.FamilyAcceptance;
import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import com.example.financetracker.ledger.family.FamilyInvariants;
import com.example.financetracker.ledger.history.AcceptanceCounters;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Family acceptance on (HIST-3 part 2; ADR 0006 sections 7.1 and 7.2; D-1159, D-1161, D-1162): a record's change set
 * posts into its actor's books only and leaves it pending for the others, and a member with pending changes may not
 * change a record (409 FAMILY_PENDING_CHANGES).
 */
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
// A context of its own: closed after the class, so that its connection pools don't add to the cached contexts'.
@DirtiesContext
class FamilyAcceptanceApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    private final String carol = newUser();

    @Autowired
    private FamilyAcceptance acceptance;

    @Autowired
    private AcceptanceCounters counters;

    @Autowired
    private LedgerAccess access;

    @Autowired
    private PlatformTransactionManager transactions;

    /**
     * The invariants with acceptance on; then everybody accepts, by SQL in place of part 3's accept, so that no other
     * class's reconcile finds this family's members lagging.
     */
    @AfterEach
    @Override
    void theInvariantsHold() {
        try {
            FamilyInvariants.checkAccepting(jdbc, family);
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.sql("SELECT set_config('app.writer', 'family-posting', true)").query(String.class).single();
                jdbc.sql("""
                        UPDATE family_entry_link l SET posted_revision = r.last_revision FROM family_record r
                        WHERE r.id = l.record_id AND l.family_ledger_id = ? AND l.posted_revision <> r.last_revision""")
                        .param(family).update();
                jdbc.sql("SELECT set_config('app.writer', '', true)").query(String.class).single();
                jdbc.sql("""
                        UPDATE ledger_member m SET accepted_revision = l.revision FROM ledger l
                        WHERE l.id = m.ledger_id AND m.ledger_id = ? AND m.accepted_revision IS NOT NULL""")
                        .param(family).update();
            });
        }
    }

    private long revision() {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(family).query(Long.class).single();
    }

    private Long accepted(long member) {
        return jdbc.sql("SELECT accepted_revision FROM ledger_member WHERE id = ?").param(member)
                .query(Long.class).optional().orElse(null);
    }

    private long memberOf(String user) {
        return jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub = ?").params(family, user)
                .query(Long.class).single();
    }

    private List<Long> pending(String user) {
        return acceptance.pendingRecords(access.member(user, family), memberOf(user));
    }

    private int version(long record) {
        return jdbc.sql("SELECT version FROM family_record WHERE id = ?").param(record).query(Integer.class).single();
    }

    private long record(String user, String date, String amount, long payer) throws IOException {
        return created(post(user, uri + "/records", expense(date, groceries, amount, payer, LATER))).get("id")
                .asLong();
    }

    /** The 409 of the author rule: its count of pending changes. */
    private int refused(MvcTestResult result) throws IOException {
        JsonNode problem = body(result, HttpStatus.CONFLICT);
        assertThat(problem.get("code").asText()).isEqualTo("FAMILY_PENDING_CHANGES");
        return problem.get("pendingChanges").asInt();
    }

    /** Alice's expense with a share of Kid's place, which Carol takes from the start: both have it in their books. */
    private long sharedWithCarol() throws IOException {
        long record = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum, LATER, """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000}, {"memberId": %d, "basisPoints": 5000}]}"""
                .formatted(mum, kid)))).get("id").asLong();
        accept(carol, kidsPlace("2026-09-01"), "Carol");
        assertThat(pending(carol)).isEmpty();
        assertThat(postedEntries(carol)).isNotEmpty();
        return record;
    }

    @Test
    void anEditPostsToItsActorOnlyAndTheOtherMayNotChangeARecordUntilAccepting() throws IOException {
        long record = sharedWithCarol();
        List<String> carolsBooks = postedEntries(carol);
        List<String> alicesBooks = postedEntries(alice);
        long carolAt = accepted(kid);

        ok(patch(alice, uri + "/records/" + record + "?version=" + version(record), """
                {"amount": "40.00"}"""));
        long r = revision();
        assertThat(accepted(mum)).isEqualTo(r);
        assertThat(postedEntries(alice)).isNotEqualTo(alicesBooks);
        assertThat(postedEntries(carol)).isEqualTo(carolsBooks);
        assertThat(accepted(kid)).isEqualTo(carolAt);
        assertThat(pending(carol)).containsExactly(record);

        assertThat(refused(patch(carol, uri + "/records/" + record + "?version=" + version(record), """
                {"comment": "ours"}"""))).isEqualTo(1);
        assertThat(refused(post(carol, uri + "/records", expense("2026-09-12", groceries, "5.00", kid, LATER))))
                .isEqualTo(1);
        assertThat(revision()).isEqualTo(r);

        // Alice is caught up: she changes it again.
        ok(patch(alice, uri + "/records/" + record + "?version=" + version(record), """
                {"amount": "44.00"}"""));
        assertThat(accepted(mum)).isEqualTo(revision());
        assertThat(postedEntries(carol)).isEqualTo(carolsBooks);
        assertThat(pending(carol)).containsExactly(record);
    }

    @Test
    void aCreatedRecordIsPendingForTheOthers() throws IOException {
        Long bobAt = accepted(dad);
        List<String> bobsBooks = postedEntries(bob);
        long record = record(alice, "2026-09-10", "30.00", mum);
        assertThat(accepted(dad)).isEqualTo(bobAt);
        assertThat(postedEntries(bob)).isEqualTo(bobsBooks);
        assertThat(pending(bob)).containsExactly(record);
        assertThat(pending(alice)).isEmpty();
        assertThat(refused(delete(bob, uri + "/records/" + record + "?version=0"))).isEqualTo(1);
    }

    @Test
    void aRenameMovesOnlyTheMembersWhoAreCaughtUp() throws IOException {
        record(alice, "2026-09-10", "30.00", mum);
        ok(get(carol, "/api/accounts"));
        accept(carol, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Carol");
        long carolMember = memberOf(carol);
        long head = revision();
        long bobAt = accepted(dad);
        assertThat(bobAt).isLessThan(head);

        ok(patch(alice, uri + "/categories/" + groceries, """
                {"name": "Food"}"""));
        assertThat(revision()).isEqualTo(head + 1);
        assertThat(accepted(mum)).isEqualTo(head + 1);
        assertThat(accepted(carolMember)).isEqualTo(head + 1);
        assertThat(accepted(dad)).isEqualTo(bobAt);
        assertThat(pending(carol)).isEmpty();
        assertThat(pending(bob)).hasSize(1);
    }

    @Test
    void aSettlementLeavesTheOtherSideAlone() throws IOException {
        List<String> bobsBooks = postedEntries(bob);
        long settlement = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "12.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(mum, dad))).get("id").asLong();
        assertThat(postedEntries(bob)).isEqualTo(bobsBooks);
        assertThat(jdbc.sql("SELECT member_id FROM family_entry_link WHERE record_id = ?").param(settlement)
                .query(Long.class).list()).containsExactly(mum);
        assertThat(pending(bob)).containsExactly(settlement);
        assertThat(accepted(mum)).isEqualTo(revision());
    }

    @Test
    void aMemberWhoLeavesWithPendingChangesKeepsNoCounter() throws IOException {
        record(alice, "2026-09-10", "30.00", mum);
        assertThat(pending(bob)).hasSize(1);
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(accepted(dad)).isNull();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM family_entry_link WHERE family_ledger_id = ? AND member_id = ?
                  AND detached_at IS NULL""").params(family, dad).query(Long.class).single()).isZero();
    }

    @Test
    void aMemberWhoJoinsIsAtTheHead() throws IOException {
        record(alice, "2026-09-10", "30.00", mum);
        ok(get(carol, "/api/accounts"));
        accept(carol, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Carol");
        assertThat(accepted(memberOf(carol))).isEqualTo(revision());
        assertThat(pending(carol)).isEmpty();
        created(post(carol, uri + "/records", expense(today().toString(), groceries, "6.00", memberOf(carol),
                LATER)));
    }

    @Test
    void detachedLinksCountForNothing() throws IOException {
        long record = sharedWithCarol();
        ok(patch(alice, uri + "/records/" + record + "?version=" + version(record), """
                {"amount": "40.00"}"""));
        assertThat(pending(carol)).containsExactly(record);
        assertThat(delete(carol, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        accept(carol, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Carol");
        // Her link of before she left stays detached, posted before the record's last change: not pending.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM family_entry_link l JOIN family_record r ON r.id = l.record_id
                WHERE l.record_id = ? AND l.member_id = ? AND l.detached_at IS NOT NULL
                  AND l.posted_revision < r.last_revision""").params(record, kid).query(Long.class).single())
                .isPositive();
        assertThat(pending(carol)).isEmpty();
        created(post(carol, uri + "/records", expense(today().toString(), groceries, "6.00", kid, LATER)));
    }

    @Test
    void theReconcileRefusesWhileAcceptanceIsOn() {
        assertThatThrownBy(counters::reconcile).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acceptance is on");
    }
}
