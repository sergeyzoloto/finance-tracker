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
 * @param pendingChanges with family acceptance on, for a member with changes of the family budget they have not
 *        accepted yet: how many records have them; their debt account is not compared until they accept. Left out
 *        otherwise (HIST-3, additive)
 */
public record IntegrityViolation(@JsonInclude(Include.NON_NULL) String currency, BigDecimal postingSum, BigDecimal balanceSheetGap,
        @JsonInclude(Include.NON_NULL) Long familyLedgerId, @JsonInclude(Include.NON_NULL) String familyLedgerName,
        @JsonInclude(Include.NON_NULL) BigDecimal debtBalance,
        @JsonInclude(Include.NON_NULL) BigDecimal familyBalance,
        @JsonInclude(Include.NON_NULL) String historyRecordType, @JsonInclude(Include.NON_NULL) Long historyRecordId,
        @JsonInclude(Include.NON_NULL) String historyProblem, @JsonInclude(Include.NON_NULL) Integer pendingChanges) {

    public IntegrityViolation {
        postingSum = Money.normalize(postingSum);
        balanceSheetGap = Money.normalize(balanceSheetGap);
        debtBalance = debtBalance == null ? null : Money.normalize(debtBalance);
        familyBalance = familyBalance == null ? null : Money.normalize(familyBalance);
    }

    /** A currency in which the ledger doesn't add up. */
    public IntegrityViolation(String currency, BigDecimal postingSum, BigDecimal balanceSheetGap) {
        this(currency, postingSum, balanceSheetGap, null, null, null, null, null, null, null, null);
    }

    /** A family difference (D-10) of one currency. */
    public IntegrityViolation(String currency, BigDecimal postingSum, BigDecimal balanceSheetGap, Long familyLedgerId,
            String familyLedgerName, BigDecimal debtBalance, BigDecimal familyBalance) {
        this(currency, postingSum, balanceSheetGap, familyLedgerId, familyLedgerName, debtBalance, familyBalance, null,
                null, null, null);
    }

    /** A record whose history doesn't match it (HIST-1, ADR 0006); asked for with {@code history=true}. */
    public static IntegrityViolation history(String recordType, long recordId, String problem) {
        return new IntegrityViolation(null, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, recordType,
                recordId, problem, null);
    }

    /** A family membership whose changes wait for the member's acceptance (HIST-3), not compared meanwhile. */
    public static IntegrityViolation pending(long familyLedgerId, String familyLedgerName, int pendingChanges) {
        return new IntegrityViolation(null, BigDecimal.ZERO, BigDecimal.ZERO, familyLedgerId, familyLedgerName, null,
                null, null, null, null, pendingChanges);
    }
}
