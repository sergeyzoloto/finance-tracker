package com.example.financetracker.ledger;

/**
 * A change that the object's current state rules out, such as renaming a system account. A conflict the client tells
 * apart from the others carries a code, which the problem detail names (additive, F6a).
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, String code) {
        super(message);
        this.code = code;
    }

    /** The conflict's code, such as {@code LAST_OWNER}; null for most. */
    public String code() {
        return code;
    }
}
