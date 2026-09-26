package com.example.financetracker.ledger.rates;

/** Where a rate comes from: {@code exchange_rate.source}. */
public enum RateSource {

    /** The ECB's euro reference rates, loaded by {@link EcbRateLoader} and shared by all users. */
    ECB,
    /** Entered by a user, for that user's reports only. */
    MANUAL
}
