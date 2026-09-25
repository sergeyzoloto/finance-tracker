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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
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

    private final String user = UUID.randomUUID().toString();
    private final String other = UUID.randomUUID().toString();
    private long cash, card, savings, oldWallet, loans, creditorDebt, familyDebt, unallocated, openingBalance,
            fxExchange, othersCash, othersOpeningBalance;
    private long groceries, restaurants, salary;
    private long friendA, friendB, bank;
    private EntryView cardOpening, oldWalletOpening;

    @BeforeEach
    void writeLedger() {
        cash = account(user, "CASH", "Cash", ASSET, "RUB", false);
        card = account(user, "CARD", "Card", ASSET, "EUR", false);
        // Never used: it still shows, at zero, in its default currency.
        savings = account(user, "SAVINGS", "Savings", ASSET, "EUR", false);
        // Never used and without a default currency: it has no currency to show a balance in.
        account(user, "RESERVE", "Reserve", EQUITY, null, false);
        oldWallet = account(user, "OLD_WALLET", "Old wallet", ASSET, "RUB", false);
        loans = account(user, "LOANS_ASSET", "Loans given", ASSET, null, true);
        creditorDebt = account(user, "CREDITOR_DEBT", "Creditor", LIABILITY, null, true);
        familyDebt = account(user, "FAMILY_DEBT", "Family budget", LIABILITY, null, false);
        unallocated = account(user, "UNALLOCATED", "Unallocated", EQUITY, null, false);
        openingBalance = account(user, "OPENING_BALANCE", "Opening balance", EQUITY, null, false);
        fxExchange = account(user, "FX_EXCHANGE", "Currency exchange", EQUITY, null, false);
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

        othersCash = account(other, "CASH", "Cash", ASSET, "RUB", false);
        othersOpeningBalance = account(other, "OPENING_BALANCE", "Opening balance", EQUITY, null, false);
        entries.create(other, new OpeningBalanceCommand(AUG_1, null, othersCash, "RUB", money("777.00"), null));
    }

    @Test
    void balancesShowEveryAccountThatIsNotArchivedInEachCurrency() {
        assertThat(reports.balances(user, SEP_30)).containsExactly(
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
        assertThat(reports.counterpartyBalances(user, "LOANS_ASSET", SEP_6)).containsExactly(
                new CounterpartyBalance(friendA, "Friend A", "RUB", money("3000.00")),
                new CounterpartyBalance(friendB, "Friend B", "RUB", money("1000.00")));
        assertThat(reports.counterpartyBalances(user, "LOANS_ASSET", SEP_30)).containsExactly(
                new CounterpartyBalance(friendA, "Friend A", "RUB", money("2000.00")));
        // A LIABILITY reads as what is owed.
        assertThat(reports.counterpartyBalances(user, "CREDITOR_DEBT", SEP_30)).containsExactly(
                new CounterpartyBalance(bank, "Bank", "RUB", money("1500.00")));
    }

    @Test
    void counterpartyBalancesNeedAnAccountOfTheUserThatRequiresACounterparty() {
        assertThatThrownBy(() -> reports.counterpartyBalances(user, "CASH", SEP_30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account CASH has no balances per counterparty: it doesn't require one");
        assertThatThrownBy(() -> reports.counterpartyBalances(other, "LOANS_ASSET", SEP_30))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account LOANS_ASSET not found");
    }

    @Test
    void cashFlowIsPositiveForIncomeAndExpenseAndRefundsReduceIt() {
        assertThat(reports.cashFlow(user, AUG_1, SEP_30)).containsExactly(
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
        assertThat(reports.cashFlow(user, AUG_31, AUG_31)).containsExactly(
                cashFlow(2026, 8, "GROCERIES", "Groceries", EXPENSE, "EUR", "40.25"),
                cashFlow(2026, 8, "RESTAURANTS", "Restaurants", EXPENSE, "RUB", "362.77"));
        assertThat(reports.cashFlow(user, SEP_1, SEP_1)).containsExactly(
                cashFlow(2026, 9, "GROCERIES", "Groceries", EXPENSE, "RUB", "800.00"));
        assertThat(reports.cashFlow(user, SEP_1, OCT_1)).containsExactly(
                cashFlow(2026, 9, "GROCERIES", "Groceries", EXPENSE, "RUB", "600.00"),
                cashFlow(2026, 9, "SALARY", "Salary", INCOME, "EUR", "1000.00"),
                cashFlow(2026, 10, "GROCERIES", "Groceries", EXPENSE, "RUB", "99.99"));
    }

    @Test
    void cashFlowNeedsARangeInOrder() {
        assertThatThrownBy(() -> reports.cashFlow(user, SEP_30, SEP_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'from' must not be after 'to'");
    }

    @Test
    void netWorthIsAssetsMinusLiabilitiesPerCurrencyAndCountsArchivedAccounts() {
        assertThat(reports.netWorth(user, SEP_30)).containsExactly(
                new NetWorth("EUR", money("1609.75"), money("50.00"), money("1559.75")),
                // Assets: CASH 45923.95, LOANS_ASSET 2000.00 and the archived OLD_WALLET 300.00. Liabilities:
                // CREDITOR_DEBT 1500.00 and FAMILY_DEBT -362.78.
                new NetWorth("RUB", money("48223.95"), money("1137.22"), money("47086.73")));
    }

    @Test
    void sharedSettlementSaysWhoOwesWhomPerCurrency() {
        assertThat(reports.sharedSettlement(user, AUG_31)).containsExactly(
                new SharedSettlement(familyDebt, "FAMILY_DEBT", "RUB", money("-362.78"), USER_IS_OWED));
        assertThat(reports.sharedSettlement(user, SEP_30)).containsExactly(
                new SharedSettlement(familyDebt, "FAMILY_DEBT", "EUR", money("50.00"), USER_OWES),
                new SharedSettlement(familyDebt, "FAMILY_DEBT", "RUB", money("-362.78"), USER_IS_OWED));
    }

    @Test
    void sharedSettlementLeavesOutSettledCurrencies() {
        // The family budget pays the user back.
        create(new TransferCommand(SEP_30, null, null, familyDebt, cash, "RUB", money("362.78"), null));

        assertThat(reports.sharedSettlement(user, SEP_30)).containsExactly(
                new SharedSettlement(familyDebt, "FAMILY_DEBT", "EUR", money("50.00"), USER_OWES));
    }

    /** Positive on an ASSET account means the opposite of positive on FAMILY_DEBT, a LIABILITY. */
    @Test
    void sharedSettlementUsesTheSharedAccountFromTheSettingsWhateverItsType() {
        long partnerShare = account(user, "PARTNER_SHARE", "Partner's share", ASSET, null, false);
        settings.save(new UserSettings(user, "EUR", partnerShare, new BigDecimal("0.5000")));
        create(new SharedExpenseCommand(SEP_30, null, null, card, "EUR", money("30.00"), restaurants, null));

        assertThat(reports.sharedSettlement(user, SEP_30)).containsExactly(
                new SharedSettlement(partnerShare, "PARTNER_SHARE", "EUR", money("15.00"), USER_IS_OWED));
    }

    @Test
    void integrityCheckFindsNothingInALedgerWrittenThroughTheService() {
        assertThat(reports.integrityCheck(user)).isEmpty();
        assertThat(reports.integrityCheck(other)).isEmpty();
    }

    @Test
    void integrityCheckReportsPostingsThatDoNotSumToZero() {
        pastTheTriggers("UPDATE posting SET amount = amount + 0.01 WHERE entry_id = ? AND line_no = 0",
                cardOpening.id());

        assertThat(reports.integrityCheck(user)).containsExactly(
                new IntegrityViolation("EUR", money("0.01"), money("0.01")));
    }

    /** The entry still balances, but its account belongs to the other user now. */
    @Test
    void integrityCheckReportsAPostingOnAnotherUsersAccount() {
        pastTheTriggers("UPDATE posting SET account_id = ? WHERE entry_id = ? AND line_no = 0", othersCash,
                oldWalletOpening.id());

        assertThat(reports.integrityCheck(user)).containsExactly(
                new IntegrityViolation("RUB", money("0.00"), money("-300.00")));
        assertThat(reports.integrityCheck(other)).containsExactly(
                new IntegrityViolation("RUB", money("0.00"), money("300.00")));
    }

    @Test
    void anotherUserSeesOnlyTheirOwnLedger() {
        assertThat(reports.balances(other, SEP_30)).containsExactly(
                new AccountBalance(othersCash, "CASH", "Cash", ASSET, "RUB", money("777.00")),
                new AccountBalance(othersOpeningBalance, "OPENING_BALANCE", "Opening balance", EQUITY, "RUB",
                        money("777.00")));
        assertThat(reports.netWorth(other, SEP_30)).containsExactly(
                new NetWorth("RUB", money("777.00"), money("0.00"), money("777.00")));
        assertThat(reports.cashFlow(other, AUG_1, SEP_30)).isEmpty();
        assertThat(reports.sharedSettlement(other, SEP_30)).isEmpty();
    }

    private EntryView create(EntryCommand command) {
        return entries.create(user, command);
    }

    private BigDecimal balance(LocalDate asOf, long accountId, String currency) {
        return reports.balances(user, asOf).stream()
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

    private long account(String owner, String code, String name, AccountType type, String defaultCurrency,
            boolean requiresCounterparty) {
        return accounts.save(new Account(null, owner, code, name, type, defaultCurrency, requiresCounterparty, false,
                null, null)).id();
    }

    private void archive(long accountId) {
        Account account = accounts.findByIdAndUserId(accountId, user).orElseThrow();
        accounts.save(new Account(account.id(), account.userId(), account.code(), account.name(), account.type(),
                account.defaultCurrency(), account.requiresCounterparty(), account.isSystem(), Instant.now(),
                account.createdAt()));
    }

    private long category(String code, String name, CategoryType type) {
        return categories.save(new LedgerCategory(null, user, code, name, type, null)).id();
    }

    private long counterparty(String name) {
        return counterparties.save(new Counterparty(null, user, name, null, null)).id();
    }

    private static CashFlowRow cashFlow(int year, int month, String code, String name, CategoryType type,
            String currency, String total) {
        return new CashFlowRow(YearMonth.of(year, month), code, name, type, currency, money(total));
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
