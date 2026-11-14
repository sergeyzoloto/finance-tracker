package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * UX-11, D-524: {@code GET /api/entries/summary} adds up every entry the entry list's filters match, on every page, as
 * the list's cells show them: one entry of each kind gives In, Out and Transfers per currency by the sign the row
 * shows (describeEntry's rule), and the distinct payees, categories and accounts with the entries without a payee or a
 * category. The filters select exactly the list's entries; another user's entries never count.
 */
class EntrySummaryApiTests extends FamilyApiTest {

    private long cash;
    private long current;
    private long savings;

    @BeforeEach
    void aliceHasOneEntryOfEachKind() throws IOException {
        cash = accountId(alice, "CASH");
        current = accountId(alice, "CURRENT_ACCOUNT");
        savings = accountId(alice, "SAVINGS_ACCOUNT");
        long unallocated = accountId(alice, "UNALLOCATED");
        long eatingOut = categoryId(alice, "EATING_OUT");
        long interest = categoryId(alice, "INTEREST");
        long fees = categoryId(alice, "BANK_FEES");
        long shop = newCounterparty(alice, "Shop A");
        long bank = newCounterparty(alice, "Bank B");
        long friend = newCounterparty(alice, "Friend C");

        // Out 10, In 3 (a refund), In 100.
        newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "2026-09-02", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "10.00", "categoryId": %d}""".formatted(shop, cash, eatingOut));
        newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "2026-09-03", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "-3.00", "categoryId": %d}""".formatted(shop, cash, eatingOut));
        newEntry(alice, """
                {"kind": "INCOME", "entryDate": "2026-09-04", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "100.00", "categoryId": %d}""".formatted(bank, current, interest));
        // Transfers 50, and an exchange: EUR 20 (its FX_EXCHANGE line) and USD 22.
        newEntry(alice, """
                {"kind": "TRANSFER", "entryDate": "2026-09-05", "fromAccountId": %d, "toAccountId": %d,
                 "currency": "EUR", "amount": "50.00"}""".formatted(current, savings));
        newEntry(alice, """
                {"kind": "CURRENCY_EXCHANGE", "entryDate": "2026-09-06", "fromAccountId": %d, "fromCurrency": "EUR",
                 "fromAmount": "20.00", "toAccountId": %d, "toCurrency": "USD", "toAmount": "22.00"}"""
                .formatted(current, current));
        // A shared expense: out 30 (cash −30, own 15 categorized, the other 15 to FAMILY_DEBT).
        newEntry(alice, """
                {"kind": "SHARED_EXPENSE", "entryDate": "2026-09-07", "accountId": %d, "currency": "EUR",
                 "total": "30.00", "categoryId": %d}""".formatted(cash, eatingOut));
        // A loan given and repaid: transfers 40 and 15, the friend on the lines.
        newEntry(alice, """
                {"kind": "LOAN_GIVEN", "entryDate": "2026-09-08", "fromAccountId": %d, "counterpartyId": %d,
                 "currency": "EUR", "amount": "40.00"}""".formatted(cash, friend));
        newEntry(alice, """
                {"kind": "LOAN_REPAID", "entryDate": "2026-09-09", "toAccountId": %d, "counterpartyId": %d,
                 "currency": "EUR", "amount": "15.00"}""".formatted(cash, friend));
        // An opening balance: transfers 500.
        newEntry(alice, """
                {"kind": "OPENING_BALANCE", "entryDate": "2026-09-01", "accountId": %d, "currency": "EUR",
                 "amount": "500.00"}""".formatted(savings));
        // A manual entry: out 5.
        newEntry(alice, """
                {"kind": "MANUAL", "entryDate": "2026-09-11", "postings": [
                  {"accountId": %d, "currency": "EUR", "amount": "-5.00"},
                  {"accountId": %d, "currency": "EUR", "amount": "5.00", "categoryId": %d}]}"""
                .formatted(cash, unallocated, fees));
        // The family budget, split equally among Mum, Dad and Kid: her payment (transfers 20) and share (out 6.68, the
        // payer's) of an expense with the shop as payee, her receipt (transfers 60) and share (in 20) of an income, her
        // side of a settlement (transfers 5).
        created(post(alice, uri + "/records", """
                {"date": "2026-09-12", "categoryId": %d, "amount": "20.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "payeeId": %d}""".formatted(groceries, mum, current, shop)));
        created(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-13", "categoryId": %d, "amount": "60.00", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(salary, mum, current)));
        created(post(alice, uri + "/settlements", """
                {"date": "2026-09-20", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(mum, dad)));
        // Bob's own, which never counts for Alice.
        newEntry(bob, """
                {"kind": "EXPENSE", "entryDate": "2026-09-05", "accountId": %d, "currency": "EUR",
                 "amount": "777.00", "categoryId": %d}""".formatted(accountId(bob, "CASH"),
                categoryId(bob, "EATING_OUT")));
    }

    private JsonNode summary(String user, String query) throws IOException {
        return ok(get(user, "/api/entries/summary" + query));
    }

    private static List<String> currencies(JsonNode summary) {
        List<String> lines = new ArrayList<>();
        summary.get("currencies").forEach(c -> lines.add(c.get("currency").asText() + " in " + c.get("incoming").asText()
                + " out " + c.get("outgoing").asText() + " transfers " + c.get("transfers").asText()
                + " spent " + c.get("spent").asText() + " (others " + c.get("spentOthersShares").asText() + ")"
                + " received " + c.get("received").asText() + " (others " + c.get("receivedOthersShares").asText()
                + ")"));
        return lines;
    }

    @Test
    void oneEntryOfEachKindAddsUpAsTheCellsShowIt() throws IOException {
        JsonNode all = summary(alice, "");
        // Ten of her own, and five the family budget posted or that are her sides of it.
        assertThat(all.get("count").asLong()).isEqualTo(15);
        assertThat(all.get("firstDate").asText()).isEqualTo("2026-09-01");
        assertThat(all.get("lastDate").asText()).isEqualTo("2026-09-20");
        // UX-18, D-797: In and Out as before UX-18 (123.00, 51.68); the expense of 20.00 counts in full (13.32 the
        // others'), the income of 60.00 too (40.00 the others'); her payment (20) and receipt (60) leave Transfers
        // (710.00 before UX-18), D-798.
        assertThat(currencies(all)).containsExactly(
                "EUR in 123.00 out 51.68 transfers 630.00 spent 65.00 (others 13.32) received 163.00 (others 40.00)",
                "USD in 0.00 out 0.00 transfers 22.00 spent 0.00 (others 0.00) received 0.00 (others 0.00)");
        // Shop A (the expense, the refund, her payment and, from its record, her share), Bank B, Friend C (both loans).
        assertThat(all.get("payees").asLong()).isEqualTo(3);
        // The transfer, the exchange, the shared expense, the opening balance, the manual entry, the receipt, the
        // income's share and the settlement.
        assertThat(all.get("withoutPayee").asLong()).isEqualTo(8);
        // Eating out, Interest, Bank fees, and the family's Groceries and Salary (a payment and a receipt by their
        // record's).
        assertThat(all.get("categories").asLong()).isEqualTo(5);
        // The transfer, the exchange, both loans, the opening balance and the settlement.
        assertThat(all.get("withoutCategory").asLong()).isEqualTo(6);
        // Cash, current, savings, FAMILY_DEBT, loans given, OPENING_BALANCE, her debt to the budget and "Payments
        // without a specified account"; FX_EXCHANGE left out of the exchange, as its row leaves it out.
        assertThat(all.get("accounts").asLong()).isEqualTo(8);
        // Bob's own: his one expense and his share of each family record.
        JsonNode bobs = summary(bob, "");
        assertThat(bobs.get("count").asLong()).isEqualTo(4);
        // His shares count the records Alice paid and received in full (D-797); before UX-18 in 20.00, out 783.66.
        assertThat(currencies(bobs)).containsExactly(
                "EUR in 20.00 out 783.66 transfers 5.00 spent 797.00 (others 13.34) received 60.00 (others 40.00)");
    }

    /** Each filter selects the list's entries: the same count, the same first and last date. */
    @Test
    void theFiltersSelectTheListsEntries() throws IOException {
        long shop = find(ok(get(alice, "/api/counterparties")), "name", "Shop A").get("id").asLong();
        for (String query : List.of("", "from=2026-09-05&to=2026-09-10", "accountId=" + cash, "accountId=" + savings,
                "categoryId=" + categoryId(alice, "EATING_OUT"), "categoryId=" + groceries,
                "counterpartyId=" + shop, "q=shop", "q=bank&from=2026-09-01", "q=nothing like it",
                "from=2026-10-01")) {
            JsonNode list = ok(get(alice, "/api/entries?size=200&" + query));
            JsonNode summary = summary(alice, "?" + query);
            assertThat(summary.get("count").asLong()).as(query).isEqualTo(list.get("totalElements").asLong());
            List<String> dates = list.get("content").findValuesAsText("entryDate");
            if (dates.isEmpty()) {
                assertThat(summary.get("firstDate").isNull()).as(query).isTrue();
                assertThat(summary.get("lastDate").isNull()).as(query).isTrue();
            } else {
                assertThat(summary.get("lastDate").asText()).as(query).isEqualTo(dates.getFirst());
                assertThat(summary.get("firstDate").asText()).as(query).isEqualTo(dates.getLast());
            }
        }
        // The payee filter finds her share by its record's payee too: the expense, the refund, the payment, the share.
        assertThat(summary(alice, "?counterpartyId=" + shop).get("count").asLong()).isEqualTo(4);
    }

    @Test
    void anEmptyMatch() throws IOException {
        JsonNode none = summary(alice, "?from=2027-01-01");
        assertThat(none.get("count").asLong()).isZero();
        assertThat(none.get("firstDate").isNull()).isTrue();
        assertThat(none.get("lastDate").isNull()).isTrue();
        for (String field : List.of("payees", "withoutPayee", "categories", "withoutCategory", "accounts")) {
            assertThat(none.get(field).asLong()).as(field).isZero();
        }
        assertThat(none.get("currencies")).isEmpty();
        String carol = newUser();
        assertThat(summary(carol, "").get("count").asLong()).isZero();
    }

    /** Bad parameters answer 400, as the list's do; another user's ids select nothing of anyone's. */
    @Test
    void badParametersAnswer400AsTheListsDo() throws IOException {
        for (String query : List.of("from=2026-09-10&to=2026-09-01", "from=yesterday", "accountId=x",
                "q=" + "x".repeat(101))) {
            assertThat(get(alice, "/api/entries/summary?" + query)).as(query).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(alice, "/api/entries?" + query)).as(query).hasStatus(HttpStatus.BAD_REQUEST);
        }
        long bobsCash = accountId(bob, "CASH");
        assertThat(summary(alice, "?accountId=" + bobsCash).get("count").asLong()).isZero();
        assertThat(summary(bob, "?accountId=" + cash).get("count").asLong()).isZero();
    }
}
