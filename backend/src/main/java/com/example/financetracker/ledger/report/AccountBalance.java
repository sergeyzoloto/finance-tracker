package com.example.financetracker.ledger.report;

import java.math.BigDecimal;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.Money;

/**
 * An account's displayed balance in one currency (rule 4): the sum of its postings for an ASSET account, minus that
 * sum for a LIABILITY or EQUITY account. The amount is {@link Money#normalize}d, as in an entry.
 */
public record AccountBalance(long accountId, String accountCode, String accountName, AccountType accountType,
        String currency, BigDecimal balance) {

    public AccountBalance {
        balance = Money.normalize(balance);
    }
}
