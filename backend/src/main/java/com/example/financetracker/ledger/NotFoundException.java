package com.example.financetracker.ledger;

/** The user has no such object: it doesn't exist, or it belongs to someone else (rule 11). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
