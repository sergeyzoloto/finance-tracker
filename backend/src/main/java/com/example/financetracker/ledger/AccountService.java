package com.example.financetracker.ledger;

import java.time.Instant;
import java.util.List;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.AccountType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
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

    AccountService(AccountRepository accounts) {
        this.accounts = accounts;
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
     * @throws ConflictException if the changes rename or archive a system account
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
        String name = changes.name() != null ? changes.name() : account.name();
        String defaultCurrency = changes.changesDefaultCurrency()
                ? changes.defaultCurrency()
                : account.defaultCurrency();
        return AccountView.of(accounts.save(new Account(account.id(), account.userId(), account.ledgerId(),
                account.code(), name, account.type(),
                defaultCurrency, account.requiresCounterparty(), account.isSystem(),
                archivedAt(account.archivedAt(), changes.archived()), account.createdAt())));
    }

    /** When an object is archived after a change of its archived flag; an archived object keeps its time. */
    static Instant archivedAt(Instant archivedAt, Boolean archived) {
        if (archived == null) {
            return archivedAt;
        }
        return archived ? (archivedAt != null ? archivedAt : Instant.now()) : null;
    }
}
