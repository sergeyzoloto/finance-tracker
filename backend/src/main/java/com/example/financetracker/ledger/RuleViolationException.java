package com.example.financetracker.ledger;

import com.example.financetracker.ledger.domain.InvalidEntryException;

/**
 * A change, other than to an entry, that breaks one of the ledger's rules, such as settings that name an account the
 * user doesn't have. An entry that breaks them is an {@link InvalidEntryException}.
 */
public class RuleViolationException extends RuntimeException {

    public RuleViolationException(String message) {
        super(message);
    }
}
