package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * ENT-2 (D-873, D-880, D-888, D-889): Entries' list filters take several values (any within a filter, all across
 * filters), repeated query parameters, with single values as before; Direction is the summary's own classification, so
 * that the entries a direction shows add up to that row of {@code /entries/summary}, the paying side of a counted
 * family record going with its record; Origin is a live family link or none. The list and the summary agree on every
 * filter.
 */
class EntryFilterListApiTests extends FamilyApiTest {

    private long current;
    private long savings;
    private long cash;
    private long shop;
    private long cafe;
    private long eatingOut;

    @BeforeEach
    void ledger() throws IOException {
        current = accountId(alice, "CURRENT_ACCOUNT");
        savings = accountId(alice, "SAVINGS_ACCOUNT");
        cash = accountId(alice, "CASH");
        shop = newCounterparty(alice, "Shop ENT2");
        cafe = newCounterparty(alice, "Cafe ENT2");
        halves = """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}""".formatted(mum, dad);
        eatingOut = categoryId(alice, "EATING_OUT");
        // Family: an expense and an income Alice paid or received, a refund, an expense Bob paid, a settlement.
        record("EXPENSE", "30.00", false, mum, "\"paymentAccountId\": " + current + ", \"payeeId\": " + shop);
        record("INCOME", "60.00", false, mum, "\"paymentAccountId\": " + current);
        record("EXPENSE", "10.00", true, mum, "\"paymentAccountId\": " + current);
        created(post(bob, uri + "/records", """
                {"date": "2026-09-14", "categoryId": %d, "amount": "8.00", "payerMemberId": %d,
                 "paymentLater": true, "split": %s}""".formatted(groceries, dad, halves)));
        created(post(alice, uri + "/settlements", """
                {"date": "2026-09-20", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, current)));
        // Personal: an expense at the cafe in cash, one at the shop, a salary, a refund, a transfer.
        expense("2026-09-02", "4.00", cash, cafe, eatingOut, false);
        expense("2026-09-03", "12.00", current, shop, eatingOut, false);
        expense("2026-09-04", "2.50", current, shop, eatingOut, true);
        newEntry(alice, """
                {"kind": "INCOME", "entryDate": "2026-09-01", "accountId": %d, "currency": "EUR", "amount": "100.00",
                 "categoryId": %d}""".formatted(current, categoryId(alice, "SALARY")));
        newEntry(alice, """
                {"kind": "TRANSFER", "entryDate": "2026-09-05", "fromAccountId": %d, "toAccountId": %d,
                 "currency": "EUR", "amount": "50.00"}""".formatted(current, savings));
    }

    /** Mum and Dad half each; Kid, without an account, none. */
    private String halves;

    private void record(String type, String amount, boolean refund, long payer, String payment) throws IOException {
        created(post(alice, uri + "/records", """
                {"type": "%s", "date": "2026-09-12", "categoryId": %d, "amount": "%s", "refund": %s,
                 "payerMemberId": %d, %s, "split": %s}""".formatted(type,
                type.equals("INCOME") ? salary : groceries, amount, refund, payer, payment, halves)));
    }

    private void expense(String date, String amount, long account, long payee, long category, boolean refund)
            throws IOException {
        newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "%s", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "%s", "categoryId": %d}""".formatted(date, payee, account, refund ? "-" + amount : amount,
                category));
    }

    /** The summary's figures as numbers: count, incoming, outgoing, transfers, spent's and received's others' shares. */
    private Map<String, BigDecimal> summary(String query) throws IOException {
        JsonNode s = ok(get(alice, "/api/entries/summary?" + query));
        Map<String, BigDecimal> figures = new TreeMap<>();
        figures.put("count", BigDecimal.valueOf(s.get("count").asLong()));
        for (String f : List.of("incoming", "outgoing", "transfers", "spentOthersShares", "receivedOthersShares")) {
            figures.put(f, BigDecimal.ZERO);
        }
        s.get("currencies").forEach(c -> figures.replaceAll((f, v) -> f.equals("count") ? v
                : v.add(new BigDecimal(c.get(f).asText()))));
        // The list finds as many as the summary counts.
        JsonNode page = ok(get(alice, "/api/entries?size=200&" + query));
        assertThat(page.get("totalElements").asLong()).as(query).isEqualTo(s.get("count").asLong());
        return figures;
    }

    private long found(String query) throws IOException {
        return ok(get(alice, "/api/entries?size=200&" + query)).get("totalElements").asLong();
    }

    private static Map<String, BigDecimal> only(Map<String, BigDecimal> all, String... kept) {
        Map<String, BigDecimal> row = new TreeMap<>(all);
        row.replaceAll((f, v) -> f.equals("count") || List.of(kept).contains(f) ? v : BigDecimal.ZERO);
        return row;
    }

    /** Count and every figure of the three directions' summaries add up to the whole one, each in its own row. */
    private void directionsAddUp(String query) throws IOException {
        Map<String, BigDecimal> all = summary(query);
        Map<String, BigDecimal> income = summary(query + "&direction=INCOME");
        Map<String, BigDecimal> expenses = summary(query + "&direction=EXPENSES");
        Map<String, BigDecimal> transfers = summary(query + "&direction=TRANSFERS");
        assertThat(withoutCount(income)).as(query).isEqualTo(withoutCount(only(all, "incoming", "receivedOthersShares")));
        assertThat(withoutCount(expenses)).as(query).isEqualTo(withoutCount(only(all, "outgoing", "spentOthersShares")));
        assertThat(withoutCount(transfers)).as(query).isEqualTo(withoutCount(only(all, "transfers")));
        assertThat(income.get("count").add(expenses.get("count")).add(transfers.get("count"))).as(query)
                .isEqualByComparingTo(all.get("count"));
        // All three are no filter.
        assertThat(summary(query + "&direction=INCOME&direction=EXPENSES&direction=TRANSFERS")).isEqualTo(all);
    }

    private static Map<String, BigDecimal> withoutCount(Map<String, BigDecimal> figures) {
        Map<String, BigDecimal> rest = new TreeMap<>();
        figures.forEach((f, v) -> {
            if (!f.equals("count")) {
                rest.put(f, v.stripTrailingZeros());
            }
        });
        return rest;
    }

    @Test
    void eachDirectionAddsUpToItsRowOfTheSummary() throws IOException {
        directionsAddUp("");
        Map<String, BigDecimal> expenses = summary("direction=EXPENSES");
        // Alice's share of 30 (15), Bob's 8 (her 4), the cafe 4 and the shop 12; the payment of the 30 goes with it.
        assertThat(expenses.get("outgoing")).isEqualByComparingTo("35.00");
        assertThat(expenses.get("spentOthersShares")).isEqualByComparingTo("19.00");
        assertThat(expenses.get("count")).isEqualByComparingTo("5");
        Map<String, BigDecimal> income = summary("direction=INCOME");
        // Her share of 60 (30) and of the refund (5), the salary 100 and the refund 2.50; with both payments.
        assertThat(income.get("incoming")).isEqualByComparingTo("137.50");
        assertThat(income.get("count")).isEqualByComparingTo("6");
        // The settlement and the transfer.
        assertThat(summary("direction=TRANSFERS").get("transfers")).isEqualByComparingTo("55.00");
    }

    /** A payment whose share the other filters don't match isn't counted; it is a transfer then (D-880). */
    @Test
    void aPaymentWhoseRecordIsntCountedIsATransfer() throws IOException {
        directionsAddUp("accountId=" + current);
        assertThat(summary("accountId=" + current + "&direction=TRANSFERS").get("transfers"))
                .isEqualByComparingTo("155.00");
        directionsAddUp("origin=FAMILY");
        directionsAddUp("payee=shop ent2");
        directionsAddUp("from=2026-09-12&to=2026-09-12");
    }

    @Test
    void originSplitsTheEntriesByALiveFamilyLink() throws IOException {
        Map<String, BigDecimal> all = summary("");
        Map<String, BigDecimal> personal = summary("origin=PERSONAL");
        Map<String, BigDecimal> family = summary("origin=FAMILY");
        assertThat(personal.get("count")).isEqualByComparingTo("5");
        // Three shares and three sides of Alice's records, Bob's record's share, her side of the settlement.
        assertThat(family.get("count")).isEqualByComparingTo("8");
        all.forEach((f, v) -> assertThat(personal.get(f).add(family.get(f))).as(f).isEqualByComparingTo(v));
        assertThat(summary("origin=PERSONAL&origin=FAMILY")).isEqualTo(all);
        assertThat(found("origin=PERSONAL&direction=EXPENSES")).isEqualTo(2);
    }

    @Test
    void aListFilterTakesAnyOfItsValuesAndFiltersAllOfThem() throws IOException {
        long onCurrent = found("accountId=" + current);
        long onCash = found("accountId=" + cash);
        assertThat(found("accountId=" + current + "&accountId=" + cash)).isEqualTo(onCurrent + onCash);
        // The single-value URL as before.
        assertThat(onCash).isEqualTo(1);
        assertThat(found("categoryId=" + eatingOut)).isEqualTo(3);
        assertThat(found("categoryId=" + eatingOut + "&categoryId=" + salary))
                .isEqualTo(3 + found("categoryId=" + salary));
        // Across filters: all of them.
        assertThat(found("categoryId=" + eatingOut + "&accountId=" + cash)).isEqualTo(1);
        assertThat(found("categoryId=" + eatingOut + "&accountId=" + cash + "&accountId=" + current)).isEqualTo(3);
        // The Payee filter: counterparties and names, either (D-888).
        assertThat(found("counterpartyId=" + cafe)).isEqualTo(1);
        long shopEntries = found("counterpartyId=" + shop);
        assertThat(found("counterpartyId=" + cafe + "&counterpartyId=" + shop)).isEqualTo(shopEntries + 1);
        assertThat(found("counterpartyId=" + cafe + "&payee=SHOP ENT2")).isEqualTo(shopEntries + 1);
        assertThat(found("payee=cafe ent2&payee=shop ent2&payee=")).isEqualTo(shopEntries + 1);
        assertThat(found("payee=cafe ent2&accountId=" + current)).isZero();
    }

    @Test
    void badValuesAre400() throws IOException {
        StringBuilder many = new StringBuilder("accountId=1");
        for (int i = 2; i <= 51; i++) {
            many.append("&accountId=").append(i);
        }
        for (String query : List.of("direction=SIDEWAYS", "origin=BOTH", "accountId=x", many.toString(),
                "payee=" + "x".repeat(101))) {
            assertThat(get(alice, "/api/entries?" + query)).as(query).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(alice, "/api/entries/summary?" + query)).as(query).hasStatus(HttpStatus.BAD_REQUEST);
        }
    }
}
