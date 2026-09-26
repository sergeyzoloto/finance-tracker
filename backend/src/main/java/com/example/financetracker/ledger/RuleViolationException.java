package com.example.financetracker.ledger;

import java.util.List;

import com.example.financetracker.ledger.domain.InvalidEntryException;

/**
 * A change, other than to an entry, that breaks one of the ledger's rules, such as settings that name an account the
 * user doesn't have, or a file of rates with invalid rows. An entry that breaks them is an
 * {@link InvalidEntryException}.
 */
public class RuleViolationException extends RuntimeException {

    private final List<String> violations;

    public RuleViolationException(String message) {
        this(List.of(message));
    }

    /** @param violations at least one; each a sentence without its full stop */
    public RuleViolationException(List<String> violations) {
        super(String.join("; ", violations));
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("A rule violation needs a message");
        }
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
