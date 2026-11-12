package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * What one category took in or paid out on one day in one currency (D-570, UX-13): a {@link CashFlowRow} with the day
 * in place of the month, from the same postings by the same classification, so that over any range and scope the days'
 * totals per category and currency add up exactly to the cash flow's.
 *
 * @param total positive for both income and expense; a refund reduces it (D-79), down to zero or below.
 *        {@link Money#normalize}d.
 * @param familyLedgerId the family budget of a family category, as {@link CashFlowRow#familyLedgerId}
 * @param familyLedgerName its name, left out likewise
 */
public record DailyCashFlowRow(LocalDate day, String categoryCode, String categoryName, CategoryType categoryType,
        String currency, BigDecimal total, @JsonInclude(Include.NON_NULL) Long familyLedgerId,
        @JsonInclude(Include.NON_NULL) String familyLedgerName) {

    public DailyCashFlowRow {
        total = Money.normalize(total);
    }
}
