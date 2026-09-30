package com.example.financetracker.ledger;

import com.example.financetracker.ledger.access.LedgerScope;

/**
 * The payer's own payment for a family expense, as an entry of their personal ledger (F4c; D-14; ADR 0003, topic E).
 * Deleting it deletes the expense. Only while the family budget is switched on (D-25) is there an implementation;
 * otherwise such an entry answers 409, as every entry a family budget posted does.
 */
public interface FamilyPayments {

    /**
     * Deletes the family expense that the entry is the payer's payment for, as its payer (D-14): with every member's
     * share of it and the payment itself.
     *
     * @param personal the payer's personal ledger, which holds the entry
     */
    void deleteExpenseOf(LedgerScope personal, long entryId);
}
