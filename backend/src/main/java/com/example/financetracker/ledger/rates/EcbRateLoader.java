package com.example.financetracker.ledger.rates;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.example.financetracker.ledger.ExchangeRateRepository;
import com.example.financetracker.ledger.rates.EcbClient.EcbDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the ECB's euro reference rates into exchange_rate with source ECB, shared by all users. {@link EcbSchedule}
 * runs it every working day and at startup.
 */
@Component
public class EcbRateLoader {

    private static final Logger log = LoggerFactory.getLogger(EcbRateLoader.class);

    /** Rows per statement. */
    private static final int CHUNK = 5_000;

    private final EcbClient ecb;
    private final ExchangeRateRepository rates;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    EcbRateLoader(EcbClient ecb, ExchangeRateRepository rates, JdbcClient jdbc, TransactionTemplate transactions) {
        this.ecb = ecb;
        this.rates = rates;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * What one load wrote.
     *
     * @param fromHistory whether it read the history, and not only the daily file
     * @param days the days written, empty if nothing was new
     */
    public record Result(boolean fromHistory, List<LocalDate> days, int rates) {
    }

    /**
     * Loads the days the ECB published since the latest day stored. The daily file holds only the latest day; if a
     * weekday between the latest day stored and that day has no rates, the history fills the gap. That happens after
     * a load failed, and on the ECB's holidays. The first load reads the whole history. The latest day stored is
     * written again, in case the ECB corrected it.
     *
     * @throws RuntimeException if a file can't be downloaded or doesn't look as the ECB's files do; nothing is
     *         written then
     */
    public Result load() {
        Optional<LocalDate> latest = rates.latestSharedDate();
        EcbDay daily = ecb.daily();
        Map<LocalDate, EcbDay> days = new TreeMap<>();
        boolean fromHistory = latest.isEmpty() || weekdayBetween(latest.get(), daily.date());
        if (fromHistory) {
            ecb.history().forEach(day -> days.put(day.date(), day));
        }
        days.put(daily.date(), daily);
        days.keySet().removeIf(day -> latest.isPresent() && day.isBefore(latest.get()));

        int written = transactions.execute(status -> save(new ArrayList<>(days.values())));
        List<LocalDate> loaded = new ArrayList<>(days.keySet());
        log.info("Loaded {} ECB rates for {} day(s){}{}", written, loaded.size(),
                loaded.isEmpty() ? "" : " from %s to %s".formatted(loaded.getFirst(), loaded.getLast()),
                fromHistory ? " from the history" : "");
        return new Result(fromHistory, loaded, written);
    }

    /** Whether a Monday to Friday lies strictly between the two days. */
    static boolean weekdayBetween(LocalDate first, LocalDate last) {
        for (LocalDate day = first.plusDays(1); day.isBefore(last); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                return true;
            }
        }
        return false;
    }

    /** Inserts the rates, or replaces the ECB's rates for the same day and currency. */
    private int save(List<EcbDay> days) {
        List<String> dates = new ArrayList<>();
        List<String> currencies = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (EcbDay day : days) {
            for (Map.Entry<String, BigDecimal> rate : day.rates().entrySet()) {
                dates.add(day.date().toString());
                currencies.add(rate.getKey());
                values.add(rate.getValue().toPlainString());
            }
        }
        for (int from = 0; from < dates.size(); from += CHUNK) {
            int to = Math.min(from + CHUNK, dates.size());
            // One statement per chunk: the first load writes about 170,000 rates.
            jdbc.sql("""
                    INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source)
                    SELECT rate_date, 'EUR', quote_currency, rate, 'ECB'
                    FROM unnest(CAST(:dates AS DATE[]), CAST(:currencies AS CHAR(3)[]), CAST(:rates AS NUMERIC[]))
                        AS t (rate_date, quote_currency, rate)
                    ON CONFLICT (base_currency, quote_currency, rate_date, user_id) DO UPDATE SET rate = EXCLUDED.rate""")
                    .param("dates", dates.subList(from, to).toArray(String[]::new))
                    .param("currencies", currencies.subList(from, to).toArray(String[]::new))
                    .param("rates", values.subList(from, to).toArray(String[]::new))
                    .update();
        }
        return dates.size();
    }
}
