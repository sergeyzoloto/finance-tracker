package com.example.financetracker.ledger;

/** The user has no such entry: it doesn't exist, or it belongs to someone else (rule 11). */
public class EntryNotFoundException extends NotFoundException {

    public EntryNotFoundException(long entryId) {
        super("Journal entry " + entryId + " not found");
    }
}
