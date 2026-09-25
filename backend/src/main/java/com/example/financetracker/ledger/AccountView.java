package com.example.financetracker.ledger;

import com.example.financetracker.ledger.domain.AccountType;

/**
 * An account as {@link AccountService} hands it out.
 *
 * @param defaultCurrency preselected in entry forms; null for none
 * @param system whether the ledger posts to it on its own, like OPENING_BALANCE and FX_EXCHANGE (rule 4). A system
 *        account can't be renamed or archived.
 */
public record AccountView(long id, String code, String name, AccountType type, String defaultCurrency,
        boolean requiresCounterparty, boolean system, boolean archived) {

    static AccountView of(Account account) {
        return new AccountView(account.id(), account.code(), account.name(), account.type(),
                account.defaultCurrency(), account.requiresCounterparty(), account.isSystem(),
                account.archivedAt() != null);
    }
}
