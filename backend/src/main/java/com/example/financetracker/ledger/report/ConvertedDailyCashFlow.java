package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Income and expenses per day and category in the user's base currency (D-570, UX-13), each posting converted at the
 * rate on its own day by the rules of {@link RateBook}, each figure rounded once per category and day. No exchange
 * results: they are the monthly cash flow's. Over a range, the days' figures may differ from the monthly ones by
 * their rounding only.
 *
 * @param currency the base currency
 * @param rows ordered by day, category type and category code, as {@link DailyCashFlowRow}s
 */
public record ConvertedDailyCashFlow(String currency, List<Row> rows) {

    /**
     * What one category took in or paid out on one day, as {@link DailyCashFlowRow#total}.
     *
     * @param total null if a posting's currency has no rate on its day
     * @param missingRates why {@code total} is null; empty otherwise
     * @param familyLedgerId the family budget of a family category
     * @param familyLedgerName its name
     * @param rates the rates {@code total} used (D-49, D-90); empty for a total in the base currency only, or a
     *        missing one
     */
    public record Row(LocalDate day, String categoryCode, String categoryName, CategoryType categoryType,
            BigDecimal total, List<MissingRate> missingRates, @JsonInclude(Include.NON_NULL) Long familyLedgerId,
            @JsonInclude(Include.NON_NULL) String familyLedgerName, List<RateBook.Rate> rates) {
    }
}
