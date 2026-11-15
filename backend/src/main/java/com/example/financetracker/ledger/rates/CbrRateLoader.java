package com.example.financetracker.ledger.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.example.financetracker.ledger.ExchangeRateRepository;
import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.rates.CbrClient.CbrRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the Bank of Russia's EUR rate in roubles into exchange_rate with source CBR, shared by all users (D-851 to
 * D-856). {@link CbrSchedule} runs it at startup and twice a day.
 */
@Component
public class CbrRateLoader {

    private static final Logger log = LoggerFactory.getLogger(CbrRateLoader.class);

    /** The first day after the ECB's last RUB rate (2022-03-01). */
    static final LocalDate FIRST_DAY = LocalDate.of(2022, 3, 2);

    /** How far back a later load asks again, in case the CBR corrected a rate. */
    static final int OVERLAP_DAYS = 10;

    private final CbrClient cbr;
    private final ExchangeRateRepository rates;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Today today;

    CbrRateLoader(CbrClient cbr, ExchangeRateRepository rates, JdbcClient jdbc, TransactionTemplate transactions,
            Today today) {
        this.cbr = cbr;
        this.rates = rates;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.today = today;
    }

    /**
     * What one load wrote.
     *
     * @param first whether it was the first load, the one that found no CBR rate
     * @param manualDeleted the manual RUB rates the first load deleted as equal to the CBR's (D-858); 0 otherwise
     */
    public record Result(boolean first, int rates, LocalDate firstDay, LocalDate lastDay, int manualDeleted) {
    }

    /**
     * Loads {@link #FIRST_DAY} to tomorrow on the first load, and later from the latest CBR day stored less
     * {@value #OVERLAP_DAYS} days to tomorrow (the CBR publishes the next working day's rate in advance); upserts them
     * in one transaction. The first load also deletes, in that transaction, every user's manual RUB rate equal to the
     * CBR's of its date (D-858); no later load deletes anything.
     *
     * @throws RuntimeException if the answer can't be downloaded or doesn't look as the CBR's does; nothing is written
     *         then
     */
    public Result load() {
        Optional<LocalDate> latest = rates.latestCbrDate();
        LocalDate from = latest.map(day -> day.minusDays(OVERLAP_DAYS)).filter(day -> day.isAfter(FIRST_DAY))
                .orElse(FIRST_DAY);
        LocalDate to = today.utc().plusDays(1);
        List<CbrRate> loaded = cbr.rates(from, to);

        Result result = transactions.execute(status -> {
            // Read again inside the transaction: the first load is the one that finds no CBR rate when it writes.
            boolean first = rates.latestCbrDate().isEmpty();
            save(loaded);
            int deleted = first ? deleteEqualManualRates() : 0;
            return new Result(first, loaded.size(), loaded.isEmpty() ? null : loaded.getFirst().date(),
                    loaded.isEmpty() ? null : loaded.getLast().date(), deleted);
        });
        log.info("Loaded {} CBR rates{}", result.rates(), loaded.isEmpty() ? ""
                : " from %s to %s".formatted(result.firstDay(), result.lastDay()));
        if (result.first()) {
            int remaining = jdbc.sql("""
                    SELECT count(*) FROM exchange_rate WHERE source = 'MANUAL' AND quote_currency = 'RUB'""")
                    .query(Integer.class).single();
            log.info("The first CBR load deleted {} manual RUB rates equal to the CBR's; {} manual RUB rates remain",
                    result.manualDeleted(), remaining);
        }
        return result;
    }

    /** Inserts the rates, or replaces the CBR's for the same day. */
    private void save(List<CbrRate> loaded) {
        if (loaded.isEmpty()) {
            return;
        }
        jdbc.sql("""
                INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source)
                SELECT rate_date, 'EUR', 'RUB', rate, 'CBR'
                FROM unnest(CAST(:dates AS DATE[]), CAST(:rates AS NUMERIC[])) AS t (rate_date, rate)
                ON CONFLICT (base_currency, quote_currency, rate_date, user_id, source) DO UPDATE
                SET rate = EXCLUDED.rate""")
                .param("dates", loaded.stream().map(rate -> rate.date().toString()).toArray(String[]::new))
                .param("rates", loaded.stream().map(rate -> rate.perEuro().toPlainString()).toArray(String[]::new))
                .update();
    }

    /** D-858: every user's manual RUB rate that the CBR's rate of its date equals as a number. */
    private int deleteEqualManualRates() {
        return jdbc.sql("""
                DELETE FROM exchange_rate m
                USING exchange_rate c
                WHERE m.source = 'MANUAL' AND m.base_currency = 'EUR' AND m.quote_currency = 'RUB'
                  AND c.source = 'CBR' AND c.base_currency = 'EUR' AND c.quote_currency = 'RUB'
                  AND c.rate_date = m.rate_date AND c.rate = m.rate""")
                .update();
    }
}
