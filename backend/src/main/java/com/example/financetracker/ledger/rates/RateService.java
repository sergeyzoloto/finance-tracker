package com.example.financetracker.ledger.rates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.ExchangeRate;
import com.example.financetracker.ledger.ExchangeRateRepository;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.SettingsService;
import com.example.financetracker.ledger.domain.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exchange rates as a user sees them: the ECB's, which all users share, and the user's own manual rates, which take
 * precedence on the same day (rule 11: a user's rates change only that user's reports). Callers pass the user id, the
 * Keycloak "sub" claim.
 */
@Service
public class RateService {

    private final JdbcClient jdbc;
    private final ExchangeRateRepository rates;
    private final SettingsService settings;

    RateService(JdbcClient jdbc, ExchangeRateRepository rates, SettingsService settings) {
        this.jdbc = jdbc;
        this.rates = rates;
        this.settings = settings;
    }

    /**
     * The currency's rate on {@code from} to {@code to}, and the latest before {@code from}, which applies from
     * {@code from} until the next one. That is every rate a conversion on those days can use.
     */
    @Transactional(readOnly = true)
    public RateBook rateBook(String userId, Collection<String> currencies, LocalDate from, LocalDate to) {
        Set<String> foreign = currencies.stream().filter(c -> !c.equals(RateBook.EURO)).collect(Collectors.toSet());
        RateBook.Builder book = RateBook.builder();
        if (foreign.isEmpty()) {
            return book.build();
        }
        jdbc.sql("""
                (SELECT quote_currency AS currency, rate_date, rate, source
                 FROM exchange_rate
                 WHERE base_currency = 'EUR' AND quote_currency IN (:currencies)
                   AND (user_id IS NULL OR user_id = :userId) AND rate_date BETWEEN :from AND :to)
                UNION ALL
                (SELECT DISTINCT ON (quote_currency, source) quote_currency, rate_date, rate, source
                 FROM exchange_rate
                 WHERE base_currency = 'EUR' AND quote_currency IN (:currencies)
                   AND (user_id IS NULL OR user_id = :userId) AND rate_date < :from
                 ORDER BY quote_currency, source, rate_date DESC)""")
                .param("currencies", foreign)
                .param("userId", userId)
                .param("from", from)
                .param("to", to)
                .query((row, n) -> new RateBook.Rate(row.getString("currency"),
                        row.getObject("rate_date", LocalDate.class), row.getBigDecimal("rate"),
                        RateSource.valueOf(row.getString("source"))))
                .list()
                .forEach(book::add);
        return book.build();
    }

    /**
     * The latest rate of every currency that has one, and of every currency in the user's ledger, whether it has a
     * rate or not; and the days on which the user's postings can't be converted to the base currency.
     */
    @Transactional(readOnly = true)
    public RatesView overview(String userId) {
        String base = settings.get(userId).baseCurrency();
        Map<String, RateBook.Rate> latest = new TreeMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (quote_currency) quote_currency AS currency, rate_date, rate, source
                FROM exchange_rate
                WHERE base_currency = 'EUR' AND (user_id IS NULL OR user_id = :userId)
                ORDER BY quote_currency, rate_date DESC, user_id NULLS LAST""")
                .param("userId", userId)
                .query((row, n) -> new RateBook.Rate(row.getString("currency"),
                        row.getObject("rate_date", LocalDate.class), row.getBigDecimal("rate"),
                        RateSource.valueOf(row.getString("source"))))
                .list()
                .forEach(rate -> latest.put(rate.currency(), rate));

        Set<String> ledger = new HashSet<>(jdbc.sql("""
                SELECT p.currency FROM journal_entry e JOIN posting p ON p.entry_id = e.id WHERE e.user_id = :userId
                UNION
                SELECT default_currency FROM account WHERE user_id = :userId AND default_currency IS NOT NULL""")
                .param("userId", userId)
                .query(String.class)
                .list());
        ledger.add(base);

        Set<String> currencies = new TreeSet<>(latest.keySet());
        currencies.addAll(ledger);
        currencies.remove(RateBook.EURO);
        List<LatestRate> rows = currencies.stream().map(currency -> {
            RateBook.Rate rate = latest.get(currency);
            return rate == null ? new LatestRate(currency, null, null, null, ledger.contains(currency))
                    : new LatestRate(currency, rate.date(), rate.perEuro(), rate.source(), ledger.contains(currency));
        }).toList();
        return new RatesView(base, rows, missing(userId, base));
    }

    /** The days on which the user's postings in a currency other than the base currency can't be converted. */
    private List<MissingRate> missing(String userId, String base) {
        record Day(String currency, LocalDate date) {
        }
        List<Day> days = jdbc.sql("""
                SELECT DISTINCT p.currency, e.entry_date AS date
                FROM journal_entry e JOIN posting p ON p.entry_id = e.id
                WHERE e.user_id = :userId AND p.currency <> :base""")
                .param("userId", userId)
                .param("base", base)
                .query(Day.class)
                .list();
        if (days.isEmpty()) {
            return List.of();
        }
        Set<String> currencies = days.stream().map(Day::currency).collect(Collectors.toSet());
        currencies.add(base);
        RateBook book = rateBook(userId, currencies, days.stream().map(Day::date).min(LocalDate::compareTo).get(),
                days.stream().map(Day::date).max(LocalDate::compareTo).get());
        MissingRate.Days missing = new MissingRate.Days();
        days.forEach(day -> book.missing(day.currency(), base, day.date())
                .ifPresent(currency -> missing.add(currency, day.date())));
        return missing.toList();
    }

    /** The user's manual rates, newest first. */
    @Transactional(readOnly = true)
    public List<ManualRateView> manualRates(String userId) {
        return rates.findManual(userId).stream().map(ManualRateView::of).toList();
    }

    /**
     * Saves the rate as the user's own, in place of one the user has for the same day and currency.
     *
     * @throws RuleViolationException if the rate isn't against EUR, or can't be stored
     */
    @Transactional
    public ManualRateView saveManual(String userId, ManualRate rate) {
        List<String> problems = rate.problems();
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        return ManualRateView.of(save(userId, rate));
    }

    /**
     * Saves every rate of a CSV file (columns date, base, quote, rate) as the user's own, or none if any row is
     * invalid.
     *
     * @return how many rates it saved
     * @throws RuleViolationException listing every invalid row
     */
    @Transactional
    public int saveManualCsv(String userId, byte[] csv) {
        List<ManualRate> read = ManualRateCsv.read(csv);
        read.forEach(rate -> save(userId, rate));
        return read.size();
    }

    /** @throws NotFoundException if the user has no manual rate for the currency on that day */
    @Transactional
    public void deleteManual(String userId, LocalDate date, String currency) {
        if (!rates.deleteManual(userId, date, currency)) {
            throw new NotFoundException("No manual rate for %s on %s".formatted(currency, date));
        }
    }

    private ExchangeRate save(String userId, ManualRate rate) {
        ExchangeRate stored = new ExchangeRate(rate.date(), RateBook.EURO, rate.currency(), rate.perEuro(),
                RateSource.MANUAL.name(), userId);
        rates.save(stored);
        return stored;
    }

    /**
     * The rates page.
     *
     * @param latest ordered by currency, EUR left out
     * @param missing by currency: the days on which the user's postings can't be converted to the base currency
     */
    public record RatesView(String baseCurrency, List<LatestRate> latest, List<MissingRate> missing) {
    }

    /**
     * A currency's latest rate as the user sees it.
     *
     * @param date null if the currency has no rate at all
     * @param perEuro units of the currency for one euro
     * @param inLedger whether the user's postings or accounts use the currency, or it is the base currency
     */
    public record LatestRate(String currency, LocalDate date, BigDecimal perEuro, RateSource source,
            boolean inLedger) {
    }

    /** A manual rate as stored: units of {@code quote} for one euro. */
    public record ManualRateView(LocalDate date, String base, String quote, BigDecimal rate) {

        static ManualRateView of(ExchangeRate rate) {
            return new ManualRateView(rate.rateDate(), rate.baseCurrency(), rate.quoteCurrency(),
                    Money.normalize(rate.rate()));
        }
    }
}
