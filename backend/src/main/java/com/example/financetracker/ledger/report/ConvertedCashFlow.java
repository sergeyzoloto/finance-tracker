package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Income and expenses per month and category in the user's base currency, each posting converted at the rate on its
 * own day by the rules of {@link RateBook} (D-49, D-90, D-91), and per month what exchange rates did. Each figure is
 * rounded once, to the base currency's minor unit.
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
     * @param familyLedgerId the family budget of a family category, as {@link CashFlowRow#familyLedgerId}
     * @param familyLedgerName its name
         * @param rates the rates {@code total} used, each with its date, source and stale mark (D-49, D-90); empty for
         *        a total in the base currency only, or a missing one
     */
    public record Row(YearMonth month, String categoryCode, String categoryName, CategoryType categoryType,
            BigDecimal total, List<MissingRate> missingRates, @JsonInclude(Include.NON_NULL) Long familyLedgerId,
            @JsonInclude(Include.NON_NULL) String familyLedgerName, List<RateBook.Rate> rates) {

        /** A row of one of the ledger's own categories. */
        public Row(YearMonth month, String categoryCode, String categoryName, CategoryType categoryType,
                BigDecimal total, List<MissingRate> missingRates) {
            this(month, categoryCode, categoryName, categoryType, total, missingRates, null, null, List.of());
        }
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
