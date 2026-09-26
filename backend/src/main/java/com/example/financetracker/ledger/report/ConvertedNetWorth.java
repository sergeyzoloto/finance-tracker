package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.util.List;

import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;

/**
 * What the user owns less what they owe, in the base currency as of a day, and what exchange rates did to it. Neither
 * FX figure is a posting: both are computed from postings and rates on every call (rule 13). Each figure is null if a
 * rate it needs is missing.
 *
 * @param currency the base currency
 * @param assets the balances of all ASSET accounts, each currency at the rate on the day
 * @param liabilities the displayed balances of all LIABILITY accounts, positive for money owed, likewise
 * @param netWorth {@code assets} minus {@code liabilities}
 * @param unrealizedRevaluation for every ASSET and LIABILITY account and currency, its balance at the rate on the day
 *        less each of its postings at the rate on the posting's own day, added up: what rate changes have done to
 *        money that is still held or owed. Positive for a gain. Zero for the base currency.
 * @param realizedExchangeResult what currency exchanges gained: minus the sum of the postings to FX_EXCHANGE, each
 *        converted at the rate on its own day. That is the account's displayed balance (rule 4) in the base currency,
 *        positive for a gain.
 * @param rates the rate used on the day for each currency with a balance, and for the base currency unless it is
 *        EUR. A rate's day can be long before the day of the report, when the currency has no newer rate.
 * @param missingRates why a figure is null; empty if none is
 */
public record ConvertedNetWorth(String currency, BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth,
        BigDecimal unrealizedRevaluation, BigDecimal realizedExchangeResult, List<RateBook.Rate> rates,
        List<MissingRate> missingRates) {
}
