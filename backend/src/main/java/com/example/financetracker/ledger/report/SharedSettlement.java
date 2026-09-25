package com.example.financetracker.ledger.report;

import java.math.BigDecimal;

import com.example.financetracker.ledger.domain.Money;

/**
 * What is open between the user and the shared budget (the family budget by default) in one currency: the displayed
 * balance (rule 4) of the user's shared account (rule 7). The amount is {@link Money#normalize}d.
 */
public record SharedSettlement(long accountId, String accountCode, String currency, BigDecimal balance,
        Direction direction) {

    public SharedSettlement {
        balance = Money.normalize(balance);
    }

    /**
     * Who owes whom. It follows from the side the account's postings add up to, whatever the account's type: the other
     * part of a shared expense the user paid is a debit, and money the shared budget paid for the user is a credit.
     */
    public enum Direction {
        /** The postings add up to a credit: the user owes the shared budget. */
        USER_OWES,
        /** The postings add up to a debit: the shared budget owes the user. */
        USER_IS_OWED
    }
}
