package com.example.financetracker.ledger.importer;

import java.util.Objects;

/**
 * One run of the importer over the Excel ledger's exports. The ledger it goes into is the caller's
 * {@link com.example.financetracker.ledger.access.LedgerScope}.
 *
 * @param openingBalances optional, null without one
 * @param commit false for a dry run, which rolls everything back
 */
public record ImportRequest(ImportFile accounts, ImportFile categories, ImportFile transactions,
        ImportFile openingBalances, boolean commit) {

    public ImportRequest {
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(transactions, "transactions");
    }
}
