package com.example.financetracker.ledger.domain;

import java.util.List;

/** An entry that breaks the ledger's rules. The message names every problem found, not just the first. */
public class InvalidEntryException extends RuntimeException {

    private final List<String> violations;

    public InvalidEntryException(List<String> violations) {
        super("Invalid entry: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
