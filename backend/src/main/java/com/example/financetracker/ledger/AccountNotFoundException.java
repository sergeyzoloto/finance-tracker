package com.example.financetracker.ledger;

/** The user has no such account: it doesn't exist, or it belongs to someone else (rule 11). */
public class AccountNotFoundException extends NotFoundException {

    public AccountNotFoundException(String code) {
        super("Account " + code + " not found");
    }

    public AccountNotFoundException(long accountId) {
        super("Account " + accountId + " not found");
    }
}
