package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * HIST-2 step 2 (ADR 0006, section 13, D-1086 to D-1088): a record's history through the API, for its owner or the
 * ACTIVE members of its family budget only, paged; the unread count of notifications; and their erasure with a user.
 */
class HistoryReadApiTests extends FamilyApiTest {

    private JsonNode history(String user, String type, long id) throws IOException {
        return ok(get(user, "/api/history/" + type + "/" + id));
    }

    /** Each version as "seq op cause actorKind". */
    private static List<String> versions(JsonNode page) {
        List<String> versions = new ArrayList<>();
        page.get("content").forEach(v -> versions.add(v.get("seq").asInt() + " " + v.get("op").asText() + " "
                + v.get("cause").asText() + " " + v.get("actor").get("kind").asText()));
        return versions;
    }

    /** The newest version's changes as "field old→new", values as JSON text. */
    private static List<String> newestChanges(JsonNode page) {
        List<String> changes = new ArrayList<>();
        page.get("content").get(0).get("changes").forEach(c -> changes.add(c.get("field").asText() + " "
                + c.get("old") + "→" + c.get("new")));
        return changes;
    }

    private long unread(String user) throws IOException {
        return ok(get(user, "/api/notifications/unread-count")).get("count").asLong();
    }

    private void notify(long member, boolean read) {
        jdbc.sql("INSERT INTO notification (ledger_id, recipient_member_id, kind, author_member_id, from_revision, "
                + "to_revision, changes_count, read_at) VALUES (?, ?, 'FAMILY_CHANGES', ?, 1, 2, 2, "
                + (read ? "now()" : "NULL") + ")").param(family).param(member).param(mum).update();
    }

    private long notifications(long member) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE recipient_member_id = ?").param(member)
                .query(Long.class).single();
    }

    @Test
    void eachPersonalRecordKindReadsItsVersionsWithTheFieldsThatChanged() throws IOException {
        long cash = accountId(alice, "CASH");
        JsonNode entry = newExpense(alice, "2026-09-10", "3.00", null, "first");
        long id = entry.get("id").asLong();
        ok(put(alice, "/api/entries/" + id + "?version=" + entry.get("version").asInt(), """
                {"kind": "EXPENSE", "entryDate": "2026-09-10", "memo": "second", "accountId": %d, "currency": "EUR",
                 "amount": "3.00", "categoryId": %d}""".formatted(cash, categoryId(alice, "GROCERIES"))));
        JsonNode entryHistory = history(alice, "ENTRY", id);
        assertThat(versions(entryHistory)).containsExactly("2 UPDATE EDIT USER", "1 CREATE EDIT USER");
        assertThat(newestChanges(entryHistory)).contains("memo \"first\"→\"second\"");
        assertThat(entryHistory.get("total").asLong()).isEqualTo(2);
        assertThat(entryHistory.get("content").get(0).get("revision").asLong())
                .isGreaterThan(entryHistory.get("content").get(1).get("revision").asLong());

        ok(patch(alice, "/api/accounts/" + cash, """
                {"name": "Wallet"}"""));
        JsonNode account = history(alice, "ACCOUNT", cash);
        assertThat(versions(account)).containsExactly("2 UPDATE EDIT USER", "1 CREATE PROVISION SYSTEM");
        assertThat(newestChanges(account)).containsExactly("name \"Cash\"→\"Wallet\"");
        // The system has no name; the account's id stays an id.
        assertThat(account.get("content").get(1).get("actor").get("name").isNull()).isTrue();

        long clothing = categoryId(alice, "CLOTHING");
        ok(patch(alice, "/api/categories/" + clothing, """
                {"archived": true}"""));
        assertThat(newestChanges(history(alice, "CATEGORY", clothing))).containsExactly("archived false→true");

        long payee = newCounterparty(alice, "Corner shop");
        ok(patch(alice, "/api/counterparties/" + payee, """
                {"name": "Corner store"}"""));
        JsonNode counterparty = history(alice, "COUNTERPARTY", payee);
        assertThat(versions(counterparty)).containsExactly("2 UPDATE EDIT USER", "1 CREATE EDIT USER");
        assertThat(newestChanges(counterparty)).containsExactly("name \"Corner shop\"→\"Corner store\"");

        // Another user's record, a record of another kind and an unknown kind answer 404.
        assertThat(get(bob, "/api/history/ENTRY/" + id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(bob, "/api/history/ACCOUNT/" + cash)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(alice, "/api/history/COUNTERPARTY/" + id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(alice, "/api/history/FAMILY_CATEGORY/" + groceries)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(alice, "/api/history/SOMETHING/" + id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aFamilyRecordsHistoryShowsTheJournalsChangesForMembersOnly() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        long id = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "100", "comment": "Weekly shop",
                 "payerMemberId": %d, "paymentAccountId": %d}""".formatted(groceries, mum, current))).get("id").asLong();
        ok(patch(alice, uri + "/records/" + id + "?version=0", """
                {"comment": "Changed", "amount": "120"}"""));

        JsonNode page = history(bob, "FAMILY_RECORD", id);
        assertThat(versions(page)).containsExactly("2 UPDATE EDIT USER", "1 CREATE EDIT USER");
        assertThat(page.get("content").get(0).get("actor").get("name").asText()).isEqualTo("Mum");
        JsonNode journal = ok(get(bob, uri + "/journal?recordId=" + id));
        assertThat(journal.get("content")).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(page.get("content").get(i).get("changes"))
                    .isEqualTo(journal.get("content").get(i).get("changes"));
        }
        assertThat(Answers.mentions(page, "CURRENT_ACCOUNT")).isFalse();

        // A non-member reads it as a record that doesn't exist.
        String stranger = newUser();
        ok(get(stranger, "/api/accounts"));
        long missing = id + 1_000_000;
        assertThat(get(stranger, "/api/history/FAMILY_RECORD/" + id)).hasStatus(HttpStatus.NOT_FOUND);
        JsonNode refused = Answers.of(get(stranger, "/api/history/FAMILY_RECORD/" + id));
        JsonNode absent = Answers.of(get(alice, "/api/history/FAMILY_RECORD/" + missing));
        assertThat(refused.get("title")).isEqualTo(absent.get("title"));
        assertThat(refused.get("detail").asText().replace(String.valueOf(id), "ID"))
                .isEqualTo(absent.get("detail").asText().replace(String.valueOf(missing), "ID"));

        // A member who left (LEFT, still with their sub) reads it as missing too.
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode left = Answers.of(get(bob, "/api/history/FAMILY_RECORD/" + id));
        assertThat(left.get("status").asInt()).isEqualTo(404);
        assertThat(left.get("title")).isEqualTo(absent.get("title"));
        assertThat(left.get("detail").asText().replace(String.valueOf(id), "ID"))
                .isEqualTo(absent.get("detail").asText().replace(String.valueOf(missing), "ID"));
    }

    @Test
    void aFormerMembersVersionsNameThemFormerMember() throws IOException {
        long cash = accountId(bob, "CASH");
        long id = created(post(bob, uri + "/records", """
                {"date": "2026-09-11", "categoryId": %d, "amount": "20", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(groceries, dad, cash))).get("id").asLong();
        assertThat(history(alice, "FAMILY_RECORD", id).get("content").get(0).get("actor").get("name").asText())
                .isEqualTo("Dad");
        // Deleting all his data makes Bob a FORMER member (leaving alone keeps his name).
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        ok(get(bob, "/api/accounts"));

        JsonNode page = history(alice, "FAMILY_RECORD", id);
        assertThat(page.get("content").get(0).get("actor").get("name").asText()).isEqualTo("Former member");
        assertThat(page.get("content").get(0).get("actor").get("memberId").asLong()).isEqualTo(dad);
        // Gone from the family budget, Bob reads it no more.
        assertThat(get(bob, "/api/history/FAMILY_RECORD/" + id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void pagesAreStableNewestFirstWithTheTotal() throws IOException {
        long payee = newCounterparty(alice, "Shop 1");
        for (int i = 2; i <= 5; i++) {
            ok(patch(alice, "/api/counterparties/" + payee, """
                    {"name": "Shop %d"}""".formatted(i)));
        }
        List<Integer> seqs = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode answer = ok(get(alice, "/api/history/COUNTERPARTY/" + payee + "?size=2&page=" + page));
            assertThat(answer.get("total").asLong()).isEqualTo(5);
            assertThat(answer.get("totalPages").asInt()).isEqualTo(3);
            answer.get("content").forEach(v -> seqs.add(v.get("seq").asInt()));
        }
        assertThat(seqs).containsExactly(5, 4, 3, 2, 1);
        assertThat(ok(get(alice, "/api/history/COUNTERPARTY/" + payee)).get("size").asInt()).isEqualTo(50);
        assertThat(get(alice, "/api/history/COUNTERPARTY/" + payee + "?size=201")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theUnreadCountIsTheCallersUnreadNotificationsOnly() throws IOException {
        assertThat(unread(alice)).isZero();
        notify(mum, false);
        notify(mum, false);
        notify(mum, true);
        notify(dad, false);
        notify(kid, false);
        assertThat(unread(alice)).isEqualTo(2);
        assertThat(unread(bob)).isEqualTo(1);
        jdbc.sql("UPDATE notification SET read_at = now() WHERE recipient_member_id = ?").param(dad).update();
        assertThat(unread(bob)).isZero();
    }

    @Test
    void aLeaversNotificationsGoWithTheirDataAndTheOthersStay() throws Exception {
        notify(mum, false);
        notify(dad, false);
        notify(dad, true);
        // As the runbook's "Delete a user" runs it, and "Delete all my data" through it.
        try (Connection db = previousImageConnection();
                var statement = db.prepareStatement("SELECT release_family_memberships(?)")) {
            statement.setString(1, bob);
            statement.executeQuery().close();
        }
        assertThat(notifications(dad)).isZero();
        assertThat(notifications(mum)).isEqualTo(1);

        assertThat(delete(alice, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(notifications(mum)).isZero();
    }
}
