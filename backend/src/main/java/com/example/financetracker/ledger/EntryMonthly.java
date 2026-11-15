package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * What {@code GET /api/entries/monthly} says (ENT-4, D-882, D-883): the filtered entries' My share by calendar month, for
 * the monthly result chart. The span is the 12 calendar months ending with the filters' To month (without a To, the month
 * of the latest matching entry), whatever their From, less the months before the first matching entry; every other filter
 * applies. Months and currencies are never added up here: the figures are the summary's In and Out, cut by month.
 *
 * @param firstMonth the span's first month, {@code YYYY-MM}, or null when no entry matches
 * @param lastMonth the span's last month, {@code YYYY-MM}, or null when no entry matches
 * @param months per month and currency with something in it, oldest first, then by currency: months of the span that are
 *        missing here (or a currency missing in one) have nothing
 */
public record EntryMonthly(String firstMonth, String lastMonth, List<Month> months) {

    /**
     * One month in one currency: {@code incoming} what came in (income and refunds) and {@code outgoing} what went out
     * (expenses), My share, each at or above 0, as {@link EntrySummary.CurrencyTotals} has them; transfers aren't in it.
     */
    public record Month(String month, String currency, BigDecimal incoming, BigDecimal outgoing) {
    }
}
