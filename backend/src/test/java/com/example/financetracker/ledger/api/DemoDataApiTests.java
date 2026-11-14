package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.financetracker.TestClock;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.demo.DemoFamily;
import com.example.financetracker.ledger.demo.DemoLedger;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * POST /api/demo-data: the demo ledger for a new user, in one transaction, and only into an empty ledger. The entries
 * go through {@link EntryService}, spied on here so that one test can make it fail halfway. With the family budget
 * switched on, as here, the demo also creates its family budget (H1, F6c); {@code FamilySwitchOffApiTests} checks the
 * demo without it. The demo's amounts follow the calendar (D-690), so the tests that assert its figures load it on a
 * fixed day, {@link #DAY}, and take the family's counts from its plan.
 */
class DemoDataApiTests extends LedgerApiTest {

    /** The day the figures below are the demo's of: 2026-11-13 in UTC, the zone of a user who has set none. */
    static final Instant DAY = Instant.parse("2026-11-13T12:00:00Z");

    @MockitoSpyBean
    private EntryService entries;

    @Autowired
    private TestClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void loadsThirtySixMonthsOfEveryKindOfEntryThatPassTheIntegrityCheckAndEveryReport() throws IOException {
        String user = newUser();
        // Long before the demo starts, so that every dollar amount has a rate in the base currency.
        ok(post(user, "/api/rates/manual", """
                {"date": "2020-01-01", "base": "EUR", "quote": "USD", "rate": "1.10"}"""));
        clock.set(DAY, ZoneOffset.UTC);
        LocalDate today = LocalDate.of(2026, 11, 13);

        JsonNode demo = ok(post(user, "/api/demo-data", null));

        assertThat(demo.get("entriesByKind").properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "EXPENSE", "INCOME", "TRANSFER", "SHARED_EXPENSE", "LOAN_GIVEN", "LOAN_REPAID", "CURRENCY_EXCHANGE",
                "OPENING_BALANCE", "MANUAL");
        int total = demo.get("entriesByKind").properties().stream().mapToInt(kind -> kind.getValue().asInt()).sum();
        LocalDate from = LocalDate.parse(demo.get("from").asText());
        // D-689: 36 months, this month included, from the first of the month; the two years before this one in full.
        assertThat(from).isEqualTo(LocalDate.of(2023, 11, 1)).isEqualTo(DemoLedger.firstDay(today));
        assertThat(demo.get("to").asText()).isEqualTo(today.toString());
        assertThat(total).isEqualTo(760);
        // The ledger starts with six opening balances on its first day.
        assertThat(ok(get(user, "/api/entries?to=" + from.minusDays(1))).get("totalElements").asInt()).isZero();
        assertThat(ok(get(user, "/api/entries?to=" + from)).get("content").findValuesAsText("kind"))
                .filteredOn("OPENING_BALANCE"::equals).hasSize(6);
        // The demo family's entries in the personal ledger: a share of every record, the user's payments and
        // receipts, and a settlement side of each of Sam's settlements.
        DemoFamily.Plan plan = DemoFamily.plan(from, today);
        long yours = plan.records().stream().filter(r -> r.payer() == DemoFamily.Who.YOU).count();
        assertThat(ok(get(user, "/api/entries?size=1")).get("totalElements").asInt())
                .isEqualTo(total + plan.records().size() + (int) yours + plan.settlements().size());
        assertThat(ok(get(user, "/api/settings")).get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(ok(get(user, "/api/accounts")).findValuesAsText("code")).contains("CREDIT_CARD", "USD_ACCOUNT");
        assertThat(ok(get(user, "/api/counterparties")).findValuesAsText("name")).contains("Landlord", "Robin");

        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
        String period = "?from=%s&to=%s".formatted(demo.get("from").asText(), demo.get("to").asText());
        JsonNode balances = ok(get(user, "/api/reports/balances"));
        // Without the demo family 4296.06 and 1171.26 (DemoLedgerTests): with it, the shops paid, the bike's 120.00
        // and Sam's euros received; the weekends paid in dollars and Sam's halves received.
        assertThat(find(balances, "accountCode", "CURRENT_ACCOUNT").get("balance").asText()).isEqualTo("1730.86");
        assertThat(find(balances, "accountCode", "USD_ACCOUNT").get("balance").asText()).isEqualTo("807.26");
        assertThat(find(balances, "accountCode", "CREDIT_CARD").get("balance").asText()).isEqualTo("79.71");
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
        assertThat(settlement.get(0).get("balance").asText()).isEqualTo("-40.09");

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

    /**
     * H1 (F6c), over the demo's 36 months (D-691): the demo's family budget, made through the family budget's
     * services, is one like any other: its owner is the user under their account's name, Sam has no account, its four
     * categories are the demo's own, merged (D-11), so no personal twin of them remains, and its records are posted
     * into the user's ledger. The numbers are its plan's: what Sam owes in each currency, the user's shares and what
     * they paid, and what Sam paid back. The invariants, E1's report against the balances and the integrity check
     * hold. E3's check of the personal cash flow: the merged categories (its groceries) hold the demo's own entries
     * too, beside the shares, which D-40 says is right and the check now expects.
     */
    @Test
    void theDemoFamilyIsAFamilyBudgetLikeAnyOther() throws IOException {
        String user = newUser();
        clock.set(DAY, ZoneOffset.UTC);
        LocalDate today = LocalDate.of(2026, 11, 13);

        JsonNode demo = ok(post(user, "/api/demo-data", null));

        DemoFamily.Plan plan = DemoFamily.plan(LocalDate.parse(demo.get("from").asText()), today);
        long family = demo.get("familyLedgerId").asLong();
        String uri = "/api/family-ledgers/" + family;
        JsonNode budget = find(ok(get(user, "/api/family-ledgers")), "id", String.valueOf(family));
        assertThat(budget.get("name").asText()).isEqualTo("Demo household");
        assertThat(budget.get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(budget.get("role").asText()).isEqualTo("OWNER");
        assertThat(budget.get("startDate").asText()).isEqualTo(demo.get("from").asText());
        assertThat(ok(get(user, uri + "/members")).findValuesAsText("displayName"))
                .containsExactly("User " + user, "Sam");
        assertThat(ok(get(user, uri + "/members")).findValuesAsText("hasAccount")).containsExactly("true", "false");
        JsonNode records = ok(get(user, uri + "/records?size=200"));
        assertThat(records.get("totalElements").asInt()).isEqualTo(plan.records().size() + plan.settlements().size());
        assertThat(records.get("content").findValuesAsText("type")).containsOnly("EXPENSE", "INCOME", "SETTLEMENT");
        // The weekends away stay in dollars (D-45).
        assertThat(records.get("content").findValuesAsText("currency")).contains("EUR", "USD");
        JsonNode categories = ok(get(user, "/api/categories"));
        for (String code : List.of("GROCERIES", "OTHER_INCOME", "TRAVEL", "UTILITIES")) {
            assertThat(categories.findValues("code")).as(code).filteredOn(c -> c.asText().equals(code)).hasSize(1);
            assertThat(find(categories, "code", code).get("familyLedgerId").asLong()).as(code).isEqualTo(family);
        }
        JsonNode balances = ok(get(user, uri + "/balances"));
        assertThat(balances.get("byCurrency").findValuesAsText("currency")).containsExactly("EUR", "USD");
        // What Sam owes in euros the user is owed; the dollars of the weekends Sam pays back in dollars (D-46).
        BigDecimal samsEuros = DemoFamily.owed(plan.records(), plan.settlements(), today, "EUR");
        BigDecimal samsDollars = DemoFamily.owed(plan.records(), plan.settlements(), today, "USD");
        assertThat(balances.get("byCurrency").get(0).get("members").findValuesAsText("balance").stream()
                .map(BigDecimal::new).toList())
                .usingElementComparator(BigDecimal::compareTo).containsExactly(samsEuros.negate(), samsEuros);
        assertThat(balances.get("byCurrency").get(1).get("members").findValuesAsText("balance").stream()
                .map(BigDecimal::new).toList())
                .usingElementComparator(BigDecimal::compareTo).containsExactly(samsDollars.negate(), samsDollars);

        FamilyInvariants.check(jdbc, family);
        JsonNode report = checkFamilyReport(user, family, Map.of(user, LocalDate.parse(demo.get("from").asText())));
        JsonNode euros = report.get("byCurrency").get(0);
        assertThat(euros.get("currency").asText()).isEqualTo("EUR");
        assertThat(new BigDecimal(euros.get("totals").get(0).get("expenseShares").asText()))
                .isEqualByComparingTo(yourShares(plan, "EUR"));
        assertThat(new BigDecimal(euros.get("totals").get(0).get("expensesPaid").asText()))
                .isEqualByComparingTo(yourPayments(plan, "EUR"));
        assertThat(new BigDecimal(euros.get("totals").get(1).get("settlementsPaid").asText()))
                .isEqualByComparingTo(settled(plan, "EUR"));
        JsonNode dollars = report.get("byCurrency").get(1);
        assertThat(dollars.get("currency").asText()).isEqualTo("USD");
        assertThat(new BigDecimal(dollars.get("totals").get(0).get("expenseShares").asText()))
                .isEqualByComparingTo(yourShares(plan, "USD"));
        assertThat(new BigDecimal(dollars.get("totals").get(0).get("expensesPaid").asText()))
                .isEqualByComparingTo(yourPayments(plan, "USD"));
        assertThat(new BigDecimal(dollars.get("totals").get(1).get("settlementsPaid").asText()))
                .isEqualByComparingTo(settled(plan, "USD"));
        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
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
        }).when(entries).create(argThat(ledger -> ledger.userId().equals(user)), any(EntryCommand.class));

        assertThat(post(user, "/api/demo-data", null)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(written).hasValue(60);
        assertThat(rowsOf(user)).isEqualTo(rows);
        assertThat(ok(get(user, "/api/accounts"))).isEqualTo(accounts);
        assertThat(ok(get(user, "/api/categories"))).isEqualTo(categories);
        assertThat(ok(get(user, "/api/counterparties"))).isEmpty();
        assertThat(ok(get(user, "/api/settings")).get("baseCurrency").asText()).isEqualTo("GBP");
    }

    /** The user's shares of the plan's expenses in the currency: half, or 60 % of the party's food. */
    private static BigDecimal yourShares(DemoFamily.Plan plan, String currency) {
        return plan.records().stream().filter(r -> r.type().equals("EXPENSE") && r.currency().equals(currency))
                .map(r -> r.amountValue().multiply(BigDecimal.valueOf(r.yourBasisPoints() == null ? 5000
                        : r.yourBasisPoints())).movePointLeft(4))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal yourPayments(DemoFamily.Plan plan, String currency) {
        return plan.records().stream().filter(r -> r.type().equals("EXPENSE") && r.currency().equals(currency)
                && r.payer() == DemoFamily.Who.YOU).map(DemoFamily.Record::amountValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal settled(DemoFamily.Plan plan, String currency) {
        return plan.settlements().stream().filter(st -> st.currency().equals(currency))
                .map(DemoFamily.Settlement::amountValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
