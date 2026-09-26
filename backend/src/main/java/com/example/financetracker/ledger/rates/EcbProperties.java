package com.example.financetracker.ledger.rates;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and when {@link EcbRateLoader} loads the ECB's euro reference rates (application.yml).
 *
 * @param enabled false turns the schedule off, as in tests and in the importer
 * @param dailyUrl the latest day's rates as XML
 * @param historyUrl every day's rates since 1999 as a ZIP file with one CSV file
 * @param cron when to load, in the ECB's time zone. The ECB publishes at about 16:00 CET on TARGET working days.
 */
@ConfigurationProperties("app.rates.ecb")
record EcbProperties(boolean enabled, URI dailyUrl, URI historyUrl, String cron) {
}
