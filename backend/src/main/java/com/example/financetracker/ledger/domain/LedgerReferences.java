package com.example.financetracker.ledger.domain;

import java.util.Map;
import java.util.Set;

/**
 * The rows an entry refers to that exist and belong to the entry's user, by id. An id that is missing here is
 * reported as not existing, whether it doesn't exist or belongs to someone else (rule 11).
 */
public record LedgerReferences(Map<Long, AccountInfo> accounts, Map<Long, CategoryInfo> categories,
        Set<Long> counterparties) {

    public LedgerReferences {
        accounts = Map.copyOf(accounts);
        categories = Map.copyOf(categories);
        counterparties = Set.copyOf(counterparties);
    }

    public record AccountInfo(String code, AccountType type, boolean requiresCounterparty) {
    }

    public record CategoryInfo(String code, CategoryType type) {
    }
}
