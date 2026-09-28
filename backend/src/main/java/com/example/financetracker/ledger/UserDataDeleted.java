package com.example.financetracker.ledger;

/** Published by {@link UserDataService#deleteAll} inside its transaction: the user's data is gone once it commits. */
public record UserDataDeleted(String userId) {
}
