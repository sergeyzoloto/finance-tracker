package com.example.financetracker.ledger.importer;

import java.util.Map;

import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;

/**
 * The user's accounts and categories as the importer finds them: by the names of the accounts and categories files,
 * which is how the journal names them, and accounts also by code.
 *
 * @param accountsByCode all the user's accounts, the system accounts included
 * @param sharedAccount the account that receives the family's part of a shared expense (rule 7), or null if the
 *        user has none
 */
record Chart(Map<String, AccountRef> accountsByName, Map<String, AccountRef> accountsByCode,
        Map<String, CategoryRef> categoriesByName, AccountRef sharedAccount) {

    Chart {
        accountsByName = Map.copyOf(accountsByName);
        accountsByCode = Map.copyOf(accountsByCode);
        categoriesByName = Map.copyOf(categoriesByName);
    }

    /** Null if the user lacks it; the importer creates it before anything is mapped. */
    AccountRef account(AccountRole role) {
        return accountsByCode.get(role.defaultCode());
    }

    record AccountRef(long id, String code, AccountType type, boolean requiresCounterparty) {

        boolean is(AccountRole role) {
            return code.equals(role.defaultCode());
        }
    }

    record CategoryRef(long id, String code, CategoryType type) {
    }
}
