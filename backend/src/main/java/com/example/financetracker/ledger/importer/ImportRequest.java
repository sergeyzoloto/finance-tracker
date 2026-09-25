package com.example.financetracker.ledger.importer;

import java.util.Objects;

/**
 * One run of the importer over the Excel ledger's exports.
 *
 * @param userId the Keycloak "sub" of the user the ledger is imported for (rule 11)
 * @param openingBalances optional, null without one
 * @param commit false for a dry run, which rolls everything back
 */
public record ImportRequest(String userId, ImportFile accounts, ImportFile categories, ImportFile transactions,
        ImportFile openingBalances, boolean commit) {

    public ImportRequest {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("The user id is missing");
        }
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(transactions, "transactions");
    }
}
