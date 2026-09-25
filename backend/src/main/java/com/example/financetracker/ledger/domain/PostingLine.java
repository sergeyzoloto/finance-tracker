package com.example.financetracker.ledger.domain;

import java.math.BigDecimal;

/**
 * A posting as commands build it and {@link LedgerValidator} checks it: debit positive, credit negative (rule 2). Any
 * field can be null in a {@link ManualCommand}; the validator reports it.
 */
public record PostingLine(Long accountId, String currency, BigDecimal amount, Long categoryId, Long counterpartyId) {

    static PostingLine of(Long accountId, String currency, BigDecimal amount) {
        return new PostingLine(accountId, currency, amount, null, null);
    }
}
