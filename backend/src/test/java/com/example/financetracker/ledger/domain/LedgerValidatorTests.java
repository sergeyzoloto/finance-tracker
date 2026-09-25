package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.EntryBuilderTests.CASH;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.CONTEXT;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.DATE;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.FAMILY_DEBT_ID;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.FRIEND_A;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.FX_EXCHANGE_ID;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.LOANS_ASSET_ID;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.MTS_SALARY;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.OPENING_BALANCE_ID;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.REST;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.ROCKET;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.SALARY;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.SBER_MOMENTUM;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.SHOP;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.UNALLOCATED_ID;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.money;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.posting;
import static com.example.financetracker.ledger.domain.EntryBuilderTests.validCommands;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.financetracker.ledger.domain.LedgerReferences.AccountInfo;
import com.example.financetracker.ledger.domain.LedgerReferences.CategoryInfo;
import org.junit.jupiter.api.Test;

/** Each rule the database enforces on an entry, caught before the database sees it. Names are invented. */
class LedgerValidatorTests {

    /** The user's rows. Anything else, another user's rows included, doesn't exist for the validator. */
    private static final LedgerReferences REFERENCES = new LedgerReferences(
            Map.of(
                    MTS_SALARY, asset("MTS_SALARY"),
                    CASH, asset("CASH"),
                    ROCKET, asset("ROCKET"),
                    SBER_MOMENTUM, asset("SBER_MOMENTUM"),
                    UNALLOCATED_ID, equity("UNALLOCATED"),
                    FAMILY_DEBT_ID, new AccountInfo("FAMILY_DEBT", AccountType.LIABILITY, false),
                    LOANS_ASSET_ID, new AccountInfo("LOANS_ASSET", AccountType.ASSET, true),
                    OPENING_BALANCE_ID, equity("OPENING_BALANCE"),
                    FX_EXCHANGE_ID, equity("FX_EXCHANGE")),
            Map.of(
                    REST, new CategoryInfo("REST", CategoryType.EXPENSE),
                    SALARY, new CategoryInfo("SALARY", CategoryType.INCOME)),
            Set.of(FRIEND_A, SHOP));
    private static final long UNKNOWN = 999;

    private final LedgerValidator validator = new LedgerValidator();

    @Test
    void unbalancedManualEntryIsRejectedNamingTheCurrencyAndTheDifference() {
        var manual = new ManualCommand(DATE, null, "typo", List.of(
                posting(CASH, "RUB", "-100.00"),
                new PostingLine(UNALLOCATED_ID, "RUB", money("110.00"), REST, null)));

        assertThatThrownBy(() -> validator.check(manual.draft(CONTEXT), REFERENCES))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessage("Invalid entry: the postings in RUB do not balance: debits 110.00, credits 100.00, "
                        + "difference 10.00");
    }

    @Test
    void balanceIsCheckedPerCurrency() {
        List<String> violations = violations(
                posting(ROCKET, "RUB", "-7500.00"),
                posting(FX_EXCHANGE_ID, "RUB", "7500.00"),
                posting(FX_EXCHANGE_ID, "EUR", "-100.00"),
                posting(CASH, "EUR", "100.01"));

        assertThat(violations).containsExactly(
                "the postings in EUR do not balance: debits 100.01, credits 100.00, difference 0.01");
    }

    @Test
    void everyValidTemplateEntryPasses() {
        for (EntryCommand command : validCommands()) {
            assertThatCode(() -> validator.check(command.draft(CONTEXT), REFERENCES))
                    .as(command.kind().name())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void refundUnderTheSameCategoryIsValid() {
        var refund = new ExpenseCommand(DATE, null, null, CASH, "RUB", money("-1400.00"), REST);

        assertThat(validator.violations(refund.draft(CONTEXT), REFERENCES)).isEmpty();
    }

    @Test
    void allViolationsAreListedInOneMessage() {
        var draft = new EntryDraft(null, EntryKind.MANUAL, UNKNOWN, "x".repeat(501), List.of(
                new PostingLine(CASH, "RUB", money("-10.00"), REST, null),
                new PostingLine(LOANS_ASSET_ID, "rub", money("10.00"), null, null),
                new PostingLine(UNKNOWN, "RUB", money("0.00"), UNKNOWN, UNKNOWN)));

        assertThatThrownBy(() -> validator.check(draft, REFERENCES))
                .isInstanceOfSatisfying(InvalidEntryException.class, e -> assertThat(e.violations()).containsExactly(
                        "the entry date is missing",
                        "the memo has 501 characters, more than 500",
                        "payee 999 does not exist",
                        "posting 1 (CASH): the account is ASSET, and only postings to EQUITY accounts can have a category",
                        "posting 2 (LOANS_ASSET): 'rub' is not an ISO 4217 currency code",
                        "posting 2 (LOANS_ASSET): the account requires a counterparty",
                        "posting 3: account 999 does not exist",
                        "posting 3: the amount is zero",
                        "posting 3: category 999 does not exist",
                        "posting 3: counterparty 999 does not exist",
                        "the postings in RUB do not balance: debits 0.00, credits 10.00, difference 10.00",
                        "the postings in rub do not balance: debits 10.00, credits 0.00, difference 10.00"))
                .hasMessageStartingWith("Invalid entry: the entry date is missing; the memo has 501 characters");
    }

    @Test
    void entryNeedsAtLeastTwoPostings() {
        assertThat(violations()).containsExactly("an entry needs at least 2 postings, this one has 0");
        assertThat(violations(posting(CASH, "RUB", "1.00"))).containsExactly(
                "an entry needs at least 2 postings, this one has 1",
                "the postings in RUB do not balance: debits 1.00, credits 0.00, difference 1.00");
    }

    @Test
    void categoryGoesOnlyOnPostingsToEquityAccounts() {
        assertThat(violations(
                new PostingLine(CASH, "RUB", money("-5.00"), REST, null),
                new PostingLine(UNALLOCATED_ID, "RUB", money("5.00"), REST, null)))
                .containsExactly("posting 1 (CASH): the account is ASSET, "
                        + "and only postings to EQUITY accounts can have a category");
    }

    @Test
    void accountThatRequiresACounterpartyGetsOneOnEveryPosting() {
        assertThat(violations(
                posting(ROCKET, "RUB", "-675.50"),
                posting(LOANS_ASSET_ID, "RUB", "675.50")))
                .containsExactly("posting 2 (LOANS_ASSET): the account requires a counterparty");
    }

    /** Another user's rows are left out of the references, so they read as not existing (rule 11). */
    @Test
    void referencesOutsideTheUsersRowsDoNotExist() {
        var draft = new EntryDraft(DATE, EntryKind.MANUAL, UNKNOWN, null, List.of(
                new PostingLine(UNKNOWN, "RUB", money("-5.00"), null, null),
                new PostingLine(UNALLOCATED_ID, "RUB", money("5.00"), UNKNOWN, UNKNOWN)));

        assertThat(validator.violations(draft, REFERENCES)).containsExactly(
                "payee 999 does not exist",
                "posting 1: account 999 does not exist",
                "posting 2 (UNALLOCATED): category 999 does not exist",
                "posting 2 (UNALLOCATED): counterparty 999 does not exist");
    }

    @Test
    void amountMustFitNumeric19Point4() {
        assertThat(violations(posting(CASH, "RUB", "-1.00001"), posting(ROCKET, "RUB", "1.00001")))
                .containsExactly(
                        "posting 1 (CASH): the amount -1.00001 has more than 4 decimal places",
                        "posting 2 (ROCKET): the amount 1.00001 has more than 4 decimal places");
        assertThat(violations(posting(CASH, "RUB", "-1000000000000000"), posting(ROCKET, "RUB", "1E+15")))
                .containsExactly(
                        "posting 1 (CASH): the amount -1000000000000000 has more than 15 digits before the decimal point",
                        "posting 2 (ROCKET): the amount 1000000000000000 has more than 15 digits before the decimal point");
        // Trailing zeros are no extra precision, and the largest amount that fits is accepted.
        assertThat(violations(posting(CASH, "RUB", "-999999999999999.999900"),
                posting(ROCKET, "RUB", "999999999999999.9999"))).isEmpty();
    }

    @Test
    void currencyIsAnIso4217Code() {
        assertThat(violations(posting(CASH, "ABC", "-1.00"), posting(ROCKET, "ABC", "1.00")))
                .containsExactly(
                        "posting 1 (CASH): 'ABC' is not an ISO 4217 currency code",
                        "posting 2 (ROCKET): 'ABC' is not an ISO 4217 currency code");
    }

    @Test
    void missingFieldsOfARawPostingAreNamed() {
        assertThat(violations(new PostingLine(null, null, null, null, null), posting(ROCKET, "RUB", "1.00")))
                .containsExactly(
                        "posting 1: the account is missing",
                        "posting 1: the currency is missing",
                        "posting 1: the amount is missing",
                        "the postings in RUB do not balance: debits 1.00, credits 0.00, difference 1.00");
    }

    /** Characters as PostgreSQL counts them for VARCHAR(500): an emoji is one, not two UTF-16 units. */
    @Test
    void memoLengthCountsCharacters() {
        String emojis = "💰".repeat(500);
        var draft = new EntryDraft(DATE, EntryKind.MANUAL, null, emojis,
                List.of(posting(CASH, "RUB", "-1.00"), posting(ROCKET, "RUB", "1.00")));

        assertThat(validator.violations(draft, REFERENCES)).isEmpty();
    }

    @Test
    void expenseAndIncomeTakeOnlyCategoriesOfTheirType() {
        var expense = new ExpenseCommand(DATE, null, null, CASH, "RUB", money("190.00"), SALARY);
        var income = new IncomeCommand(DATE, null, null, MTS_SALARY, "RUB", money("100.00"), REST);
        var shared = new SharedExpenseCommand(DATE, null, null, CASH, "RUB", money("100.00"), SALARY, null);

        assertThat(validator.violations(expense.draft(CONTEXT), REFERENCES)).containsExactly(
                "posting 2 (UNALLOCATED): category SALARY is INCOME, and an entry of kind EXPENSE needs EXPENSE categories");
        assertThat(validator.violations(income.draft(CONTEXT), REFERENCES)).containsExactly(
                "posting 2 (UNALLOCATED): category REST is EXPENSE, and an entry of kind INCOME needs INCOME categories");
        assertThat(validator.violations(shared.draft(CONTEXT), REFERENCES)).containsExactly("posting 2 (UNALLOCATED): "
                + "category SALARY is INCOME, and an entry of kind SHARED_EXPENSE needs EXPENSE categories");
    }

    /** A raw entry may use any category on an EQUITY account; its kind restricts nothing. */
    @Test
    void manualEntryTakesCategoriesOfEitherType() {
        assertThat(violations(
                new PostingLine(UNALLOCATED_ID, "RUB", money("-5.00"), SALARY, null),
                new PostingLine(UNALLOCATED_ID, "RUB", money("5.00"), REST, null))).isEmpty();
    }

    /** A shared expense too small to split leaves nothing for one side, and a zero posting is refused. */
    @Test
    void sharedExpenseOfOneCentIsRefused() {
        var shared = new SharedExpenseCommand(DATE, null, null, CASH, "RUB", money("0.01"), REST, null);

        assertThat(validator.violations(shared.draft(CONTEXT), REFERENCES))
                .containsExactly("posting 2 (UNALLOCATED): the amount is zero");
    }

    private List<String> violations(PostingLine... postings) {
        return validator.violations(new EntryDraft(DATE, EntryKind.MANUAL, null, null, List.of(postings)), REFERENCES);
    }

    private static AccountInfo asset(String code) {
        return new AccountInfo(code, AccountType.ASSET, false);
    }

    private static AccountInfo equity(String code) {
        return new AccountInfo(code, AccountType.EQUITY, false);
    }
}
