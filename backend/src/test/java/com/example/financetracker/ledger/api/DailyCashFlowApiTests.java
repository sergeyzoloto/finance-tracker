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
 * D-570's daily cash flow (UX-13), on {@link FamilyCashFlowScopeApiTests}' worked example: Mum (Alice) and Dad (Bob),
 * members with an account of "Home" (EUR), and Kid without one. In September 2026 Mum pays 15.00 for transport and
 * 25.00 for groceries of her own, on the Groceries she merged into the family budget; then the family records: Mum's
 * groceries of 60.00 and her refund of 9.00, Mum's salary of 300.00, Dad's rent of 90.00 USD, Dad's salary of 30.00,
 * and Kid's groceries of 12.00, each split equally among the three. Mum has a rate of 1.25 USD per euro; Dad has none.
 * <p>
 * Each day's rows, the days against the cash flow in every scope and both shapes, a refund's day, a missing rate, the
 * family budget filter and the refusals; and the demo's six months, in both shapes.
 */
class DailyCashFlowApiTests extends FamilyApiTest {

    private static final String SEPTEMBER = "from=2026-09-01&to=2026-09-30";
    private static final List<String> SCOPES = List.of("", "&scope=ALL", "&scope=PERSONAL", "&scope=FAMILY",
            "&scope=FAMILY_MINE", "&scope=FAMILY_OTHERS");

    @BeforeEach
    void september() throws IOException {
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "EUR", "quote": "USD", "rate": "1.25"}"""));
        long cash = accountId(alice, "CASH");
        created(post(alice, "/api/entries", """
                {"kind": "EXPENSE", "entryDate": "2026-09-05", "accountId": %d, "currency": "EUR", "amount": "15.00",
                 "categoryId": %d}""".formatted(cash, categoryId(alice, "TRANSPORT"))));
        created(post(alice, "/api/entries", """
                {"kind": "EXPENSE", "entryDate": "2026-09-06", "accountId": %d, "currency": "EUR", "amount": "25.00",
                 "categoryId": %d}""".formatted(cash, groceries)));
        String later = "\"paymentLater\": true,";
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "60.00", mum, later)));
        created(post(alice, uri + "/records", """
                {"date": "2026-09-11", "categoryId": %d, "amount": "9.00", "payerMemberId": %d, "paymentLater": true,
                 "refund": true}""".formatted(groceries, mum)));
        created(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-12", "categoryId": %d, "amount": "300.00", "payerMemberId": %d,
                 "paymentLater": true}""".formatted(salary, mum)));
        created(post(bob, uri + "/records", """
                {"date": "2026-09-13", "categoryId": %d, "amount": "90.00", "currency": "USD", "payerMemberId": %d,
                 "paymentLater": true}""".formatted(rent, dad)));
        created(post(bob, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-14", "categoryId": %d, "amount": "30.00", "payerMemberId": %d,
                 "paymentLater": true}""".formatted(salary, dad)));
        created(post(alice, uri + "/records", expense("2026-09-15", groceries, "12.00", kid, "")));
    }

    @Test
    void eachDayHoldsItsCategoriesRows() throws IOException {
        // Her refund's share is a day of its own below zero (D-79): 3.00 of the 9.00.
        assertThat(rows(alice, "")).containsExactly(
                "2026-09-05 TRANSPORT EUR 15.00", "2026-09-06 GROCERIES EUR 25.00", "2026-09-10 GROCERIES EUR 20.00",
                "2026-09-11 GROCERIES EUR -3.00", "2026-09-12 SALARY EUR 100.00", "2026-09-13 RENT USD 30.00",
                "2026-09-14 SALARY EUR 10.00", "2026-09-15 GROCERIES EUR 4.00");
        assertThat(rows(alice, "&scope=PERSONAL")).containsExactly(
                "2026-09-05 TRANSPORT EUR 15.00", "2026-09-06 GROCERIES EUR 25.00");
        assertThat(rows(alice, "&scope=FAMILY_MINE")).containsExactly(
                "2026-09-10 GROCERIES EUR 20.00", "2026-09-11 GROCERIES EUR -3.00", "2026-09-12 SALARY EUR 100.00");
        assertThat(rows(alice, "&scope=FAMILY_OTHERS")).containsExactly(
                "2026-09-13 RENT USD 30.00", "2026-09-14 SALARY EUR 10.00", "2026-09-15 GROCERIES EUR 4.00");
        assertThat(rows(bob, "&scope=PERSONAL")).isEmpty();
        // The family budget's categories say so, as in the cash flow.
        for (JsonNode row : ok(get(alice, "/api/reports/daily-cash-flow?" + SEPTEMBER + "&scope=FAMILY"))) {
            assertThat(row.get("familyLedgerId").asLong()).isEqualTo(family);
            assertThat(row.get("familyLedgerName").asText()).isEqualTo("Home");
        }
        JsonNode transport = ok(get(alice, "/api/reports/daily-cash-flow?from=2026-09-05&to=2026-09-05")).get(0);
        assertThat(transport.has("familyLedgerId")).isFalse();
        assertThat(transport.get("categoryName").asText()).isNotBlank();
        assertThat(transport.get("categoryType").asText()).isEqualTo("EXPENSE");
        // A range of one day, and of days with nothing.
        assertThat(rows(alice, "", "from=2026-09-13&to=2026-09-13")).containsExactly("2026-09-13 RENT USD 30.00");
        assertThat(rows(alice, "", "from=2026-09-16&to=2026-09-30")).isEmpty();
    }

    @Test
    void theDaysAddUpToTheCashFlowInEveryScope() throws IOException {
        for (String user : List.of(alice, bob)) {
            for (String scope : SCOPES) {
                checkDailyCashFlow(user, "2026-09-01", "2026-09-30", scope);
                checkDailyCashFlow(user, "2026-08-20", "2026-10-10", scope);
            }
            checkDailyCashFlow(user, "2026-09-01", "2026-09-30", "&scope=FAMILY&familyLedgerId=" + family);
            checkDailyCashFlow(user, "2026-09-01", "2026-09-30", "&scope=FAMILY_MINE&familyLedgerId=" + family);
        }
        assertThat(checkDailyCashFlow(alice, "2026-09-01", "2026-09-30", "")).isEqualTo(8);
    }

    @Test
    void inTheBaseCurrencyEachDayIsConvertedAtItsRateAndAMissingRateIsNull() throws IOException {
        JsonNode inBase = ok(get(alice, "/api/reports/daily-cash-flow?" + SEPTEMBER + "&currency=BASE"));
        assertThat(inBase.get("currency").asText()).isEqualTo("EUR");
        assertThat(inBase.has("exchangeResults")).isFalse();
        List<String> rows = new ArrayList<>();
        for (JsonNode row : inBase.get("rows")) {
            rows.add(row.get("day").asText() + " " + row.get("categoryCode").asText() + " "
                    + row.get("total").asText());
        }
        assertThat(rows).containsExactly("2026-09-05 TRANSPORT 15.00", "2026-09-06 GROCERIES 25.00",
                "2026-09-10 GROCERIES 20.00", "2026-09-11 GROCERIES -3.00", "2026-09-12 SALARY 100.00",
                "2026-09-13 RENT 24.00", "2026-09-14 SALARY 10.00", "2026-09-15 GROCERIES 4.00");
        JsonNode rent = find(inBase.get("rows"), "categoryCode", "RENT");
        assertThat(rent.get("rates")).hasSize(1);
        assertThat(rent.get("rates").get(0).get("currency").asText()).isEqualTo("USD");
        assertThat(rent.get("missingRates")).isEmpty();
        assertThat(find(inBase.get("rows"), "categoryCode", "TRANSPORT").get("rates")).isEmpty();

        // Dad has no dollar rate: his rent's day is null, saying why, and the month's figure too.
        JsonNode his = ok(get(bob, "/api/reports/daily-cash-flow?" + SEPTEMBER + "&currency=BASE&scope=FAMILY_MINE"));
        JsonNode missing = find(his.get("rows"), "categoryCode", "RENT");
        assertThat(missing.get("day").asText()).isEqualTo("2026-09-13");
        assertThat(missing.get("total").isNull()).isTrue();
        assertThat(missing.get("missingRates").get(0).get("currency").asText()).isEqualTo("USD");
        assertThat(find(his.get("rows"), "categoryCode", "SALARY").get("total").asText()).isEqualTo("10.00");
        checkDailyCashFlow(bob, "2026-09-01", "2026-09-30", "&scope=FAMILY_MINE");
    }

    @Test
    void aRangeIsCheckedAndAFamilyBudgetIsTheUsersOwn() throws IOException {
        String path = "/api/reports/daily-cash-flow?";
        for (String shape : List.of("", "&currency=BASE")) {
            assertThat(detail(get(alice, path + "from=2026-09-02&to=2026-09-01" + shape), HttpStatus.BAD_REQUEST))
                    .isEqualTo("'from' must not be after 'to'.");
            // 366 days, a leap year's, answer; 367 don't.
            assertThat(get(alice, path + "from=2024-01-01&to=2024-12-31" + shape)).hasStatus(HttpStatus.OK);
            assertThat(get(alice, path + "from=2026-01-01&to=2027-01-01" + shape)).hasStatus(HttpStatus.OK);
            assertThat(detail(get(alice, path + "from=2026-01-01&to=2027-01-02" + shape), HttpStatus.BAD_REQUEST))
                    .isEqualTo("The daily cash flow covers at most 366 days.");
            assertThat(get(alice, path + "from=2026-09-01" + shape)).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(alice, path + "to=2026-09-01" + shape)).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(alice, path + SEPTEMBER + shape + "&scope=MINE")).hasStatus(HttpStatus.BAD_REQUEST);
            // A family budget goes with a family scope only (D-308).
            assertThat(get(alice, path + SEPTEMBER + shape + "&familyLedgerId=" + family))
                    .hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(alice, path + SEPTEMBER + shape + "&scope=PERSONAL&familyLedgerId=" + family))
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }
        assertThat(get(alice, path + SEPTEMBER + "&currency=EUR")).hasStatus(HttpStatus.BAD_REQUEST);

        // Another user's family budget, a personal ledger and an unknown id: the answer for a missing ledger.
        String carol = newUser();
        long theirs = newFamily(carol, """
                {"name": "Cottage", "baseCurrency": "EUR", "displayName": "Carol"}""").get("id").asLong();
        long personal = jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(alice).query(Long.class).single();
        for (long id : List.of(theirs, personal, 999_999_999L)) {
            for (String shape : List.of("", "&currency=BASE")) {
                assertThat(detail(get(alice, path + SEPTEMBER + shape + "&scope=FAMILY&familyLedgerId=" + id),
                        HttpStatus.NOT_FOUND)).isEqualTo("Ledger %d not found.".formatted(id));
            }
        }
        // Nobody else's entries: Carol has none, and neither has Alice in Carol's budget.
        assertThat(ok(get(carol, path + SEPTEMBER))).isEmpty();
        assertThat(ok(get(carol, path + SEPTEMBER + "&currency=BASE")).get("rows")).isEmpty();
        assertThat(rows(alice, "&scope=FAMILY&familyLedgerId=" + family)).isEqualTo(rows(alice, "&scope=FAMILY"));
    }

    /** The demo's six months in EUR and USD with its family budget: the days against the months in both shapes. */
    @Test
    void theDemosDaysAddUpToItsMonths() throws IOException {
        String user = newUser();
        // Long before the demo starts, as DemoDataApiTests, so that every dollar amount has a rate.
        ok(post(user, "/api/rates/manual", """
                {"date": "2020-01-01", "base": "EUR", "quote": "USD", "rate": "1.10"}"""));
        JsonNode demo = ok(post(user, "/api/demo-data", null));
        String from = demo.get("from").asText();
        String to = demo.get("to").asText();
        for (String scope : List.of("", "&scope=PERSONAL", "&scope=FAMILY", "&scope=FAMILY_MINE",
                "&scope=FAMILY_OTHERS")) {
            assertThat(checkDailyCashFlow(user, from, to, scope)).as(scope).isPositive();
        }
        JsonNode inBase = ok(get(user, "/api/reports/daily-cash-flow?from=%s&to=%s&currency=BASE".formatted(from, to)));
        assertThat(inBase.get("rows").findValues("total")).isNotEmpty().noneMatch(JsonNode::isNull);
        assertThat(inBase.get("rows").findValuesAsText("day")).allMatch(day -> day.compareTo(from) >= 0
                && day.compareTo(to) <= 0);
    }

    /** The user's September daily rows as "day CODE CURRENCY total", in the report's order. */
    private List<String> rows(String user, String query) throws IOException {
        return rows(user, query, SEPTEMBER);
    }

    private List<String> rows(String user, String query, String period) throws IOException {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : ok(get(user, "/api/reports/daily-cash-flow?" + period + query))) {
            rows.add(row.get("day").asText() + " " + row.get("categoryCode").asText() + " "
                    + row.get("currency").asText() + " " + row.get("total").asText());
        }
        return rows;
    }
}
