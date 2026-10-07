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
 * @param payeeId the payer's own counterparty on their own payment, the payee (D-81), a counterparty of their personal
 *        ledger that nobody else sees; null for every other entry
 */
record PostedEntry(long memberId, Long recordId, LinkType link, EntryKind kind, boolean systemOwned, LocalDate date,
        List<Line> lines, String memo, Long payeeId) {

    PostedEntry {
        lines = List.copyOf(lines);
    }

    /** An entry with no payee. */
    PostedEntry(long memberId, Long recordId, LinkType link, EntryKind kind, boolean systemOwned, LocalDate date,
            List<Line> lines, String memo) {
        this(memberId, recordId, link, kind, systemOwned, date, lines, memo, null);
    }

    /**
     * A posting: debit positive, credit negative (rule 2).
     *
     * @param counterpartyId the counterparty of the payer's own account's line, when that account requires one (D-80);
     *        null for every other line
     */
    record Line(long accountId, String currency, BigDecimal amount, Long categoryId, Long counterpartyId) {

        Line(long accountId, String currency, BigDecimal amount, Long categoryId) {
            this(accountId, currency, amount, categoryId, null);
        }

        boolean sameAs(Line other) {
            return accountId == other.accountId && currency.equals(other.currency)
                    && amount.compareTo(other.amount) == 0 && java.util.Objects.equals(categoryId, other.categoryId)
                    && java.util.Objects.equals(counterpartyId, other.counterpartyId);
        }
    }

    /** Whether the other entry has the same date, memo, payee, owner and lines, in the same order. */
    boolean sameAs(LocalDate otherDate, String otherMemo, Long otherPayeeId, boolean otherSystemOwned,
            List<Line> otherLines) {
        if (!date.equals(otherDate) || !java.util.Objects.equals(memo, otherMemo)
                || !java.util.Objects.equals(payeeId, otherPayeeId) || systemOwned != otherSystemOwned
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
