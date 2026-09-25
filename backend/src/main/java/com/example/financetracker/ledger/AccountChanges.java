package com.example.financetracker.ledger;

/**
 * A partial update of an account: fields left null stay as they are.
 *
 * @param archived true archives the account, false restores it
 * @param changesDefaultCurrency whether to set the default currency to {@code defaultCurrency}, which may be null
 *        for none
 */
public record AccountChanges(String name, Boolean archived, boolean changesDefaultCurrency, String defaultCurrency) {
}
