package com.example.financetracker.ledger.importer;

import java.util.Objects;

/**
 * One run of the importer over the Excel ledger's exports (D3b). The personal ledger it goes into is the caller's
 * {@link com.example.financetracker.ledger.access.LedgerScope}.
 *
 * @param dailyBalance the owner's daily balance file: the opening balances (D-62) and the reconciliation (D-63)
 * @param dailyChange the owner's daily change file: the monthly family lumps (D-83)
 * @param partnerTransactions the partner's family rows, as the import page filters them (D-140); null without them
 * @param familyLedgerId the family budget the family rows go into (D-141); null for a ledger without family rows
 * @param seatMemberId the partner's seat in it, a member without an account (D-141); with {@code familyLedgerId}
 * @param commit false for a dry run, which rolls everything back
 */
public record ImportRequest(ImportFile accounts, ImportFile categories, ImportFile transactions,
        ImportFile dailyBalance, ImportFile dailyChange, ImportFile partnerTransactions, Long familyLedgerId,
        Long seatMemberId, boolean commit) {

    public ImportRequest {
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(dailyBalance, "dailyBalance");
        Objects.requireNonNull(dailyChange, "dailyChange");
        if ((familyLedgerId == null) != (seatMemberId == null)) {
            throw new IllegalArgumentException("A family budget and the partner's seat come together");
        }
        if (partnerTransactions != null && familyLedgerId == null) {
            throw new IllegalArgumentException("The partner's rows go into a family budget: name it and his seat");
        }
    }
}
