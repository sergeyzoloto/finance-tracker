package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * A payment from an account that requires a counterparty (F8d; D-80) and the payer's payee (D-81). The account's line
 * names the counterparty, who is required then and refused for any other account; the payee is the payer's own
 * counterparty on their payment entry. Both belong to the payer's personal ledger: no family answer, no journal row and
 * no other member's answer holds them, and another user's counterparty reads as a missing one.
 */
class FamilyCounterpartyPaymentApiTests extends FamilyApiTest {

    private String even() {
        return """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000}, {"memberId": %d, "basisPoints": 5000}]}"""
                .formatted(mum, dad);
    }

    private JsonNode paymentEntry(String user) throws IOException {
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            if (!entry.get("family").isNull() && entry.get("family").get("link").asText().equals("PAYMENT")) {
                return entry;
            }
        }
        throw new AssertionError("no payment entry");
    }

    /** D-80: LOANS_ASSET and CREDITOR_DEBT require a counterparty; the payment names the one on the account's line. */
    @Test
    void anExpenseIsPaidFromAnAccountThatRequiresACounterpartyWithOne() throws IOException {
        long creditors = accountId(alice, "CREDITOR_DEBT");
        long bank = newCounterparty(alice, "The bank");
        JsonNode record = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "60", "payerMemberId": %d,
                 "paymentAccountId": %d, "paymentCounterpartyId": %d, "split": %s}"""
                .formatted(groceries, mum, creditors, bank, even())));

        assertThat(record.get("yourPayment").get("accountId").asLong()).isEqualTo(creditors);
        assertThat(record.get("yourPayment").get("counterpartyId").asLong()).isEqualTo(bank);
        JsonNode payment = paymentEntry(alice);
        JsonNode accountLine = find(payment.get("postings"), "accountId", String.valueOf(creditors));
        assertThat(accountLine.get("amount").asText()).isEqualTo("-60.00");
        assertThat(accountLine.get("counterpartyId").asLong()).isEqualTo(bank);
        // The debt account's line has none, and nobody else's answer names the counterparty or the account.
        assertThat(StreamSupport.stream(payment.get("postings").spliterator(), false)
                .filter(p -> !p.get("counterpartyId").isNull()).count()).isEqualTo(1);
        assertThat(balances(bob)).containsExactly("Mum -30.00", "Dad 30.00 you", "Kid 0.00");
        JsonNode bobsView = ok(get(bob, uri + "/records/" + record.get("id").asLong()));
        assertThat(bobsView.has("yourPayment")).isFalse();
        assertThat(bobsView.toString()).doesNotContain("counterparty").doesNotContain("The bank");
        // The liability's balance per counterparty is the bank's, in Mum's personal reports.
        JsonNode owed = find(ok(get(alice, "/api/reports/counterparty-balances?accountCode=CREDITOR_DEBT&asOf=2026-09-30")), "counterpartyId",
                String.valueOf(bank));
        assertThat(owed.get("balance").asText()).isEqualTo("60.00");

        // Changing the amount keeps the account and its counterparty; so does a new date.
        ok(patch(alice, uri + "/records/%d?version=0".formatted(record.get("id").asLong()), """
                {"amount": "80", "date": "2026-09-11"}"""));
        JsonNode changed = find(paymentEntry(alice).get("postings"), "accountId", String.valueOf(creditors));
        assertThat(changed.get("amount").asText()).isEqualTo("-80.00");
        assertThat(changed.get("counterpartyId").asLong()).isEqualTo(bank);
        // Another creditor, with the same account: the payment's change alone, no journal row, no new version.
        long other = newCounterparty(alice, "A friend");
        JsonNode view = ok(patch(alice, uri + "/records/%d?version=1".formatted(record.get("id").asLong()), """
                {"paymentCounterpartyId": %d}""".formatted(other)));
        assertThat(view.get("version").asInt()).isEqualTo(1);
        assertThat(view.get("yourPayment").get("counterpartyId").asLong()).isEqualTo(other);
        assertThat(ok(get(alice, uri + "/journal?recordId=" + record.get("id").asLong())).get("content")).hasSize(2);
        // To an account that takes none: the counterparty goes with the old account.
        JsonNode cash = ok(patch(alice, uri + "/records/%d?version=1".formatted(record.get("id").asLong()), """
                {"paymentAccountId": %d}""".formatted(accountId(alice, "CASH"))));
        assertThat(cash.get("yourPayment").has("counterpartyId")).isFalse();
        assertThat(StreamSupport.stream(paymentEntry(alice).get("postings").spliterator(), false)
                .allMatch(p -> p.get("counterpartyId").isNull())).isTrue();
        // And back, with the counterparty it needs.
        assertThat(details(patch(alice, uri + "/records/%d?version=1".formatted(record.get("id").asLong()), """
                {"paymentAccountId": %d}""".formatted(accountId(alice, "LOANS_ASSET"))))).containsExactly(
                "COUNTERPARTY %d the account LOANS_ASSET requires a counterparty: name who you owe or who owes you"
                        .formatted(mum));
        ok(patch(alice, uri + "/records/%d?version=1".formatted(record.get("id").asLong()), """
                {"paymentAccountId": %d, "paymentCounterpartyId": %d}""".formatted(accountId(alice, "LOANS_ASSET"), bank)));
        // Deleting the expense takes its payment with it.
        assertThat(delete(alice, uri + "/records/%d?version=1".formatted(record.get("id").asLong())))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(postedEntries(alice)).isEmpty();
    }

    /** The counterparty is required exactly where the account requires it, and is the payer's own. */
    @Test
    void theCounterpartyIsRequiredExactlyWhereTheAccountRequiresIt() throws IOException {
        long creditors = accountId(alice, "CREDITOR_DEBT");
        long cash = accountId(alice, "CASH");
        long bank = newCounterparty(alice, "The bank");
        long bobsFriend = newCounterparty(bob, "Bob's friend");
        String base = """
                {"date": "2026-09-10", "categoryId": %d, "amount": "10", "payerMemberId": %d, %s}""";
        assertThat(details(post(alice, uri + "/records", base.formatted(groceries, mum,
                "\"paymentAccountId\": " + creditors)))).containsExactly(
                "COUNTERPARTY %d the account CREDITOR_DEBT requires a counterparty: name who you owe or who owes you"
                        .formatted(mum));
        assertThat(details(post(alice, uri + "/records", base.formatted(groceries, mum,
                "\"paymentAccountId\": %d, \"paymentCounterpartyId\": %d".formatted(cash, bank))))).containsExactly(
                "COUNTERPARTY %d the account CASH takes no counterparty".formatted(mum));
        assertThat(details(post(alice, uri + "/records", base.formatted(groceries, mum,
                "\"paymentLater\": true, \"paymentCounterpartyId\": %d".formatted(bank))))).containsExactly(
                "COUNTERPARTY %d \"Specify later\" takes no counterparty: name it with the account".formatted(mum));
        // Another user's counterparty is a missing one.
        assertThat(details(post(alice, uri + "/records", base.formatted(groceries, mum,
                "\"paymentAccountId\": %d, \"paymentCounterpartyId\": %d".formatted(creditors, bobsFriend)))))
                .containsExactly("COUNTERPARTY %d the counterparty %d is not one of your counterparties"
                        .formatted(mum, bobsFriend));
        // Only the payer's own payment has them: a member without an account has none.
        assertThat(details(post(alice, uri + "/records", base.formatted(groceries, kid,
                "\"paymentCounterpartyId\": " + bank)))).containsExactly(
                "PAYMENT %d a private note goes only on your own payment, and you didn't pay this".formatted(kid));
        // An income can't be received into such an account, and a settlement's side takes neither.
        assertThat(details(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-10", "categoryId": %d, "amount": "10", "payerMemberId": %d,
                 "paymentAccountId": %d, "paymentCounterpartyId": %d}""".formatted(salary, mum, creditors, bank))))
                .containsExactly("PAYMENT %d the account CREDITOR_DEBT can't receive a family income: use an account of "
                        .formatted(mum) + "your own money or credit, or specify it later");
        assertThat(details(post(alice, uri + "/settlements", """
                {"date": "2026-09-10", "amount": "5", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, creditors)))).containsExactly(
                "PAYMENT %d the account CREDITOR_DEBT can't take a settlement: use an account of your own money or "
                        .formatted(mum) + "credit, or specify it later");
        assertThat(postedEntries(alice)).isEmpty();
    }

    /** D-81: the payee is on the payer's own entry and nowhere else. */
    @Test
    void thePayeeStaysOnThePayersOwnEntry() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        long shop = newCounterparty(alice, "The corner shop");
        JsonNode record = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "30", "payerMemberId": %d,
                 "paymentAccountId": %d, "payeeId": %d, "comment": "Dinner", "split": %s}"""
                .formatted(groceries, mum, current, shop, even())));
        long id = record.get("id").asLong();

        assertThat(record.get("yourPayment").get("payeeId").asLong()).isEqualTo(shop);
        assertThat(paymentEntry(alice).get("payeeId").asLong()).isEqualTo(shop);
        // Her share entry and everyone else's entries have no payee; Dad sees date, category, amount and shares.
        for (JsonNode entry : ok(get(alice, "/api/entries?size=200")).get("content")) {
            if (entry.get("family").get("link").asText().equals("SHARE")) {
                assertThat(entry.get("payeeId").isNull()).isTrue();
            }
        }
        for (JsonNode entry : ok(get(bob, "/api/entries?size=200")).get("content")) {
            assertThat(entry.get("payeeId").isNull()).isTrue();
        }
        JsonNode dads = ok(get(bob, uri + "/records/" + id));
        assertThat(dads.has("yourPayment")).isFalse();
        assertThat(dads.toString()).doesNotContain("payeeId").doesNotContain("corner shop");
        assertThat(ok(get(bob, uri + "/records")).toString()).doesNotContain("payeeId").doesNotContain("corner shop");
        assertThat(ok(get(bob, uri + "/journal")).toString()).doesNotContain("payee").doesNotContain("corner shop");
        assertThat(ok(get(alice, uri + "/journal")).toString()).doesNotContain("corner shop");

        // Change it through the record and through the entry: private changes, no version, no journal row.
        long cafe = newCounterparty(alice, "The cafe");
        JsonNode viaRecord = ok(patch(alice, uri + "/records/%d?version=0".formatted(id), """
                {"payeeId": %d}""".formatted(cafe)));
        assertThat(viaRecord.get("version").asInt()).isZero();
        assertThat(viaRecord.get("yourPayment").get("payeeId").asLong()).isEqualTo(cafe);
        long entryId = paymentEntry(alice).get("id").asLong();
        int entryVersion = paymentEntry(alice).get("version").asInt();
        ok(patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(entryId, entryVersion), """
                {"payeeId": %d, "amount": "35"}""".formatted(shop)));
        JsonNode afterEntry = ok(get(alice, uri + "/records/" + id));
        assertThat(afterEntry.get("yourPayment").get("payeeId").asLong()).isEqualTo(shop);
        assertThat(afterEntry.get("amount").asText()).isEqualTo("35.00");
        // An amount change keeps the payee; null removes it.
        assertThat(paymentEntry(alice).get("payeeId").asLong()).isEqualTo(shop);
        JsonNode removed = ok(patch(alice, uri + "/records/%d?version=1".formatted(id), """
                {"payeeId": null}"""));
        assertThat(removed.get("yourPayment").has("payeeId")).isFalse();
        assertThat(paymentEntry(alice).get("payeeId").isNull()).isTrue();
        List<String> journal = changes(ok(get(alice, uri + "/journal?recordId=" + id)));
        assertThat(journal).hasSize(2).noneMatch(line -> line.contains("payee"));

        // Specify later keeps a payee too, which moves to the account with it.
        ok(patch(alice, uri + "/records/%d?version=1".formatted(id), """
                {"paymentLater": true, "payeeId": %d}""".formatted(cafe)));
        assertThat(paymentEntry(alice).get("payeeId").asLong()).isEqualTo(cafe);
        ok(patch(alice, uri + "/records/%d?version=1".formatted(id), """
                {"paymentAccountId": %d}""".formatted(current)));
        assertThat(paymentEntry(alice).get("payeeId").asLong()).isEqualTo(cafe);
        // The payer's own entry is still changed only as a payment: the personal PUT answers 409.
        assertThat(put(alice, "/api/entries/%d?version=%d".formatted(paymentEntry(alice).get("id").asLong(),
                paymentEntry(alice).get("version").asInt()), """
                {"kind": "EXPENSE", "entryDate": "2026-09-10", "accountId": %d, "currency": "EUR", "amount": "1",
                 "categoryId": %d}""".formatted(current, categoryId(alice, "HOUSING")))).hasStatus(HttpStatus.CONFLICT);
        // Deleting the record leaves no entry and no trace.
        assertThat(delete(alice, uri + "/records/%d?version=1".formatted(id))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(postedEntries(alice)).isEmpty();
    }

    /** Only the payer has a payee, and only one of their own counterparties; a settlement has none. */
    @Test
    void aPayeeIsTheirsAndOwn() throws IOException {
        long shop = newCounterparty(alice, "The corner shop");
        long bobsShop = newCounterparty(bob, "Bob's shop");
        long current = accountId(alice, "CURRENT_ACCOUNT");
        String base = """
                {"date": "2026-09-10", "categoryId": %d, "amount": "10", "payerMemberId": %d, %s}""";
        assertThat(details(post(alice, uri + "/records", base.formatted(groceries, mum,
                "\"paymentAccountId\": %d, \"payeeId\": %d".formatted(current, bobsShop))))).containsExactly(
                "COUNTERPARTY %d the payee %d is not one of your counterparties".formatted(mum, bobsShop));
        assertThat(details(post(alice, uri + "/records", base.formatted(groceries, kid, "\"payeeId\": " + shop))))
                .containsExactly("PAYMENT %d a private note goes only on your own payment, and you didn't pay this"
                        .formatted(kid));
        // Mum's payment can't be reached through Bob's entry endpoint, and Bob can't name her counterparty.
        JsonNode record = created(post(alice, uri + "/records", base.formatted(groceries, mum,
                "\"paymentLater\": true, \"payeeId\": " + shop)));
        assertThat(patch(bob, uri + "/records/%d?version=0".formatted(record.get("id").asLong()), """
                {"payeeId": %d}""".formatted(bobsShop))).hasStatus(HttpStatus.CONFLICT);
        // A settlement's side takes neither, through the record or through its entry.
        JsonNode settlement = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-10", "amount": "5", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, current)));
        long entryId = settlement.get("yourPayment").get("entryId").asLong();
        assertThat(details(patch(alice, "/api/entries/%d/family-payment?version=0".formatted(entryId), """
                {"payeeId": %d}""".formatted(shop)))).containsExactly(
                "COUNTERPARTY %d a settlement takes no counterparty or payee".formatted(mum));
    }
}
