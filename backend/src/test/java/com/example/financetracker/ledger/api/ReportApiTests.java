package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

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
    }

    /** Each element of the array as the fields' values, separated by spaces. */
    private static List<String> rows(JsonNode array, String... fields) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(row -> String.join(" ", Arrays.stream(fields).map(f -> row.get(f).asText()).toList()))
                .toList();
    }
}
