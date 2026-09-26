package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.util.List;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.rates.MissingRate;

/**
 * An account's displayed balance (rule 4) in the user's base currency: its balance in each currency, converted at
 * the rate on the day of the balance.
 *
 * @param currency the base currency
 * @param balance null if a currency with a balance other than zero has no rate on that day
 * @param missingRates why {@code balance} is null; empty otherwise
 */
public record ConvertedBalance(long accountId, String accountCode, String accountName, AccountType accountType,
        String currency, BigDecimal balance, List<MissingRate> missingRates) {
}
