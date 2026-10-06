package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.util.List;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;

/**
 * An account's displayed balance (rule 4) in the user's base currency: its balance in each currency, converted at
 * the rate on the day of the balance by the rules of {@link RateBook} (D-49, D-90, D-91), rounded once to the base
 * currency's minor unit.
 *
 * @param currency the base currency
 * @param balance null if a currency with a balance other than zero has no rate on that day
 * @param missingRates why {@code balance} is null; empty otherwise
 * @param rates the rates {@code balance} used, each with its date, source and stale mark (D-49, D-90); empty for a
 *        balance in the base currency only, or a missing one
 */
public record ConvertedBalance(long accountId, String accountCode, String accountName, AccountType accountType,
        String currency, BigDecimal balance, List<MissingRate> missingRates, List<RateBook.Rate> rates) {
}
