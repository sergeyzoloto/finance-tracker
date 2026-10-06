package com.example.financetracker.ledger.rates;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import com.example.financetracker.ledger.domain.Money;

/**
 * Euro rates of some currencies as one user sees them, and conversions between currencies through the euro, by the
 * rules of every displayed conversion (D-49, D-90, D-91). Plain Java, without a database.
 * <ul>
 * <li>The rate on a day is the latest published on or before it. Of the ECB's, only one at most
 * {@value #ECB_DAYS} days old applies; an older one is never used, so that no figure rests on a stale rate (D-49: the
 * ECB stopped publishing RUB on 2022-03-01). A manual rate is its user's, and applies from its day until that user's
 * next one, at any age.
 * <li>Among the rates that apply on a day, the one of the most recent date wins; on the same date the manual one
 * (D-91).
 * <li>A manual rate more than {@value #STALE_DAYS} days older than the day it converts on is marked stale (D-49):
 * the figure that uses it says so.
 * <li>A currency without an applicable rate can't be converted: nothing is guessed, no later rate is used, and no rate
 * stands in for a missing one.
 * <li>A cross rate is computed through the euro: X to Y is Y per euro divided by X per euro. EUR itself is 1.
 * </ul>
 */
public final class RateBook {

    public static final String EURO = "EUR";

    /** The age, in days, up to which an ECB rate applies (D-49). */
    public static final int ECB_DAYS = 7;

    /** The age, in days, past which a manual rate is marked stale (D-49). */
    public static final int STALE_DAYS = 31;

    /** Precision of a conversion before a report rounds its figures. */
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private final Map<String, NavigableMap<LocalDate, Rate>> ecb;
    private final Map<String, NavigableMap<LocalDate, Rate>> manual;

    private RateBook(Map<String, NavigableMap<LocalDate, Rate>> ecb,
            Map<String, NavigableMap<LocalDate, Rate>> manual) {
        this.ecb = ecb;
        this.manual = manual;
    }

    /**
     * Units of {@code currency} for one euro from {@code date} on, as published or entered.
     *
     * @param perEuro positive
     * @param source ECB or MANUAL; null for the euro itself
     * @param stale whether it is a manual rate more than {@value #STALE_DAYS} days older than the day it was looked up
     *        for (D-49); false as stored
     */
    public record Rate(String currency, LocalDate date, BigDecimal perEuro, RateSource source, boolean stale) {

        public Rate {
            perEuro = Money.normalize(perEuro);
        }

        public Rate(String currency, LocalDate date, BigDecimal perEuro, RateSource source) {
            this(currency, date, perEuro, source, false);
        }

        /** Whether the user entered it. */
        public boolean manual() {
            return source == RateSource.MANUAL;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The rate that applies on {@code day} (D-49, D-91): of the ECB's latest on or before it, if at most
     * {@value #ECB_DAYS} days old, and the user's latest manual one on or before it, the more recent, the manual one on
     * the same date; none if neither applies. A manual rate is marked stale as it is old on {@code day}.
     */
    public Optional<Rate> rate(String currency, LocalDate day) {
        if (currency.equals(EURO)) {
            return Optional.of(new Rate(EURO, day, BigDecimal.ONE, null));
        }
        Rate published = floor(ecb, currency, day);
        if (published != null && published.date().plusDays(ECB_DAYS).isBefore(day)) {
            published = null;
        }
        Rate entered = floor(manual, currency, day);
        if (entered != null && entered.date().plusDays(STALE_DAYS).isBefore(day)) {
            entered = new Rate(entered.currency(), entered.date(), entered.perEuro(), entered.source(), true);
        }
        if (entered == null || published != null && published.date().isAfter(entered.date())) {
            return Optional.ofNullable(published);
        }
        return Optional.of(entered);
    }

    private static Rate floor(Map<String, NavigableMap<LocalDate, Rate>> rates, String currency, LocalDate day) {
        NavigableMap<LocalDate, Rate> ofCurrency = rates.get(currency);
        Map.Entry<LocalDate, Rate> entry = ofCurrency == null ? null : ofCurrency.floorEntry(day);
        return entry == null ? null : entry.getValue();
    }

    /**
     * The amount in {@code from} on {@code day}, in {@code to}. Unrounded; the figure that adds amounts up rounds once,
     * at its end.
     *
     * @return empty if {@code from} or {@code to} has no rate on {@code day}, see {@link #missing}
     */
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to, LocalDate day) {
        if (from.equals(to)) {
            return Optional.of(amount);
        }
        Optional<Rate> fromRate = rate(from, day);
        Optional<Rate> toRate = rate(to, day);
        if (fromRate.isEmpty() || toRate.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(amount.multiply(toRate.get().perEuro()).divide(fromRate.get().perEuro(), PRECISION));
    }

    /**
     * The rates a conversion from {@code from} to {@code to} on {@code day} uses: none between one currency and
     * itself, else that of each currency that isn't the euro. Empty too if one is missing, see {@link #missing}.
     */
    public List<Rate> used(String from, String to, LocalDate day) {
        if (from.equals(to) || missing(from, to, day).isPresent()) {
            return List.of();
        }
        List<Rate> used = new ArrayList<>();
        for (String currency : List.of(from, to)) {
            if (!currency.equals(EURO)) {
                used.add(rate(currency, day).orElseThrow());
            }
        }
        return used;
    }

    /**
     * Why an amount in {@code from} can't be converted to {@code to} on {@code day}: the currency that has no rate by
     * then, {@code from} if neither has one. Empty if it can be converted.
     */
    public Optional<String> missing(String from, String to, LocalDate day) {
        if (from.equals(to)) {
            return Optional.empty();
        }
        if (rate(from, day).isEmpty()) {
            return Optional.of(from);
        }
        return rate(to, day).isEmpty() ? Optional.of(to) : Optional.empty();
    }

    public static final class Builder {

        private final Map<String, NavigableMap<LocalDate, Rate>> ecb = new HashMap<>();
        private final Map<String, NavigableMap<LocalDate, Rate>> manual = new HashMap<>();

        private Builder() {
        }

        /** Adds a rate, the ECB's or the user's own; one of each per currency and day. */
        public Builder add(Rate rate) {
            if (rate.currency().equals(EURO) || rate.perEuro().signum() <= 0 || rate.source() == null) {
                throw new IllegalArgumentException("Not a euro rate: " + rate);
            }
            (rate.manual() ? manual : ecb).computeIfAbsent(rate.currency(), currency -> new TreeMap<>())
                    .put(rate.date(), rate);
            return this;
        }

        public Builder add(String currency, LocalDate date, BigDecimal perEuro, RateSource source) {
            return add(new Rate(currency, date, perEuro, source));
        }

        public RateBook build() {
            return new RateBook(copy(ecb), copy(manual));
        }

        private static Map<String, NavigableMap<LocalDate, Rate>> copy(Map<String, NavigableMap<LocalDate, Rate>> rates) {
            Map<String, NavigableMap<LocalDate, Rate>> copy = new HashMap<>();
            rates.forEach((currency, byDay) -> copy.put(currency, new TreeMap<>(byDay)));
            return copy;
        }
    }
}
