package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * The ECB's rates are shared by all users, and a manual rate belongs to the user who entered it. Queries that take a
 * user id see the shared rates and that user's own.
 */
public interface ExchangeRateRepository extends Repository<ExchangeRate, Void> {

    /** The ECB's rate, or with a user id that user's manual rate, for the day and currencies. */
    @Query("""
            SELECT * FROM exchange_rate
            WHERE rate_date = :rateDate AND base_currency = :baseCurrency AND quote_currency = :quoteCurrency
              AND user_id IS NOT DISTINCT FROM :userId""")
    Optional<ExchangeRate> find(LocalDate rateDate, String baseCurrency, String quoteCurrency, String userId);

    /** The user's manual rates, newest first. */
    @Query("""
            SELECT * FROM exchange_rate
            WHERE user_id = :userId
            ORDER BY rate_date DESC, quote_currency""")
    List<ExchangeRate> findManual(String userId);

    /** The day of the latest rate from the ECB, none before the first load. */
    @Query("SELECT max(rate_date) FROM exchange_rate WHERE user_id IS NULL")
    Optional<LocalDate> latestSharedDate();

    /** Inserts the rate or replaces the one for the same day, currencies and owner. */
    default void save(ExchangeRate rate) {
        upsert(rate.rateDate(), rate.baseCurrency(), rate.quoteCurrency(), rate.rate(), rate.source(), rate.userId());
    }

    @Modifying
    @Query("""
            INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source, user_id)
            VALUES (:rateDate, :baseCurrency, :quoteCurrency, :rate, :source, :userId)
            ON CONFLICT (base_currency, quote_currency, rate_date, user_id) DO UPDATE
            SET rate = EXCLUDED.rate, source = EXCLUDED.source""")
    void upsert(LocalDate rateDate, String baseCurrency, String quoteCurrency, BigDecimal rate, String source,
            String userId);

    /** Deletes the user's manual rate for the day and currency; returns whether there was one. */
    @Modifying
    @Query("""
            DELETE FROM exchange_rate
            WHERE user_id = :userId AND rate_date = :rateDate AND base_currency = 'EUR'
              AND quote_currency = :quoteCurrency""")
    boolean deleteManual(String userId, LocalDate rateDate, String quoteCurrency);
}
