package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.util.List;

import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;

/**
 * What one counterparty owes or is owed on an account kept per counterparty (rule 8), in the user's base currency
 * (D-298, UX-1): its {@link CounterpartyBalance}s in each currency, converted at the rate on the day of the balance by
 * the rules of {@link RateBook} and added up, rounded once to the base currency's minor unit.
 *
 * @param currency the base currency
 * @param balance null if a currency with a balance other than zero has no rate on that day
 * @param missingRates why {@code balance} is null; empty otherwise
 * @param rates the rates {@code balance} used; empty for a balance in the base currency only, or a missing one
 * @param amounts the balance in each currency, as the report per currency has it, ordered by currency
 */
public record ConvertedCounterpartyBalance(long counterpartyId, String counterpartyName, String currency,
        BigDecimal balance, List<MissingRate> missingRates, List<RateBook.Rate> rates, List<Amount> amounts) {

    /** A balance in one currency. */
    public record Amount(String currency, BigDecimal balance) {
    }
}
