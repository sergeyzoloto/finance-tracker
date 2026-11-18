package com.example.financetracker.ledger.history;

/** What a version is of (ADR 0006, 2.1, D-998): the units a user sees as one thing and saves as one. */
public enum RecordType {
    /** A personal journal entry with all its postings. */
    ENTRY,
    ACCOUNT,
    /** A personal category. */
    CATEGORY,
    COUNTERPARTY,
    /** A family record (expense, income, settlement, refund) with its shares: the family fields only (D-16, D-1022). */
    FAMILY_RECORD,
    /** A family category (no user). */
    FAMILY_CATEGORY
}
