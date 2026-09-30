package com.example.financetracker.ledger;

import com.example.financetracker.ledger.access.LedgerScope;

/**
 * The payer's own payment for a family expense, or a member's own side of a settlement, as an entry of their personal
 * ledger (F4c, F4d; D-14; ADR 0003, topic E). Deleting it deletes the record. Only while the family budget is switched
 * on (D-25) is there an implementation; otherwise such an entry answers 409, as every entry a family budget posted does.
 */
public interface FamilyPayments {

    /**
     * Deletes the family record that the entry is the caller's side of, as that member (D-14): an expense they paid,
     * with every member's share of it and the payment itself; a settlement they recorded, with both sides.
     *
     * @param personal the caller's personal ledger, which holds the entry
     * @throws ConflictException if the member may not delete the record, such as the other side of a settlement
     */
    void deleteRecordOf(LedgerScope personal, long entryId);
}
