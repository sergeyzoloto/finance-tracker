package com.example.financetracker.ledger.report;

import java.math.BigDecimal;

import com.example.financetracker.ledger.domain.Money;

/**
 * A currency in which the user's ledger doesn't add up. Both amounts are zero in a sound ledger, and at least one of
 * them isn't here. Both are {@link Money#normalize}d.
 *
 * @param postingSum the sum of all postings in the user's entries (rule 2)
 * @param balanceSheetGap assets − liabilities − equity, from the displayed balances (rule 4) of all the user's
 *        accounts. It differs from {@code postingSum} when a posting in one user's entry is on another user's account.
 */
public record IntegrityViolation(String currency, BigDecimal postingSum, BigDecimal balanceSheetGap) {

    public IntegrityViolation {
        postingSum = Money.normalize(postingSum);
        balanceSheetGap = Money.normalize(balanceSheetGap);
    }
}
