package com.example.financetracker.ledger.report;

/**
 * Which income and expenses of a personal ledger the cash flow counts (D-302, UX-1). An entry is classified by its
 * SHARE link to a family record and that record's payer (the receiver, for an income), never by its category; a
 * detached link, of a member who left, still counts by its record. The amounts are the user's own, as the ledger holds
 * them, so {@link #PERSONAL}, {@link #FAMILY_MINE} and {@link #FAMILY_OTHERS} add up to {@link #ALL}, and
 * {@link #FAMILY} (D-421, UX-6) is {@link #FAMILY_MINE} and {@link #FAMILY_OTHERS} together, so {@link #PERSONAL} and
 * {@link #FAMILY} add up to {@link #ALL} too. It is a scope of its own, not a sum the client makes, because in the base
 * currency each figure is rounded once (D-49).
 */
public enum CashFlowScope {
    /** Every categorized posting: the cash flow as it was before the scopes. */
    ALL,
    /** The user's own entries, those no family record posted, whatever their category. */
    PERSONAL,
    /** The user's shares of every family record, whoever paid or received it (D-421). */
    FAMILY,
    /** The user's shares of family records that the user's own member paid or received. */
    FAMILY_MINE,
    /** The user's shares of family records that another member paid or received, with or without an account. */
    FAMILY_OTHERS;

    public boolean family() {
        return this == FAMILY || this == FAMILY_MINE || this == FAMILY_OTHERS;
    }
}
