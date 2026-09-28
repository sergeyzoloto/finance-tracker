package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * POST /api/demo-data: the demo ledger for a new user, in one transaction, and only into an empty ledger. The entries
 * go through {@link EntryService}, spied on here so that one test can make it fail halfway.
 */
class DemoDataApiTests extends LedgerApiTest {

    @MockitoSpyBean
    private EntryService entries;

    @Test
    void loadsSixMonthsOfEveryKindOfEntryThatPassTheIntegrityCheckAndEveryReport() throws IOException {
        String user = newUser();
        // Long before the demo starts, so that every dollar amount has a rate in the base currency.
        ok(post(user, "/api/rates/manual", """
                {"date": "2020-01-01", "base": "EUR", "quote": "USD", "rate": "1.10"}"""));
        LocalDate today = LocalDate.now();

        JsonNode demo = ok(post(user, "/api/demo-data", null));

        assertThat(demo.get("entriesByKind").properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "EXPENSE", "INCOME", "TRANSFER", "SHARED_EXPENSE", "LOAN_GIVEN", "LOAN_REPAID", "CURRENCY_EXCHANGE",
                "OPENING_BALANCE", "MANUAL");
        int total = demo.get("entriesByKind").properties().stream().mapToInt(kind -> kind.getValue().asInt()).sum();
        LocalDate from = LocalDate.parse(demo.get("from").asText());
        assertThat(from.getDayOfMonth()).isOne();
        assertThat(from).isAfter(today.minusMonths(7)).isBefore(today.minusMonths(5));
        assertThat(demo.get("to").asText()).isEqualTo(today.toString());
        // The ledger starts with five opening balances on its first day.
        assertThat(ok(get(user, "/api/entries?to=" + from.minusDays(1))).get("totalElements").asInt()).isZero();
        assertThat(ok(get(user, "/api/entries?to=" + from)).get("content").findValuesAsText("kind"))
                .filteredOn("OPENING_BALANCE"::equals).hasSize(5);
        assertThat(ok(get(user, "/api/entries?size=1")).get("totalElements").asInt()).isEqualTo(total);
        assertThat(ok(get(user, "/api/settings")).get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(ok(get(user, "/api/accounts")).findValuesAsText("code")).contains("CREDIT_CARD", "USD_ACCOUNT");
        assertThat(ok(get(user, "/api/counterparties")).findValuesAsText("name")).contains("Landlord", "Robin");

        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
        String period = "?from=%s&to=%s".formatted(demo.get("from").asText(), demo.get("to").asText());
        JsonNode balances = ok(get(user, "/api/reports/balances"));
        assertThat(find(balances, "accountCode", "CURRENT_ACCOUNT").get("balance").asText()).isEqualTo("6054.12");
        assertThat(find(balances, "accountCode", "USD_ACCOUNT").get("balance").asText()).isEqualTo("1247.00");
        assertThat(find(balances, "accountCode", "CREDIT_CARD").get("balance").asText()).isEqualTo("94.69");
        assertThat(ok(get(user, "/api/reports/net-worth")).findValuesAsText("currency"))
                .containsExactlyInAnyOrder("EUR", "USD");
        JsonNode cashFlow = ok(get(user, "/api/reports/cash-flow" + period));
        assertThat(cashFlow.findValuesAsText("categoryCode")).contains("SALARY", "FREELANCE", "HOUSING", "GROCERIES",
                "EATING_OUT", "CLOTHING", "GIFTS", "TRAVEL");
        JsonNode loans = ok(get(user, "/api/reports/counterparty-balances?accountCode=LOANS_ASSET"));
        assertThat(find(loans, "counterpartyName", "Robin").get("balance").asText()).isEqualTo("50.00");
        assertThat(ok(get(user, "/api/reports/counterparty-balances?accountCode=CREDITOR_DEBT"))).isEmpty();
        JsonNode settlement = ok(get(user, "/api/reports/shared-settlement"));
        assertThat(settlement.get(0).get("direction").asText()).isEqualTo("USER_IS_OWED");
        assertThat(settlement.get(0).get("balance").asText()).isEqualTo("-88.60");

        // In the base currency every figure converts, and nothing is missing.
        JsonNode netWorth = ok(get(user, "/api/reports/net-worth?currency=BASE"));
        assertThat(netWorth.get("missingRates")).isEmpty();
        assertThat(netWorth.get("netWorth").isNull()).isFalse();
        assertThat(netWorth.get("realizedExchangeResult").isNull()).isFalse();
        assertThat(ok(get(user, "/api/reports/balances?currency=BASE")).findValues("balance"))
                .noneMatch(JsonNode::isNull);
        JsonNode baseCashFlow = ok(get(user, "/api/reports/cash-flow" + period + "&currency=BASE"));
        assertThat(baseCashFlow.get("rows").findValues("total")).isNotEmpty().noneMatch(JsonNode::isNull);
        assertThat(baseCashFlow.findValues("missingRates")).allMatch(JsonNode::isEmpty);
        assertThat(ok(get(user, "/api/rates")).get("missing")).isEmpty();
    }

    @Test
    void aSecondLoadIsRefusedWith409AndChangesNothing() throws IOException {
        String user = newUser();
        ok(post(user, "/api/demo-data", null));
        Map<String, Long> rows = rowsOf(user);

        JsonNode refused = body(post(user, "/api/demo-data", null), HttpStatus.CONFLICT);

        assertThat(refused.get("detail").asText()).startsWith("The demo data can only go into an empty ledger");
        assertThat(rowsOf(user)).isEqualTo(rows);
    }

    @Test
    void aLedgerWithAnythingOfTheUsersOwnIsRefusedWith409() throws IOException {
        List<String> ownRows = List.of("""
                {"kind": "OPENING_BALANCE", "entryDate": "2026-08-01", "accountId": %s, "currency": "EUR",
                 "amount": "100"}""", """
                {"code": "BANK", "name": "My bank", "type": "ASSET"}""", """
                {"code": "PETS", "name": "Pets", "type": "EXPENSE"}""", """
                {"name": "Landlord"}""");
        List<String> paths = List.of("/api/entries", "/api/accounts", "/api/categories", "/api/counterparties");
        for (int i = 0; i < ownRows.size(); i++) {
            String user = newUser();
            assertThat(post(user, paths.get(i), ownRows.get(i).formatted(accountId(user, "CASH"))))
                    .hasStatus(HttpStatus.CREATED);
            Map<String, Long> rows = rowsOf(user);

            assertThat(post(user, "/api/demo-data", null)).as(paths.get(i)).hasStatus(HttpStatus.CONFLICT);
            assertThat(rowsOf(user)).as(paths.get(i)).isEqualTo(rows);
        }
    }

    @Test
    void startsFromTheStarterLedgerEvenIfTheUserChangedIt() throws IOException {
        String user = newUser();
        long cash = accountId(user, "CASH");
        ok(patch(user, "/api/accounts/" + cash, """
                {"name": "Wallet", "archived": true}"""));
        ok(patch(user, "/api/categories/" + categoryId(user, "GROCERIES"), """
                {"name": "Food", "archived": true}"""));
        ok(put(user, "/api/settings", """
                {"baseCurrency": "GBP", "defaultShareRatio": "0.3"}"""));

        ok(post(user, "/api/demo-data", null));

        JsonNode account = find(ok(get(user, "/api/accounts")), "code", "CASH");
        assertThat(account.get("id").asLong()).isEqualTo(cash);
        assertThat(account.get("name").asText()).isEqualTo("Cash");
        assertThat(account.get("archived").asBoolean()).isFalse();
        JsonNode groceries = find(ok(get(user, "/api/categories")), "code", "GROCERIES");
        assertThat(groceries.get("name").asText()).isEqualTo("Groceries");
        assertThat(groceries.get("archived").asBoolean()).isFalse();
        assertThat(ok(get(user, "/api/settings"))).isEqualTo(json.readTree("""
                {"baseCurrency": "EUR", "sharedAccountId": null, "defaultShareRatio": "0.50"}"""));
        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
    }

    @Test
    void aFailureHalfwayLeavesTheLedgerAsItWas() throws IOException {
        String user = newUser();
        ok(put(user, "/api/settings", """
                {"baseCurrency": "GBP", "defaultShareRatio": "0.3"}"""));
        JsonNode accounts = ok(get(user, "/api/accounts"));
        JsonNode categories = ok(get(user, "/api/categories"));
        Map<String, Long> rows = rowsOf(user);
        AtomicInteger written = new AtomicInteger();
        doAnswer(invocation -> {
            if (written.incrementAndGet() == 60) {
                throw new IllegalStateException("The database went away");
            }
            return invocation.callRealMethod();
        }).when(entries).create(eq(user), any(EntryCommand.class));

        assertThat(post(user, "/api/demo-data", null)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(written).hasValue(60);
        assertThat(rowsOf(user)).isEqualTo(rows);
        assertThat(ok(get(user, "/api/accounts"))).isEqualTo(accounts);
        assertThat(ok(get(user, "/api/categories"))).isEqualTo(categories);
        assertThat(ok(get(user, "/api/counterparties"))).isEmpty();
        assertThat(ok(get(user, "/api/settings")).get("baseCurrency").asText()).isEqualTo("GBP");
    }
}
