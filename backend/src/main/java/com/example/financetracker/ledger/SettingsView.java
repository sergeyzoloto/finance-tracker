package com.example.financetracker.ledger;

import java.math.BigDecimal;

/**
 * The user's settings as {@link SettingsService} hands them out.
 *
 * @param baseCurrency the currency the user thinks in; the ledger itself never converts to it
 * @param sharedAccountId receives the other part of a shared expense (rule 7); null for the account FAMILY_DEBT
 * @param defaultShareRatio the other part's share when a shared expense doesn't set one
 */
public record SettingsView(String baseCurrency, Long sharedAccountId, BigDecimal defaultShareRatio) {
}
