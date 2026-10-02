package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * A claimed seat's records (D-35 as clarified by the PM on 2026-10-02; ADR 0003 topic J, "F6b as built"), in the family
 * of {@link FamilyApiTest}: Carol takes Kid's place from 09-20. A claim changes nothing about who takes part, so she
 * takes part in records from the budget's start date, as Kid did; the claim's date only divides what posts into her
 * personal ledger: before it her opening balance, from it entries. {@link FamilyClaimApiTests} has the records moved
 * across the claim's date and a new record of the rule before it; this has new records naming her, and her own.
 * The invariants hold after every test, and the integrity check finds nothing.
 */
class FamilyClaimedSeatApiTests extends FamilyApiTest {

    private final String carol = newUser();

    /**
     * New records dated before the claim's date that name the seat, each moving Carol's opening balance (F6a refused
     * them all, {@code JOINED_AFTER}):
     * <ul>
     * <li>Mum pays 12.00 of rent on 09-12, entirely on Carol: opening balance +12.00;
     * <li>30.00 of groceries on 09-14, 50 % each for Mum and Carol: +15.00, so +27.00;
     * <li>20.00 on 09-16, by amounts, Dad 5.00 and Carol 15.00: +42.00;
     * <li>Carol's own 9.00 on 09-18, equal shares among the three: no account and no "Specify later" for it (D-32), no
     * payment entry, her share 3.00 less the 9.00 she paid: +36.00;
     * <li>Carol's settlement of 6.00 to Mum on 09-19, without an account: +30.00; Mum's side on her placeholder.
     * </ul>
     * Carol then moves her 9.00 to 09-21: its share entry (3.00) and her payment on her placeholder (9.00) appear, and
     * the opening balance is +36.00; moved back to 09-17, both go and it is +30.00 again. Her balance stays 30.00, and
     * the journal shows the records' changes and never an account.
     */
    @Test
    void newRecordsBeforeTheClaimNameTheSeat() throws IOException {
        long alicesCurrent = accountId(alice, "CURRENT_ACCOUNT");
        String paid = "\"paymentAccountId\": %d,".formatted(alicesCurrent);
        JsonNode joined = accept(carol, kidsPlace("2026-09-20"), "Carol");
        assertThat(joined.get("memberId").asLong()).isEqualTo(kid);
        JsonNode members = ok(get(alice, uri + "/members"));
        assertThat(find(members, "displayName", "Carol").get("claimedSeat").asBoolean()).isTrue();
        assertThat(find(members, "displayName", "Dad").get("claimedSeat").asBoolean()).isFalse();
        long debt = accountId(carol, "FAMILY_DEBT_" + family);
        long opening = accountId(carol, "OPENING_BALANCE");
        long unallocated = accountId(carol, "UNALLOCATED");
        assertThat(postedEntries(carol)).isEmpty();

        long rentOnCarol = created(post(alice, uri + "/records", expense("2026-09-12", rent, "12.00", mum, paid, """
                {"method": "ONE_MEMBER", "memberId": %d}""".formatted(kid)))).get("id").asLong();
        assertThat(postedEntries(carol)).containsExactly(openingEntry(debt, opening, "12.00"));
        JsonNode halves = created(post(alice, uri + "/records", expense("2026-09-14", groceries, "30.00", mum, paid, """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                  {"memberId": %d, "basisPoints": 5000}]}""".formatted(mum, kid))));
        assertThat(shares(halves)).containsExactly("Mum 15.00 5000", "Carol 15.00 5000");
        assertThat(postedEntries(carol)).containsExactly(openingEntry(debt, opening, "27.00"));
        created(post(alice, uri + "/records", expense("2026-09-16", groceries, "20.00", mum, paid, """
                {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "5.00"},
                  {"memberId": %d, "amount": "15.00"}]}""".formatted(dad, kid))));
        assertThat(postedEntries(carol)).containsExactly(openingEntry(debt, opening, "42.00"));
        everyonesIntegrity();

        // Her own, before her claim's date: in her opening balance, so with no account of hers (D-32).
        long cash = accountId(carol, "CASH");
        for (String payment : List.of("\"paymentAccountId\": %d,".formatted(cash), "\"paymentLater\": true,")) {
            assertThat(details(post(carol, uri + "/records", expense("2026-09-18", groceries, "9.00", kid, payment))))
                    .containsExactly(("PAYMENT %d this expense is dated before Carol took their place in the family "
                            + "budget on 2026-09-20, so it is part of their opening balance and has no account of "
                            + "theirs").formatted(kid));
        }
        JsonNode own = created(post(carol, uri + "/records", expense("2026-09-18", groceries, "9.00", kid, "")));
        long ownId = own.get("id").asLong();
        assertThat(shares(own)).containsExactly("Mum 3.00 null", "Dad 3.00 null", "Carol 3.00 null");
        assertThat(own.has("yourPayment")).isFalse();
        assertThat(postedEntries(carol)).containsExactly(openingEntry(debt, opening, "36.00"));
        assertThat(details(post(carol, uri + "/settlements", """
                {"date": "2026-09-19", "amount": "6.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(kid, mum, cash))))
                .containsExactly(("PAYMENT %d this settlement is dated before Carol took their place in the family "
                        + "budget on 2026-09-20, so it is part of their opening balance and has no account of theirs")
                        .formatted(kid));
        created(post(carol, uri + "/settlements", """
                {"date": "2026-09-19", "amount": "6.00", "payerMemberId": %d, "payeeMemberId": %d}"""
                .formatted(kid, mum)));
        assertThat(postedEntries(carol)).containsExactly(openingEntry(debt, opening, "30.00"));
        assertThat(postedEntries(alice)).contains("FAMILY_SETTLEMENT SETTLEMENT %d:6.00:null %d:-6.00:null"
                .formatted(accountId(alice, "UNSPECIFIED_PAYMENTS"), accountId(alice, "FAMILY_DEBT_" + family)));
        assertThat(balances(carol)).containsExactly("Mum -38.00", "Dad 8.00", "Carol 30.00 you");
        everyonesIntegrity();

        // Across the claim's date and back: the effect moves between the opening balance and her entries.
        ok(patch(carol, uri + "/records/" + ownId + "?version=0", """
                {"date": "2026-09-21"}"""));
        long placeholder = accountId(carol, "UNSPECIFIED_PAYMENTS");
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(openingEntry(debt, opening, "36.00"),
                "FAMILY_SHARE SHARE %d:3.00:%d %d:-3.00:null".formatted(unallocated, groceries, debt),
                "FAMILY_PAYMENT PAYMENT %d:-9.00:null %d:9.00:null".formatted(placeholder, debt));
        assertThat(ok(get(carol, uri + "/records/" + ownId)).get("yourPayment").get("later").asBoolean()).isTrue();
        everyonesIntegrity();
        ok(patch(carol, uri + "/records/" + ownId + "?version=1", """
                {"date": "2026-09-17"}"""));
        assertThat(postedEntries(carol)).containsExactly(openingEntry(debt, opening, "30.00"));
        assertThat(balances(carol)).containsExactly("Mum -38.00", "Dad 8.00", "Carol 30.00 you");
        everyonesIntegrity();

        assertThat(changes(ok(get(carol, uri + "/journal?recordId=" + ownId)))).containsExactly(
                "UPDATE by Carol: date 2026-09-21→2026-09-17", "UPDATE by Carol: date 2026-09-18→2026-09-21",
                "CREATE by Carol: date null→2026-09-18, category null→Groceries, amount null→9.00, payer null→Carol, "
                        + "splitMethod null→EQUAL, share of Mum null→3.00, share of Dad null→3.00, "
                        + "share of Carol null→3.00");
        assertThat(changes(ok(get(alice, uri + "/journal?recordId=" + rentOnCarol)))).containsExactly(
                "CREATE by Mum: date null→2026-09-12, category null→Rent, amount null→12.00, payer null→Mum, "
                        + "splitMethod null→ONE_MEMBER, share of Carol null→12.00");
        JsonNode journal = ok(get(alice, uri + "/journal?size=100"));
        assertThat(journal.toString()).doesNotContain("account", "Account", "UNSPECIFIED");
    }

    /**
     * D-35 and D-39 together: Carol, who took Kid's place, leaves and is invited back as a new member. From her return
     * she takes part as any returning member does, from that day only: a record before it can't name her (422
     * {@code JOINED_AFTER}), and the rule leaves her out of it.
     */
    @Test
    void aClaimedMemberWhoReturnsTakesPartFromTheReturn() throws IOException {
        accept(carol, kidsPlace("2026-09-20"), "Carol");
        assertThat(delete(carol, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        accept(carol, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Carol");
        JsonNode carolNow = find(ok(get(alice, uri + "/members")), "displayName", "Carol");
        assertThat(carolNow.get("claimedSeat").asBoolean()).isFalse();
        assertThat(carolNow.get("joinDate").asText()).isEqualTo(today().toString());

        String later = "\"paymentLater\": true,";
        assertThat(details(post(alice, uri + "/records", expense("2026-09-25", groceries, "10.00", mum, later, """
                {"method": "ONE_MEMBER", "memberId": %d}""".formatted(kid))))).containsExactly(
                        "JOINED_AFTER %d Carol joined on %s, after the expense's date 2026-09-25".formatted(kid,
                                today()));
        assertThat(shares(created(post(alice, uri + "/records", expense("2026-09-25", groceries, "10.00", mum,
                later))))).containsExactly("Mum 5.00 null", "Dad 5.00 null");
        FamilyInvariants.check(jdbc, family);
    }

    private static String openingEntry(long debt, long opening, String amount) {
        return "FAMILY_OPENING OPENING_BALANCE %d:-%s:null %d:%s:null".formatted(debt, amount, opening, amount);
    }

    /** Nobody's integrity check finds anything, and the invariants hold. */
    private void everyonesIntegrity() throws IOException {
        for (String user : List.of(alice, bob, carol)) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
        FamilyInvariants.check(jdbc, family);
    }
}
