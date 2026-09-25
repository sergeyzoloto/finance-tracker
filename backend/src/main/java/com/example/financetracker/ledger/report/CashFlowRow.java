package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.Money;

/**
 * What one category took in or paid out in one month and currency (rule 5).
 *
 * @param total positive for both income and expense. Refunds and reversals are posted with the opposite sign and
 *        reduce it, down to zero or below when they outweigh the month's other postings. {@link Money#normalize}d.
 */
public record CashFlowRow(YearMonth month, String categoryCode, String categoryName, CategoryType categoryType,
        String currency, BigDecimal total) {

    public CashFlowRow {
        total = Money.normalize(total);
    }
}
