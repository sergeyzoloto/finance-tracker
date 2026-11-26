package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.example.financetracker.ScreenTexts;
import com.example.financetracker.ledger.history.Notifications;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HIST-2 step 4 (ADR 0006, section 13, D-1077, D-1105 to D-1108): the family notifications written with the change
 * they describe, to the other ACTIVE members with an account only, merged while unread; their list and marks.
 */
class NotificationApiTests extends FamilyApiTest {

    @Autowired // the spy of IntegrationTest (@MockitoSpyBean on the base class)
    private Notifications notifications;

    @Autowired
    private PlatformTransactionManager transactions;

    /** The member's notifications as "kind author from-to count read", oldest first. */
    private List<String> rows(long member) {
        return jdbc.sql("""
                SELECT kind || ' ' || coalesce(author_member_id::text, '-') || ' ' || coalesce(from_revision::text, '-')
                       || '-' || coalesce(to_revision::text, '-') || ' ' || coalesce(changes_count::text, '-') || ' '
                       || (read_at IS NOT NULL)
                FROM notification WHERE recipient_member_id = ? ORDER BY id""").param(member).query(String.class).list();
    }

    private long revision() {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(family).query(Long.class).single();
    }

    private JsonNode list(String user) throws IOException {
        return ok(get(user, "/api/notifications"));
    }

    private long createRecord(String user, long payer, String account) throws IOException {
        return created(post(user, uri + "/records", expense("2026-09-10", groceries, "40", payer,
                "\"paymentAccountId\": %d,".formatted(accountId(user, account))))).get("id").asLong();
    }

    @Test
    void recordChangesByOneAuthorMergeWhileUnreadAndNeverReachTheAuthorOrAPlaceWithoutAnAccount() throws IOException {
        long id = createRecord(alice, mum, "CURRENT_ACCOUNT");
        long first = revision();
        assertThat(rows(dad)).containsExactly("FAMILY_CHANGES %d %d-%d 1 false".formatted(mum, first, first));
        ok(patch(alice, uri + "/records/" + id + "?version=0", """
                {"comment": "Changed"}"""));
        long second = revision();
        assertThat(second).isGreaterThan(first);
        assertThat(rows(dad)).containsExactly("FAMILY_CHANGES %d %d-%d 2 false".formatted(mum, first, second));
        created(post(alice, uri + "/settlements", """
                {"date": "2026-09-20", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(mum, dad)));
        long settled = revision();
        assertThat(rows(dad)).containsExactly("FAMILY_CHANGES %d %d-%d 3 false".formatted(mum, first, settled));
        assertThat(rows(mum)).isEmpty();
        assertThat(rows(kid)).isEmpty();

        JsonNode page = list(bob);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("text").asText()).isEqualTo("Mum made 3 changes in the family budget");
        assertThat(row.get("author").get("name").asText()).isEqualTo("Mum");
        assertThat(row.get("read").asBoolean()).isFalse();
        ScreenTexts.assertNoDecisionNumber(page);

        // Read, it is never widened again: the deletion is a row of its own.
        assertThat(call(bob, HttpMethod.POST, "/api/notifications/" + row.get("id").asLong() + "/read", null))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(alice, uri + "/records/" + id + "?version=1")).hasStatus(HttpStatus.NO_CONTENT);
        long deleted = revision();
        assertThat(rows(dad)).containsExactly("FAMILY_CHANGES %d %d-%d 3 true".formatted(mum, first, settled),
                "FAMILY_CHANGES %d %d-%d 1 false".formatted(mum, deleted, deleted));

        // Another author's change is its own row, and Bob isn't told of his own.
        createRecord(bob, dad, "CASH");
        assertThat(rows(mum)).containsExactly("FAMILY_CHANGES %d %d-%d 1 false".formatted(dad, revision(),
                revision()));
        assertThat(rows(dad)).hasSize(2);
    }

    /** The user's payment entry of a family record. */
    private JsonNode paymentEntry(String user) throws IOException {
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            if (!entry.get("family").isNull() && entry.get("family").get("link").asText().equals("PAYMENT")) {
                return entry;
            }
        }
        throw new AssertionError("no payment entry");
    }

    @Test
    void editingOrDeletingThePersonalPaymentEntryTellsTheOthersOnce() throws IOException {
        createRecord(alice, mum, "CURRENT_ACCOUNT");
        jdbc.sql("UPDATE notification SET read_at = now() WHERE recipient_member_id = ?").param(dad).update();
        JsonNode entry = paymentEntry(alice);
        ok(patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(entry.get("id").asLong(),
                entry.get("version").asInt()), """
                {"amount": "35"}"""));
        long edited = revision();
        assertThat(rows(dad)).hasSize(2).last()
                .isEqualTo("FAMILY_CHANGES %d %d-%d 1 false".formatted(mum, edited, edited));
        jdbc.sql("UPDATE notification SET read_at = now() WHERE recipient_member_id = ?").param(dad).update();

        entry = paymentEntry(alice);
        assertThat(delete(alice, "/api/entries/%d?version=%d".formatted(entry.get("id").asLong(),
                entry.get("version").asInt()))).hasStatus(HttpStatus.NO_CONTENT);
        long deleted = revision();
        assertThat(rows(dad)).hasSize(3).last()
                .isEqualTo("FAMILY_CHANGES %d %d-%d 1 false".formatted(mum, deleted, deleted));
        assertThat(rows(mum)).isEmpty();
        assertThat(rows(kid)).isEmpty();
    }

    @Test
    void aChangeThatFailsWritesNoneAndANotificationThatFailsUndoesTheChange() throws IOException {
        assertThat(post(alice, uri + "/records", expense("2026-09-10", groceries, "-1", mum, ""))).hasStatus(
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rows(dad)).isEmpty();

        long records = jdbc.sql("SELECT count(*) FROM family_record WHERE ledger_id = ?").param(family)
                .query(Long.class).single();
        long before = revision();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("The connection went away");
        }).when(notifications).familyChanged(anyLong(), anyLong());
        assertThat(post(alice, uri + "/records", expense("2026-09-10", groceries, "40", mum,
                "\"paymentAccountId\": %d,".formatted(accountId(alice, "CURRENT_ACCOUNT")))))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(jdbc.sql("SELECT count(*) FROM family_record WHERE ledger_id = ?").param(family)
                .query(Long.class).single()).isEqualTo(records);
        assertThat(revision()).isEqualTo(before);
        assertThat(rows(dad)).isEmpty();
    }

    @Test
    void joiningAndLeavingTellTheOthersOnly() throws IOException {
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        long gran = accept(carol, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Gran").get("memberId").asLong();
        assertThat(rows(mum)).containsExactly("MEMBER_JOINED %d --- - false".formatted(gran));
        assertThat(rows(dad)).containsExactly("MEMBER_JOINED %d --- - false".formatted(gran));
        assertThat(rows(gran)).isEmpty();
        assertThat(list(bob).get("content").get(0).get("text").asText()).isEqualTo("Gran joined the family budget");

        // Removed by Alice: Bob is told, neither Alice nor Gran.
        assertThat(delete(alice, uri + "/members/" + gran)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rows(dad)).containsExactly("MEMBER_JOINED %d --- - false".formatted(gran),
                "MEMBER_LEFT %d --- - false".formatted(gran));
        assertThat(rows(mum)).hasSize(1);
        // Gran, who left, sees none of the family budget's.
        assertThat(list(carol).get("total").asLong()).isZero();

        // Bob leaves: Alice is told; Bob's own rows are out of his view now, and his marks find nothing.
        long bobsRow = jdbc.sql("SELECT max(id) FROM notification WHERE recipient_member_id = ?").param(dad)
                .query(Long.class).single();
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rows(mum)).containsExactly("MEMBER_JOINED %d --- - false".formatted(gran),
                "MEMBER_LEFT %d --- - false".formatted(dad));
        assertThat(list(bob).get("total").asLong()).isZero();
        assertThat(call(bob, HttpMethod.POST, "/api/notifications/" + bobsRow + "/read", null))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(list(alice).get("content").get(0).get("text").asText()).isEqualTo("Dad left the family budget");
    }

    @Test
    void readAllMarksTheCallersOnlyAndAnotherUsersRowIs404() throws IOException {
        createRecord(alice, mum, "CURRENT_ACCOUNT");
        createRecord(bob, dad, "CASH");
        long alicesRow = list(alice).get("content").get(0).get("id").asLong();
        assertThat(call(bob, HttpMethod.POST, "/api/notifications/" + alicesRow + "/read", null))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(call(bob, HttpMethod.POST, "/api/notifications/" + (alicesRow + 1_000_000) + "/read", null))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(call(bob, HttpMethod.POST, "/api/notifications/read-all", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ok(get(bob, "/api/notifications/unread-count")).get("count").asLong()).isZero();
        assertThat(list(bob).get("content").get(0).get("read").asBoolean()).isTrue();
        assertThat(ok(get(alice, "/api/notifications/unread-count")).get("count").asLong()).isEqualTo(1);
        assertThat(get(alice, "/api/notifications?size=201")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(list(alice).get("size").asInt()).isEqualTo(50);

        // Erasure: "Delete all my data" takes the caller's rows along, and Alice's change stays told to no one.
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rows(dad)).isEmpty();
        assertThat(list(alice).get("content").get(0).get("author").get("name").asText()).isEqualTo("Former member");
    }

    /**
     * Two transactions merge into one unread row at once: the second waits for the first's row lock, and its UPDATE
     * re-reads the count after it, so neither change is lost.
     */
    @Test
    void twoMergesAtOnceKeepBothCounts() throws Exception {
        createRecord(alice, mum, "CURRENT_ACCOUNT");
        long first = revision();
        CountDownLatch merged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            notifications.familyChanged(family, mum, first + 1000);
            merged.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(merged.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> tx.executeWithoutResult(
                status -> notifications.familyChanged(family, mum, first + 1001)));
        // B is waiting for A's lock on the row.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE "
                + "'%changes_count = n.changes_count + 1%'").query(Long.class).single() == 0) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            assertThat(b).isNotDone();
            Thread.onSpinWait();
        }
        release.countDown();
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
        assertThat(rows(dad)).containsExactly("FAMILY_CHANGES %d %d-%d 3 false".formatted(mum, first, first + 1001));
        assertThat(rows(mum)).isEmpty();
    }
}
