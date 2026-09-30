package com.example.financetracker.ledger;

/**
 * What a family budget has to do with a personal entry (D-9; ADR 0003, topics E and I): the entry is a member's
 * share that the family budget posted, or the payer's payment for a family record. Such an entry is read-only
 * through the personal endpoints and changes through its family record (D-8); until F4c, that holds for the payment
 * too.
 *
 * @param ledgerId the family budget, which the user is a member of
 * @param recordId the family record, or null for an opening balance or a correction (F5)
 * @param link SHARE, PAYMENT, SETTLEMENT, OPENING_BALANCE or CORRECTION
 */
public record EntryFamily(long ledgerId, String ledgerName, Long recordId, String link, boolean readOnly) {
}
