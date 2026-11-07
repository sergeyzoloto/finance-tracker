package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * /api/reports over a small ledger of Alice's, written through the API. Bob, a user of his own, must never see any
 * of it. The expected figures are worked out by hand from {@link #writeAlicesLedger}.
 */
class ReportApiTests extends LedgerApiTest {

    private static final String AUGUST = "?from=2026-08-01&to=2026-08-31";
    private static final String AS_OF = "?asOf=2026-08-31";

    private final String alice = newUser();
    private final String bob = newUser();

    @BeforeEach
    void writeAlicesLedger() throws IOException {
        long cash = accountId(alice, "CASH");
        long current = accountId(alice, "CURRENT_ACCOUNT");
        long groceries = categoryId(alice, "GROCERIES");
        newEntry(alice, """
                {"kind": "OPENING_BALANCE", "entryDate": "2026-08-01", "accountId": %d, "currency": "EUR",
                 "amount": "200"}""".formatted(cash));
        newEntry(alice, """
                {"kind": "INCOME", "entryDate": "2026-08-02", "accountId": %d, "currency": "EUR", "amount": "1000",
                 "categoryId": %d}""".formatted(current, categoryId(alice, "SALARY")));
        newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "2026-08-03", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "40", "categoryId": %d}""".formatted(newCounterparty(alice, "Shop"), cash, groceries));
        newEntry(alice, """
                {"kind": "LOAN_GIVEN", "entryDate": "2026-08-04", "fromAccountId": %d, "counterpartyId": %d,
                 "currency": "EUR", "amount": "100"}""".formatted(current, newCounterparty(alice, "Friend")));
        newEntry(alice, """
                {"kind": "SHARED_EXPENSE", "entryDate": "2026-08-05", "accountId": %d, "currency": "EUR",
                 "total": "60", "categoryId": %d}""".formatted(current, groceries));
        // Bob's first request gives him his own starter ledger.
        ok(get(bob, "/api/accounts"));
    }

    @Test
    void reportsShowTheUsersOwnLedger() throws IOException {
        JsonNode balances = ok(get(alice, "/api/reports/balances" + AS_OF));
        assertThat(rows(balances, "accountCode", "currency", "balance")).containsExactly(
                "CASH EUR 160.00", "CREDITOR_DEBT EUR 0.00", "CURRENT_ACCOUNT EUR 840.00", "FAMILY_DEBT EUR -30.00",
                "LOANS_ASSET EUR 100.00", "OPENING_BALANCE EUR 200.00", "RESERVE EUR 0.00", "SAVINGS_ACCOUNT EUR 0.00",
                "UNALLOCATED EUR 930.00");
        assertThat(balances.get(0).get("balance").isTextual()).isTrue();

        assertThat(rows(ok(get(alice, "/api/reports/cash-flow" + AUGUST)), "month", "categoryCode", "total"))
                .containsExactly("2026-08 GROCERIES 70.00", "2026-08 SALARY 1000.00");
        assertThat(rows(ok(get(alice, "/api/reports/counterparty-balances" + AS_OF + "&accountCode=LOANS_ASSET")),
                "counterpartyName", "currency", "balance")).containsExactly("Friend EUR 100.00");
        assertThat(rows(ok(get(alice, "/api/reports/net-worth" + AS_OF)), "currency", "assets", "liabilities",
                "netWorth")).containsExactly("EUR 1100.00 -30.00 1130.00");
        assertThat(rows(ok(get(alice, "/api/reports/shared-settlement" + AS_OF)), "accountCode", "balance",
                "direction")).containsExactly("FAMILY_DEBT -30.00 USER_IS_OWED");
        assertThat(ok(get(alice, "/api/reports/integrity"))).isEmpty();
        // Without asOf, as of today.
        assertThat(ok(get(alice, "/api/reports/balances"))).isEqualTo(balances);
        // Before the ledger starts.
        assertThat(rows(ok(get(alice, "/api/reports/balances?asOf=2026-07-31")), "balance")).containsOnly("0.00");
    }

    @Test
    void reportsNeverIncludeAnotherUsersData() throws IOException {
        List<Long> bobsAccounts = StreamSupport.stream(ok(get(bob, "/api/accounts")).spliterator(), false)
                .map(account -> account.get("id").asLong())
                .toList();

        JsonNode balances = ok(get(bob, "/api/reports/balances" + AS_OF));
        assertThat(balances).isNotEmpty();
        assertThat(balances.findValuesAsText("accountId")).allMatch(id -> bobsAccounts.contains(Long.valueOf(id)));
        assertThat(rows(balances, "balance")).containsOnly("0.00");
        assertThat(ok(get(bob, "/api/reports/cash-flow" + AUGUST))).isEmpty();
        assertThat(ok(get(bob, "/api/reports/counterparty-balances" + AS_OF + "&accountCode=LOANS_ASSET"))).isEmpty();
        assertThat(ok(get(bob, "/api/reports/net-worth" + AS_OF))).isEmpty();
        assertThat(ok(get(bob, "/api/reports/shared-settlement" + AS_OF))).isEmpty();
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();
    }

    /** Alice's ledger is in euros, her base currency, so the figures are the same and nothing needs a rate. */
    @Test
    void reportsInTheBaseCurrency() throws IOException {
        JsonNode balances = ok(get(alice, "/api/reports/balances" + AS_OF + "&currency=BASE"));
        assertThat(rows(balances, "accountCode", "currency", "balance")).containsExactly(
                "CASH EUR 160.00", "CREDITOR_DEBT EUR 0.00", "CURRENT_ACCOUNT EUR 840.00", "FAMILY_DEBT EUR -30.00",
                "LOANS_ASSET EUR 100.00", "OPENING_BALANCE EUR 200.00", "RESERVE EUR 0.00", "SAVINGS_ACCOUNT EUR 0.00",
                "UNALLOCATED EUR 930.00");
        assertThat(balances.get(0).get("missingRates")).isEmpty();

        assertThat(ok(get(alice, "/api/reports/net-worth" + AS_OF + "&currency=BASE"))).isEqualTo(Answers.json("""
                {"currency":"EUR","assets":"1100.00","liabilities":"-30.00","netWorth":"1130.00",\
                "unrealizedRevaluation":"0.00","realizedExchangeResult":"0.00","rates":[],"missingRates":[]}"""));

        JsonNode cashFlow = ok(get(alice, "/api/reports/cash-flow" + AUGUST + "&currency=BASE"));
        assertThat(cashFlow.get("currency").asText()).isEqualTo("EUR");
        assertThat(rows(cashFlow.get("rows"), "month", "categoryCode", "total"))
                .containsExactly("2026-08 GROCERIES 70.00", "2026-08 SALARY 1000.00");
        assertThat(rows(cashFlow.get("exchangeResults"), "month", "realized", "unrealized"))
                .containsExactly("2026-08 0.00 0.00");
    }

    /**
     * UX-2, D-355: an archived account is listed, marked, in a currency it still holds money in, so that the balances
     * of each type add up to net worth's assets, liabilities and net worth, which count archived accounts; an archived
     * account without money is not listed.
     */
    @Test
    void archivedAccountsWithMoneyAreListedAndMarked() throws IOException {
        ok(patch(alice, "/api/accounts/" + accountId(alice, "CASH"), """
                {"archived": true}"""));
        ok(patch(alice, "/api/accounts/" + accountId(alice, "SAVINGS_ACCOUNT"), """
                {"archived": true}"""));

        JsonNode balances = ok(get(alice, "/api/reports/balances" + AS_OF));
        assertThat(rows(balances, "accountCode", "currency", "balance", "archived")).containsExactly(
                "CASH EUR 160.00 true", "CREDITOR_DEBT EUR 0.00 false", "CURRENT_ACCOUNT EUR 840.00 false",
                "FAMILY_DEBT EUR -30.00 false", "LOANS_ASSET EUR 100.00 false", "OPENING_BALANCE EUR 200.00 false",
                "RESERVE EUR 0.00 false", "UNALLOCATED EUR 930.00 false");
        assertThat(rows(ok(get(alice, "/api/reports/balances" + AS_OF + "&currency=BASE")), "accountCode", "balance",
                "archived")).containsExactly(
                "CASH 160.00 true", "CREDITOR_DEBT 0.00 false", "CURRENT_ACCOUNT 840.00 false", "FAMILY_DEBT -30.00 false",
                "LOANS_ASSET 100.00 false", "OPENING_BALANCE 200.00 false", "RESERVE 0.00 false",
                "UNALLOCATED 930.00 false");
        // Before the cash had any money, the archived account isn't listed.
        assertThat(rows(ok(get(alice, "/api/reports/balances?asOf=2026-07-31")), "accountCode"))
                .doesNotContain("CASH", "SAVINGS_ACCOUNT");
        // Assets 160 + 840 + 100 and liabilities -30, as net worth has them.
        JsonNode worth = ok(get(alice, "/api/reports/net-worth" + AS_OF + "&currency=BASE"));
        assertThat(List.of(worth.get("assets").asText(), worth.get("liabilities").asText(),
                worth.get("netWorth").asText())).containsExactly("1100.00", "-30.00", "1130.00");
    }

    /** Alice has no rate for USD: the balance is missing, and says why. */
    @Test
    void aFigureWithoutARateIsNullInTheBaseCurrency() throws IOException {
        newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "2026-08-06", "accountId": %d, "currency": "USD", "amount": "10",
                 "categoryId": %d}""".formatted(accountId(alice, "CASH"), categoryId(alice, "GROCERIES")));

        JsonNode cash = find(ok(get(alice, "/api/reports/balances" + AS_OF + "&currency=BASE")), "accountCode", "CASH");
        assertThat(cash.get("balance").isNull()).isTrue();
        assertThat(cash.get("missingRates")).isEqualTo(Answers.json("""
                [{"currency":"USD","from":"2026-08-31","to":"2026-08-31","days":1}]"""));
        assertThat(ok(get(alice, "/api/reports/net-worth" + AS_OF + "&currency=BASE")).get("netWorth").isNull())
                .isTrue();
    }

    /**
     * D-298: open loans in the base currency, one row per counterparty, its currencies added up at the rate on the day
     * with the rates used and the amounts in their own currencies; a currency without a rate leaves the row null.
     */
    @Test
    void openLoansInTheBaseCurrency() throws IOException {
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-08-01", "base": "EUR", "quote": "USD", "rate": "1.25"}"""));
        long current = accountId(alice, "CURRENT_ACCOUNT");
        long friend = find(ok(get(alice, "/api/counterparties")), "name", "Friend").get("id").asLong();
        newEntry(alice, """
                {"kind": "LOAN_GIVEN", "entryDate": "2026-08-06", "fromAccountId": %d, "counterpartyId": %d,
                 "currency": "USD", "amount": "50"}""".formatted(current, friend));
        newEntry(alice, """
                {"kind": "LOAN_GIVEN", "entryDate": "2026-08-07", "fromAccountId": %d, "counterpartyId": %d,
                 "currency": "RUB", "amount": "1000"}""".formatted(current, newCounterparty(alice, "Neighbour")));

        JsonNode loans = ok(get(alice, "/api/reports/counterparty-balances" + AS_OF
                + "&accountCode=LOANS_ASSET&currency=BASE"));
        assertThat(rows(loans, "counterpartyName", "currency", "balance"))
                .containsExactly("Friend EUR 140.00", "Neighbour EUR null");
        JsonNode friends = loans.get(0);
        assertThat(rows(friends.get("amounts"), "currency", "balance")).containsExactly("EUR 100.00", "USD 50.00");
        assertThat(rows(friends.get("rates"), "currency", "perEuro", "source"))
                .containsExactly("USD 1.25 MANUAL");
        assertThat(friends.get("missingRates")).isEmpty();
        JsonNode neighbours = loans.get(1);
        assertThat(rows(neighbours.get("amounts"), "currency", "balance")).containsExactly("RUB 1000.00");
        assertThat(neighbours.get("missingRates")).isEqualTo(Answers.json("""
                [{"currency":"RUB","from":"2026-08-31","to":"2026-08-31","days":1}]"""));
        // The report per currency is as before, and the base currency's shape answers only to BASE.
        assertThat(rows(ok(get(alice, "/api/reports/counterparty-balances" + AS_OF + "&accountCode=LOANS_ASSET")),
                "counterpartyName", "currency", "balance"))
                .containsExactly("Friend EUR 100.00", "Friend USD 50.00", "Neighbour RUB 1000.00");
        assertThat(get(alice, "/api/reports/counterparty-balances" + AS_OF + "&accountCode=LOANS_ASSET&currency=EUR"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(ok(get(bob, "/api/reports/counterparty-balances" + AS_OF
                + "&accountCode=LOANS_ASSET&currency=BASE"))).isEmpty();
    }

    @Test
    void reportParametersAreChecked() throws IOException {
        assertThat(body(get(alice, "/api/reports/cash-flow?from=2026-08-01"), HttpStatus.BAD_REQUEST)
                .get("errors").get(0).get("field").asText()).isEqualTo("to");
        assertThat(body(get(alice, "/api/reports/cash-flow?from=2026-09-01&to=2026-08-01"), HttpStatus.BAD_REQUEST)
                .get("detail").asText()).isEqualTo("'from' must not be after 'to'.");
        assertThat(body(get(alice, "/api/reports/counterparty-balances?accountCode=NO_SUCH"), HttpStatus.NOT_FOUND)
                .get("detail").asText()).isEqualTo("Account NO_SUCH not found.");
        assertThat(body(get(alice, "/api/reports/counterparty-balances?accountCode=CASH"), HttpStatus.BAD_REQUEST)
                .get("detail").asText()).isEqualTo("Account CASH has no balances per counterparty: it doesn't "
                        + "require one.");
        for (String report : new String[] {"balances?", "net-worth?", "cash-flow" + AUGUST + "&"}) {
            assertThat(body(get(alice, "/api/reports/" + report + "currency=USD"), HttpStatus.BAD_REQUEST)
                    .get("errors")).as(report).isEqualTo(Answers.json("""
                    [{"field":"currency","message":"must be BASE, or left out"}]"""));
        }
    }

    /** Each element of the array as the fields' values, separated by spaces. */
    private static List<String> rows(JsonNode array, String... fields) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(row -> String.join(" ", Arrays.stream(fields).map(f -> row.get(f).asText()).toList()))
                .toList();
    }
}
