package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Refunds (F8d; D-79): an expense with a minus, in the same category, entered with a "Refund" flag. Requests and answers
 * carry the amount the user typed, above 0, and {@code refund}; the database holds the record, its paying side and its
 * shares negative, so that balances, the report, a member's debt account and their personal cash flow reduce by
 * themselves. The invariants of {@link FamilyApiTest} (the balances add up, D-10, the shares' sign) hold after every
 * test.
 */
class FamilyRefundApiTests extends FamilyApiTest {

    private static final String EQUAL_MUM_DAD = """
            {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000}, {"memberId": %d, "basisPoints": 5000}]}""";

    private String split() {
        return EQUAL_MUM_DAD.formatted(mum, dad);
    }

    private String refund(String date, long category, String amount, long receiver, String payment, String split) {
        String record = expense(date, category, amount, receiver, payment, split);
        return record.substring(0, record.length() - 1) + ", \"refund\": true}";
    }

    /**
     * Mum pays 40.00 of groceries on the 10th, split 50/50 with Dad, and gets 10.01 back on the 12th, split the same
     * way: the shares are 5.01 and 5.00, as D-12 splits 10.01, and what each owes falls by them.
     */
    @Test
    void aRefundIsAnExpenseWithAMinusSplitByTheSameShares() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        String account = "\"paymentAccountId\": %d,".formatted(current);
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "40", mum, account, split())));
        JsonNode refund = created(post(alice, uri + "/records",
                refund("2026-09-12", groceries, "10.01", mum, account, split())));

        // What the user typed, with the flag; the shares in the same terms (D-12's 5.01 to the payer).
        assertThat(refund.get("refund").asBoolean()).isTrue();
        assertThat(refund.get("amount").asText()).isEqualTo("10.01");
        assertThat(refund.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(refund.get("category").get("name").asText()).isEqualTo("Groceries");
        assertThat(shares(refund)).containsExactly("Mum 5.01 5000", "Dad 5.00 5000");
        assertThat(refund.get("yourPayment").get("amount").asText()).isEqualTo("10.01");
        assertThat(ok(get(alice, uri + "/records")).get("content").get(0).get("refund").asBoolean()).isTrue();
        assertThat(ok(get(alice, uri + "/records")).get("content").get(1).get("refund").asBoolean()).isFalse();
        // The database holds it negative, the paying side and each share too.
        assertThat(jdbc.sql("SELECT base_amount FROM family_record WHERE id = ?").param(refund.get("id").asLong())
                .query(BigDecimal.class).single()).isEqualByComparingTo("-10.01");
        assertThat(jdbc.sql("SELECT original_amount FROM family_record WHERE id = ?").param(refund.get("id").asLong())
                .query(BigDecimal.class).single()).isEqualByComparingTo("-10.01");
        assertThat(jdbc.sql("SELECT amount FROM family_share WHERE record_id = ? ORDER BY member_id")
                .param(refund.get("id").asLong()).query(BigDecimal.class).list())
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("-5.01"), new BigDecimal("-5.00"));

        // Mum's ledger: her share with UNALLOCATED credited (the category is reduced), her debt up by it, and the money
        // back on her account and off her debt.
        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(postedEntries(alice)).contains(
                "FAMILY_SHARE SHARE %d:-5.01:%d %d:5.01:null".formatted(accountId(alice, "UNALLOCATED"), groceries, debt),
                "FAMILY_PAYMENT PAYMENT %d:10.01:null %d:-10.01:null".formatted(current, debt));
        assertThat(postedEntries(bob)).contains("FAMILY_SHARE SHARE %d:-5.00:%d %d:5.00:null".formatted(
                accountId(bob, "UNALLOCATED"), groceries, accountId(bob, "FAMILY_DEBT_" + family)));

        // Balances: after the expense Mum −20, Dad +20; the refund takes 5.00 off Dad's and Mum's net: Mum got 10.01
        // back and her own share fell by 5.01, so she is owed 5.00 less.
        assertThat(balances(alice)).containsExactly("Mum -15.00 you", "Dad 15.00", "Kid 0.00");
        assertThat(find(ok(get(alice, "/api/reports/balances?asOf=2026-09-30")), "accountCode", "FAMILY_DEBT_" + family)
                .get("balance").asText()).isEqualTo("-15.00");

        // The report: the category's total of the month is the expense less the refund, and E3 holds against Mum's and
        // Dad's personal cash flow (checkFamilyReport).
        JsonNode report = checkFamilyReport(alice, family, Map.of(alice, LocalDate.of(2026, 9, 1),
                bob, LocalDate.of(2026, 9, 1)));
        JsonNode row = report.get("byCurrency").get(0).get("rows").get(0);
        assertThat(row.get("total").asText()).isEqualTo("29.99");
        assertThat(row.get("members").get(0).get("share").asText()).isEqualTo("14.99");
        assertThat(find(ok(get(alice, "/api/reports/cash-flow?from=2026-09-01&to=2026-09-30")), "categoryName",
                "Groceries").get("total").asText()).isEqualTo("14.99");

        // The journal says it was a refund, in its currency; the summary carries what was refunded.
        JsonNode journal = ok(get(alice, uri + "/journal?recordId=" + refund.get("id").asLong()));
        assertThat(changes(journal)).containsExactly("CREATE by Mum: date null→2026-09-12, category null→Groceries, "
                + "amount null→10.01, refund null→true, payer null→Mum, splitMethod null→PERCENT, "
                + "share of Mum null→5.01, share of Dad null→5.00");
        JsonNode entry = journal.get("content").get(0);
        assertThat(entry.get("currency").asText()).isEqualTo("EUR");
        assertThat(entry.get("record").get("refund").asBoolean()).isTrue();
        assertThat(entry.get("record").get("amount").asText()).isEqualTo("10.01");
    }

    /** A refund is changed as an expense is: its amount, date, category and split keep it a refund. */
    @Test
    void aRefundIsChangedAndDeletedAsAnExpenseIs() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        String account = "\"paymentAccountId\": %d,".formatted(current);
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "40", mum, account, split())));
        JsonNode refund = created(post(alice, uri + "/records",
                refund("2026-09-12", groceries, "10.00", mum, account, split())));
        long id = refund.get("id").asLong();

        JsonNode changed = ok(patch(alice, uri + "/records/%d?version=0".formatted(id), """
                {"amount": "12.50", "date": "2026-09-13"}"""));
        assertThat(changed.get("refund").asBoolean()).isTrue();
        assertThat(changed.get("amount").asText()).isEqualTo("12.50");
        assertThat(shares(changed)).containsExactly("Mum 6.25 5000", "Dad 6.25 5000");
        assertThat(changed.get("yourPayment").get("amount").asText()).isEqualTo("12.50");
        assertThat(jdbc.sql("SELECT base_amount FROM family_record WHERE id = ?").param(id).query(BigDecimal.class)
                .single()).isEqualByComparingTo("-12.50");
        assertThat(balances(alice)).containsExactly("Mum -13.75 you", "Dad 13.75", "Kid 0.00");

        JsonNode toRent = ok(patch(alice, uri + "/records/%d?version=1".formatted(id), """
                {"categoryId": %d, "split": {"method": "ONE_MEMBER", "memberId": %d}}""".formatted(rent, dad)));
        assertThat(toRent.get("refund").asBoolean()).isTrue();
        assertThat(shares(toRent)).containsExactly("Dad 12.50 null");
        // Dad's share: UNALLOCATED credited 12.50 in Rent, his debt up by it.
        assertThat(postedEntries(bob)).contains("FAMILY_SHARE SHARE %d:-12.50:%d %d:12.50:null".formatted(
                accountId(bob, "UNALLOCATED"), rent, accountId(bob, "FAMILY_DEBT_" + family)));
        JsonNode journal = ok(get(alice, uri + "/journal?recordId=" + id));
        assertThat(journal.get("content").get(0).get("record").get("refund").asBoolean()).isTrue();
        assertThat(journal.get("content").get(0).get("currency").asText()).isEqualTo("EUR");

        assertThat(delete(alice, uri + "/records/%d?version=2".formatted(id))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(balances(alice)).containsExactly("Mum -20.00 you", "Dad 20.00", "Kid 0.00");
        assertThat(postedEntries(bob)).hasSize(1);
        JsonNode afterDelete = ok(get(alice, uri + "/journal?recordId=" + id));
        assertThat(afterDelete.get("content").get(0).get("record").get("deleted").asBoolean()).isTrue();
        assertThat(afterDelete.get("content").get(0).get("record").get("refund").asBoolean()).isTrue();
    }

    /** A refund by a member without an account (nobody's payment is posted), and one before a seat is claimed. */
    @Test
    void aRefundReceivedByAMemberWithoutAnAccountMovesOnlyTheShares() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30", kid, "", split())));
        JsonNode refund = created(post(alice, uri + "/records", refund("2026-09-11", groceries, "9", kid, "", """
                {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "4"}, {"memberId": %d, "amount": "5"}]}"""
                .formatted(mum, dad))));
        assertThat(shares(refund)).containsExactly("Mum 4.00 null", "Dad 5.00 null");
        assertThat(refund.has("yourPayment")).isFalse();
        // Kid paid 30 and got 9 back: 21 net, against 15 and 15 less 4 and 5 (a positive balance is what one owes).
        assertThat(balances(alice)).containsExactly("Mum 11.00 you", "Dad 10.00", "Kid -21.00");
        assertThat(postedEntries(alice)).hasSize(2).allMatch(entry -> entry.startsWith("FAMILY_SHARE"));
        // Shares by percent of a refund are D-12's too.
        JsonNode third = created(post(alice, uri + "/records", refund("2026-09-12", groceries, "10.01", kid, "", split())));
        // No payer among the shares: the remainder of the tie goes by join order, to Mum.
        assertThat(shares(third)).containsExactly("Mum 5.01 5000", "Dad 5.00 5000");
    }

    /** A refund in another currency, paid into an account in the account's: the side goes through FX_EXCHANGE, signed. */
    @Test
    void aRefundInDollarsIntoAnEuroAccountGoesThroughFxExchange() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "20.00", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "18.40", "accountCurrency": "EUR",
                 "split": %s}""".formatted(groceries, mum, current, split())));
        JsonNode refund = created(post(alice, uri + "/records", """
                {"date": "2026-09-11", "categoryId": %d, "amount": "5.00", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "4.60", "accountCurrency": "EUR", "refund": true,
                 "split": %s}""".formatted(groceries, mum, current, split())));
        assertThat(refund.get("yourPayment").get("amount").asText()).isEqualTo("4.60");
        assertThat(refund.get("yourPayment").get("currency").asText()).isEqualTo("EUR");
        // The account got 4.60 EUR, which FX_EXCHANGE took in the same currency, and 5.00 USD of debt came off.
        long fx = accountId(alice, "FX_EXCHANGE");
        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(postedEntries(alice)).contains("FAMILY_PAYMENT PAYMENT %d:4.60:null %d:-4.60:null %d:5.00:null "
                .formatted(current, fx, fx) + "%d:-5.00:null".formatted(debt));
        JsonNode usd = ok(get(alice, uri + "/balances")).get("byCurrency").get(1);
        assertThat(usd.get("currency").asText()).isEqualTo("USD");
        assertThat(usd.get("members").get(0).get("balance").asText()).isEqualTo("-7.50");
    }

    /** What a refund isn't: an income, a settlement, a negative amount, a zero. */
    @Test
    void onlyAnExpenseIsRefundedAndItsAmountIsAboveZero() throws IOException {
        String account = "\"paymentLater\": true,";
        String income = """
                {"type": "INCOME", "date": "2026-09-10", "categoryId": %d, "amount": "100", "payerMemberId": %d,
                 %s "refund": true}""".formatted(salary, mum, account);
        assertThat(details(post(alice, uri + "/records", income))).containsExactly(
                "REFUND null only an expense is refunded: an income has no refund");
        assertThat(details(post(alice, uri + "/records", refund("2026-09-10", groceries, "-5", mum, account, null))))
                .containsExactly("AMOUNT null the amount must be above 0");
        assertThat(details(post(alice, uri + "/records", refund("2026-09-10", groceries, "0", mum, account, null))))
                .containsExactly("AMOUNT null the amount must be above 0");
        // A settlement has no refund field: it is ignored, and the settlement is as ever.
        JsonNode settlement = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-10", "amount": "5", "payerMemberId": %d, "payeeMemberId": %d, "paymentLater": true,
                 "refund": true}""".formatted(mum, dad)));
        assertThat(settlement.get("refund").asBoolean()).isFalse();
        // The refund of a refund isn't a thing: a refund entered as a plain expense is an expense.
        JsonNode plain = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "5", mum, account)));
        assertThat(plain.get("refund").asBoolean()).isFalse();
    }

    /** A refund in the personal ledger's own words: the shares are credits to UNALLOCATED, and the cash flow nets them. */
    @Test
    void theRefundsAreInEveryMembersPersonalCashFlow() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30", kid, "", split())));
        created(post(alice, uri + "/records", refund("2026-09-11", groceries, "30", kid, "", split())));
        // Fully refunded: nothing left of the category in anyone's cash flow, and every balance back to 0.
        assertThat(balances(alice)).containsExactly("Mum 0.00 you", "Dad 0.00", "Kid 0.00");
        JsonNode flow = ok(get(alice, "/api/reports/cash-flow?from=2026-09-01&to=2026-09-30"));
        assertThat(find(flow, "categoryName", "Groceries").get("total").asText()).isEqualTo("0.00");
        checkFamilyReport(alice, family, Map.of(alice, LocalDate.of(2026, 9, 1), bob, LocalDate.of(2026, 9, 1)));
        List<String> categories = ok(get(alice, uri + "/report")).get("byCurrency").get(0).get("rows")
                .findValuesAsText("total");
        assertThat(categories).containsExactly("0.00");
    }
}
