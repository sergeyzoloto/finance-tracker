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
 * D-302's scopes of the personal cash flow (UX-1), on a worked example: Mum (Alice) and Dad (Bob), members with an
 * account of "Home" (EUR), and Kid without one. In September 2026 Mum pays 15.00 for transport and 25.00 for groceries
 * of her own, on the Groceries she merged into the family budget; then the family records: Mum's groceries of 60.00 and
 * her refund of 9.00, Mum's salary of 300.00, Dad's rent of 90.00 USD, Dad's salary of 30.00, and Kid's groceries of
 * 12.00, each split equally among the three. Mum has a rate of 1.25 USD per euro.
 * <p>
 * Each scope's rows, then the parts against ALL in both shapes, the exchange results under ALL only, the family budget
 * filter, a detached link (Dad leaves) still counted by its record, and the refusals; and the debt accounts' family
 * budget on the balances (D-299). D-421 (UX-6): FAMILY, the user's shares of every family record, in both shapes, with
 * and without a family budget, and its refusals.
 */
class FamilyCashFlowScopeApiTests extends FamilyApiTest {

    private static final String SEPTEMBER = "from=2026-09-01&to=2026-09-30";

    @BeforeEach
    void september() throws IOException {
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "EUR", "quote": "USD", "rate": "1.25"}"""));
        long cash = accountId(alice, "CASH");
        created(post(alice, "/api/entries", """
                {"kind": "EXPENSE", "entryDate": "2026-09-05", "accountId": %d, "currency": "EUR", "amount": "15.00",
                 "categoryId": %d}""".formatted(cash, categoryId(alice, "TRANSPORT"))));
        // A personal bill on a category merged into the family budget is personal (D-302).
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
    void eachScopeHoldsItsOwnRows() throws IOException {
        assertThat(rows(alice, "")).containsExactly(
                "GROCERIES EUR 46.00", "RENT USD 30.00", "TRANSPORT EUR 15.00", "SALARY EUR 110.00");
        assertThat(rows(alice, "&scope=ALL")).isEqualTo(rows(alice, ""));
        // Her own entries, the groceries on the family's category among them.
        assertThat(rows(alice, "&scope=PERSONAL")).containsExactly("GROCERIES EUR 25.00", "TRANSPORT EUR 15.00");
        // Her shares of what she paid or received: 20.00 less 3.00 of the refund, and a third of her salary.
        assertThat(rows(alice, "&scope=FAMILY_MINE")).containsExactly("GROCERIES EUR 17.00", "SALARY EUR 100.00");
        // Her shares of Dad's and Kid's records, Kid without an account included.
        assertThat(rows(alice, "&scope=FAMILY_OTHERS")).containsExactly(
                "GROCERIES EUR 4.00", "RENT USD 30.00", "SALARY EUR 10.00");

        assertThat(rows(bob, "&scope=PERSONAL")).isEmpty();
        assertThat(rows(bob, "&scope=FAMILY_MINE")).containsExactly("RENT USD 30.00", "SALARY EUR 10.00");
        assertThat(rows(bob, "&scope=FAMILY_OTHERS")).containsExactly("GROCERIES EUR 21.00", "SALARY EUR 100.00");
        // The family budget's rows say so, as in the whole cash flow.
        for (JsonNode row : ok(get(alice, "/api/reports/cash-flow?" + SEPTEMBER + "&scope=FAMILY_OTHERS"))) {
            assertThat(row.get("familyLedgerId").asLong()).isEqualTo(family);
        }
        checkCashFlowScopes(alice);
        checkCashFlowScopes(bob);
    }

    @Test
    void inTheBaseCurrencyThePartsAddUpAndExchangeResultsAreAllsOnly() throws IOException {
        assertThat(baseRows(alice, "")).containsExactly(
                "GROCERIES 46.00", "RENT 24.00", "TRANSPORT 15.00", "SALARY 110.00");
        assertThat(baseRows(alice, "&scope=PERSONAL")).containsExactly("GROCERIES 25.00", "TRANSPORT 15.00");
        assertThat(baseRows(alice, "&scope=FAMILY_MINE")).containsExactly("GROCERIES 17.00", "SALARY 100.00");
        assertThat(baseRows(alice, "&scope=FAMILY_OTHERS")).containsExactly(
                "GROCERIES 4.00", "RENT 24.00", "SALARY 10.00");
        String path = "/api/reports/cash-flow?" + SEPTEMBER + "&currency=BASE";
        assertThat(ok(get(alice, path)).get("exchangeResults")).hasSize(1);
        for (String scope : List.of("PERSONAL", "FAMILY_MINE", "FAMILY_OTHERS")) {
            assertThat(ok(get(alice, path + "&scope=" + scope)).get("exchangeResults")).as(scope).isEmpty();
        }
    }

    @Test
    void aFamilyBudgetNarrowsTheFamilyScopes() throws IOException {
        String carol = newUser();
        long theirs = newFamily(carol, """
                {"name": "Cottage", "baseCurrency": "EUR", "displayName": "Carol"}""").get("id").asLong();
        long cottage = newFamily(alice, """
                {"name": "Cottage of Mum's", "baseCurrency": "EUR", "displayName": "Mum"}""").get("id").asLong();

        assertThat(rows(alice, "&scope=FAMILY_MINE&familyLedgerId=" + family))
                .isEqualTo(rows(alice, "&scope=FAMILY_MINE"));
        assertThat(rows(alice, "&scope=FAMILY_OTHERS&familyLedgerId=" + family))
                .isEqualTo(rows(alice, "&scope=FAMILY_OTHERS"));
        assertThat(rows(alice, "&scope=FAMILY_MINE&familyLedgerId=" + cottage)).isEmpty();
        assertThat(ok(get(alice, "/api/reports/cash-flow?" + SEPTEMBER
                + "&currency=BASE&scope=FAMILY_OTHERS&familyLedgerId=" + cottage)).get("rows")).isEmpty();

        // Another user's family budget, a personal ledger and an unknown id: the answer for a missing ledger.
        long personal = jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(alice).query(Long.class).single();
        for (long id : List.of(theirs, personal, 999_999_999L)) {
            for (String shape : List.of("", "&currency=BASE")) {
                assertThat(detail(get(alice, "/api/reports/cash-flow?" + SEPTEMBER + shape
                        + "&scope=FAMILY_MINE&familyLedgerId=" + id), HttpStatus.NOT_FOUND))
                        .isEqualTo("Ledger %d not found.".formatted(id));
            }
        }
        // A family budget goes with a family scope only (D-308), and a scope is one of the five.
        assertThat(get(alice, "/api/reports/cash-flow?" + SEPTEMBER + "&familyLedgerId=" + family))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(alice, "/api/reports/cash-flow?" + SEPTEMBER + "&scope=PERSONAL&familyLedgerId=" + family))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(alice, "/api/reports/cash-flow?" + SEPTEMBER + "&scope=MINE"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    /** D-421: FAMILY is FAMILY_MINE and FAMILY_OTHERS together, row by row, whoever paid or received the record. */
    @Test
    void theFamilyScopeIsEveryRecordsShare() throws IOException {
        // Her groceries: 17.00 of her own records and 4.00 of Kid's; Dad's rent; her salary's third and Dad's.
        assertThat(rows(alice, "&scope=FAMILY")).containsExactly(
                "GROCERIES EUR 21.00", "RENT USD 30.00", "SALARY EUR 110.00");
        assertThat(rows(bob, "&scope=FAMILY")).containsExactly(
                "GROCERIES EUR 21.00", "RENT USD 30.00", "SALARY EUR 110.00");
        assertThat(baseRows(alice, "&scope=FAMILY")).containsExactly("GROCERIES 21.00", "RENT 24.00", "SALARY 110.00");
        assertThat(ok(get(alice, "/api/reports/cash-flow?" + SEPTEMBER + "&currency=BASE&scope=FAMILY"))
                .get("exchangeResults")).isEmpty();
        for (JsonNode row : ok(get(alice, "/api/reports/cash-flow?" + SEPTEMBER + "&scope=FAMILY"))) {
            assertThat(row.get("familyLedgerId").asLong()).isEqualTo(family);
        }

        // With a family budget: this one's records, another of hers has none.
        long cottage = newFamily(alice, """
                {"name": "Cottage of Mum's", "baseCurrency": "EUR", "displayName": "Mum"}""").get("id").asLong();
        assertThat(rows(alice, "&scope=FAMILY&familyLedgerId=" + family)).isEqualTo(rows(alice, "&scope=FAMILY"));
        assertThat(baseRows(alice, "&scope=FAMILY&familyLedgerId=" + family))
                .isEqualTo(baseRows(alice, "&scope=FAMILY"));
        assertThat(rows(alice, "&scope=FAMILY&familyLedgerId=" + cottage)).isEmpty();
        assertThat(baseRows(alice, "&scope=FAMILY&familyLedgerId=" + cottage)).isEmpty();
        assertThat(ok(get(alice, "/api/reports/cash-flow?" + SEPTEMBER
                + "&currency=BASE&scope=FAMILY&familyLedgerId=" + family)).get("exchangeResults")).isEmpty();

        // Another user's family budget, a personal ledger and an unknown id: the answer for a missing ledger.
        String carol = newUser();
        long theirs = newFamily(carol, """
                {"name": "Cottage", "baseCurrency": "EUR", "displayName": "Carol"}""").get("id").asLong();
        long personal = jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(alice).query(Long.class).single();
        for (long id : List.of(theirs, personal, 999_999_999L)) {
            for (String shape : List.of("", "&currency=BASE")) {
                assertThat(detail(get(alice, "/api/reports/cash-flow?" + SEPTEMBER + shape
                        + "&scope=FAMILY&familyLedgerId=" + id), HttpStatus.NOT_FOUND))
                        .isEqualTo("Ledger %d not found.".formatted(id));
            }
        }
        checkCashFlowScopes(alice);
        checkCashFlowScopes(bob);
    }

    /** A member who left keeps their shares, which still count by their records (D-302), whatever D-33 did. */
    @Test
    void aDetachedShareStillCountsByItsRecord() throws IOException {
        assertThat(totals(bob, "&scope=FAMILY_MINE")).containsExactly("EUR 10.00", "USD 30.00");
        assertThat(totals(bob, "&scope=FAMILY_OTHERS")).containsExactly("EUR 100.00", "EUR 21.00");
        assertThat(totals(bob, "&scope=FAMILY")).containsExactly("EUR 110.00", "EUR 21.00", "USD 30.00");
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);

        // D-33 moved his shares to personal copies of the family's categories (with D-30's codes where his own take
        // the family's), so the rows lose the budget's mark, and keep their amounts and scopes.
        assertThat(totals(bob, "&scope=FAMILY_MINE")).containsExactly("EUR 10.00", "USD 30.00");
        assertThat(totals(bob, "&scope=FAMILY_OTHERS")).containsExactly("EUR 100.00", "EUR 21.00");
        assertThat(totals(bob, "&scope=FAMILY")).containsExactly("EUR 110.00", "EUR 21.00", "USD 30.00");
        assertThat(rows(bob, "&scope=PERSONAL")).isEmpty();
        assertThat(ok(get(bob, "/api/reports/cash-flow?" + SEPTEMBER + "&scope=FAMILY_MINE")).get(0)
                .has("familyLedgerId")).isFalse();
        // And it is no budget of his to choose any more.
        assertThat(get(bob, "/api/reports/cash-flow?" + SEPTEMBER + "&scope=FAMILY_MINE&familyLedgerId=" + family))
                .hasStatus(HttpStatus.NOT_FOUND);
        checkCashFlowScopes(bob);
    }

    /** D-299: a debt account names its family budget in both shapes of the balances, and no other account does. */
    @Test
    void aDebtAccountNamesItsFamilyBudget() throws IOException {
        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        for (String shape : List.of("", "?currency=BASE")) {
            JsonNode balances = ok(get(alice, "/api/reports/balances" + shape));
            for (JsonNode balance : balances) {
                if (balance.get("accountId").asLong() == debt) {
                    assertThat(balance.get("familyLedgerId").asLong()).isEqualTo(family);
                    assertThat(balance.get("familyLedgerName").asText()).isEqualTo("Home");
                } else {
                    assertThat(balance.has("familyLedgerId") || balance.has("familyLedgerName"))
                            .as(balance.get("accountCode").asText()).isFalse();
                }
            }
        }
        // In the base currency, the debt in euros and dollars as one figure: her expense shares of 21.00 and 30.00 USD
        // (24.00), less the 51.00 she paid net of the refund, plus the 300.00 she received less her 110.00 of incomes.
        JsonNode inBase = find(ok(get(alice, "/api/reports/balances?currency=BASE")), "accountId", String.valueOf(debt));
        assertThat(inBase.get("balance").asText()).isEqualTo("184.00");
        assertThat(inBase.get("rates")).hasSize(1);

        // Once Bob left, his former debt account is an ordinary liability: no budget named.
        long bobsDebt = accountId(bob, "FAMILY_DEBT_" + family);
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode his = find(ok(get(bob, "/api/reports/balances")), "accountId", String.valueOf(bobsDebt));
        assertThat(his.has("familyLedgerId")).isFalse();
    }

    /** The user's September cash flow rows as "CODE CURRENCY total", in the report's order. */
    private List<String> rows(String user, String query) throws IOException {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : ok(get(user, "/api/reports/cash-flow?" + SEPTEMBER + query))) {
            rows.add(row.get("categoryCode").asText() + " " + row.get("currency").asText() + " "
                    + row.get("total").asText());
        }
        return rows;
    }

    /** As {@link #rows} without the categories: "CURRENCY total", sorted. */
    private List<String> totals(String user, String query) throws IOException {
        return rows(user, query).stream().map(row -> row.substring(row.indexOf(' ') + 1)).sorted().toList();
    }

    /** As {@link #rows} in the base currency: "CODE total". */
    private List<String> baseRows(String user, String query) throws IOException {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : ok(get(user, "/api/reports/cash-flow?" + SEPTEMBER + "&currency=BASE" + query))
                .get("rows")) {
            rows.add(row.get("categoryCode").asText() + " " + row.get("total").asText());
        }
        return rows;
    }
}
