package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The payment fields of family expenses (F4c; D-14; ADR 0003, topic E): who changes the date, the amount, the payer and
 * the paying account, how the amount is split again, how the posted entries follow, what the journal says, and
 * {@code yourPayment}, the payer's own view of how they paid, in the family of {@link FamilyApiTest}.
 */
class FamilyPaymentApiTests extends FamilyApiTest {

    /**
     * Alice pays 100 with her current account, 50/50 with Bob. Only she changes the date, the amount and the account:
     * the shares follow the amount, her payment entry follows all three, and the journal names the date and the amount
     * but never the account. Moving the payment to "Specify later" and back changes nothing the family sees.
     */
    @Test
    void thePayerWithAnAccountChangesTheDateAmountAndAccount() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        long cash = accountId(alice, "CASH");
        JsonNode record = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "100", "payerMemberId": %d, "paymentAccountId": %d,
                 "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                   {"memberId": %d, "basisPoints": 5000}]}}""".formatted(groceries, mum, current, mum, dad)));
        long id = record.get("id").asLong();
        String path = uri + "/records/" + id;
        long entryId = record.get("yourPayment").get("entryId").asLong();
        assertThat(record.get("yourPayment")).isEqualTo(json.readTree("""
                {"entryId": %d, "accountId": %d, "accountName": "Current account", "later": false}"""
                .formatted(entryId, current)));
        assertThat(record.get("canEditPayment").asBoolean()).isTrue();
        JsonNode asBob = ok(get(bob, path));
        assertThat(asBob.has("yourPayment")).isFalse();
        assertThat(asBob.get("canEditPayment").asBoolean()).isFalse();

        assertThat(detail(patch(bob, path + "?version=0", """
                {"amount": "120"}"""), HttpStatus.CONFLICT)).isEqualTo("Only Mum, who paid it, can change the expense's "
                + "date, amount, payer or paying account.");
        JsonNode changed = ok(patch(alice, path + "?version=0", """
                {"date": "2026-09-12", "amount": "120"}"""));
        assertThat(changed.get("version").asInt()).isOne();
        assertThat(changed.get("date").asText()).isEqualTo("2026-09-12");
        assertThat(changed.get("amount").asText()).isEqualTo("120.00");
        assertThat(shares(changed)).containsExactly("Mum 60.00 5000", "Dad 60.00 5000");
        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(postedEntries(alice)).containsExactly(
                "FAMILY_SHARE SHARE %d:60.00:%d %d:-60.00:null".formatted(accountId(alice, "UNALLOCATED"), groceries,
                        debt),
                "FAMILY_PAYMENT PAYMENT %d:-120.00:null %d:120.00:null".formatted(current, debt));
        JsonNode payment = ok(get(alice, "/api/entries/" + entryId));
        assertThat(payment.get("entryDate").asText()).isEqualTo("2026-09-12");
        assertThat(payment.get("version").asInt()).isOne();
        assertThat(postedEntries(bob)).containsExactly("FAMILY_SHARE SHARE %d:60.00:%d %d:-60.00:null".formatted(
                accountId(bob, "UNALLOCATED"), groceries, accountId(bob, "FAMILY_DEBT_" + family)));
        assertThat(balances(bob)).containsExactly("Mum -60.00", "Dad 60.00 you", "Kid 0.00");
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + id))).getFirst()).isEqualTo("UPDATE by Mum: "
                + "date 2026-09-10→2026-09-12, amount 100.00→120.00, share of Mum 50.00→60.00, share of Dad 50.00→60.00");

        // The account is private: it changes neither the record's version nor its journal (D-16).
        JsonNode withCash = ok(patch(alice, path + "?version=1", """
                {"paymentAccountId": %d}""".formatted(cash)));
        assertThat(withCash.get("version").asInt()).isOne();
        assertThat(withCash.get("yourPayment")).isEqualTo(json.readTree("""
                {"entryId": %d, "accountId": %d, "accountName": "Cash", "later": false}""".formatted(entryId, cash)));
        JsonNode later = ok(patch(alice, path + "?version=1", """
                {"paymentLater": true}"""));
        assertThat(later.get("yourPayment")).isEqualTo(json.readTree("""
                {"entryId": %d, "accountId": null, "accountName": null, "later": true}""".formatted(entryId)));
        long placeholder = accountId(alice, "UNSPECIFIED_PAYMENTS");
        assertThat(postedEntries(alice)).contains("FAMILY_PAYMENT PAYMENT %d:-120.00:null %d:120.00:null"
                .formatted(placeholder, debt));
        assertThat(systemOwned(entryId)).isTrue();
        // From "Specify later" to an account: the payment is hers again, the same entry.
        JsonNode moved = ok(patch(alice, path + "?version=1", """
                {"paymentAccountId": %d}""".formatted(current)));
        assertThat(moved.get("yourPayment").get("entryId").asLong()).isEqualTo(entryId);
        assertThat(moved.get("yourPayment").get("accountName").asText()).isEqualTo("Current account");
        assertThat(systemOwned(entryId)).isFalse();
        assertThat(ok(get(alice, "/api/entries/" + entryId)).get("version").asInt()).isEqualTo(4);
        assertThat(moved.get("version").asInt()).isOne();
        assertThat(ok(get(bob, path)).get("updatedAt")).isEqualTo(changed.get("updatedAt"));

        JsonNode journal = ok(get(bob, uri + "/journal?recordId=" + id));
        assertThat(journal.get("totalElements").asInt()).isEqualTo(2);
        assertThat(journal.toString()).doesNotContain("Cash", "Current account", "UNSPECIFIED", "account");
        // The same values again change nothing.
        List<String> versions = entryVersions(alice);
        assertThat(ok(patch(alice, path + "?version=1", """
                {"date": "2026-09-12", "amount": "120.00", "paymentAccountId": %d}""".formatted(current)))
                .get("version").asInt()).isOne();
        assertThat(entryVersions(alice)).isEqualTo(versions);
    }

    /**
     * A record Bob paid is his to change: an owner who didn't pay it changes its family fields, not its payment. Equal
     * shares follow a new amount among the members who had them.
     */
    @Test
    void onlyThePayerWithAnAccountChangesTheirPayment() throws IOException {
        long id = created(post(bob, uri + "/records", expense("2026-09-10", groceries, "20", dad,
                "\"paymentLater\": true,"))).get("id").asLong();
        String path = uri + "/records/" + id;
        String onlyDad = "Only Dad, who paid it, can change the expense's date, amount, payer or paying account.";
        assertThat(detail(patch(alice, path + "?version=0", """
                {"date": "2026-09-11"}"""), HttpStatus.CONFLICT)).isEqualTo(onlyDad);
        assertThat(detail(patch(alice, path + "?version=0", """
                {"paymentAccountId": %d}""".formatted(accountId(alice, "CASH"))), HttpStatus.CONFLICT))
                .isEqualTo(onlyDad);
        assertThat(detail(patch(alice, path + "?version=0", """
                {"payerMemberId": %d}""".formatted(kid)), HttpStatus.CONFLICT)).isEqualTo(onlyDad);
        JsonNode commented = ok(patch(alice, path + "?version=0", """
                {"comment": "Milk"}"""));
        assertThat(commented.get("canEditPayment").asBoolean()).isFalse();
        assertThat(commented.has("yourPayment")).isFalse();

        JsonNode changed = ok(patch(bob, path + "?version=1", """
                {"amount": "21", "paymentAccountId": %d}""".formatted(accountId(bob, "CASH"))));
        assertThat(shares(changed)).containsExactly("Mum 7.00 null", "Dad 7.00 null", "Kid 7.00 null");
        assertThat(changed.get("yourPayment").get("accountName").asText()).isEqualTo("Cash");
        assertThat(changed.get("comment").asText()).isEqualTo("Milk");
        assertThat(balances(alice)).containsExactly("Mum 7.00 you", "Dad -14.00", "Kid 7.00");
    }

    /**
     * For a payer without an account, the author and the owners change the payment fields, and nobody else. A member
     * added since keeps out of the record's equal shares (D-18).
     */
    @Test
    void theAuthorOrAnOwnerChangesThePaymentOfAMemberWithoutAnAccount() throws IOException {
        long id = created(post(bob, uri + "/records", expense("2026-09-10", groceries, "30", kid, ""))).get("id")
                .asLong();
        String path = uri + "/records/" + id;
        String grandpasSub = newUser();
        ok(get(grandpasSub, "/api/accounts"));
        long grandpa = join(family, grandpasSub, "Grandpa", "MEMBER", LocalDate.of(2026, 9, 1));

        assertThat(shares(ok(patch(bob, path + "?version=0", """
                {"amount": "33"}""")))).containsExactly("Mum 11.00 null", "Dad 11.00 null", "Kid 11.00 null");
        JsonNode byTheOwner = ok(patch(alice, path + "?version=1", """
                {"date": "2026-09-11"}"""));
        assertThat(byTheOwner.get("date").asText()).isEqualTo("2026-09-11");
        assertThat(shares(byTheOwner)).containsExactly("Mum 11.00 null", "Dad 11.00 null", "Kid 11.00 null");
        JsonNode asGrandpa = ok(get(grandpasSub, path));
        assertThat(asGrandpa.get("canEditPayment").asBoolean()).isFalse();
        assertThat(detail(patch(grandpasSub, path + "?version=2", """
                {"amount": "34"}"""), HttpStatus.CONFLICT))
                .isEqualTo("Only the expense's author or an owner of the family budget can change it.");
        assertThat(grandpa).isPositive();
        assertThat(changes(ok(get(alice, uri + "/journal?recordId=" + id))).subList(0, 2)).containsExactly(
                "UPDATE by Mum: date 2026-09-10→2026-09-11",
                "UPDATE by Dad: amount 30.00→33.00, share of Mum 10.00→11.00, share of Dad 10.00→11.00, "
                        + "share of Kid 10.00→11.00");
    }

    /**
     * The payer changes to a member without an account, or to the member who changes it, who then says how they paid;
     * only the caller can become a payer with an account (D-14). When the payer with an account changes, their payment
     * entry goes.
     */
    @Test
    void thePayerChangesToYourselfOrToAMemberWithoutAnAccount() throws IOException {
        long id = created(post(bob, uri + "/records", expense("2026-09-10", groceries, "30", kid, ""))).get("id")
                .asLong();
        String path = uri + "/records/" + id;
        long sam = body(post(alice, uri + "/members", """
                {"displayName": "Sam"}"""), HttpStatus.CREATED).get("id").asLong();
        long bobsCash = accountId(bob, "CASH");

        assertThat(details(patch(bob, path + "?version=0", """
                {"payerMemberId": %d}""".formatted(dad)))).containsExactly(
                "PAYMENT %d name the account you paid with, or specify it later".formatted(dad));
        assertThat(details(patch(bob, path + "?version=0", """
                {"payerMemberId": %d}""".formatted(mum)))).containsExactly(("PAYER %d Mum has an account: only they can "
                + "record what they paid; name yourself, or a member without an account").formatted(mum));
        assertThat(details(patch(bob, path + "?version=0", """
                {"payerMemberId": %d, "paymentLater": true}""".formatted(sam)))).containsExactly(
                "PAYMENT %d Sam has no account, so there is no account of theirs to pay with".formatted(sam));
        assertThat(details(patch(bob, path + "?version=0", """
                {"payerMemberId": 9000000000}"""))).containsExactly(
                "PAYER 9000000000 Member 9000000000 is not an active member of the family budget");

        assertThat(ok(patch(bob, path + "?version=0", """
                {"payerMemberId": %d}""".formatted(sam))).get("payer").get("displayName").asText()).isEqualTo("Sam");
        JsonNode bobPaid = ok(patch(bob, path + "?version=1", """
                {"payerMemberId": %d, "paymentAccountId": %d}""".formatted(dad, bobsCash)));
        assertThat(bobPaid.get("payer").get("displayName").asText()).isEqualTo("Dad");
        assertThat(bobPaid.get("yourPayment").get("accountId").asLong()).isEqualTo(bobsCash);
        long bobsDebt = accountId(bob, "FAMILY_DEBT_" + family);
        assertThat(postedEntries(bob)).contains("FAMILY_PAYMENT PAYMENT %d:-30.00:null %d:30.00:null"
                .formatted(bobsCash, bobsDebt));
        // Now Bob's to change: not the owner's.
        assertThat(detail(patch(alice, path + "?version=2", """
                {"payerMemberId": %d}""".formatted(kid)), HttpStatus.CONFLICT)).startsWith("Only Dad, who paid it,");
        JsonNode kidPaid = ok(patch(bob, path + "?version=2", """
                {"payerMemberId": %d}""".formatted(kid)));
        assertThat(kidPaid.has("yourPayment")).isFalse();
        assertThat(postedEntries(bob)).containsExactly("FAMILY_SHARE SHARE %d:10.00:%d %d:-10.00:null".formatted(
                accountId(bob, "UNALLOCATED"), groceries, bobsDebt));
        assertThat(balances(alice)).containsExactly("Mum 10.00 you", "Dad 10.00", "Kid -20.00", "Sam 0.00");
        // An owner makes herself the payer, and says how.
        JsonNode alicePaid = ok(patch(alice, path + "?version=3", """
                {"payerMemberId": %d, "paymentLater": true}""".formatted(mum)));
        assertThat(alicePaid.get("yourPayment").get("later").asBoolean()).isTrue();
        assertThat(balances(alice)).containsExactly("Mum -20.00 you", "Dad 10.00", "Kid 10.00", "Sam 0.00");

        assertThat(changes(ok(get(alice, uri + "/journal?recordId=" + id))).subList(0, 4)).containsExactly(
                "UPDATE by Mum: payer Kid→Mum", "UPDATE by Dad: payer Dad→Kid", "UPDATE by Dad: payer Sam→Dad",
                "UPDATE by Dad: payer Kid→Sam");
    }

    /**
     * A new amount is split again by the stored split: equal shares with the remainder to the payer, the same
     * percentages, the same member; a split by amounts needs the new amounts (D-12, D-14).
     */
    @Test
    void aNewAmountIsSplitAgainByTheStoredSplit() throws IOException {
        JsonNode equal = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "10.01", kid, "")));
        assertThat(shares(ok(patch(alice, uri + "/records/" + equal.get("id").asLong() + "?version=0", """
                {"amount": "10.00"}""")))).containsExactly("Mum 3.33 null", "Dad 3.33 null", "Kid 3.34 null");

        JsonNode percent = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "100", kid, "", """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 6000},
                 {"memberId": %d, "basisPoints": 4000}]}""".formatted(mum, dad))));
        assertThat(shares(ok(patch(alice, uri + "/records/" + percent.get("id").asLong() + "?version=0", """
                {"amount": "50.01"}""")))).containsExactly("Mum 30.01 6000", "Dad 20.00 4000");

        JsonNode one = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "40", kid, "", """
                {"method": "ONE_MEMBER", "memberId": %d}""".formatted(dad))));
        assertThat(shares(ok(patch(alice, uri + "/records/" + one.get("id").asLong() + "?version=0", """
                {"amount": "41"}""")))).containsExactly("Dad 41.00 null");

        String amounts = uri + "/records/" + created(post(alice, uri + "/records", expense("2026-09-10", groceries,
                "9", kid, "", """
                {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "4"}, {"memberId": %d, "amount": "5"}]}"""
                        .formatted(mum, dad)))).get("id").asLong();
        assertThat(details(patch(alice, amounts + "?version=0", """
                {"amount": "10"}"""))).containsExactly("AMOUNTS_NEEDED null the expense is split by amounts: send the "
                + "new amounts with the new amount");
        // A new date alone keeps the amounts.
        assertThat(shares(ok(patch(alice, amounts + "?version=0", """
                {"date": "2026-09-11"}""")))).containsExactly("Mum 4.00 null", "Dad 5.00 null");
        assertThat(shares(ok(patch(alice, amounts + "?version=1", """
                {"amount": "10", "split": {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "4"},
                 {"memberId": %d, "amount": "6"}]}}""".formatted(mum, dad))))).containsExactly("Mum 4.00 null",
                "Dad 6.00 null");
        assertThat(details(patch(alice, amounts + "?version=2", """
                {"amount": "0"}"""))).containsExactly("AMOUNT null the amount must be above 0");
    }

    /**
     * A new date follows each member's join date (D-7): a member with an account who joined after the old date and by
     * the new one comes into equal shares, with a share entry, and goes again when the date moves back. A share by
     * percentage or the payment of a member who joined after the new date is refused, as for a new record; a date
     * before the start date is a conflict (D-27).
     */
    @Test
    void aNewDateCrossesAMembersJoinDate() throws IOException {
        String grandpasSub = newUser();
        ok(get(grandpasSub, "/api/accounts"));
        long grandpa = join(family, grandpasSub, "Grandpa", "MEMBER", LocalDate.of(2026, 9, 15));
        long id = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "40", mum,
                "\"paymentAccountId\": %d,".formatted(accountId(alice, "CASH"))))).get("id").asLong();
        String path = uri + "/records/" + id;
        assertThat(shares(ok(get(alice, path)))).containsExactly("Mum 13.34 null", "Dad 13.33 null", "Kid 13.33 null");

        JsonNode later = ok(patch(alice, path + "?version=0", """
                {"date": "2026-09-20"}"""));
        // By join order: Grandpa joined on 2026-09-15, Kid, without an account, when Alice added them.
        assertThat(shares(later)).containsExactly("Mum 10.00 null", "Dad 10.00 null", "Grandpa 10.00 null",
                "Kid 10.00 null");
        assertThat(postedEntries(grandpasSub)).containsExactly("FAMILY_SHARE SHARE %d:10.00:%d %d:-10.00:null"
                .formatted(accountId(grandpasSub, "UNALLOCATED"), groceries,
                        accountId(grandpasSub, "FAMILY_DEBT_" + family)));
        assertThat(changes(ok(get(alice, uri + "/journal?recordId=" + id))).getFirst()).isEqualTo("UPDATE by Mum: "
                + "date 2026-09-10→2026-09-20, share of Mum 13.34→10.00, share of Dad 13.33→10.00, "
                + "share of Kid 13.33→10.00, share of Grandpa null→10.00");
        JsonNode back = ok(patch(alice, path + "?version=1", """
                {"date": "2026-09-10"}"""));
        assertThat(shares(back)).containsExactly("Mum 13.34 null", "Dad 13.33 null", "Kid 13.33 null");
        assertThat(postedEntries(grandpasSub)).isEmpty();

        String withGrandpa = uri + "/records/" + created(post(alice, uri + "/records", expense("2026-09-20", groceries,
                "10", kid, "", """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}""".formatted(mum, grandpa)))).get("id").asLong();
        assertThat(details(patch(alice, withGrandpa + "?version=0", """
                {"date": "2026-09-10"}"""))).containsExactly(
                "JOINED_AFTER %d Grandpa joined on 2026-09-15, after the expense's date 2026-09-10".formatted(grandpa));
        String grandpaPaid = uri + "/records/" + created(post(grandpasSub, uri + "/records", expense("2026-09-20",
                groceries, "10", grandpa, "\"paymentLater\": true,", """
                {"method": "ONE_MEMBER", "memberId": %d}""".formatted(mum)))).get("id").asLong();
        assertThat(details(patch(grandpasSub, grandpaPaid + "?version=0", """
                {"date": "2026-09-14"}"""))).containsExactly(
                "JOINED_AFTER %d Grandpa joined on 2026-09-15, after the expense's date 2026-09-14".formatted(grandpa));
        assertThat(detail(patch(alice, path + "?version=2", """
                {"date": "2026-08-31"}"""), HttpStatus.CONFLICT)).isEqualTo("The family budget starts on 2026-09-01, "
                + "and an expense can't be dated before its start date.");
    }

    /** A stale version and a frozen record are conflicts for the payment fields too. */
    @Test
    void aStaleOrFrozenExpenseIsAConflict() throws IOException {
        long id = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30", mum,
                "\"paymentAccountId\": %d,".formatted(accountId(alice, "CASH"))))).get("id").asLong();
        String path = uri + "/records/" + id;
        ok(patch(alice, path + "?version=0", """
                {"amount": "31"}"""));
        assertThat(detail(patch(alice, path + "?version=0", """
                {"amount": "32"}"""), HttpStatus.CONFLICT))
                .isEqualTo("Expense %d has changed since version 0. Reload it and try again.".formatted(id));

        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        String frozen = "The expense is frozen: a member it involves has left the family budget or deleted their data, "
                + "so nobody can change it.";
        assertThat(detail(patch(alice, path + "?version=1", """
                {"amount": "32"}"""), HttpStatus.CONFLICT)).isEqualTo(frozen);
        assertThat(detail(patch(alice, path + "?version=1", """
                {"paymentLater": true}"""), HttpStatus.CONFLICT)).isEqualTo(frozen);
        JsonNode record = ok(get(alice, path));
        assertThat(record.get("canEditPayment").asBoolean()).isFalse();
        assertThat(record.get("yourPayment").get("accountName").asText()).isEqualTo("Cash");
    }

    /**
     * The payer's private note (C2) stays on their payment entry: no family answer and no journal holds it, and it
     * stays through a change of the payment. Only the payer's own payment takes one.
     */
    @Test
    void thePrivateNoteStaysOnThePayersPaymentEntry() throws IOException {
        JsonNode record = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "30", "payerMemberId": %d, "paymentAccountId": %d,
                 "comment": "For the week", "privateNote": "  Paid with the old card  "}"""
                .formatted(groceries, mum, accountId(alice, "CASH"))));
        long entryId = record.get("yourPayment").get("entryId").asLong();
        assertThat(ok(get(alice, "/api/entries/" + entryId)).get("memo").asText()).isEqualTo("Paid with the old card");
        String path = uri + "/records/" + record.get("id").asLong();
        ok(patch(alice, path + "?version=0", """
                {"amount": "33", "paymentLater": true}"""));
        assertThat(ok(get(alice, "/api/entries/" + entryId)).get("memo").asText()).isEqualTo("Paid with the old card");
        for (String user : List.of(alice, bob)) {
            for (String read : List.of(path, uri + "/records", uri + "/journal", uri + "/balances")) {
                assertThat(ok(get(user, read)).toString()).as(read).doesNotContain("old card");
            }
        }
        // Bob's share entry has no memo at all.
        assertThat(ok(get(bob, "/api/entries")).get("content").get(0).get("memo").isNull()).isTrue();

        assertThat(details(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "30", "payerMemberId": %d,
                 "privateNote": "Kid's"}""".formatted(groceries, kid)))).containsExactly(
                "PAYMENT %d a private note goes only on your own payment, and you didn't pay this".formatted(kid));
    }

    /** Whether the link of the payment entry says that only the family budget changes it. */
    private boolean systemOwned(long entryId) {
        return jdbc.sql("SELECT system_owned FROM family_entry_link WHERE entry_id = ?").param(entryId)
                .query(Boolean.class).single();
    }
}
