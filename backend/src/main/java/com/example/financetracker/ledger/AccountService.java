package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

import com.example.financetracker.WallClock;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A ledger's accounts. Callers pass the {@link LedgerScope} that LedgerAccess resolved (rule 11). Accounts are
 * archived, never deleted (rule 12). Their type and whether they require a counterparty are fixed once created:
 * postings were checked against both.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final JdbcClient jdbc;

    AccountService(AccountRepository accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    /** All the ledger's accounts, archived ones included, by code. */
    @Transactional(readOnly = true)
    public List<AccountView> list(LedgerScope ledger) {
        return accounts.findAll(ledger).stream().map(AccountView::of).toList();
    }

    /**
     * @param defaultCurrency null for none
     * @throws ConflictException if the ledger has an account with this code already
     */
    @Transactional
    public AccountView create(LedgerScope ledger, String code, String name, AccountType type, String defaultCurrency,
            boolean requiresCounterparty) {
        try {
            return AccountView.of(accounts.save(new Account(null, ledger.userId(), ledger.ledgerId(), code, name, type,
                    defaultCurrency, requiresCounterparty, false, null, null)));
        } catch (DbActionExecutionException e) {
            // Spring Data JDBC wraps what the database refuses.
            if (e.getCause() instanceof DuplicateKeyException) {
                throw new ConflictException("You have an account with the code %s already".formatted(code));
            }
            throw e;
        }
    }

    /**
     * @throws AccountNotFoundException if the ledger has no such account
     * @throws ConflictException if the changes rename or archive a system account, archive UNALLOCATED, or archive an
     *         account that still holds money (D-366)
     */
    @Transactional
    public AccountView update(LedgerScope ledger, long accountId, AccountChanges changes) {
        Account account = accounts.find(ledger, accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (account.isSystem()) {
            if (changes.name() != null && !changes.name().equals(account.name())) {
                throw new ConflictException("%s is a system account and can't be renamed".formatted(account.code()));
            }
            if (Boolean.TRUE.equals(changes.archived())) {
                throw new ConflictException("%s is a system account and can't be archived".formatted(account.code()));
            }
        }
        if (account.code().equals(AccountRole.UNALLOCATED.defaultCode()) && Boolean.TRUE.equals(changes.archived())
                && account.archivedAt() == null) {
            // D-8: the family budget posts every share there, so it is always there to find (F4a).
            throw new ConflictException("%s can be renamed, but not archived: a family budget posts its shares there"
                    .formatted(account.code()));
        }
        if (Boolean.TRUE.equals(changes.archived()) && account.archivedAt() == null) {
            refuseWhileItHoldsMoney(ledger, account);
        }
        String name = changes.name() != null ? changes.name() : account.name();
        String defaultCurrency = changes.changesDefaultCurrency()
                ? changes.defaultCurrency()
                : account.defaultCurrency();
        return AccountView.of(accounts.save(new Account(account.id(), account.userId(), account.ledgerId(),
                account.code(), name, account.type(),
                defaultCurrency, account.requiresCounterparty(), account.isSystem(),
                archivedAt(account.archivedAt(), changes.archived()), account.createdAt())));
    }

    /**
     * D-366: an account is archived only at zero. Its balance in each currency, over all its postings whatever their
     * date, must round to 0 at that currency's minor unit (HALF_UP, as D-349's test does on the dashboard), so that
     * RUB 0.004 left by the import's four decimals doesn't keep an account open. The account's row is locked first:
     * an entry being written to it holds the row FOR SHARE ({@code EntryService}), so it commits before the sum, which
     * then counts it, or waits until the archive is done. Later entries may still post to it: D-366 is checked when
     * the account is archived, as an archived account's entries stay editable.
     */
    private void refuseWhileItHoldsMoney(LedgerScope ledger, Account account) {
        jdbc.sql("SELECT id FROM account WHERE ledger_id = :ledger AND id = :id FOR UPDATE")
                .param("ledger", ledger.ledgerId()).param("id", account.id()).query(Long.class).single();
        List<Remaining> remaining = jdbc.sql("""
                SELECT p.currency, sum(p.amount) AS amount
                FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE e.ledger_id = :ledger AND p.account_id = :id
                GROUP BY p.currency ORDER BY p.currency""")
                .param("ledger", ledger.ledgerId()).param("id", account.id())
                .query((rs, n) -> new Remaining(rs.getString("currency"), rs.getBigDecimal("amount"))).list()
                .stream().filter(r -> !roundsToZero(r.amount(), r.currency())).toList();
        if (remaining.isEmpty()) {
            return;
        }
        // Rule 4's displayed balance, as the screens show it.
        boolean asset = account.type() == AccountType.ASSET;
        String amounts = remaining.stream()
                .map(r -> Money.normalize(asset ? r.amount() : r.amount().negate()).toPlainString() + " " + r.currency())
                .collect(Collectors.joining(", "));
        throw new ConflictException(("%s still holds %s. An account is archived only once it holds nothing: move what "
                + "remains to another account first").formatted(account.name(), amounts));
    }

    private record Remaining(String currency, BigDecimal amount) {
    }

    /** Whether an amount is nothing in its currency: it rounds HALF_UP to 0 at the currency's minor unit. */
    static boolean roundsToZero(BigDecimal amount, String currency) {
        int digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        return amount.setScale(digits, RoundingMode.HALF_UP).signum() == 0;
    }

    /** When an object is archived after a change of its archived flag; an archived object keeps its time. */
    static Instant archivedAt(Instant archivedAt, Boolean archived) {
        if (archived == null) {
            return archivedAt;
        }
        return archived ? (archivedAt != null ? archivedAt : WallClock.now()) : null;
    }
}
