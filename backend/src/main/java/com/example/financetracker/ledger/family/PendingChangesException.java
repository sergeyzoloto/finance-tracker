package com.example.financetracker.ledger.family;

import com.example.financetracker.ledger.ConflictException;

/**
 * The author rule (HIST-3, ADR 0006 section 7.2): a member who has not accepted every change of the family budget's
 * records may not change one. The problem detail carries the code and the count of pending changes.
 */
public class PendingChangesException extends ConflictException {

    /** The conflict's code, which the client tells apart. */
    public static final String CODE = "FAMILY_PENDING_CHANGES";

    private final int pending;

    PendingChangesException(int pending) {
        super("Accept the %d pending %s first".formatted(pending, pending == 1 ? "change" : "changes"), CODE);
        this.pending = pending;
    }

    /** How many records have changes the member has not accepted. */
    public int pending() {
        return pending;
    }
}
