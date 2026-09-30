package com.example.financetracker.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a family budget has to do with a personal entry (D-9; ADR 0003, topics E and I): the entry is a member's
 * share that the family budget posted, or the payer's payment for a family record. Such an entry is read-only through
 * {@code PUT /api/entries/{id}} and changes through its family record (D-8). The payer's payment changes as a payment
 * too, through {@code PATCH /api/entries/{id}/family-payment}, and deleting it deletes the record (F4c, D-14).
 *
 * @param ledgerId the family budget, which the user is a member of
 * @param recordId the family record, or null for an opening balance or a correction (F5)
 * @param link SHARE, PAYMENT, SETTLEMENT, OPENING_BALANCE or CORRECTION
 * @param recordType the family record's type, EXPENSE, INCOME or SETTLEMENT, so that a payment reads as the receipt of
 *        an income; left out for an opening balance or a correction (F4d, additive)
 */
public record EntryFamily(long ledgerId, String ledgerName, Long recordId, String link, boolean readOnly,
        @JsonInclude(JsonInclude.Include.NON_NULL) String recordType) {
}
