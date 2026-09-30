package com.example.financetracker.ledger.family.posting;

/** What a personal entry is for a family record (D-9; ADR 0003, topic E). */
public enum LinkType {
    /** A member's share, posted by the family budget. */
    SHARE,
    /** The payer's payment: their own entry, or one to "Payments without a specified account". */
    PAYMENT,
    /** A settlement between members (F4c). */
    SETTLEMENT,
    /** A member's family balance before their join date (F5). */
    OPENING_BALANCE,
    /** A returning member's correction (F5). */
    CORRECTION
}
