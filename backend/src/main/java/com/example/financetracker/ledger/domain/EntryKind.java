package com.example.financetracker.ledger.domain;

/** The command an entry was made with. A hint for the UI only: reports are computed from postings (rule 6). */
public enum EntryKind {

    EXPENSE(CategoryType.EXPENSE),
    INCOME(CategoryType.INCOME),
    TRANSFER(null),
    SHARED_EXPENSE(CategoryType.EXPENSE),
    LOAN_GIVEN(null),
    LOAN_REPAID(null),
    CURRENCY_EXCHANGE(null),
    OPENING_BALANCE(null),
    MANUAL(null),
    /** A member's share of a family record, posted by the family budget (D-7; ADR 0003, topic E). */
    FAMILY_SHARE(null, false),
    /** The payer's payment of a family record, to their own account or to "Payments without a specified account". */
    FAMILY_PAYMENT(null, false),
    /** A settlement between members of a family budget (F4c). */
    FAMILY_SETTLEMENT(null, false),
    /** A member's family balance before their join date (D-18, F5). */
    FAMILY_OPENING(null, false),
    /** The difference a returning member's debt account had from their family balance (D-26, F5). */
    FAMILY_CORRECTION(null, false);

    private final CategoryType categoryType;
    private final boolean command;

    EntryKind(CategoryType categoryType) {
        this(categoryType, true);
    }

    EntryKind(CategoryType categoryType, boolean command) {
        this.categoryType = categoryType;
        this.command = command;
    }

    /** The type every category in an entry of this kind must have, or null if the kind doesn't restrict it. */
    public CategoryType categoryType() {
        return categoryType;
    }

    /**
     * Whether an entry command of this kind exists, which users send; the family kinds are written only by the family
     * budget's posting service.
     */
    public boolean isCommand() {
        return command;
    }
}
