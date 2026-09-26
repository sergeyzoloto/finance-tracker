package com.example.financetracker.ledger.report;

import static com.example.financetracker.ledger.domain.AccountType.ASSET;
import static com.example.financetracker.ledger.domain.AccountType.EQUITY;
import static com.example.financetracker.ledger.domain.CategoryType.EXPENSE;
import static com.example.financetracker.ledger.domain.CategoryType.INCOME;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.Account;
import com.example.financetracker.ledger.AccountRepository;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.LedgerCategory;
import com.example.financetracker.ledger.LedgerCategoryRepository;
import com.example.financetracker.ledger.UserSettings;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.CurrencyExchangeCommand;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.ExpenseCommand;
import com.example.financetracker.ledger.domain.IncomeCommand;
import com.example.financetracker.ledger.domain.OpeningBalanceCommand;
import com.example.financetracker.ledger.rates.ManualRate;
import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;
import com.example.financetracker.ledger.rates.RateService;
import com.example.financetracker.ledger.rates.RateSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reports of {@link ReportService} in the base currency, against real PostgreSQL, with ledgers written through
 * {@link EntryService} and the user's own manual rates, which no other test sees. Every test runs as a fresh user;
 * the expected figures are worked out by hand.
 */
class BaseCurrencyReportTests extends IntegrationTest {

    private static final LocalDate JUL_31 = LocalDate.of(2026, 7, 31);
    private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUG_10 = LocalDate.of(2026, 8, 10);
    private static final LocalDate AUG_15 = LocalDate.of(2026, 8, 15);
    private static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);
    private static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);

    @Autowired
    private ReportService reports;
    @Autowired
    private EntryService entries;
    @Autowired
    private RateService rates;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private LedgerCategoryRepository categories;
    @Autowired
    private UserSettingsRepository settings;

    private final String user = UUID.randomUUID().toString();
    private long cash, card, tenge, unallocated, openingBalance, fxExchange;
    private long groceries, salary;

    @BeforeEach
    void createAccounts() {
        cash = account("CASH", "Cash", ASSET, "RUB");
        card = account("CARD", "Card", ASSET, "EUR");
        tenge = account("TENGE", "Tenge wallet", ASSET, "KZT");
        unallocated = account("UNALLOCATED", "Unallocated", EQUITY, null);
        openingBalance = account("OPENING_BALANCE", "Opening balance", EQUITY, null);
        fxExchange = account("FX_EXCHANGE", "Currency exchange", EQUITY, null);
        groceries = category("GROCERIES", "Groceries", EXPENSE);
        salary = category("SALARY", "Salary", INCOME);
        baseCurrency("EUR");
    }

    /** No KZT rate exists at all: every figure that needs one is missing, and none is added up as if it were 0. */
    @Test
    void aValueWithoutARateIsMarkedMissingAndNeverCountedAsZero() {
        rate(AUG_1, "RUB", "100");
        create(new OpeningBalanceCommand(AUG_1, null, tenge, "KZT", money("50000.00"), null));
        create(new OpeningBalanceCommand(AUG_1, null, cash, "RUB", money("10000.00"), null));
        create(new ExpenseCommand(AUG_10, null, null, tenge, "KZT", money("5000.00"), groceries));
        create(new ExpenseCommand(AUG_10, null, null, cash, "RUB", money("1000.00"), groceries));

        List<ConvertedBalance> balances = reports.balancesInBase(user, AUG_31);
        assertThat(balance(balances, "TENGE").balance()).isNull();
        assertThat(balance(balances, "TENGE").missingRates()).containsExactly(new MissingRate("KZT", AUG_31, AUG_31, 1));
        assertThat(balance(balances, "CASH").balance()).isEqualTo(money("90.00"));
        assertThat(balance(balances, "CASH").missingRates()).isEmpty();
        // Zero needs no rate: the card holds nothing yet.
        assertThat(balance(balances, "CARD").balance()).isEqualTo(money("0.00"));

        ConvertedNetWorth netWorth = reports.netWorthInBase(user, AUG_31);
        assertThat(netWorth.assets()).isNull();
        assertThat(netWorth.netWorth()).isNull();
        assertThat(netWorth.unrealizedRevaluation()).isNull();
        // Liabilities need no rate: there are none.
        assertThat(netWorth.liabilities()).isEqualTo(money("0.00"));
        assertThat(netWorth.missingRates()).containsExactly(new MissingRate("KZT", AUG_1, AUG_31, 3));

        ConvertedCashFlow cashFlow = reports.cashFlowInBase(user, AUG_1, AUG_31);
        assertThat(cashFlow.rows()).containsExactly(new ConvertedCashFlow.Row(YearMonth.of(2026, 8), "GROCERIES",
                "Groceries", EXPENSE, null, List.of(new MissingRate("KZT", AUG_10, AUG_10, 1))));
        assertThat(cashFlow.exchangeResults().getFirst().unrealized()).isNull();
    }

    /** Base currency USD: 1 EUR = 1.25 USD = 100 RUB, so 800 RUB are 10 USD. */
    @Test
    void aCrossRateIsComputedThroughTheEuro() {
        baseCurrency("USD");
        rate(AUG_1, "USD", "1.25");
        rate(AUG_1, "RUB", "100");
        create(new OpeningBalanceCommand(AUG_1, null, cash, "RUB", money("10000.00"), null));
        create(new ExpenseCommand(AUG_10, null, null, cash, "RUB", money("800.00"), groceries));

        assertThat(balance(reports.balancesInBase(user, AUG_31), "CASH").balance()).isEqualTo(money("115.00"));
        assertThat(reports.cashFlowInBase(user, AUG_1, AUG_31).rows()).containsExactly(new ConvertedCashFlow.Row(
                YearMonth.of(2026, 8), "GROCERIES", "Groceries", EXPENSE, money("10.00"), List.of()));
        ConvertedNetWorth netWorth = reports.netWorthInBase(user, AUG_31);
        assertThat(netWorth.netWorth()).isEqualTo(money("115.00"));
        // Both rates the conversion went through.
        assertThat(netWorth.rates()).containsExactly(
                new RateBook.Rate("RUB", AUG_1, money("100"), RateSource.MANUAL),
                new RateBook.Rate("USD", AUG_1, money("1.25"), RateSource.MANUAL));
    }

    /** Base currency USD. 100.00 EUR bought 110.00 USD on 1 August and 120.00 USD from 1 September. */
    @Test
    void aBalanceOf100EurIsRevaluedWhenTheRateMoves() {
        baseCurrency("USD");
        rate(AUG_1, "USD", "1.10");
        rate(SEP_1, "USD", "1.20");
        create(new OpeningBalanceCommand(AUG_1, null, card, "EUR", money("100.00"), null));

        ConvertedNetWorth august = reports.netWorthInBase(user, AUG_31);
        assertThat(august.netWorth()).isEqualTo(money("110.00"));
        assertThat(august.unrealizedRevaluation()).isEqualTo(money("0.00"));
        ConvertedNetWorth september = reports.netWorthInBase(user, SEP_30);
        assertThat(september.netWorth()).isEqualTo(money("120.00"));
        assertThat(september.unrealizedRevaluation()).isEqualTo(money("10.00"));
        assertThat(september.realizedExchangeResult()).isEqualTo(money("0.00"));
        assertThat(september.missingRates()).isEmpty();

        // The cash flow shows the gain in the month the rate moved in.
        assertThat(reports.cashFlowInBase(user, AUG_1, SEP_30).exchangeResults()).containsExactly(
                new ConvertedCashFlow.ExchangeResult(YearMonth.of(2026, 8), money("0.00"), money("0.00"), List.of()),
                new ConvertedCashFlow.ExchangeResult(YearMonth.of(2026, 9), money("0.00"), money("10.00"), List.of()));
        // A period that starts on 15 August takes the balance before it at the rate of 14 August.
        assertThat(reports.cashFlowInBase(user, AUG_15, SEP_15).exchangeResults()).extracting(
                ConvertedCashFlow.ExchangeResult::unrealized).containsExactly(money("0.00"), money("10.00"));
    }

    /**
     * 9000 RUB, worth 100.00 EUR at 90 RUB per euro on 1 August, are exchanged for 100.00 EUR on 15 September, when
     * the rate is 100 RUB per euro and they are worth 90.00 EUR: the exchange gained 10.00 EUR. The fall of the rouble
     * before the exchange cost the same 10.00 EUR, so net worth stays 100.00 EUR.
     */
    @Test
    void anExchangesRealizedResultAppearsInTheFxLine() {
        rate(AUG_1, "RUB", "90");
        rate(SEP_15, "RUB", "100");
        create(new OpeningBalanceCommand(AUG_1, null, cash, "RUB", money("9000.00"), null));
        create(new CurrencyExchangeCommand(SEP_15, null, null, cash, "RUB", money("9000.00"), card, "EUR",
                money("100.00")));

        ConvertedNetWorth netWorth = reports.netWorthInBase(user, SEP_30);
        assertThat(netWorth.realizedExchangeResult()).isEqualTo(money("10.00"));
        assertThat(netWorth.unrealizedRevaluation()).isEqualTo(money("-10.00"));
        assertThat(netWorth.netWorth()).isEqualTo(money("100.00"));
        // Before the exchange, nothing is realized.
        assertThat(reports.netWorthInBase(user, AUG_31).realizedExchangeResult()).isEqualTo(money("0.00"));

        assertThat(reports.cashFlowInBase(user, AUG_1, SEP_30).exchangeResults()).containsExactly(
                new ConvertedCashFlow.ExchangeResult(YearMonth.of(2026, 8), money("0.00"), money("0.00"), List.of()),
                new ConvertedCashFlow.ExchangeResult(YearMonth.of(2026, 9), money("10.00"), money("-10.00"),
                        List.of()));
        // The exchange is neither income nor an expense.
        assertThat(reports.cashFlowInBase(user, AUG_1, SEP_30).rows()).isEmpty();
    }

    /** 1000 RUB at 100 per euro on 10 August and 1000 RUB at 80 per euro on 20 August. */
    @Test
    void cashFlowConvertsEachPostingAtTheRateOnItsOwnDay() {
        rate(AUG_1, "RUB", "100");
        rate(AUG_15, "RUB", "80");
        create(new ExpenseCommand(AUG_10, null, null, cash, "RUB", money("1000.00"), groceries));
        create(new ExpenseCommand(AUG_20, null, null, cash, "RUB", money("1000.00"), groceries));
        create(new IncomeCommand(AUG_20, null, null, card, "EUR", money("2000.00"), salary));

        assertThat(reports.cashFlowInBase(user, AUG_1, AUG_31)).isEqualTo(new ConvertedCashFlow("EUR", List.of(
                new ConvertedCashFlow.Row(YearMonth.of(2026, 8), "GROCERIES", "Groceries", EXPENSE, money("22.50"),
                        List.of()),
                new ConvertedCashFlow.Row(YearMonth.of(2026, 8), "SALARY", "Salary", INCOME, money("2000.00"),
                        List.of())),
                // The cash account went into the red, and each rouble of that cost more after the rate changed.
                List.of(new ConvertedCashFlow.ExchangeResult(YearMonth.of(2026, 8), money("0.00"), money("-2.50"),
                        List.of()))));
    }

    @Test
    void anotherUsersManualRatesAreNotUsed() {
        String other = UUID.randomUUID().toString();
        rates.saveManual(other, new ManualRate(JUL_31, "EUR", "KZT", money("550")));
        create(new OpeningBalanceCommand(AUG_1, null, tenge, "KZT", money("55000.00"), null));

        assertThat(balance(reports.balancesInBase(user, AUG_31), "TENGE").balance()).isNull();

        rate(JUL_31, "KZT", "500");
        assertThat(balance(reports.balancesInBase(user, AUG_31), "TENGE").balance()).isEqualTo(money("110.00"));
    }

    private void rate(LocalDate day, String currency, String perEuro) {
        rates.saveManual(user, new ManualRate(day, "EUR", currency, money(perEuro)));
    }

    private void create(EntryCommand command) {
        entries.create(user, command);
    }

    private void baseCurrency(String currency) {
        settings.save(new UserSettings(user, currency, null, new BigDecimal("0.5000")));
    }

    private static ConvertedBalance balance(List<ConvertedBalance> balances, String accountCode) {
        return balances.stream().filter(b -> b.accountCode().equals(accountCode)).findFirst().orElseThrow();
    }

    private long account(String code, String name, AccountType type, String defaultCurrency) {
        return accounts.save(new Account(null, user, code, name, type, defaultCurrency, false, false, null, null)).id();
    }

    private long category(String code, String name, CategoryType type) {
        return categories.save(new LedgerCategory(null, user, code, name, type, null)).id();
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
