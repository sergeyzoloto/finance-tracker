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
    MANUAL(null);

    private final CategoryType categoryType;

    EntryKind(CategoryType categoryType) {
        this.categoryType = categoryType;
    }

    /** The type every category in an entry of this kind must have, or null if the kind doesn't restrict it. */
    public CategoryType categoryType() {
        return categoryType;
    }
}
