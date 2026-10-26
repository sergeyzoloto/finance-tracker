package com.example.financetracker.ledger.importer;

import java.util.Map;

import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;

/**
 * The user's accounts and categories as the importer finds them: by the names the transactions file's rows give them
 * (D-228), which is how the journal names them, and accounts also by code. A category of a family budget's family rows is the
 * family category, which personal rows use too (D-11).
 *
 * @param accountsByName by the rows' names in lower case, every spelling: the journal spells them in any case
 * @param accountsByCode all the user's accounts, the system accounts included
 */
record Chart(Map<String, AccountRef> accountsByName, Map<String, AccountRef> accountsByCode,
        Map<String, CategoryRef> categoriesByName) {

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

        /** The workbook's FAMILY_DEBT, which the family budget's records and settlements stand for (D-82). */
        boolean familyDebt() {
            return is(AccountRole.SHARED);
        }
    }

    record CategoryRef(long id, String code, CategoryType type) {
    }
}
