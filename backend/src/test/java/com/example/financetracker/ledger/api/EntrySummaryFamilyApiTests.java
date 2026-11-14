package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UX-18, D-796 to D-800: the entry list's summary counts a family record once, through the user's matched share entry,
 * by its full amount, whoever paid: Spent (or Received) the full amount, Others' shares what falls on the other members,
 * Out (or In) as before, and the paying side of a counted record no longer under Transfers. A paying side whose record
 * isn't counted, a settlement and a plain transfer stay under Transfers.
 */
class EntrySummaryFamilyApiTests extends FamilyApiTest {

    private long current;
    private long savings;
    private long shop;

    @BeforeEach
    void accounts() throws IOException {
        current = accountId(alice, "CURRENT_ACCOUNT");
        savings = accountId(alice, "SAVINGS_ACCOUNT");
        shop = newCounterparty(alice, "Shop A");
    }

    private String split(int mumsBasisPoints) {
        return """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": %d},
                 {"memberId": %d, "basisPoints": %d}]}""".formatted(mum, mumsBasisPoints, dad,
                10000 - mumsBasisPoints);
    }

    private void alicePays(String type, String amount, int mumsBasisPoints) throws IOException {
        created(post(alice, uri + "/records", """
                {"type": "%s", "date": "2026-09-12", "categoryId": %d, "amount": "%s", "payerMemberId": %d,
                 "paymentAccountId": %d, "payeeId": %d, "split": %s}""".formatted(type,
                type.equals("INCOME") ? salary : groceries, amount, mum, current, shop, split(mumsBasisPoints))));
    }

    /** Per currency: in, out, transfers, spent (others'), received (others'). */
    private List<String> totals(String user, String query) throws IOException {
        JsonNode summary = ok(get(user, "/api/entries/summary" + query));
        List<String> lines = new ArrayList<>();
        summary.get("currencies").forEach(c -> {
            lines.add(c.get("currency").asText() + " in " + c.get("incoming").asText() + " out "
                    + c.get("outgoing").asText() + " transfers " + c.get("transfers").asText() + " spent "
                    + c.get("spent").asText() + " (" + c.get("spentOthersShares").asText() + ") received "
                    + c.get("received").asText() + " (" + c.get("receivedOthersShares").asText() + ")");
            // D-797's invariant.
            assertThat(c.get("spent").decimalValue().subtract(c.get("spentOthersShares").decimalValue()))
                    .isEqualByComparingTo(c.get("outgoing").decimalValue());
            assertThat(c.get("received").decimalValue().subtract(c.get("receivedOthersShares").decimalValue()))
                    .isEqualByComparingTo(c.get("incoming").decimalValue());
        });
        lines.addFirst("count " + summary.get("count").asLong());
        return lines;
    }

    @Test
    void anExpenseShePaidCountsInFull() throws IOException {
        alicePays("EXPENSE", "30.00", 5000);
        assertThat(totals(alice, "")).containsExactly("count 2",
                "EUR in 0.00 out 15.00 transfers 0.00 spent 30.00 (15.00) received 0.00 (0.00)");
    }

    @Test
    void anExpenseThePartnerPaidCountsTheSame() throws IOException {
        created(post(bob, uri + "/records", """
                {"date": "2026-09-12", "categoryId": %d, "amount": "30.00", "payerMemberId": %d,
                 "paymentLater": true, "split": %s}""".formatted(groceries, dad, split(5000))));
        assertThat(totals(alice, "")).containsExactly("count 1",
                "EUR in 0.00 out 15.00 transfers 0.00 spent 30.00 (15.00) received 0.00 (0.00)");
        // His payment isn't shown again either.
        assertThat(totals(bob, "")).containsExactly("count 2",
                "EUR in 0.00 out 15.00 transfers 0.00 spent 30.00 (15.00) received 0.00 (0.00)");
    }

    @Test
    void aSplitOtherThanHalves() throws IOException {
        alicePays("EXPENSE", "40.00", 7000);
        assertThat(totals(alice, "")).containsExactly("count 2",
                "EUR in 0.00 out 28.00 transfers 0.00 spent 40.00 (12.00) received 0.00 (0.00)");
        assertThat(totals(bob, "")).containsExactly("count 1",
                "EUR in 0.00 out 12.00 transfers 0.00 spent 40.00 (28.00) received 0.00 (0.00)");
    }

    @Test
    void anIncomeCountsInFull() throws IOException {
        alicePays("INCOME", "60.00", 5000);
        assertThat(totals(alice, "")).containsExactly("count 2",
                "EUR in 30.00 out 0.00 transfers 0.00 spent 0.00 (0.00) received 60.00 (30.00)");
    }

    /** A refund comes in: it counts on the Received side, by its full amount (D-803). */
    @Test
    void aRefundCountsOnTheReceivedSide() throws IOException {
        created(post(alice, uri + "/records", """
                {"date": "2026-09-12", "categoryId": %d, "amount": "10.00", "refund": true, "payerMemberId": %d,
                 "paymentAccountId": %d, "split": %s}""".formatted(groceries, mum, current, split(5000))));
        assertThat(totals(alice, "")).containsExactly("count 2",
                "EUR in 5.00 out 0.00 transfers 0.00 spent 0.00 (0.00) received 10.00 (5.00)");
    }

    @Test
    void aPaymentWhoseShareTheFilterDoesntMatchStaysUnderTransfers() throws IOException {
        alicePays("EXPENSE", "30.00", 5000);
        assertThat(totals(alice, "?accountId=" + current)).containsExactly("count 1",
                "EUR in 0.00 out 0.00 transfers 30.00 spent 0.00 (0.00) received 0.00 (0.00)");
    }

    @Test
    void aSettlementAndAPlainTransferStayUnderTransfers() throws IOException {
        created(post(alice, uri + "/settlements", """
                {"date": "2026-09-20", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, current)));
        newEntry(alice, """
                {"kind": "TRANSFER", "entryDate": "2026-09-05", "fromAccountId": %d, "toAccountId": %d,
                 "currency": "EUR", "amount": "50.00"}""".formatted(current, savings));
        assertThat(totals(alice, "")).containsExactly("count 2",
                "EUR in 0.00 out 0.00 transfers 55.00 spent 0.00 (0.00) received 0.00 (0.00)");
    }

    /** The owner's case: a payee filter matches the payment and the share of each record; each counts once. */
    @Test
    void aPayeeFilterMatchingThePaymentAndTheShare() throws IOException {
        alicePays("EXPENSE", "30.00", 5000);
        alicePays("EXPENSE", "12.50", 5000);
        newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "2026-09-02", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "4.00", "categoryId": %d}""".formatted(shop, current, categoryId(alice, "EATING_OUT")));
        List<String> expected = List.of("count 5",
                "EUR in 0.00 out 25.25 transfers 0.00 spent 46.50 (21.25) received 0.00 (0.00)");
        assertThat(totals(alice, "?counterpartyId=" + shop)).isEqualTo(expected);
        assertThat(totals(alice, "?payee=shop a")).isEqualTo(expected);
    }

    /** With the family switch's rule unchanged otherwise, a dollar record counts in dollars, where its share is. */
    @Test
    void aRecordCountsInItsOwnCurrency() throws IOException {
        created(post(bob, uri + "/records", """
                {"date": "2026-09-12", "categoryId": %d, "amount": "56.00", "currency": "USD", "payerMemberId": %d,
                 "paymentLater": true, "split": %s}""".formatted(groceries, dad, split(5000))));
        assertThat(totals(alice, "")).containsExactly("count 1",
                "USD in 0.00 out 28.00 transfers 0.00 spent 56.00 (28.00) received 0.00 (0.00)");
    }
}
