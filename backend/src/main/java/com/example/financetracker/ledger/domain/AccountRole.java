package com.example.financetracker.ledger.domain;

/**
 * An account that commands post to without the user picking it. The service finds each by its code in the user's
 * accounts, except that {@code user_settings.shared_account_id} can name another account for {@link #SHARED}.
 */
public enum AccountRole {

    /** The user's own money not set aside for anything; income and expenses post here (rule 5). */
    UNALLOCATED("UNALLOCATED"),
    /** Rule 10. */
    OPENING_BALANCE("OPENING_BALANCE"),
    /** Rule 9. */
    FX_EXCHANGE("FX_EXCHANGE"),
    /** Money lent to others, per borrower (rule 8). */
    LOANS("LOANS_ASSET"),
    /** Receives the other part of a shared expense (rule 7). */
    SHARED("FAMILY_DEBT");

    private final String defaultCode;

    AccountRole(String defaultCode) {
        this.defaultCode = defaultCode;
    }

    public String defaultCode() {
        return defaultCode;
    }
}
