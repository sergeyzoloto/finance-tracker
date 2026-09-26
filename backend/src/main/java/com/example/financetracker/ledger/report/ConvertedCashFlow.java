package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.rates.MissingRate;

/**
 * Income and expenses per month and category in the user's base currency, each posting converted at the rate on its
 * own day, and per month what exchange rates did.
 *
 * @param currency the base currency
 * @param rows ordered by month, category type and category code, as the cash flow in each currency
 * @param exchangeResults one per month of the period, oldest first
 */
public record ConvertedCashFlow(String currency, List<Row> rows, List<ExchangeResult> exchangeResults) {

    /**
     * What one category took in or paid out in one month, as {@link CashFlowRow#total}.
     *
     * @param total null if a posting's currency has no rate on its day
     * @param missingRates why {@code total} is null; empty otherwise
     */
    public record Row(YearMonth month, String categoryCode, String categoryName, CategoryType categoryType,
            BigDecimal total, List<MissingRate> missingRates) {
    }

    /**
     * What exchange rates did in one month, or in the part of it within the period. Neither is income or an expense:
     * both are computed from postings and rates, never posted (rule 13). Each is null if a rate it needs is missing.
     *
     * @param realized the {@link ConvertedNetWorth#realizedExchangeResult} of the exchanges on those days
     * @param unrealized how much the {@link ConvertedNetWorth#unrealizedRevaluation} changed from the day before the
     *        first day to the last day
     * @param missingRates why a figure is null; empty if none is
     */
    public record ExchangeResult(YearMonth month, BigDecimal realized, BigDecimal unrealized,
            List<MissingRate> missingRates) {
    }
}
