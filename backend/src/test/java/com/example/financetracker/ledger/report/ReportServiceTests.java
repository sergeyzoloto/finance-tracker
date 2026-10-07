package com.example.financetracker.ledger.report;

import static com.example.financetracker.ledger.domain.AccountType.ASSET;
import static com.example.financetracker.ledger.domain.AccountType.EQUITY;
import static com.example.financetracker.ledger.domain.AccountType.LIABILITY;
import static com.example.financetracker.ledger.domain.CategoryType.EXPENSE;
import static com.example.financetracker.ledger.domain.CategoryType.INCOME;
import static com.example.financetracker.ledger.report.SharedSettlement.Direction.USER_IS_OWED;
import static com.example.financetracker.ledger.report.SharedSettlement.Direction.USER_OWES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.WallClock;
import com.example.financetracker.ledger.Account;
import com.example.financetracker.ledger.AccountNotFoundException;
import com.example.financetracker.ledger.AccountRepository;
import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.CounterpartyRepository;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.EntryView;
import com.example.financetracker.ledger.LedgerCategory;
import com.example.financetracker.ledger.LedgerCategoryRepository;
import com.example.financetracker.ledger.UserSettings;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.CurrencyExchangeCommand;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.ExpenseCommand;
import com.example.financetracker.ledger.domain.IncomeCommand;
import com.example.financetracker.ledger.domain.LoanGivenCommand;
import com.example.financetracker.ledger.domain.LoanRepaidCommand;
import com.example.financetracker.ledger.domain.OpeningBalanceCommand;
import com.example.financetracker.ledger.domain.SharedExpenseCommand;
import com.example.financetracker.ledger.domain.TransferCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ReportService} against real PostgreSQL, over two months of a ledger written through {@link EntryService}.
 * Every test runs as a fresh user. The expected figures are worked out by hand from the entries in
 * {@link #writeLedger}.
 */
class ReportServiceTests extends IntegrationTest {

    private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUG_30 = LocalDate.of(2026, 8, 30);
    private static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_6 = LocalDate.of(2026, 9, 6);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);

    @Autowired
    private ReportService reports;
    @Autowired
    private EntryService entries;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private LedgerCategoryRepository categories;
    @Autowired
    private CounterpartyRepository counterparties;
    @Autowired
    private UserSettingsRepository settings;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private LedgerAccess ledgers;

    private final String user = UUID.randomUUID().toString();
    private final String other = UUID.randomUUID().toString();
    private LedgerScope ledger;
    private LedgerScope othersLedger;
    private long cash, card, savings, oldWallet, loans, creditorDebt, familyDebt, unallocated, openingBalance,
            fxExchange, othersCash, othersOpeningBalance;
    private long groceries, restaurants, salary;
    private long friendA, friendB, bank;
    private EntryView cardOpening, oldWalletOpening;

    @BeforeEach
    void writeLedger() {
        ledger = ledgers.provisionPersonal(user);
        othersLedger = ledgers.provisionPersonal(other);
        cash = account(ledger, "CASH", "Cash", ASSET, "RUB", false);
        card = account(ledger, "CARD", "Card", ASSET, "EUR", false);
        // Never used: it still shows, at zero, in its default currency.
        savings = account(ledger, "SAVINGS", "Savings", ASSET, "EUR", false);
        // Never used and without a default currency: it has no currency to show a balance in.
        account(ledger, "RESERVE", "Reserve", EQUITY, null, false);
        oldWallet = account(ledger, "OLD_WALLET", "Old wallet", ASSET, "RUB", false);
        loans = account(ledger, "LOANS_ASSET", "Loans given", ASSET, null, true);
        creditorDebt = account(ledger, "CREDITOR_DEBT", "Creditor", LIABILITY, null, true);
        familyDebt = account(ledger, "FAMILY_DEBT", "Family budget", LIABILITY, null, false);
        unallocated = account(ledger, "UNALLOCATED", "Unallocated", EQUITY, null, false);
        openingBalance = account(ledger, "OPENING_BALANCE", "Opening balance", EQUITY, null, false);
        fxExchange = account(ledger, "FX_EXCHANGE", "Currency exchange", EQUITY, null, false);
        groceries = category("GROCERIES", "Groceries", EXPENSE);
        restaurants = category("RESTAURANTS", "Restaurants", EXPENSE);
        salary = category("SALARY", "Salary", INCOME);
        friendA = counterparty("Friend A");
        friendB = counterparty("Friend B");
        bank = counterparty("Bank");

        // August.
        create(new OpeningBalanceCommand(AUG_1, null, cash, "RUB", money("10000.00"), null));
        cardOpening = create(new OpeningBalanceCommand(AUG_1, null, card, "EUR", money("500.00"), null));
        oldWalletOpening = create(new OpeningBalanceCommand(AUG_1, null, oldWallet, "RUB", money("300.00"), null));
        create(new OpeningBalanceCommand(AUG_1, null, creditorDebt, "RUB", money("-2000.00"), bank));
        create(new IncomeCommand(LocalDate.of(2026, 8, 5), null, null, cash, "RUB", money("50000.00"), salary));
        create(new ExpenseCommand(LocalDate.of(2026, 8, 10), null, null, cash, "RUB", money("1250.50"), groceries));
        create(new ExpenseCommand(AUG_31, null, null, card, "EUR", money("40.25"), groceries));
        // 362.775 for the family rounds up to 362.78; the user's own part is 362.77.
        create(new SharedExpenseCommand(AUG_31, null, null, cash, "RUB", money("725.55"), restaurants, null));

        // September.
        create(new ExpenseCommand(SEP_1, null, null, cash, "RUB", money("800.00"), groceries));
        create(new ExpenseCommand(LocalDate.of(2026, 9, 3), null, "refund", cash, "RUB", money("-200.00"),
                groceries));
        create(new LoanGivenCommand(LocalDate.of(2026, 9, 5), null, null, cash, friendA, "RUB", money("3000.00")));
        create(new LoanGivenCommand(SEP_6, null, null, cash, friendB, "RUB", money("1000.00")));
        create(new LoanRepaidCommand(LocalDate.of(2026, 9, 10), null, null, cash, friendA, "RUB", money("1000.00")));
        create(new LoanRepaidCommand(LocalDate.of(2026, 9, 12), null, null, cash, friendB, "RUB", money("1000.00")));
        create(new CurrencyExchangeCommand(LocalDate.of(2026, 9, 15), null, null, cash, "RUB", money("9000.00"), card,
                "EUR", money("100.00")));
        // The family budget hands the user 50 EUR.
        create(new TransferCommand(LocalDate.of(2026, 9, 18), null, null, familyDebt, card, "EUR", money("50.00"),
                null));
        create(new TransferCommand(LocalDate.of(2026, 9, 20), null, null, cash, creditorDebt, "RUB", money("500.00"),
                bank));
        create(new IncomeCommand(SEP_30, null, null, card, "EUR", money("1000.00"), salary));

        // Later than SEP_30, where most reports here end.
        create(new ExpenseCommand(OCT_1, null, null, cash, "RUB", money("99.99"), groceries));

        // Archived with money still in it.
        archive(oldWallet);

        othersCash = account(othersLedger, "CASH", "Cash", ASSET, "RUB", false);
        othersOpeningBalance = account(othersLedger, "OPENING_BALANCE", "Opening balance", EQUITY, null, false);
        entries.create(othersLedger, new OpeningBalanceCommand(AUG_1, null, othersCash, "RUB", money("777.00"), null));
    }

    @Test
    void balancesShowEveryAccountThatIsNotArchivedInEachCurrency() {
        assertThat(reports.balances(ledger, SEP_30)).containsExactly(
                new AccountBalance(card, "CARD", "Card", ASSET, "EUR", money("1609.75")),
                new AccountBalance(cash, "CASH", "Cash", ASSET, "RUB", money("45923.95")),
                new AccountBalance(creditorDebt, "CREDITOR_DEBT", "Creditor", LIABILITY, "RUB", money("1500.00")),
                new AccountBalance(familyDebt, "FAMILY_DEBT", "Family budget", LIABILITY, "EUR", money("50.00")),
                new AccountBalance(familyDebt, "FAMILY_DEBT", "Family budget", LIABILITY, "RUB", money("-362.78")),
                new AccountBalance(fxExchange, "FX_EXCHANGE", "Currency exchange", EQUITY, "EUR", money("100.00")),
                new AccountBalance(fxExchange, "FX_EXCHANGE", "Currency exchange", EQUITY, "RUB", money("-9000.00")),
                new AccountBalance(loans, "LOANS_ASSET", "Loans given", ASSET, "RUB", money("2000.00")),
                new AccountBalance(openingBalance, "OPENING_BALANCE", "Opening balance", EQUITY, "EUR",
                        money("500.00")),
                new AccountBalance(openingBalance, "OPENING_BALANCE", "Opening balance", EQUITY, "RUB",
                        money("8300.00")),
                new AccountBalance(savings, "SAVINGS", "Savings", ASSET, "EUR", money("0.00")),
                new AccountBalance(unallocated, "UNALLOCATED", "Unallocated", EQUITY, "EUR", money("959.75")),
                new AccountBalance(unallocated, "UNALLOCATED", "Unallocated", EQUITY, "RUB", money("47786.73")));
    }

    @Test
    void balanceOnADayIncludesThatDaysEntriesAndNoLaterOnes() {
        assertThat(balance(AUG_30, cash, "RUB")).isEqualTo(money("58749.50"));
        assertThat(balance(AUG_31, cash, "RUB")).isEqualTo(money("58023.95"));
        assertThat(balance(SEP_1, cash, "RUB")).isEqualTo(money("57223.95"));
        assertThat(balance(OCT_1, cash, "RUB")).isEqualTo(money("45823.96"));
        assertThat(balance(AUG_30, card, "EUR")).isEqualTo(money("500.00"));
        assertThat(balance(AUG_31, card, "EUR")).isEqualTo(money("459.75"));
    }

    @Test
    void counterpartyBalancesLeaveOutCounterpartiesThatAreSettled() {
        assertThat(reports.counterpartyBalances(ledger, "LOANS_ASSET", SEP_6)).containsExactly(
                new CounterpartyBalance(friendA, "Friend A", "RUB", money("3000.00")),
                new CounterpartyBalance(friendB, "Friend B", "RUB", money("1000.00")));
        assertThat(reports.counterpartyBalances(ledger, "LOANS_ASSET", SEP_30)).containsExactly(
                new CounterpartyBalance(friendA, "Friend A", "RUB", money("2000.00")));
        // A LIABILITY reads as what is owed.
        assertThat(reports.counterpartyBalances(ledger, "CREDITOR_DEBT", SEP_30)).containsExactly(
                new CounterpartyBalance(bank, "Bank", "RUB", money("1500.00")));
    }

    @Test
    void counterpartyBalancesNeedAnAccountOfTheUserThatRequiresACounterparty() {
        assertThatThrownBy(() -> reports.counterpartyBalances(ledger, "CASH", SEP_30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account CASH has no balances per counterparty: it doesn't require one");
        assertThatThrownBy(() -> reports.counterpartyBalances(othersLedger, "LOANS_ASSET", SEP_30))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account LOANS_ASSET not found");
    }

    @Test
    void cashFlowIsPositiveForIncomeAndExpenseAndRefundsReduceIt() {
        assertThat(reports.cashFlow(ledger, AUG_1, SEP_30)).containsExactly(
                cashFlow(2026, 8, "GROCERIES", "Groceries", EXPENSE, "EUR", "40.25"),
                cashFlow(2026, 8, "GROCERIES", "Groceries", EXPENSE, "RUB", "1250.50"),
                // The user's own part of the shared dinner.
                cashFlow(2026, 8, "RESTAURANTS", "Restaurants", EXPENSE, "RUB", "362.77"),
                cashFlow(2026, 8, "SALARY", "Salary", INCOME, "RUB", "50000.00"),
                // 800.00 spent less the 200.00 refund.
                cashFlow(2026, 9, "GROCERIES", "Groceries", EXPENSE, "RUB", "600.00"),
                cashFlow(2026, 9, "SALARY", "Salary", INCOME, "EUR", "1000.00"));
    }

    @Test
    void cashFlowSplitsMonthsAtTheEntryDate() {
        assertThat(reports.cashFlow(ledger, AUG_31, AUG_31)).containsExactly(
                cashFlow(2026, 8, "GROCERIES", "Groceries", EXPENSE, "EUR", "40.25"),
                cashFlow(2026, 8, "RESTAURANTS", "Restaurants", EXPENSE, "RUB", "362.77"));
        assertThat(reports.cashFlow(ledger, SEP_1, SEP_1)).containsExactly(
                cashFlow(2026, 9, "GROCERIES", "Groceries", EXPENSE, "RUB", "800.00"));
        assertThat(reports.cashFlow(ledger, SEP_1, OCT_1)).containsExactly(
                cashFlow(2026, 9, "GROCERIES", "Groceries", EXPENSE, "RUB", "600.00"),
                cashFlow(2026, 9, "SALARY", "Salary", INCOME, "EUR", "1000.00"),
                cashFlow(2026, 10, "GROCERIES", "Groceries", EXPENSE, "RUB", "99.99"));
    }

    @Test
    void cashFlowNeedsARangeInOrder() {
        assertThatThrownBy(() -> reports.cashFlow(ledger, SEP_30, SEP_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'from' must not be after 'to'");
    }

    @Test
    void netWorthIsAssetsMinusLiabilitiesPerCurrencyAndCountsArchivedAccounts() {
        assertThat(reports.netWorth(ledger, SEP_30)).containsExactly(
                new NetWorth("EUR", money("1609.75"), money("50.00"), money("1559.75")),
                // Assets: CASH 45923.95, LOANS_ASSET 2000.00 and the archived OLD_WALLET 300.00. Liabilities:
                // CREDITOR_DEBT 1500.00 and FAMILY_DEBT -362.78.
                new NetWorth("RUB", money("48223.95"), money("1137.22"), money("47086.73")));
    }

    @Test
    void sharedSettlementSaysWhoOwesWhomPerCurrency() {
        assertThat(reports.sharedSettlement(ledger, AUG_31)).containsExactly(
                new SharedSettlement(familyDebt, "FAMILY_DEBT", "RUB", money("-362.78"), USER_IS_OWED));
        assertThat(reports.sharedSettlement(ledger, SEP_30)).containsExactly(
                new SharedSettlement(familyDebt, "FAMILY_DEBT", "EUR", money("50.00"), USER_OWES),
                new SharedSettlement(familyDebt, "FAMILY_DEBT", "RUB", money("-362.78"), USER_IS_OWED));
    }

    @Test
    void sharedSettlementLeavesOutSettledCurrencies() {
        // The family budget pays the user back.
        create(new TransferCommand(SEP_30, null, null, familyDebt, cash, "RUB", money("362.78"), null));

        assertThat(reports.sharedSettlement(ledger, SEP_30)).containsExactly(
                new SharedSettlement(familyDebt, "FAMILY_DEBT", "EUR", money("50.00"), USER_OWES));
    }

    /** Positive on an ASSET account means the opposite of positive on FAMILY_DEBT, a LIABILITY. */
    @Test
    void sharedSettlementUsesTheSharedAccountFromTheSettingsWhateverItsType() {
        long partnerShare = account(ledger, "PARTNER_SHARE", "Partner's share", ASSET, null, false);
        settings.save(new UserSettings(user, "EUR", partnerShare, new BigDecimal("0.5000")));
        create(new SharedExpenseCommand(SEP_30, null, null, card, "EUR", money("30.00"), restaurants, null));

        assertThat(reports.sharedSettlement(ledger, SEP_30)).containsExactly(
                new SharedSettlement(partnerShare, "PARTNER_SHARE", "EUR", money("15.00"), USER_IS_OWED));
    }

    @Test
    void integrityCheckFindsNothingInALedgerWrittenThroughTheService() {
        assertThat(reports.integrityCheck(ledger)).isEmpty();
        assertThat(reports.integrityCheck(othersLedger)).isEmpty();
    }

    @Test
    void integrityCheckReportsPostingsThatDoNotSumToZero() {
        pastTheTriggers("UPDATE posting SET amount = amount + 0.01 WHERE entry_id = ? AND line_no = 0",
                cardOpening.id());

        assertThat(reports.integrityCheck(ledger)).containsExactly(
                new IntegrityViolation("EUR", money("0.01"), money("0.01")));
    }

    /** The entry still balances, but its account belongs to the other user now. */
    @Test
    void integrityCheckReportsAPostingOnAnotherUsersAccount() {
        pastTheTriggers("UPDATE posting SET account_id = ? WHERE entry_id = ? AND line_no = 0", othersCash,
                oldWalletOpening.id());

        assertThat(reports.integrityCheck(ledger)).containsExactly(
                new IntegrityViolation("RUB", money("0.00"), money("-300.00")));
        assertThat(reports.integrityCheck(othersLedger)).containsExactly(
                new IntegrityViolation("RUB", money("0.00"), money("300.00")));
    }

    /**
     * Postings changed past the triggers to name the other user's category and counterparty: the reports that show
     * names leave them out rather than show the other user's.
     */
    @Test
    void reportsNeverShowAnotherUsersCategoryOrCounterpartyEvenPastTheTriggers() {
        long othersCategory = categories.save(new LedgerCategory(null, other, othersLedger.ledgerId(), "SECRET",
                "Other's secret", EXPENSE, null)).id();
        long othersCounterparty = counterparties.save(new Counterparty(null, other, othersLedger.ledgerId(),
                "Other's friend", null, null)).id();
        pastTheTriggers("UPDATE posting SET category_id = ? WHERE category_id = ?", othersCategory, restaurants);
        pastTheTriggers("UPDATE posting SET counterparty_id = ? WHERE counterparty_id = ?", othersCounterparty,
                friendB);

        assertThat(reports.cashFlow(ledger, AUG_1, SEP_30)).extracting(CashFlowRow::categoryCode)
                .contains("GROCERIES").doesNotContain("SECRET");
        assertThat(reports.cashFlowInBase(ledger, AUG_1, SEP_30).rows()).extracting(ConvertedCashFlow.Row::categoryCode)
                .contains("GROCERIES").doesNotContain("SECRET");
        assertThat(reports.counterpartyBalances(ledger, "LOANS_ASSET", SEP_6)).containsExactly(
                new CounterpartyBalance(friendA, "Friend A", "RUB", money("3000.00")));
    }

    @Test
    void anotherUserSeesOnlyTheirOwnLedger() {
        assertThat(reports.balances(othersLedger, SEP_30)).containsExactly(
                new AccountBalance(othersCash, "CASH", "Cash", ASSET, "RUB", money("777.00")),
                new AccountBalance(othersOpeningBalance, "OPENING_BALANCE", "Opening balance", EQUITY, "RUB",
                        money("777.00")));
        assertThat(reports.netWorth(othersLedger, SEP_30)).containsExactly(
                new NetWorth("RUB", money("777.00"), money("0.00"), money("777.00")));
        assertThat(reports.cashFlow(othersLedger, AUG_1, SEP_30)).isEmpty();
        assertThat(reports.sharedSettlement(othersLedger, SEP_30)).isEmpty();
    }

    private EntryView create(EntryCommand command) {
        return entries.create(ledger, command);
    }

    private BigDecimal balance(LocalDate asOf, long accountId, String currency) {
        return reports.balances(ledger, asOf).stream()
                .filter(b -> b.accountId() == accountId && b.currency().equals(currency))
                .map(AccountBalance::balance)
                .findFirst().orElseThrow();
    }

    /** Runs the statement in replica mode, which fires no triggers, as when a restore or a replica writes rows. */
    private void pastTheTriggers(String sql, Object... params) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql(sql).params(params).update();
        });
    }

    private long account(LedgerScope owner, String code, String name, AccountType type, String defaultCurrency,
            boolean requiresCounterparty) {
        return accounts.save(new Account(null, owner.userId(), owner.ledgerId(), code, name, type, defaultCurrency,
                requiresCounterparty, false, null, null)).id();
    }

    private void archive(long accountId) {
        Account account = accounts.find(ledger, accountId).orElseThrow();
        accounts.save(new Account(account.id(), account.userId(), account.ledgerId(), account.code(), account.name(),
                account.type(), account.defaultCurrency(), account.requiresCounterparty(), account.isSystem(),
                WallClock.now(), account.createdAt()));
    }

    private long category(String code, String name, CategoryType type) {
        return categories.save(new LedgerCategory(null, user, ledger.ledgerId(), code, name, type, null)).id();
    }

    private long counterparty(String name) {
        return counterparties.save(new Counterparty(null, user, ledger.ledgerId(), name, null, null)).id();
    }

    private static CashFlowRow cashFlow(int year, int month, String code, String name, CategoryType type,
            String currency, String total) {
        return new CashFlowRow(YearMonth.of(year, month), code, name, type, currency, money(total));
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
