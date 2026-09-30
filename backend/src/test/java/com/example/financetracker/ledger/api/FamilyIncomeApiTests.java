package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Family incomes (F4d; C5, D-7, D-12, D-14; ADR 0003, topic E) in the family of {@link FamilyApiTest}: an income mirrors
 * an expense, with the receiver in the payer's place, an INCOME category, shares posted as income, and D-12's tie to
 * the receiver. The invariants hold after every test.
 */
class FamilyIncomeApiTests extends FamilyApiTest {

    private static String income(String date, long category, String amount, long receiver, String payment,
            String split) {
        return """
                {"type": "INCOME", "date": "%s", "categoryId": %d, "amount": "%s", %s "payerMemberId": %d%s}"""
                .formatted(date, category, amount, payment, receiver, split == null ? "" : ", \"split\": " + split);
    }

    private String halves() {
        return """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}""".formatted(mum, dad);
    }

    /**
     * D-7's income example: Alice receives 1000 into her current account, half of it Bob's. Her receipt is her account
     * +1000 and her debt account −1000, each share is income of 500 on UNALLOCATED and the debt account +500, so she
     * ends owing 500 and Bob is owed 500. Bob's personal cash flow counts his 500 as the family's salary.
     */
    @Test
    void anIncomeIsPostedAsD7Says() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        JsonNode record = created(post(alice, uri + "/records", income("2026-09-10", salary, "1000", mum,
                "\"paymentAccountId\": %d,".formatted(current), halves())));
        assertThat(record.get("type").asText()).isEqualTo("INCOME");
        assertThat(record.get("payer").get("displayName").asText()).isEqualTo("Mum");
        assertThat(record.get("category").get("code").asText()).isEqualTo("SALARY");
        assertThat(shares(record)).containsExactly("Mum 500.00 5000", "Dad 500.00 5000");
        assertThat(record.get("yourPayment").get("accountName").asText()).isEqualTo("Current account");
        assertThat(record.has("payee")).isFalse();
        assertThat(balances(bob)).containsExactly("Mum 500.00", "Dad -500.00 you", "Kid 0.00");

        long alicesDebt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(postedEntries(alice)).containsExactly(
                "FAMILY_SHARE SHARE %d:-500.00:%d %d:500.00:null".formatted(accountId(alice, "UNALLOCATED"), salary,
                        alicesDebt),
                "FAMILY_PAYMENT PAYMENT %d:1000.00:null %d:-1000.00:null".formatted(current, alicesDebt));
        assertThat(postedEntries(bob)).containsExactly("FAMILY_SHARE SHARE %d:-500.00:%d %d:500.00:null".formatted(
                accountId(bob, "UNALLOCATED"), salary, accountId(bob, "FAMILY_DEBT_" + family)));
        JsonNode receipt = ok(get(alice, "/api/entries/" + record.get("yourPayment").get("entryId").asLong()));
        assertThat(receipt.get("family").get("link").asText()).isEqualTo("PAYMENT");
        assertThat(receipt.get("family").get("recordType").asText()).isEqualTo("INCOME");
        JsonNode bobsShare = ok(get(bob, "/api/entries")).get("content").get(0);
        assertThat(bobsShare.get("family").get("recordType").asText()).isEqualTo("INCOME");
        assertThat(bobsShare.get("family").get("readOnly").asBoolean()).isTrue();

        JsonNode cashFlow = ok(get(bob, "/api/reports/cash-flow?from=2026-09-01&to=2026-09-30"));
        JsonNode salaryRow = StreamSupport.stream(cashFlow.spliterator(), false)
                .filter(row -> row.get("categoryCode").asText().equals("SALARY") && row.has("familyLedgerId"))
                .findFirst().orElseThrow();
        assertThat(salaryRow.get("categoryType").asText()).isEqualTo("INCOME");
        assertThat(new BigDecimal(salaryRow.get("total").asText()).abs()).isEqualByComparingTo("500");
        assertThat(salaryRow.get("familyLedgerName").asText()).isEqualTo("Home");
    }

    /**
     * D-12: 10.01 split 50/50 gives the odd cent to the receiver, whoever of the two it is; the rule's equal shares
     * too. A member without an account receives an income too, with no receipt of anyone's.
     */
    @Test
    void theRemainderGoesToTheReceiver() throws IOException {
        JsonNode toMum = created(post(alice, uri + "/records", income("2026-09-10", salary, "10.01", mum,
                "\"paymentLater\": true,", halves())));
        assertThat(shares(toMum)).containsExactly("Mum 5.01 5000", "Dad 5.00 5000");
        JsonNode toDad = created(post(bob, uri + "/records", income("2026-09-10", salary, "10.01", dad,
                "\"paymentLater\": true,", halves())));
        assertThat(shares(toDad)).containsExactly("Mum 5.00 5000", "Dad 5.01 5000");
        // The rule, equal among Mum, Dad and Kid: 3.33 each and the remainder of 0.01 to Kid, who received it.
        JsonNode toKid = created(post(bob, uri + "/records", income("2026-09-11", salary, "10.00", kid, "", null)));
        assertThat(toKid.get("splitMethod").asText()).isEqualTo("EQUAL");
        assertThat(shares(toKid)).containsExactly("Mum 3.33 null", "Dad 3.33 null", "Kid 3.34 null");
        assertThat(toKid.has("yourPayment")).isFalse();
        // Kid received 10.00 and keeps 3.34 of it: the family's 6.66 is on Kid.
        assertThat(balances(alice)).containsExactly("Mum -3.33 you", "Dad -3.33", "Kid 6.66");
    }

    /** An income takes an INCOME category and an expense an EXPENSE one, when created and when changed. */
    @Test
    void theCategoryHasTheRecordsType() throws IOException {
        assertThat(details(post(alice, uri + "/records", income("2026-09-10", groceries, "10", mum,
                "\"paymentLater\": true,", null)))).containsExactly(
                "CATEGORY null the category GROCERIES is EXPENSE, and an income needs an INCOME category");
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", salary, "10", mum,
                "\"paymentLater\": true,")))).containsExactly(
                "CATEGORY null the category SALARY is INCOME, and an expense needs an EXPENSE category");
        long id = created(post(alice, uri + "/records", income("2026-09-10", salary, "10", mum,
                "\"paymentLater\": true,", null))).get("id").asLong();
        assertThat(details(patch(alice, uri + "/records/" + id + "?version=0", """
                {"categoryId": %d}""".formatted(rent)))).containsExactly(
                "CATEGORY null the category RENT is EXPENSE, and an income needs an INCOME category");
        // A settlement isn't created as a record with a category.
        assertThat(post(alice, uri + "/records", """
                {"type": "SETTLEMENT", "date": "2026-09-10", "categoryId": %d, "amount": "1", "payerMemberId": %d}"""
                .formatted(salary, mum))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    /**
     * Edits as for expenses (D-14): the receiver with an account changes the date, amount, receiver and account, also
     * through their receipt, with their private note; the author and the owners change the family fields; for a
     * receiver without an account, the author or an owner changes everything. The journal names the changes, never
     * the account or the note, and deleting the receipt deletes the income.
     */
    @Test
    void anIncomeChangesAsAnExpenseDoes() throws IOException {
        long cash = accountId(alice, "CASH");
        long current = accountId(alice, "CURRENT_ACCOUNT");
        JsonNode record = created(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-10", "categoryId": %d, "amount": "100", "payerMemberId": %d,
                 "paymentAccountId": %d, "privateNote": "Bonus", "split": %s}""".formatted(salary, mum, cash,
                halves())));
        long id = record.get("id").asLong();
        String path = uri + "/records/" + id;
        long receipt = record.get("yourPayment").get("entryId").asLong();
        assertThat(ok(get(alice, "/api/entries/" + receipt)).get("memo").asText()).isEqualTo("Bonus");
        assertThat(ok(get(alice, uri + "/journal?recordId=" + id)).toString()).doesNotContain("Bonus");

        // Only Mum, who received it, changes its date, amount, receiver and account; the owner's family fields.
        assertThat(detail(patch(bob, path + "?version=0", """
                {"amount": "120"}"""), HttpStatus.CONFLICT)).isEqualTo("Only Mum, who received it, can change the "
                        + "income's date, amount, receiver or receiving account.");
        assertThat(detail(patch(bob, path + "?version=0", """
                {"comment": "Mine"}"""), HttpStatus.CONFLICT)).isEqualTo("Only the income's author or an owner of the "
                        + "family budget can change its category, split or comment.");
        JsonNode changed = ok(patch(alice, path + "?version=0", """
                {"amount": "120", "date": "2026-09-12", "comment": "September"}"""));
        assertThat(shares(changed)).containsExactly("Mum 60.00 5000", "Dad 60.00 5000");
        assertThat(balances(alice)).containsExactly("Mum 60.00 you", "Dad -60.00", "Kid 0.00");
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + id))).getFirst()).isEqualTo(
                "UPDATE by Mum: date 2026-09-10→2026-09-12, amount 100.00→120.00, share of Mum 50.00→60.00, "
                        + "share of Dad 50.00→60.00, comment null→September");
        long alicesDebt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(postedEntries(alice)).endsWith("FAMILY_PAYMENT PAYMENT %d:120.00:null %d:-120.00:null"
                .formatted(cash, alicesDebt));

        // Through her receipt: the account, and the note stays hers.
        int version = ok(get(alice, "/api/entries/" + receipt)).get("version").asInt();
        JsonNode moved = ok(patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(receipt, version), """
                {"accountId": %d}""".formatted(current)));
        assertThat(moved.get("memo").asText()).isEqualTo("Bonus");
        assertThat(ok(get(alice, path)).get("yourPayment").get("accountId").asLong()).isEqualTo(current);
        assertThat(ok(get(alice, path)).get("version").asInt()).isOne();
        assertThat(detail(put(alice, "/api/entries/%d?version=%d".formatted(receipt, moved.get("version").asInt()), """
                {"kind": "INCOME", "entryDate": "2026-09-12", "accountId": %d, "currency": "EUR", "amount": "1",
                 "categoryId": %d}""".formatted(cash, categoryId(alice, "INTEREST"))), HttpStatus.CONFLICT))
                .isEqualTo("Entry %d is what you received for an income of the family budget \"Home\"; change or "
                        .formatted(receipt) + "delete the income there.");
        // To Kid, without an account: her receipt goes, and the author or an owner changes it from now on.
        JsonNode toKid = ok(patch(alice, path + "?version=1", """
                {"payerMemberId": %d}""".formatted(kid)));
        assertThat(toKid.has("yourPayment")).isFalse();
        assertThat(get(alice, "/api/entries/" + receipt)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(balances(alice)).containsExactly("Mum -60.00 you", "Dad -60.00", "Kid 120.00");
        assertThat(detail(patch(bob, path + "?version=2", """
                {"amount": "90"}"""), HttpStatus.CONFLICT)).isEqualTo("Only the income's author or an owner of the "
                        + "family budget can change it.");
        assertThat(details(patch(alice, path + "?version=2", """
                {"payerMemberId": %d, "paymentLater": true}""".formatted(dad)))).containsExactly(
                "PAYER %d Dad has an account: only they can record what they received; name yourself, or a member "
                        .formatted(dad) + "without an account");
        // Back to Mum, into "Specify later"; then she deletes it through her receipt.
        JsonNode back = ok(patch(alice, path + "?version=2", """
                {"payerMemberId": %d, "paymentLater": true}""".formatted(mum)));
        long newReceipt = back.get("yourPayment").get("entryId").asLong();
        int receiptVersion = ok(get(alice, "/api/entries/" + newReceipt)).get("version").asInt();
        assertThat(delete(alice, "/api/entries/%d?version=%d".formatted(newReceipt, receiptVersion)))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(detail(get(bob, path), HttpStatus.NOT_FOUND)).isEqualTo("Income %d not found.".formatted(id));
        assertThat(balances(alice)).containsExactly("Mum 0.00 you", "Dad 0.00", "Kid 0.00");
        assertThat(postedEntries(bob)).isEmpty();
        List<String> journal = changes(ok(get(alice, uri + "/journal?recordId=" + id)));
        assertThat(journal).hasSize(5);
        assertThat(journal.getFirst()).isEqualTo("DELETE by Mum: ");
    }
}
