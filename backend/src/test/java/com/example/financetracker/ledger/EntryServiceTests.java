package com.example.financetracker.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.CurrencyExchangeCommand;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.ExpenseCommand;
import com.example.financetracker.ledger.domain.InvalidEntryException;
import com.example.financetracker.ledger.domain.LoanGivenCommand;
import com.example.financetracker.ledger.domain.ManualCommand;
import com.example.financetracker.ledger.domain.PostingLine;
import com.example.financetracker.ledger.domain.SharedExpenseCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link EntryService} against real PostgreSQL with the ledger's triggers. Every test runs as a fresh user with
 * invented accounts.
 */
class EntryServiceTests extends IntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 25);

    @Autowired
    private EntryService service;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private LedgerCategoryRepository categories;
    @Autowired
    private CounterpartyRepository counterparties;
    @Autowired
    private UserSettingsRepository settings;
    @Autowired
    private JournalEntryRepository entries;
    @Autowired
    private JdbcClient jdbc;

    private final String user = UUID.randomUUID().toString();
    private long cash, rocket, unallocated, familyDebt, loans, fxExchange, rest, friendA;

    @BeforeEach
    void createAccounts() {
        cash = account(user, "CASH", AccountType.ASSET, false);
        rocket = account(user, "ROCKET", AccountType.ASSET, false);
        unallocated = account(user, "UNALLOCATED", AccountType.EQUITY, false);
        familyDebt = account(user, "FAMILY_DEBT", AccountType.LIABILITY, false);
        loans = account(user, "LOANS_ASSET", AccountType.ASSET, true);
        fxExchange = account(user, "FX_EXCHANGE", AccountType.EQUITY, false);
        rest = categories.save(new LedgerCategory(null, user, "REST", "Rest", CategoryType.EXPENSE, null)).id();
        friendA = counterparties.save(new Counterparty(null, user, "Friend A", Counterparty.Kind.PERSON, null)).id();
    }

    @Test
    void createWritesTheEntryWithItsPostingsInOrder() {
        EntryView created = service.create(user,
                new SharedExpenseCommand(DATE, null, "dinner", rocket, "RUB", money("725.55"), rest, null));

        assertThat(created.version()).isZero();
        assertThat(created.kind()).isEqualTo(EntryKind.SHARED_EXPENSE);
        assertThat(created.postings()).containsExactly(
                posting(rocket, "RUB", "-725.55"),
                new PostingLine(unallocated, "RUB", money("362.77"), rest, null),
                posting(familyDebt, "RUB", "362.78"));
        assertThat(service.get(user, created.id())).isEqualTo(created);
        assertThat(jdbc.sql("SELECT line_no FROM posting WHERE entry_id = ? ORDER BY id").param(created.id())
                .query(Integer.class).list()).containsExactly(0, 1, 2);
    }

    @Test
    void sharedExpenseUsesTheAccountAndRatioFromTheUsersSettings() {
        long partnerDebt = account(user, "PARTNER_DEBT", AccountType.LIABILITY, false);
        settings.save(new UserSettings(user, "EUR", partnerDebt, money("0.3000")));

        EntryView created = service.create(user,
                new SharedExpenseCommand(DATE, null, null, cash, "EUR", money("100.00"), rest, null));

        assertThat(created.postings()).extracting(PostingLine::accountId, PostingLine::amount).containsExactly(
                tuple(cash, money("-100.00")),
                tuple(unallocated, money("70.00")),
                tuple(partnerDebt, money("30.00")));
    }

    @Test
    void updateReplacesAllPostingsAndBumpsTheVersion() {
        EntryView created = service.create(user, expense("190.00"));

        EntryView updated = service.update(user, created.id(), created.version(), new CurrencyExchangeCommand(DATE,
                null, "exchange office", rocket, "RUB", money("7500.00"), cash, "EUR", money("100.00")));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.kind()).isEqualTo(EntryKind.CURRENCY_EXCHANGE);
        assertThat(updated.postings()).containsExactly(
                posting(rocket, "RUB", "-7500.00"),
                posting(fxExchange, "RUB", "7500.00"),
                posting(fxExchange, "EUR", "-100.00"),
                posting(cash, "EUR", "100.00"));
        assertThat(service.get(user, created.id())).isEqualTo(updated);
        assertThat(postingCount(created.id())).isEqualTo(4);
    }

    @Test
    void updateWithAStaleVersionIsAConflictAndChangesNothing() {
        EntryView created = service.create(user, expense("190.00"));
        JournalEntry readBeforeUpdate = entries.findByIdAndUserId(created.id(), user).orElseThrow();
        EntryView updated = service.update(user, created.id(), 0, expense("200.00"));

        assertThatThrownBy(() -> service.update(user, created.id(), 0, expense("300.00")))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessage("Journal entry %d has changed since version 0. Reload it and try again."
                        .formatted(created.id()));
        // The UPDATE itself checks the version too, so a change between the service's check and its save loses.
        assertThatThrownBy(() -> entries.save(readBeforeUpdate)).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(service.get(user, created.id())).isEqualTo(updated);
    }

    @Test
    void deleteRemovesTheEntryAndItsPostings() {
        EntryView created = service.create(user, expense("190.00"));

        service.delete(user, created.id(), created.version());

        assertThatThrownBy(() -> service.get(user, created.id())).isInstanceOf(EntryNotFoundException.class);
        assertThat(postingCount(created.id())).isZero();
    }

    @Test
    void deleteWithAStaleVersionIsAConflictAndKeepsTheEntry() {
        EntryView created = service.create(user, expense("190.00"));
        EntryView updated = service.update(user, created.id(), 0, expense("200.00"));

        assertThatThrownBy(() -> service.delete(user, created.id(), 0))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(service.get(user, created.id())).isEqualTo(updated);
    }

    @Test
    void anotherUsersEntryIsNotFound() {
        EntryView created = service.create(user, expense("190.00"));
        String other = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.get(other, created.id())).isInstanceOf(EntryNotFoundException.class);
        assertThatThrownBy(() -> service.update(other, created.id(), 0, expense("1.00")))
                .isInstanceOf(EntryNotFoundException.class);
        assertThatThrownBy(() -> service.delete(other, created.id(), 0)).isInstanceOf(EntryNotFoundException.class);
        assertThat(service.get(user, created.id())).isEqualTo(created);
    }

    /** The validator's message, not a trigger's SQL error, and nothing is written. */
    @Test
    void invalidEntryIsRejectedBeforeTheDatabaseSeesIt() {
        String other = UUID.randomUUID().toString();
        long othersAccount = account(other, "CASH", AccountType.ASSET, false);

        assertThatThrownBy(() -> service.create(user, new ManualCommand(DATE, null, null, List.of(
                posting(othersAccount, "RUB", "-100.00"),
                posting(loans, "RUB", "90.00")))))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessage("Invalid entry: posting 1: account %d does not exist; ".formatted(othersAccount)
                        + "posting 2 (LOANS_ASSET): the account requires a counterparty; "
                        + "the postings in RUB do not balance: debits 90.00, credits 100.00, difference 10.00");
        assertThat(entryCount()).isZero();
    }

    @Test
    void loanNeedsTheUsersLoansAccount() {
        String newcomer = UUID.randomUUID().toString();
        long newcomersCash = account(newcomer, "CASH", AccountType.ASSET, false);

        assertThatThrownBy(() -> service.create(newcomer,
                new LoanGivenCommand(DATE, null, null, newcomersCash, friendA, "RUB", money("675.50"))))
                .isInstanceOf(InvalidEntryException.class)
                .hasMessage("Invalid entry: the account LOANS_ASSET does not exist");
    }

    private ExpenseCommand expense(String amount) {
        return new ExpenseCommand(DATE, friendA, null, cash, "RUB", money(amount), rest);
    }

    private long account(String owner, String code, AccountType type, boolean requiresCounterparty) {
        return accounts.save(new Account(null, owner, code, code, type, null, requiresCounterparty, false, null, null))
                .id();
    }

    private long postingCount(long entryId) {
        return jdbc.sql("SELECT count(*) FROM posting WHERE entry_id = ?").param(entryId).query(Long.class).single();
    }

    private long entryCount() {
        return jdbc.sql("SELECT count(*) FROM journal_entry WHERE user_id = ?").param(user).query(Long.class).single();
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    private static PostingLine posting(long account, String currency, String amount) {
        return new PostingLine(account, currency, money(amount), null, null);
    }
}
