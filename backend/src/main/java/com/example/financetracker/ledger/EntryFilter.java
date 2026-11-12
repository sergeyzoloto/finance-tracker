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
 * @param payee a name the entry's Payee cell shows, in any case (UX-12, D-548): the entry's payee's, a line's
 *        counterparty's, or for an entry with neither its family record's payee that the reader may see; trimmed,
 *        blank as absent, at most {@value #PAYEE_MAX} characters after trimming (D-554), never with
 *        {@code counterpartyId}
 */
public record EntryFilter(LocalDate from, LocalDate to, Long accountId, Long categoryId, Long counterpartyId,
        String text, String payee) {

    /** The longest {@code payee}, counted after trimming. */
    public static final int PAYEE_MAX = 100;

    public EntryFilter {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        text = text == null || text.isBlank() ? null : text.strip();
        payee = payee == null || payee.isBlank() ? null : payee.strip();
        if (payee != null && payee.length() > PAYEE_MAX) {
            throw new IllegalArgumentException("'payee' must be at most " + PAYEE_MAX + " characters");
        }
        if (payee != null && counterpartyId != null) {
            throw new IllegalArgumentException("Give 'payee' or 'counterpartyId', not both");
        }
    }
}
