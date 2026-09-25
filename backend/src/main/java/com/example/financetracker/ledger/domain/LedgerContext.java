package com.example.financetracker.ledger.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What commands need beyond their own fields: the ids of the user's {@link AccountRole} accounts, and the share ratio
 * of a shared expense that doesn't set one (rule 7).
 */
public record LedgerContext(Map<AccountRole, Long> accounts, BigDecimal defaultShareRatio) {

    public LedgerContext {
        accounts = Map.copyOf(accounts);
        Objects.requireNonNull(defaultShareRatio, "defaultShareRatio");
    }

    /** @throws InvalidEntryException if the user has no account in this role */
    public long account(AccountRole role) {
        Long id = accounts.get(role);
        if (id == null) {
            throw new InvalidEntryException(List.of("the account " + role.defaultCode() + " does not exist"));
        }
        return id;
    }
}
