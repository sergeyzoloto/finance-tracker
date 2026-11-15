package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * What {@code GET /api/entries/breakdown} says (ENT-3, D-873, D-881): the entries the filters match, except the
 * filters of the tab's own dimension, grouped by category, payee or account. A row is a value of the dimension with how
 * many entries have it and My share per currency, as {@link EntrySummary}'s In and Out are; never added across
 * currencies. An entry with several values of the dimension is in each of their rows, in full (D-896).
 *
 * @param by the dimension
 * @param rows sorted by entries, descending, then by name ignoring case; the row of the entries with no category or
 *        no payee has no {@code key}
 */
public record EntryBreakdown(By by, List<Row> rows) {

    /** The dimensions the tabs break the entries down by. */
    public enum By {
        CATEGORY, PAYEE, ACCOUNT;

        /** The dimension a {@code by} parameter names, in any case; anything else is an {@code IllegalArgumentException}. */
        public static By named(String name) {
            for (By by : values()) {
                if (by.name().equalsIgnoreCase(name)) {
                    return by;
                }
            }
            throw new IllegalArgumentException("'by' must be category, payee or account");
        }
    }

    /**
     * @param key the value the filter takes: a category's or an account's id, a payee's name in lower case; null for
     *        the row of entries without one
     * @param name the value as shown: the name, as the newest entry spells it; "No category" or "No payee" without a key
     * @param entries how many matching entries have it
     * @param currencies My share: per currency what came in (income) and went out (expenses), those that aren't both 0
     */
    public record Row(String key, String name, long entries, List<Amounts> currencies) {
    }

    /** My share in one currency: the entries' own side of income and of expenses, as the summary's In and Out. */
    public record Amounts(String currency, BigDecimal incoming, BigDecimal outgoing) {
    }
}
