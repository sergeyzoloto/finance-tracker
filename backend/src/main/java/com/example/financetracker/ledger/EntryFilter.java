package com.example.financetracker.ledger;

import java.time.LocalDate;

/**
 * Which entries {@link EntryService#search} finds. Every field is optional, and an entry must match all that are set.
 *
 * @param from the earliest entry date, inclusive
 * @param to the latest entry date, inclusive
 * @param accountId an account one of the entry's postings is to
 * @param categoryId a category one of the entry's postings has
 * @param counterpartyId the entry's payee, or the counterparty of one of its postings, or for an entry with neither
 *        its family record's payee that the reader may see by that counterparty's name, in any case (UX-11, D-523)
 * @param text a part of the entry's memo, its payee's name or its family record's payee that the reader may see, in
 *        any case
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
