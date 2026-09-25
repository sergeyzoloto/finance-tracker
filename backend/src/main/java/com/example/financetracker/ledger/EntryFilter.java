package com.example.financetracker.ledger;

import java.time.LocalDate;

/**
 * Which entries {@link EntryService#search} finds. Every field is optional, and an entry must match all that are set.
 *
 * @param from the earliest entry date, inclusive
 * @param to the latest entry date, inclusive
 * @param accountId an account one of the entry's postings is to
 * @param categoryId a category one of the entry's postings has
 * @param counterpartyId the entry's payee, or the counterparty of one of its postings
 * @param text a part of the entry's memo or its payee's name, in any case
 */
public record EntryFilter(LocalDate from, LocalDate to, Long accountId, Long categoryId, Long counterpartyId,
        String text) {

    public EntryFilter {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        text = text == null || text.isBlank() ? null : text.strip();
    }
}
