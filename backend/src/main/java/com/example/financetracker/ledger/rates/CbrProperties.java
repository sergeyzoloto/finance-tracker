package com.example.financetracker.ledger.rates;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and when {@link CbrRateLoader} loads the Bank of Russia's EUR rate in roubles (application.yml, D-856).
 *
 * @param enabled false turns the schedule off, wherever the ECB's is off: tests, the importer, the local stacks
 * @param url XML_dynamic.asp, without a query: the loader adds the dates and the currency id
 * @param cron when to load, in Europe/Moscow
 */
@ConfigurationProperties("app.rates.cbr")
record CbrProperties(boolean enabled, URI url, String cron) {
}
