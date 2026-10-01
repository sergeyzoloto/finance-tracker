package com.example.financetracker.ledger.rates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.ExchangeRate;
import com.example.financetracker.ledger.ExchangeRateRepository;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.SettingsService;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exchange rates as a user sees them: the ECB's, which all users share, and the user's own manual rates, which take
 * precedence on the same day (rule 11: a user's rates change only that user's reports). Rates are the person's, so
 * callers pass the user id, the Keycloak "sub" claim; what the rates page says about a ledger's postings takes the
 * {@link LedgerScope} that LedgerAccess resolved.
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
        rates.findForPeriod(userId, foreign, from, to).stream().map(RateService::rate).forEach(book::add);
        return book.build();
    }

    /**
     * The latest rate of every currency that has one, and of every currency in the user's personal ledger, whether it
     * has a rate or not; and the days on which the ledger's postings can't be converted to the base currency. The
     * rates and the base currency are those of the ledger's member.
     */
    @Transactional(readOnly = true)
    public RatesView overview(LedgerScope personalLedger) {
        String userId = personalLedger.userId();
        String base = settings.get(userId).baseCurrency();
        Map<String, RateBook.Rate> latest = new TreeMap<>();
        rates.findLatest(userId).stream().map(RateService::rate).forEach(rate -> latest.put(rate.currency(), rate));

        Set<String> ledger = new HashSet<>(jdbc.sql("""
                SELECT p.currency FROM journal_entry e JOIN posting p ON p.entry_id = e.id
                WHERE e.ledger_id = :ledgerId
                UNION
                SELECT default_currency FROM account WHERE ledger_id = :ledgerId AND default_currency IS NOT NULL""")
                .param("ledgerId", personalLedger.ledgerId())
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
        return new RatesView(base, rows, missing(personalLedger, base));
    }

    /**
     * The days on which the ledger's postings in a currency other than the base currency can't be converted with the
     * rates its member sees.
     */
    private List<MissingRate> missing(LedgerScope ledger, String base) {
        record Day(String currency, LocalDate date) {
        }
        List<Day> days = jdbc.sql("""
                SELECT DISTINCT p.currency, e.entry_date AS date
                FROM journal_entry e JOIN posting p ON p.entry_id = e.id
                WHERE e.ledger_id = :ledgerId AND p.currency <> :base""")
                .param("ledgerId", ledger.ledgerId())
                .param("base", base)
                .query(Day.class)
                .list();
        if (days.isEmpty()) {
            return List.of();
        }
        Set<String> currencies = days.stream().map(Day::currency).collect(Collectors.toSet());
        currencies.add(base);
        RateBook book = rateBook(ledger.userId(), currencies,
                days.stream().map(Day::date).min(LocalDate::compareTo).get(),
                days.stream().map(Day::date).max(LocalDate::compareTo).get());
        MissingRate.Days missing = new MissingRate.Days();
        days.forEach(day -> book.missing(day.currency(), base, day.date())
                .ifPresent(currency -> missing.add(currency, day.date())));
        return missing.toList();
    }

    /**
     * The rate of a family record's amount in {@code from} into the family's base currency {@code to} on the record's
     * day (D-13; ADR 0003, topic D, as decided for F4e): for each currency that isn't EUR, the ECB's latest rate on or
     * before the day, at any age; for a currency of which the ECB has none by then (RUB, which it no longer publishes),
     * the acting member's own latest manual rate on or before the day. Nobody else's manual rate is ever used.
     *
     * @param userId the acting member's sub, whose manual rates stand in for the ECB's
     * @return empty if a currency has neither by then
     */
    @Transactional(readOnly = true)
    public Optional<RecordRate> recordRate(String userId, String from, String to, LocalDate day) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("A record in its base currency " + to + " needs no rate");
        }
        Optional<RateBook.Rate> fromRate = euroRate(userId, from, day);
        Optional<RateBook.Rate> toRate = euroRate(userId, to, day);
        if (fromRate.isEmpty() || toRate.isEmpty()) {
            return Optional.empty();
        }
        RateSource source = fromRate.get().source() == RateSource.MANUAL || toRate.get().source() == RateSource.MANUAL
                ? RateSource.MANUAL : RateSource.ECB;
        LocalDate date = fromRate.get().date().isBefore(toRate.get().date()) ? fromRate.get().date()
                : toRate.get().date();
        return Optional.of(new RecordRate(fromRate.get().perEuro(), toRate.get().perEuro(), source, date));
    }

    /** The currency's euro rate for a record on the day: EUR itself, else the ECB's, else the member's own. */
    private Optional<RateBook.Rate> euroRate(String userId, String currency, LocalDate day) {
        if (currency.equals(RateBook.EURO)) {
            // Dated on the day itself, so that it never makes the other currency's rate look older.
            return Optional.of(new RateBook.Rate(RateBook.EURO, LocalDate.MAX, BigDecimal.ONE, RateSource.ECB));
        }
        return rates.findLatestShared(currency, day).or(() -> rates.findLatestManual(userId, currency, day))
                .map(RateService::rate);
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

    private static RateBook.Rate rate(ExchangeRate rate) {
        return new RateBook.Rate(rate.quoteCurrency(), rate.rateDate(), rate.rate(), RateSource.valueOf(rate.source()));
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
