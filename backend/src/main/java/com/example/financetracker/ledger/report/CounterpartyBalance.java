package com.example.financetracker.ledger.report;

import java.math.BigDecimal;

import com.example.financetracker.ledger.domain.Money;

/**
 * The part of an account's displayed balance (rule 4) that is owed by or to one counterparty, in one currency
 * (rule 8). The amount is {@link Money#normalize}d.
 */
public record CounterpartyBalance(long counterpartyId, String counterpartyName, String currency, BigDecimal balance) {

    public CounterpartyBalance {
        balance = Money.normalize(balance);
    }
}
