package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * What one category took in or paid out in one month and currency (rule 5).
 *
 * @param total positive for both income and expense. Refunds and reversals are posted with the opposite sign and
 *        reduce it, down to zero or below when they outweigh the month's other postings. {@link Money#normalize}d.
 * @param familyLedgerId the family budget of a family category (D-11, F4a); left out for the ledger's own
 * @param familyLedgerName its name, left out likewise
 */
public record CashFlowRow(YearMonth month, String categoryCode, String categoryName, CategoryType categoryType,
        String currency, BigDecimal total, @JsonInclude(Include.NON_NULL) Long familyLedgerId,
        @JsonInclude(Include.NON_NULL) String familyLedgerName) {

    public CashFlowRow {
        total = Money.normalize(total);
    }

    /** A row of one of the ledger's own categories. */
    public CashFlowRow(YearMonth month, String categoryCode, String categoryName, CategoryType categoryType,
            String currency, BigDecimal total) {
        this(month, categoryCode, categoryName, categoryType, currency, total, null, null);
    }
}
