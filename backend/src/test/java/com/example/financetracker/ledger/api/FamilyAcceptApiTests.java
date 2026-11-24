package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import com.example.financetracker.ledger.family.FamilyInvariants;
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
 * The catch-up accept (HIST-3 part 3; ADR 0006, 7.3; D-1168 to D-1171) with acceptance on: {@code POST /accept} posts a
 * member's pending records into their books, {@code GET /pending} and the records' {@code pendingForMe} say what is
 * pending, and the integrity check waits for a lagging member.
 */
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
// A context of its own: closed after the class, so that its connection pools don't add to the cached contexts'.
@DirtiesContext
class FamilyAcceptApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    private final String carol = newUser();

    @Autowired
    private PlatformTransactionManager transactions;

    /** Everybody accepts through the endpoint; then the invariants of acceptance and the classic ones hold. */
    @AfterEach
    @Override
    void theInvariantsHold() {
        try {
            AcceptingMode.everybodyAccepts(this, family);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long revision() {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(family).query(Long.class).single();
    }

    private Long accepted(long member) {
        return jdbc.sql("SELECT accepted_revision FROM ledger_member WHERE id = ?").param(member)
                .query(Long.class).optional().orElse(null);
    }

    private int version(long record) {
        return jdbc.sql("SELECT version FROM family_record WHERE id = ?").param(record).query(Integer.class).single();
    }

    private long record(String user, String date, String amount, long payer) throws IOException {
        return created(post(user, uri + "/records", expense(date, groceries, amount, payer, LATER))).get("id")
                .asLong();
    }

    private int acceptAs(String user) throws IOException {
        return ok(post(user, uri + "/accept", "{}")).get("accepted").asInt();
    }

    private List<Long> pendingOf(String user) throws IOException {
        JsonNode pending = ok(get(user, uri + "/pending"));
        List<Long> ids = new ArrayList<>();
        pending.get("recordIds").forEach(id -> ids.add(id.asLong()));
        assertThat(pending.get("count").asInt()).isEqualTo(ids.size());
        return ids;
    }

    private long linksOf(long record, long member) {
        return jdbc.sql("SELECT count(*) FROM family_entry_link WHERE record_id = ? AND member_id = ?")
                .params(record, member).query(Long.class).single();
    }

    @Test
    void anAcceptBringsEveryKindOfChangeIntoTheMembersBooks() throws IOException {
        long edited = record(alice, "2026-09-10", "30.00", mum);
        long deleted = record(alice, "2026-09-11", "20.00", mum);
        assertThat(acceptAs(bob)).isEqualTo(2);
        List<String> bobsBooks = postedEntries(bob);
        assertThat(linksOf(deleted, dad)).isOne();

        ok(patch(alice, uri + "/records/" + edited + "?version=" + version(edited), """
                {"amount": "44.00"}"""));
        assertThat(delete(alice, uri + "/records/" + deleted + "?version=" + version(deleted)))
                .hasStatus(HttpStatus.NO_CONTENT);
        long created = record(alice, "2026-09-12", "8.00", mum);
        long settlement = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-13", "amount": "12.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(mum, dad))).get("id").asLong();
        long head = revision();
        // Nothing of it is in Bob's books yet; all of it is pending for him, one change set behind several.
        assertThat(postedEntries(bob)).isEqualTo(bobsBooks);
        assertThat(pendingOf(bob)).containsExactlyInAnyOrder(edited, deleted, created, settlement);
        assertThat(pendingOf(alice)).isEmpty();

        assertThat(acceptAs(bob)).isEqualTo(4);
        assertThat(revision()).as("an accept takes no revision").isEqualTo(head);
        assertThat(accepted(dad)).isEqualTo(head);
        assertThat(pendingOf(bob)).isEmpty();
        assertThat(postedEntries(bob)).isNotEqualTo(bobsBooks);
        // The deleted record's entry is gone from his books; the settlement's side is there.
        assertThat(linksOf(deleted, dad)).isZero();
        assertThat(linksOf(settlement, dad)).isOne();
        assertThat(linksOf(created, dad)).isOne();
        FamilyInvariants.checkAccepting(jdbc, family);
        FamilyInvariants.check(jdbc, family);
    }

    @Test
    void aCaughtUpMemberAcceptsNothing() throws IOException {
        record(alice, "2026-09-10", "30.00", mum);
        assertThat(acceptAs(bob)).isOne();
        List<String> books = postedEntries(bob);
        assertThat(acceptAs(bob)).isZero();
        assertThat(acceptAs(alice)).isZero();
        assertThat(postedEntries(bob)).isEqualTo(books);
    }

    @Test
    void anAcceptRunsTheOpeningBalanceAgain() throws IOException {
        accept(carol, kidsPlace("2026-09-15"), "Carol");
        List<String> carolsBooks = postedEntries(carol);
        // Before Carol's join date, with a share of hers: it changes her opening balance only.
        long before = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum, LATER, """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000}, {"memberId": %d, "basisPoints": 5000}]}"""
                .formatted(mum, kid)))).get("id").asLong();
        assertThat(pendingOf(carol)).containsExactly(before);
        assertThat(postedEntries(carol)).isEqualTo(carolsBooks);

        assertThat(acceptAs(carol)).isOne();
        assertThat(postedEntries(carol)).isNotEqualTo(carolsBooks);
        assertThat(linksOf(before, kid)).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM family_entry_link
                WHERE family_ledger_id = ? AND member_id = ? AND link_type = 'OPENING_BALANCE' AND detached_at IS NULL""")
                .params(family, kid).query(Long.class).single()).isOne();
        // Carol is caught up: her debt account shows her family balance, opening included.
        FamilyInvariants.checkAccepting(jdbc, family);
    }

    @Test
    void pendingForMeAndThePendingSummaryFollowTheAccept() throws IOException {
        long record = record(alice, "2026-09-10", "30.00", mum);
        assertThat(ok(get(bob, uri + "/records/" + record)).get("pendingForMe").asBoolean()).isTrue();
        assertThat(ok(get(alice, uri + "/records/" + record)).get("pendingForMe").asBoolean()).isFalse();
        JsonNode page = ok(get(bob, uri + "/records")).get("content");
        assertThat(page).hasSize(1);
        assertThat(page.get(0).get("pendingForMe").asBoolean()).isTrue();
        assertThat(pendingOf(bob)).containsExactly(record);

        acceptAs(bob);
        assertThat(ok(get(bob, uri + "/records/" + record)).get("pendingForMe").asBoolean()).isFalse();
        assertThat(ok(get(bob, uri + "/records")).get("content").get(0).get("pendingForMe").asBoolean()).isFalse();
        assertThat(pendingOf(bob)).isEmpty();
    }

    @Test
    void aMemberChangesRecordsAgainOnceTheyAccepted() throws IOException {
        long own = record(bob, "2026-09-10", "10.00", dad);
        acceptAs(alice);
        record(alice, "2026-09-11", "30.00", mum);
        JsonNode problem = body(patch(bob, uri + "/records/" + own + "?version=" + version(own), """
                {"comment": "ours"}"""), HttpStatus.CONFLICT);
        assertThat(problem.get("code").asText()).isEqualTo("FAMILY_PENDING_CHANGES");
        assertThat(problem.get("pendingChanges").asInt()).isOne();
        acceptAs(bob);
        ok(patch(bob, uri + "/records/" + own + "?version=" + version(own), """
                {"comment": "ours"}"""));
    }

    @Test
    void anAcceptReadsOnlyTheChangeNotificationsItCovers() throws IOException {
        record(alice, "2026-09-10", "30.00", mum);
        long head = revision();
        long other = newFamily(bob, """
                {"name": "Other", "baseCurrency": "EUR", "displayName": "Dad", "startDate": "2026-09-01",
                 "categoryIds": []}""").get("memberId").asLong();
        long otherLedger = jdbc.sql("SELECT ledger_id FROM ledger_member WHERE id = ?").param(other)
                .query(Long.class).single();
        long elsewhere = notification(otherLedger, other, "FAMILY_CHANGES", 0L);
        long joined = notification(family, dad, "MEMBER_JOINED", null);
        long later = notification(family, dad, "FAMILY_CHANGES", head + 1);
        long imported = notification(family, dad, "FAMILY_IMPORTED", head);
        List<Long> covered = new ArrayList<>(jdbc.sql("""
                SELECT id FROM notification WHERE recipient_member_id = ? AND kind = 'FAMILY_CHANGES'
                  AND to_revision <= ? AND read_at IS NULL""").params(dad, head).query(Long.class).list());
        assertThat(covered).as("the record's own notification").isNotEmpty();
        covered.add(imported);

        acceptAs(bob);
        for (long id : covered) {
            assertThat(read(id)).as("notification %d", id).isTrue();
        }
        for (long id : List.of(elsewhere, joined, later)) {
            assertThat(read(id)).as("notification %d", id).isFalse();
        }
    }

    private long notification(long ledger, long recipient, String kind, Long revision) {
        return jdbc.sql("""
                INSERT INTO notification (ledger_id, recipient_member_id, kind, author_member_id, from_revision,
                                          to_revision)
                VALUES (?, ?, ?, NULL, ?, ?) RETURNING id""")
                .params(ledger, recipient, kind, revision, revision).query(Long.class).single();
    }

    private boolean read(long notification) {
        return jdbc.sql("SELECT read_at IS NOT NULL FROM notification WHERE id = ?").param(notification)
                .query(Boolean.class).single();
    }

    @Test
    void whoIsNoActiveMemberCannotAccept() throws IOException {
        record(alice, "2026-09-10", "30.00", mum);
        // Carol is no member; Bob leaves.
        ok(get(carol, "/api/accounts"));
        assertThat(post(carol, uri + "/accept", "{}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(carol, uri + "/pending")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(post(bob, uri + "/accept", "{}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(bob, uri + "/pending")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(accepted(dad)).isNull();
    }

    @Test
    void aRemovedOrFormerMemberCannotAccept() throws IOException {
        record(alice, "2026-09-10", "30.00", mum);
        assertThat(delete(alice, uri + "/members/" + dad)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(post(bob, uri + "/accept", "{}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT status FROM ledger_member WHERE id = ?").param(dad).query(String.class).single())
                .isEqualTo("FORMER");
        assertThat(post(bob, uri + "/accept", "{}")).hasStatus(HttpStatus.NOT_FOUND);
        // Kid's place has no account: nobody signs in as it, and it has no counter.
        assertThat(accepted(kid)).isNull();
    }

    @Test
    void theIntegrityCheckWaitsForALaggingMember() throws IOException {
        record(alice, "2026-09-10", "30.00", mum);
        record(alice, "2026-09-11", "10.00", mum);
        JsonNode lagging = ok(get(bob, "/api/reports/integrity"));
        assertThat(lagging).hasSize(1);
        assertThat(lagging.get(0).get("familyLedgerId").asLong()).isEqualTo(family);
        assertThat(lagging.get(0).get("pendingChanges").asInt()).isEqualTo(2);
        // His debt account isn't compared meanwhile, though it differs.
        assertThat(lagging.get(0).has("debtBalance")).isFalse();
        assertThat(ok(get(alice, "/api/reports/integrity"))).isEmpty();
        acceptAs(bob);
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();
    }

    /** Two accepts of one member at once: the second waits for the first's ledger lock and finds nothing pending. */
    @Test
    void twoAcceptsAtOnceAcceptEachChangeOnce() throws Exception {
        record(alice, "2026-09-10", "30.00", mum);
        record(alice, "2026-09-11", "10.00", mum);
        List<MvcTestResult> answers = whileTheLedgerIsLocked(() -> post(bob, uri + "/accept", "{}"),
                () -> post(bob, uri + "/accept", "{}"));
        assertThat(ok(answers.get(0)).get("accepted").asInt() + ok(answers.get(1)).get("accepted").asInt())
                .isEqualTo(2);
        assertThat(pendingOf(bob)).isEmpty();
    }

    /** An accept queued before an author's edit: the edit stays pending for him. */
    @Test
    void anAcceptBeforeAnEditLeavesTheEditPending() throws Exception {
        long record = record(alice, "2026-09-10", "30.00", mum);
        int version = version(record);
        List<MvcTestResult> answers = whileTheLedgerIsLocked(() -> post(bob, uri + "/accept", "{}"),
                () -> patch(alice, uri + "/records/" + record + "?version=" + version, """
                        {"amount": "44.00"}"""));
        assertThat(ok(answers.get(0)).get("accepted").asInt()).isOne();
        ok(answers.get(1));
        assertThat(pendingOf(bob)).containsExactly(record);
        FamilyInvariants.checkAccepting(jdbc, family);
    }

    /** An author's edit queued before an accept: the accept takes it in. */
    @Test
    void anEditBeforeAnAcceptIsAccepted() throws Exception {
        long record = record(alice, "2026-09-10", "30.00", mum);
        int version = version(record);
        List<MvcTestResult> answers = whileTheLedgerIsLocked(
                () -> patch(alice, uri + "/records/" + record + "?version=" + version, """
                        {"amount": "44.00"}"""),
                () -> post(bob, uri + "/accept", "{}"));
        ok(answers.get(0));
        assertThat(ok(answers.get(1)).get("accepted").asInt()).isOne();
        assertThat(pendingOf(bob)).isEmpty();
        assertThat(accepted(dad)).isEqualTo(revision());
        FamilyInvariants.check(jdbc, family);
    }

    /**
     * Holds the family ledger's row lock in a transaction of the test's own, starts the first request and waits until
     * it waits for the lock, then the second likewise, and lets go: Postgres grants the row lock in the order they
     * asked for it.
     */
    private List<MvcTestResult> whileTheLedgerIsLocked(Request first, Request second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT id FROM ledger WHERE id = ? FOR NO KEY UPDATE").param(family).query(Long.class).single();
            locked.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcTestResult> a = CompletableFuture.supplyAsync(first::send);
        waitForLockWaiters(1);
        CompletableFuture<MvcTestResult> b = CompletableFuture.supplyAsync(second::send);
        waitForLockWaiters(2);
        assertThat(a).isNotDone();
        assertThat(b).isNotDone();
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);
        return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }

    private void waitForLockWaiters(long count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND query LIKE '%FROM ledger WHERE id = $1 FOR NO KEY UPDATE%'""")
                .query(Long.class).single() < count) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @FunctionalInterface
    private interface Request {
        MvcTestResult send();
    }
}
