package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import com.example.financetracker.Answers;
import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A member who returns (D-26) in F6b, in the family of {@link FamilyApiTest}: they take part from their return date only
 * (D-39), and a return waits while their former debt account holds entries of their own dated after it (D-37). Mum paid
 * 90.00 on 09-05 and Dad 60.00 on 09-10, each split equally among the three: Dad's balance is −10.00 when he leaves,
 * and his debt account shows it. The invariants hold after every test, and the integrity check finds nothing.
 */
class FamilyReturnApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    private long early;

    /** The records, and Bob leaving. */
    private void dadLeaves() throws IOException {
        early = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, LATER)))
                .get("id").asLong();
        created(post(bob, uri + "/records", expense("2026-09-10", groceries, "60.00", dad, LATER)));
        assertThat(balances(bob)).containsExactly("Mum -40.00", "Dad -10.00 you", "Kid 50.00");
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
    }

    private String newMemberInvite() throws IOException {
        return newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
    }

    /**
     * D-39: Dad, back today, takes part from today only, as a new member does. Naming him in a record dated before his
     * return is 422 {@code JOINED_AFTER}, whether by an equal split's absence, a percentage, an amount or one member, or
     * as the payer; the rule leaves him out of such a record, and brings him in when it moves to his return date.
     */
    @Test
    void aReturningMemberTakesPartFromTheReturnOnly() throws IOException {
        dadLeaves();
        accept(bob, newMemberInvite(), "Dad");
        String today = today().toString();
        JsonNode dadNow = find(ok(get(alice, uri + "/members")), "displayName", "Dad");
        assertThat(dadNow.get("joinDate").asText()).isEqualTo(today);
        assertThat(dadNow.get("claimedSeat").asBoolean()).isFalse();
        String joinedAfter = "JOINED_AFTER %d Dad joined on %s, after the expense's date 2026-09-25".formatted(dad,
                today);

        for (String split : List.of("""
                {"method": "ONE_MEMBER", "memberId": %d}""".formatted(dad), """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                  {"memberId": %d, "basisPoints": 5000}]}""".formatted(mum, dad), """
                {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "5.00"},
                  {"memberId": %d, "amount": "5.00"}]}""".formatted(mum, dad))) {
            assertThat(details(post(alice, uri + "/records", expense("2026-09-25", groceries, "10.00", mum, LATER,
                    split)))).as(split).containsExactly(joinedAfter);
        }
        assertThat(details(post(bob, uri + "/records", expense("2026-09-25", groceries, "10.00", dad, LATER))))
                .containsExactly(joinedAfter);
        // The rule leaves him out of a record before his return, and splits him into one from it.
        JsonNode before = created(post(alice, uri + "/records", expense("2026-09-25", groceries, "10.00", mum, LATER)));
        assertThat(shares(before)).containsExactly("Mum 5.00 null", "Kid 5.00 null");
        assertThat(shares(created(post(alice, uri + "/records", expense(today, groceries, "9.00", mum, LATER)))))
                .containsExactly("Mum 3.00 null", "Dad 3.00 null", "Kid 3.00 null");
        // Moved to his return date, it brings him in, as it would a new member (F4c's rule for equal shares).
        assertThat(shares(ok(patch(alice, uri + "/records/" + before.get("id").asLong() + "?version=0",
                "{\"date\": \"%s\"}".formatted(today))))).containsExactly("Mum 3.34 null", "Dad 3.33 null",
                        "Kid 3.33 null");
        everyonesIntegrity();
    }

    /**
     * D-39: an earlier record that includes Dad keeps him, and its effect before his return goes into his correction,
     * as built in F6a. Back with nothing to correct (his debt account shows −10.00, his balance), then:
     * <ul>
     * <li>Mum's 90.00 of 09-05 becomes 120.00: his share is 40.00, his balance 0.00, and the correction +10.00
     * (debt account −10.00, OPENING_BALANCE +10.00), so the account shows 0.00 (D-10);
     * <li>it moves to 09-07, still before his return: he stays in it, and nothing else changes;
     * <li>it moves to his return date: its share of 40.00 is an entry of his, and the correction −30.00.
     * </ul>
     */
    @Test
    void anEarlierRecordThatIncludesHimRepostsIntoTheCorrection() throws IOException {
        dadLeaves();
        JsonNode lookup = ok(inviteCall(bob, "lookup", token(newMemberInvite(), null)));
        assertThat(lookup.get("corrections")).isEmpty();
        assertThat(lookup.get("entriesAfterReturn")).isEmpty();
        accept(bob, newMemberInvite(), "Dad");
        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        long opening = accountId(bob, "OPENING_BALANCE");
        long unallocated = accountId(bob, "UNALLOCATED");
        assertThat(postedEntries(bob)).isEmpty();
        everyonesIntegrity();

        JsonNode more = ok(patch(alice, uri + "/records/" + early + "?version=0", """
                {"amount": "120.00"}"""));
        assertThat(shares(more)).containsExactly("Mum 40.00 null", "Dad 40.00 null", "Kid 40.00 null");
        assertThat(postedEntries(bob)).containsExactly(
                "FAMILY_CORRECTION CORRECTION %d:-10.00:null %d:10.00:null".formatted(debt, opening));
        assertThat(balances(bob)).containsExactly("Mum -60.00", "Dad 0.00 you", "Kid 60.00");
        everyonesIntegrity();

        assertThat(shares(ok(patch(alice, uri + "/records/" + early + "?version=1", """
                {"date": "2026-09-07"}""")))).containsExactly("Mum 40.00 null", "Dad 40.00 null", "Kid 40.00 null");
        assertThat(postedEntries(bob)).containsExactly(
                "FAMILY_CORRECTION CORRECTION %d:-10.00:null %d:10.00:null".formatted(debt, opening));
        everyonesIntegrity();

        String today = today().toString();
        ok(patch(alice, uri + "/records/" + early + "?version=2", "{\"date\": \"%s\"}".formatted(today)));
        assertThat(postedEntries(bob)).containsExactlyInAnyOrder(
                "FAMILY_CORRECTION CORRECTION %d:30.00:null %d:-30.00:null".formatted(debt, opening),
                "FAMILY_SHARE SHARE %d:40.00:%d %d:-40.00:null".formatted(unallocated, groceries, debt));
        assertThat(balances(bob)).containsExactly("Mum -60.00", "Dad 0.00 you", "Kid 60.00");
        everyonesIntegrity();
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + early))).subList(0, 2)).containsExactly(
                "UPDATE by Mum: date 2026-09-07→" + today, "UPDATE by Mum: date 2026-09-05→2026-09-07");
    }

    /**
     * D-37: while away, Dad put entries of his own on his former debt account: one dated today, his return date, and
     * one dated after it. The lookup lists the one after it, his own data (date, what it adds to his debt, memo), and
     * accepting answers 409 {@code ENTRIES_AFTER_RETURN}, changing nothing; the one dated today is in the correction,
     * as F6a counts it. Once he moves the later one to another account, he comes back, and his debt account shows his
     * family balance (D-10).
     */
    @Test
    void aReturnWaitsForTheMembersOwnEntriesAfterIt() throws IOException {
        dadLeaves();
        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        long cash = accountId(bob, "CASH");
        long reserve = accountId(bob, "RESERVE");
        String today = today().toString();
        String later = today().plusDays(5).toString();
        created(post(bob, "/api/entries", manual(today, "Today's", debt, "4.00", cash)));
        JsonNode future = created(post(bob, "/api/entries", manual(later, "Lent to the family", debt, "25.00", cash)));
        String token = newMemberInvite();

        JsonNode lookup = ok(inviteCall(bob, "lookup", token(token, null)));
        assertThat(lookup.get("returning").asBoolean()).isTrue();
        // His balance −10.00, less what the account shows by today: −10.00 + 4.00.
        assertThat(lookup.get("corrections")).isEqualTo(Answers.json("[{\"currency\":\"EUR\",\"amount\":\"-4.00\"}]"));
        assertThat(lookup.get("entriesAfterReturn")).hasSize(1);
        JsonNode entry = lookup.get("entriesAfterReturn").get(0);
        assertThat(entry.get("entryId").asLong()).isEqualTo(future.get("id").asLong());
        assertThat(entry.get("date").asText()).isEqualTo(later);
        assertThat(entry.get("amount").asText()).isEqualTo("25.00");
        assertThat(entry.get("currency").asText()).isEqualTo("EUR");
        assertThat(entry.get("memo").asText()).isEqualTo("Lent to the family");
        JsonNode members = ok(get(alice, uri + "/members"));

        MvcTestResult refused = inviteCall(bob, "accept", token(token, "\"displayName\": \"Dad\", \"categoryIds\": []"));
        JsonNode problem = body(refused, HttpStatus.CONFLICT);
        assertThat(problem.get("code").asText()).isEqualTo("ENTRIES_AFTER_RETURN");
        assertThat(problem.get("detail").asText()).isEqualTo("Your former debt to this family budget has 1 entry dated "
                + "after today that belong to none of its records. Move it to another account or delete it, then "
                + "accept again.");
        assertThat(ok(get(alice, uri + "/members"))).isEqualTo(members);
        assertThat(get(bob, uri)).hasStatus(HttpStatus.NOT_FOUND);
        // Alice's invites: still pending.
        assertThat(find(ok(get(alice, uri + "/invites")), "kind", "NEW_MEMBER").get("status").asText())
                .isEqualTo("PENDING");

        ok(put(bob, "/api/entries/" + future.get("id").asLong() + "?version=" + future.get("version").asInt(),
                manual(later, "Lent to the family", reserve, "25.00", cash)));
        assertThat(ok(inviteCall(bob, "lookup", token(token, null))).get("entriesAfterReturn")).isEmpty();
        accept(bob, token, "Dad");
        assertThat(postedEntries(bob)).containsExactly("FAMILY_CORRECTION CORRECTION %d:4.00:null %d:-4.00:null"
                .formatted(debt, accountId(bob, "OPENING_BALANCE")));
        assertThat(balances(bob)).containsExactly("Mum -40.00", "Dad -10.00 you", "Kid 50.00");
        everyonesIntegrity();
    }

    /** A manual entry of the amount between the two accounts: the first one −amount, so it adds to a debt there. */
    private static String manual(String date, String memo, long from, String amount, long to) {
        return """
                {"kind": "MANUAL", "entryDate": "%s", "memo": "%s", "postings": [
                  {"accountId": %d, "currency": "EUR", "amount": "-%s"},
                  {"accountId": %d, "currency": "EUR", "amount": "%s"}]}""".formatted(date, memo, from, amount, to,
                amount);
    }

    /** Nobody's integrity check finds anything, and the invariants hold. */
    private void everyonesIntegrity() throws IOException {
        for (String user : List.of(alice, bob)) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
        FamilyInvariants.check(jdbc, family);
    }
}
