package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/** Rates are shared by all users, so nothing here is scoped by user. */
public interface ExchangeRateRepository extends Repository<ExchangeRate, Void> {

    @Query("""
            SELECT * FROM exchange_rate
            WHERE rate_date = :rateDate AND base_currency = :baseCurrency AND quote_currency = :quoteCurrency""")
    Optional<ExchangeRate> find(LocalDate rateDate, String baseCurrency, String quoteCurrency);

    /** Inserts the rate or replaces the one for the same day and currencies. */
    default void save(ExchangeRate rate) {
        upsert(rate.rateDate(), rate.baseCurrency(), rate.quoteCurrency(), rate.rate(), rate.source());
    }

    @Modifying
    @Query("""
            INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source)
            VALUES (:rateDate, :baseCurrency, :quoteCurrency, :rate, :source)
            ON CONFLICT (rate_date, base_currency, quote_currency) DO UPDATE
            SET rate = EXCLUDED.rate, source = EXCLUDED.source""")
    void upsert(LocalDate rateDate, String baseCurrency, String quoteCurrency, BigDecimal rate, String source);
}
