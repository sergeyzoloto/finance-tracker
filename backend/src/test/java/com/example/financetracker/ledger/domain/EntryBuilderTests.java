package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.AccountRole.FX_EXCHANGE;
import static com.example.financetracker.ledger.domain.AccountRole.LOANS;
import static com.example.financetracker.ledger.domain.AccountRole.OPENING_BALANCE;
import static com.example.financetracker.ledger.domain.AccountRole.SHARED;
import static com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/**
 * The postings each command builds, without Spring or a database. Amounts are compared as exact decimals, scale
 * included. Account, category and counterparty names are invented.
 */
class EntryBuilderTests {

    static final long MTS_SALARY = 1, CASH = 2, ROCKET = 3, SBER_MOMENTUM = 4, PARTNER_DEBT = 5;
    static final long UNALLOCATED_ID = 11, FAMILY_DEBT_ID = 12, LOANS_ASSET_ID = 13, OPENING_BALANCE_ID = 14,
            FX_EXCHANGE_ID = 15;
    static final long REST = 21, SALARY = 22;
    static final long FRIEND_A = 31, SHOP = 32;
    static final LocalDate DATE = LocalDate.of(2026, 9, 25);

    static final LedgerContext CONTEXT = new LedgerContext(Map.of(
            UNALLOCATED, UNALLOCATED_ID,
            SHARED, FAMILY_DEBT_ID,
            LOANS, LOANS_ASSET_ID,
            OPENING_BALANCE, OPENING_BALANCE_ID,
            FX_EXCHANGE, FX_EXCHANGE_ID), money("0.50"));

    @Test
    void expenseMovesTheAmountFromTheAccountToUnallocatedUnderTheCategory() {
        var expense = new ExpenseCommand(DATE, SHOP, "lunch", MTS_SALARY, "RUB", money("190.00"), REST);

        assertThat(expense.postings(CONTEXT)).containsExactly(
                posting(MTS_SALARY, "RUB", "-190.00"),
                new PostingLine(UNALLOCATED_ID, "RUB", money("190.00"), REST, null));
        assertThat(expense.kind()).isEqualTo(EntryKind.EXPENSE);
    }

    @Test
    void refundIsAnExpenseWithTheOppositeSign() {
        var refund = new ExpenseCommand(DATE, null, null, CASH, "RUB", money("-1400.00"), REST);

        assertThat(refund.postings(CONTEXT)).containsExactly(
                posting(CASH, "RUB", "1400.00"),
                new PostingLine(UNALLOCATED_ID, "RUB", money("-1400.00"), REST, null));
    }

    @Test
    void incomeMovesTheAmountFromUnallocatedToTheAccount() {
        var income = new IncomeCommand(DATE, null, null, MTS_SALARY, "RUB", money("50000.00"), SALARY);

        assertThat(income.postings(CONTEXT)).containsExactly(
                posting(MTS_SALARY, "RUB", "50000.00"),
                new PostingLine(UNALLOCATED_ID, "RUB", money("-50000.00"), SALARY, null));
    }

    @Test
    void sharedExpenseOfAnEvenAmountSplitsIntoEqualParts() {
        var shared = new SharedExpenseCommand(DATE, SHOP, null, ROCKET, "RUB", money("8687.00"), REST, null);

        assertThat(shared.postings(CONTEXT)).containsExactly(
                posting(ROCKET, "RUB", "-8687.00"),
                new PostingLine(UNALLOCATED_ID, "RUB", money("4343.50"), REST, null),
                posting(FAMILY_DEBT_ID, "RUB", "4343.50"));
    }

    /** The other part rounds half up, and the own part is what is left, so no cent appears or disappears. */
    @Test
    void sharedExpenseGivesTheOddHalfCentToTheOtherPart() {
        var shared = new SharedExpenseCommand(DATE, null, null, ROCKET, "RUB", money("725.55"), REST, money("0.50"));

        List<PostingLine> postings = shared.postings(CONTEXT);

        assertThat(postings).containsExactly(
                posting(ROCKET, "RUB", "-725.55"),
                new PostingLine(UNALLOCATED_ID, "RUB", money("362.77"), REST, null),
                posting(FAMILY_DEBT_ID, "RUB", "362.78"));
        assertThat(postings.get(1).amount().add(postings.get(2).amount())).isEqualTo(money("725.55"));
    }

    /** HALF_UP rounds away from zero, so a refund splits exactly like the expense it undoes. */
    @Test
    void sharedRefundSplitsLikeTheExpense() {
        var refund = new SharedExpenseCommand(DATE, null, null, ROCKET, "RUB", money("-725.55"), REST, null);

        assertThat(refund.postings(CONTEXT)).containsExactly(
                posting(ROCKET, "RUB", "725.55"),
                new PostingLine(UNALLOCATED_ID, "RUB", money("-362.77"), REST, null),
                posting(FAMILY_DEBT_ID, "RUB", "-362.78"));
    }

    @Test
    void sharedExpenseTakesItsOwnRatioOverTheDefaultAndTheSharedAccountFromTheContext() {
        var context = new LedgerContext(Map.of(UNALLOCATED, UNALLOCATED_ID, SHARED, PARTNER_DEBT), money("0.40"));

        var ownRatio = new SharedExpenseCommand(DATE, null, null, CASH, "EUR", money("100.00"), REST, money("0.3"));
        var defaultRatio = new SharedExpenseCommand(DATE, null, null, CASH, "EUR", money("100.00"), REST, null);

        assertThat(ownRatio.postings(context)).extracting(PostingLine::accountId, PostingLine::amount).containsExactly(
                tuple(CASH, "-100.00"), tuple(UNALLOCATED_ID, "70.00"), tuple(PARTNER_DEBT, "30.00"));
        assertThat(defaultRatio.postings(context)).extracting(PostingLine::accountId, PostingLine::amount)
                .containsExactly(tuple(CASH, "-100.00"), tuple(UNALLOCATED_ID, "60.00"), tuple(PARTNER_DEBT, "40.00"));
    }

    @Test
    void loanGivenAndPartlyRepaidLeavesTheRestOwedByTheBorrower() {
        var given = new LoanGivenCommand(DATE, null, null, ROCKET, FRIEND_A, "RUB", money("675.50"));
        var repaid = new LoanRepaidCommand(DATE.plusDays(10), null, null, SBER_MOMENTUM, FRIEND_A, "RUB",
                money("350.00"));

        assertThat(given.postings(CONTEXT)).containsExactly(
                posting(ROCKET, "RUB", "-675.50"),
                new PostingLine(LOANS_ASSET_ID, "RUB", money("675.50"), null, FRIEND_A));
        assertThat(repaid.postings(CONTEXT)).containsExactly(
                new PostingLine(LOANS_ASSET_ID, "RUB", money("-350.00"), null, FRIEND_A),
                posting(SBER_MOMENTUM, "RUB", "350.00"));
        BigDecimal owedByFriendA = concat(given.postings(CONTEXT), repaid.postings(CONTEXT)).stream()
                .filter(p -> p.accountId() == LOANS_ASSET_ID && Objects.equals(p.counterpartyId(), FRIEND_A))
                .map(PostingLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(owedByFriendA).isEqualTo(money("325.50"));
    }

    @Test
    void currencyExchangeHasFourPostingsThatBalanceInEachCurrency() {
        var exchange = new CurrencyExchangeCommand(DATE, null, null, ROCKET, "RUB", money("7500.00"), CASH, "EUR",
                money("100.00"));

        List<PostingLine> postings = exchange.postings(CONTEXT);

        assertThat(postings).containsExactly(
                posting(ROCKET, "RUB", "-7500.00"),
                posting(FX_EXCHANGE_ID, "RUB", "7500.00"),
                posting(FX_EXCHANGE_ID, "EUR", "-100.00"),
                posting(CASH, "EUR", "100.00"));
        assertThat(sumsByCurrency(postings)).containsOnlyKeys("EUR", "RUB")
                .allSatisfy((currency, sum) -> assertThat(sum).isZero());
    }

    @Test
    void transferPutsTheCounterpartyOnBothPostings() {
        var transfer = new TransferCommand(DATE, null, null, ROCKET, CASH, "RUB", money("1000.00"), FRIEND_A);

        assertThat(transfer.postings(CONTEXT)).containsExactly(
                new PostingLine(ROCKET, "RUB", money("-1000.00"), null, FRIEND_A),
                new PostingLine(CASH, "RUB", money("1000.00"), null, FRIEND_A));
    }

    @Test
    void openingBalanceIsPostedAgainstOpeningBalance() {
        var opening = new OpeningBalanceCommand(DATE, null, CASH, "RUB", money("12000.00"), null);

        assertThat(opening.postings(CONTEXT)).containsExactly(
                posting(CASH, "RUB", "12000.00"),
                posting(OPENING_BALANCE_ID, "RUB", "-12000.00"));
        assertThat(opening.payeeId()).isNull();
    }

    @Test
    void manualEntryKeepsItsPostingsAsGiven() {
        List<PostingLine> postings = List.of(posting(CASH, "RUB", "-5.00"), posting(ROCKET, "RUB", "5.00"));

        assertThat(new ManualCommand(DATE, null, null, postings).postings(CONTEXT)).isEqualTo(postings);
    }

    /** Every built entry balances, so that the validator and the database only ever see a balanced template. */
    @Test
    void everyTemplateBalancesInEveryCurrency() {
        for (EntryCommand command : validCommands()) {
            assertThat(sumsByCurrency(command.postings(CONTEXT)))
                    .as(command.kind().name())
                    .allSatisfy((currency, sum) -> assertThat(sum).isZero());
        }
    }

    @Test
    void commandNamesEveryFieldItCannotBuildWith() {
        assertThatThrownBy(() -> new ExpenseCommand(null, null, null, null, "RUB", money("0.00"), null))
                .isInstanceOfSatisfying(InvalidEntryException.class, e -> assertThat(e.violations()).containsExactly(
                        "the entry date is missing", "the account is missing", "the amount must not be zero",
                        "the category is missing"))
                .hasMessage("Invalid entry: the entry date is missing; the account is missing; "
                        + "the amount must not be zero; the category is missing");
    }

    @Test
    void commandsRejectAmountsAndChoicesTheirTemplateDoesNotAllow() {
        assertInvalid(() -> new TransferCommand(DATE, null, null, CASH, ROCKET, "RUB", money("-1.00"), null),
                "the amount must be positive");
        assertInvalid(() -> new TransferCommand(DATE, null, null, CASH, CASH, "RUB", money("1.00"), null),
                "the two accounts must differ");
        assertInvalid(() -> new LoanGivenCommand(DATE, null, null, ROCKET, null, "RUB", money("1.00")),
                "the borrower is missing");
        assertInvalid(() -> new CurrencyExchangeCommand(DATE, null, null, ROCKET, "RUB", money("1.00"), CASH, "RUB",
                money("1.00")), "the two currencies must differ; money moved within one currency is a transfer");
        assertInvalid(() -> new SharedExpenseCommand(DATE, null, null, CASH, "RUB", money("1.00"), REST, money("1")),
                "the share ratio must be greater than 0 and less than 1");
        assertInvalid(() -> new ManualCommand(DATE, null, null, null), "the list of postings is missing");
    }

    @Test
    void missingRoleAccountIsNamed() {
        var context = new LedgerContext(Map.of(), money("0.50"));
        var expense = new ExpenseCommand(DATE, null, null, CASH, "RUB", money("190.00"), REST);

        assertThatThrownBy(() -> expense.postings(context))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessage("Invalid entry: the account UNALLOCATED does not exist");
    }

    /** One valid command of every kind. */
    static List<EntryCommand> validCommands() {
        return List.of(
                new ExpenseCommand(DATE, SHOP, null, MTS_SALARY, "RUB", money("190.00"), REST),
                new IncomeCommand(DATE, null, null, MTS_SALARY, "RUB", money("50000.00"), SALARY),
                new TransferCommand(DATE, null, null, ROCKET, CASH, "RUB", money("1000.00"), null),
                new SharedExpenseCommand(DATE, SHOP, null, ROCKET, "RUB", money("725.55"), REST, null),
                new LoanGivenCommand(DATE, null, null, ROCKET, FRIEND_A, "RUB", money("675.50")),
                new LoanRepaidCommand(DATE, null, null, SBER_MOMENTUM, FRIEND_A, "RUB", money("350.00")),
                new CurrencyExchangeCommand(DATE, null, null, ROCKET, "RUB", money("7500.00"), CASH, "EUR",
                        money("100.00")),
                new OpeningBalanceCommand(DATE, null, CASH, "RUB", money("12000.00"), null),
                new ManualCommand(DATE, null, "correction",
                        List.of(posting(CASH, "RUB", "-5.00"), posting(ROCKET, "RUB", "5.00"))));
    }

    static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    static PostingLine posting(long account, String currency, String amount) {
        return PostingLine.of(account, currency, money(amount));
    }

    static Map<String, BigDecimal> sumsByCurrency(List<PostingLine> postings) {
        Map<String, BigDecimal> sums = new TreeMap<>();
        postings.forEach(p -> sums.merge(p.currency(), p.amount(), BigDecimal::add));
        return sums;
    }

    private static Tuple tuple(long account, String amount) {
        return Tuple.tuple(account, money(amount));
    }

    private static List<PostingLine> concat(List<PostingLine> first, List<PostingLine> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    private static void assertInvalid(ThrowingCallable command, String problem) {
        assertThatThrownBy(command)
                .isInstanceOfSatisfying(InvalidEntryException.class,
                        e -> assertThat(e.violations()).containsExactly(problem));
    }
}
