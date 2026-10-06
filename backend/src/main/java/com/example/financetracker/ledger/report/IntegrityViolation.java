package com.example.financetracker.ledger.report;

import java.math.BigDecimal;

import com.example.financetracker.ledger.domain.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * A currency in which the user's ledger doesn't add up. Both amounts are zero in a sound ledger, and at least one of
 * them isn't here. Both are {@link Money#normalize}d.
 *
 * @param postingSum the sum of all postings in the user's entries (rule 2)
 * @param balanceSheetGap assets − liabilities − equity, from the displayed balances (rule 4) of all the user's
 *        accounts. It differs from {@code postingSum} when a posting in one user's entry is on another user's account.
 * @param familyLedgerId for a family membership whose debt account doesn't show the member's family balance in
 *        {@code currency} (D-10, F4a; per currency since F8a, D-45): the family budget; left out for a currency of the
 *        ledger, as are the three fields after it
 * @param debtBalance the displayed balance of the member's debt account for the family budget, in {@code currency}
 * @param familyBalance the member's balance in the family budget, in {@code currency}
 */
public record IntegrityViolation(String currency, BigDecimal postingSum, BigDecimal balanceSheetGap,
        @JsonInclude(Include.NON_NULL) Long familyLedgerId, @JsonInclude(Include.NON_NULL) String familyLedgerName,
        @JsonInclude(Include.NON_NULL) BigDecimal debtBalance,
        @JsonInclude(Include.NON_NULL) BigDecimal familyBalance) {

    public IntegrityViolation {
        postingSum = Money.normalize(postingSum);
        balanceSheetGap = Money.normalize(balanceSheetGap);
        debtBalance = debtBalance == null ? null : Money.normalize(debtBalance);
        familyBalance = familyBalance == null ? null : Money.normalize(familyBalance);
    }

    /** A currency in which the ledger doesn't add up. */
    public IntegrityViolation(String currency, BigDecimal postingSum, BigDecimal balanceSheetGap) {
        this(currency, postingSum, balanceSheetGap, null, null, null, null);
    }
}
