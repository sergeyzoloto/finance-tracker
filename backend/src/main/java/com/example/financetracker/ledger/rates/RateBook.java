package com.example.financetracker.ledger.rates;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import com.example.financetracker.ledger.domain.Money;

/**
 * Euro rates of some currencies as one user sees them, and conversions between currencies through the euro. Plain
 * Java, without a database.
 * <ul>
 * <li>An amount on a day uses the latest rate on or before that day. A currency without a rate by then can't be
 * converted: nothing is guessed, no later rate is used, and no rate stands in for a missing one.
 * <li>On one day, the user's own manual rate takes precedence over the ECB's.
 * <li>A cross rate is computed through the euro: X to Y is Y per euro divided by X per euro. EUR itself is 1.
 * </ul>
 */
public final class RateBook {

    public static final String EURO = "EUR";

    /** Precision of a conversion before a report rounds its figures. */
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private final Map<String, NavigableMap<LocalDate, Rate>> byCurrency;

    private RateBook(Map<String, NavigableMap<LocalDate, Rate>> byCurrency) {
        this.byCurrency = byCurrency;
    }

    /**
     * Units of {@code currency} for one euro from {@code date} on, until the next rate.
     *
     * @param perEuro positive
     */
    public record Rate(String currency, LocalDate date, BigDecimal perEuro, RateSource source) {

        public Rate {
            perEuro = Money.normalize(perEuro);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The rate that applies on {@code day}: the latest on or before it, none if the currency has none by then. */
    public Optional<Rate> rate(String currency, LocalDate day) {
        if (currency.equals(EURO)) {
            return Optional.of(new Rate(EURO, day, BigDecimal.ONE, null));
        }
        NavigableMap<LocalDate, Rate> rates = byCurrency.get(currency);
        return Optional.ofNullable(rates == null ? null : rates.floorEntry(day)).map(Map.Entry::getValue);
    }

    /**
     * The amount in {@code from} on {@code day}, in {@code to}. Unrounded; the report that adds amounts up rounds its
     * figures.
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

        private final Map<String, NavigableMap<LocalDate, Rate>> byCurrency = new HashMap<>();

        private Builder() {
        }

        /** Adds a rate. On a day that has one already, a manual rate replaces the ECB's, and not the other way. */
        public Builder add(Rate rate) {
            if (rate.currency().equals(EURO) || rate.perEuro().signum() <= 0) {
                throw new IllegalArgumentException("Not a euro rate: " + rate);
            }
            byCurrency.computeIfAbsent(rate.currency(), currency -> new TreeMap<>())
                    .merge(rate.date(), rate, (present, added) ->
                            present.source() == RateSource.MANUAL && added.source() != RateSource.MANUAL ? present
                                    : added);
            return this;
        }

        public Builder add(String currency, LocalDate date, BigDecimal perEuro, RateSource source) {
            return add(new Rate(currency, date, perEuro, source));
        }

        public RateBook build() {
            Map<String, NavigableMap<LocalDate, Rate>> copy = new HashMap<>();
            byCurrency.forEach((currency, rates) -> copy.put(currency, new TreeMap<>(rates)));
            return new RateBook(copy);
        }
    }
}
