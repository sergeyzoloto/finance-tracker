package com.example.financetracker.ledger;

import java.math.BigDecimal;

/**
 * A saved entry like the one being typed (UX-8, D-463), with what its row in "Similar entries" shows.
 *
 * @param entry the entry as {@code GET /api/entries} gives it
 * @param amount a personal entry's amount on its account, or a family row's record's amount, above 0
 * @param currency that amount's currency
 * @param categoryId the categorized line's category, or the family record's
 * @param refund an expense whose money came back, or an income that went back
 */
public record EntryMatch(EntryView entry, BigDecimal amount, String currency, Long categoryId, boolean refund) {
}
