package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * The ECB's rates are shared by all users, and a manual rate belongs to the user who entered it. Queries that take a
 * user id see the shared rates and that user's own. Rates belong to no ledger, so this is not a
 * {@link LedgerScopedRepository}.
 */
public interface ExchangeRateRepository extends Repository<ExchangeRate, Void> {

    /** The ECB's rate, or with a user id that user's manual rate, for the day and currencies. */
    @Query("""
            SELECT * FROM exchange_rate
            WHERE rate_date = :rateDate AND base_currency = :baseCurrency AND quote_currency = :quoteCurrency
              AND user_id IS NOT DISTINCT FROM :userId""")
    Optional<ExchangeRate> find(LocalDate rateDate, String baseCurrency, String quoteCurrency, String userId);

    /**
     * The shared rates and the user's own of the currencies, against EUR, on {@code from} to {@code to}, and of each
     * currency and source the latest before {@code from}.
     */
    @Query("""
            (SELECT * FROM exchange_rate
             WHERE base_currency = 'EUR' AND quote_currency IN (:currencies)
               AND (user_id IS NULL OR user_id = :userId) AND rate_date BETWEEN :from AND :to)
            UNION ALL
            (SELECT DISTINCT ON (quote_currency, source) * FROM exchange_rate
             WHERE base_currency = 'EUR' AND quote_currency IN (:currencies)
               AND (user_id IS NULL OR user_id = :userId) AND rate_date < :from
             ORDER BY quote_currency, source, rate_date DESC)""")
    List<ExchangeRate> findForPeriod(String userId, Collection<String> currencies, LocalDate from, LocalDate to);

    /** The ECB's latest rate of the currency against EUR on or before the day, at any age. */
    @Query("""
            SELECT * FROM exchange_rate
            WHERE base_currency = 'EUR' AND quote_currency = :currency AND user_id IS NULL AND rate_date <= :date
            ORDER BY rate_date DESC LIMIT 1""")
    Optional<ExchangeRate> findLatestShared(String currency, LocalDate date);

    /** The user's own latest manual rate of the currency against EUR on or before the day, at any age. */
    @Query("""
            SELECT * FROM exchange_rate
            WHERE base_currency = 'EUR' AND quote_currency = :currency AND user_id = :userId AND rate_date <= :date
            ORDER BY rate_date DESC LIMIT 1""")
    Optional<ExchangeRate> findLatestManual(String userId, String currency, LocalDate date);

    /** The latest rate of every currency against EUR, the user's own before the shared one on the same day. */
    @Query("""
            SELECT DISTINCT ON (quote_currency) * FROM exchange_rate
            WHERE base_currency = 'EUR' AND (user_id IS NULL OR user_id = :userId)
            ORDER BY quote_currency, rate_date DESC, user_id NULLS LAST""")
    List<ExchangeRate> findLatest(String userId);

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
