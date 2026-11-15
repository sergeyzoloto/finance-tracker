package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * ENT-3 (D-873, D-881, D-896): {@code /entries/breakdown} groups the entries the filters match, except the tab's own
 * dimension's filters, by category, payee or account, from the summary's classification. With one value per income or
 * expense entry the rows' My share adds up to the summary's In and Out over the same filters without that dimension's,
 * and their counts to its entries; rows are sorted by entries, then by name; entries without a category or payee are one
 * row without a key.
 */
class EntryBreakdownApiTests extends FamilyApiTest {

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


    /** The summary's In and Out and count over the query, EUR only (the fixture has one currency). */
    private Map<String, BigDecimal> summary(String query) throws IOException {
        JsonNode s = ok(get(alice, "/api/entries/summary?" + query));
        Map<String, BigDecimal> figures = new TreeMap<>();
        figures.put("count", BigDecimal.valueOf(s.get("count").asLong()));
        figures.put("incoming", BigDecimal.ZERO);
        figures.put("outgoing", BigDecimal.ZERO);
        s.get("currencies").forEach(c -> {
            figures.merge("incoming", new BigDecimal(c.get("incoming").asText()), BigDecimal::add);
            figures.merge("outgoing", new BigDecimal(c.get("outgoing").asText()), BigDecimal::add);
        });
        return figures;
    }

    private JsonNode rows(String by, String query) throws IOException {
        JsonNode answer = ok(get(alice, "/api/entries/breakdown?by=" + by + (query.isEmpty() ? "" : "&" + query)));
        assertThat(answer.get("by").asText()).isEqualTo(by.toUpperCase());
        return answer.get("rows");
    }

    /** Every row's name, in order, with its entries. */
    private List<String> shown(JsonNode rows) {
        List<String> lines = new ArrayList<>();
        rows.forEach(r -> lines.add(r.get("name").asText() + " " + r.get("entries").asLong()));
        return lines;
    }

    private void addsUpToTheSummary(String by, String query, String ownFilter) throws IOException {
        JsonNode rows = rows(by, query);
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal out = BigDecimal.ZERO;
        for (JsonNode row : rows) {
            for (JsonNode c : row.get("currencies")) {
                in = in.add(new BigDecimal(c.get("incoming").asText()));
                out = out.add(new BigDecimal(c.get("outgoing").asText()));
            }
        }
        Map<String, BigDecimal> whole = summary(query);
        assertThat(in).as(by + " " + query + ": In").isEqualByComparingTo(whole.get("incoming"));
        assertThat(out).as(by + " " + query + ": Out").isEqualByComparingTo(whole.get("outgoing"));
        // The tab's own filter changes nothing.
        assertThat(rows(by, (query + "&" + ownFilter).replaceFirst("^&", ""))).as("own filter").isEqualTo(rows);
    }

    @Test
    void eachTabsRowsAddUpToTheSummaryWithoutItsOwnFilter() throws IOException {
        for (String query : List.of("", "direction=EXPENSES", "direction=INCOME", "origin=FAMILY", "origin=PERSONAL",
                "from=2026-09-12&to=2026-09-12")) {
            addsUpToTheSummary("category", query, "categoryId=" + salary);
            addsUpToTheSummary("account", query, "accountId=" + cash);
            addsUpToTheSummary("payee", query, "payee=cafe ent2&counterpartyId=" + cafe);
        }
        // The other tabs' filters apply: the payee's narrows the categories and accounts, and the summary agrees.
        addsUpToTheSummary("category", "payee=shop ent2", "categoryId=" + salary);
        addsUpToTheSummary("account", "payee=shop ent2&direction=EXPENSES", "accountId=" + cash);
        addsUpToTheSummary("payee", "categoryId=" + eatingOut, "payee=cafe ent2");
    }

    @Test
    void theRowsAreSortedByEntriesThenByNameAndHoldTheNoneRow() throws IOException {
        JsonNode categories = rows("category", "");
        // Groceries: the three records' shares and payments and Bob's; Eating out three; Salary two; the transfer and
        // the settlement have no category.
        List<String> lines = shown(categories);
        List<Long> counts = new ArrayList<>();
        categories.forEach(r -> counts.add(r.get("entries").asLong()));
        assertThat(counts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(lines).contains("Eating out 3", "No category 2");
        JsonNode none = categories.get(lines.indexOf("No category 2"));
        assertThat(none.has("key")).isTrue();
        assertThat(none.get("key").isNull()).isTrue();
        // The none row's entries are transfers: no My share.
        assertThat(none.get("currencies")).isEmpty();
        JsonNode payees = rows("payee", "");
        List<String> names = shown(payees);
        assertThat(names).contains("Shop ENT2 4", "Cafe ENT2 1");
        assertThat(names.stream().filter(n -> n.startsWith("No payee")).count()).isOne();
        // A row's key is what a filter takes: an id for categories and accounts, a lower-case name for payees.
        for (JsonNode row : payees) {
            if (!row.get("key").isNull()) {
                assertThat(row.get("key").asText()).isEqualTo(row.get("key").asText().toLowerCase());
            }
        }
    }

    @Test
    void anAccountRowCountsItsEntriesAndMyShare() throws IOException {
        JsonNode accounts = rows("account", "");
        JsonNode cashRow = null;
        for (JsonNode row : accounts) {
            if (row.get("key").asText().equals(String.valueOf(cash))) {
                cashRow = row;
            }
        }
        assertThat(cashRow).isNotNull();
        assertThat(cashRow.get("entries").asLong()).isEqualTo(1);
        assertThat(cashRow.get("currencies")).hasSize(1);
        assertThat(new BigDecimal(cashRow.get("currencies").get(0).get("outgoing").asText())).isEqualByComparingTo("4.00");
        assertThat(new BigDecimal(cashRow.get("currencies").get(0).get("incoming").asText())).isEqualByComparingTo("0");
    }

    @Test
    void otherFiltersNarrowTheRowsAndBadValuesAre400() throws IOException {
        // Only expenses at the cafe: one category, one account.
        assertThat(shown(rows("category", "counterpartyId=" + cafe + "&direction=EXPENSES"))).containsExactly("Eating out 1");
        assertThat(shown(rows("account", "counterpartyId=" + cafe))).containsExactly("Cash 1");
        for (String query : List.of("", "by=", "by=bank", "by=category&direction=SIDEWAYS", "by=payee&accountId=x")) {
            assertThat(get(alice, "/api/entries/breakdown" + (query.isEmpty() ? "" : "?" + query)))
                    .as(query).hasStatus(HttpStatus.BAD_REQUEST);
        }
    }
}
