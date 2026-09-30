package com.example.financetracker.ledger.family.posting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.domain.EntryKind;

/**
 * An entry that the posting service wants in a member's personal ledger, as {@link FamilyPostingService}'s factories
 * build it; {@link CrossLedgerWriter} checks every line before it writes one (D-8).
 *
 * @param recordId the family record it is for; null for an opening balance or a correction
 * @param systemOwned whether only the family budget changes it; false for the payer's payment with their own account
 * @param memo the payer's private note on their own payment (F4c); null for every other entry, which carries no text:
 *        a family comment copied into personal ledgers would be out of reach of D-20's erasure
 */
record PostedEntry(long memberId, Long recordId, LinkType link, EntryKind kind, boolean systemOwned, LocalDate date,
        List<Line> lines, String memo) {

    PostedEntry {
        lines = List.copyOf(lines);
    }

    /** A posting: debit positive, credit negative (rule 2). */
    record Line(long accountId, String currency, BigDecimal amount, Long categoryId) {

        boolean sameAs(Line other) {
            return accountId == other.accountId && currency.equals(other.currency)
                    && amount.compareTo(other.amount) == 0 && java.util.Objects.equals(categoryId, other.categoryId);
        }
    }

    /** Whether the other entry has the same date, memo, owner and lines, in the same order. */
    boolean sameAs(LocalDate otherDate, String otherMemo, boolean otherSystemOwned, List<Line> otherLines) {
        if (!date.equals(otherDate) || !java.util.Objects.equals(memo, otherMemo) || systemOwned != otherSystemOwned
                || lines.size() != otherLines.size()) {
            return false;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).sameAs(otherLines.get(i))) {
                return false;
            }
        }
        return true;
    }
}
