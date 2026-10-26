package com.example.financetracker.ledger.importer;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One run of the importer over a user's transactions file (D3b, D4b). The personal ledger it goes into is the caller's
 * {@link com.example.financetracker.ledger.access.LedgerScope}.
 *
 * @param transactions the user's own transactions file: D-235's 32 columns, the Excel ledger's or the app's export
 * @param partnerTransactions the partner's family rows for a place without an account, as the import page filters them
 *        (D-140, D-260); null without them
 * @param familyLedgerId the family budget the family rows go into (D-141); null for a ledger without family rows
 * @param seatMemberId the partner's seat in it, or the member's own in his own import (D-175); with
 *        {@code familyLedgerId}
 * @param upTo "Import up to" (D-165): rows of either file dated after it are not imported, and the check stops at it;
 *        null for no such date
 * @param commit false for a dry run, which rolls everything back
 */
public record ImportRequest(ImportFile transactions, ImportFile partnerTransactions, Long familyLedgerId,
        Long seatMemberId, LocalDate upTo, boolean commit) {

    public ImportRequest {
        Objects.requireNonNull(transactions, "transactions");
        if ((familyLedgerId == null) != (seatMemberId == null)) {
            throw new IllegalArgumentException("A family budget and the partner's seat come together");
        }
        if (partnerTransactions != null && familyLedgerId == null) {
            throw new IllegalArgumentException("The partner's rows go into a family budget: name it and his seat");
        }
    }
}
