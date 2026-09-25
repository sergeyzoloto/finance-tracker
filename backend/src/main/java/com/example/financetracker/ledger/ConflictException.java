package com.example.financetracker.ledger;

/** A change that the object's current state rules out, such as renaming a system account. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
