package com.example.financetracker.ledger.rates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.financetracker.ledger.domain.Money;

/**
 * A figure of a report in one currency, added up from amounts in any currency, each converted on its own day by the
 * rules of every displayed conversion (D-49, D-90, D-91; {@link RateBook}). If an amount can't be converted, the figure
 * is missing: it is never added up from the amounts that can. It keeps the rates it used, with their dates, sources
 * and stale marks, and is rounded once, at the end, to its currency's minor unit (D-49).
 */
public final class ConvertedSum {

    private final RateBook rates;
    private final String currency;
    private final MissingRate.Days missing = new MissingRate.Days();
    private final Set<RateBook.Rate> used = new HashSet<>();
    private BigDecimal total = BigDecimal.ZERO;

    public ConvertedSum(RateBook rates, String currency) {
        this.rates = rates;
        this.currency = currency;
    }

    /** Adds the amount in {@code from} on {@code day}. Zero is zero in any currency and needs no rate. */
    public ConvertedSum add(BigDecimal amount, String from, LocalDate day) {
        if (amount.signum() != 0) {
            rates.convert(amount, from, currency, day).ifPresentOrElse(
                    converted -> {
                        total = total.add(converted);
                        used.addAll(rates.used(from, currency, day));
                    },
                    () -> missing.add(rates.missing(from, currency, day).orElseThrow(), day));
        }
        return this;
    }

    /** Adds what another figure in the same currency added, its rates and its missing days included. */
    public ConvertedSum addAll(ConvertedSum other, boolean negated) {
        total = negated ? total.subtract(other.total) : total.add(other.total);
        used.addAll(other.used);
        missing.addAll(other.missing);
        return this;
    }

    /**
     * The total, rounded HALF_UP to the currency's minor unit once all amounts are added, or null if an amount
     * couldn't be converted.
     */
    public BigDecimal total() {
        return missing.isEmpty() ? round(total, currency) : null;
    }

    public MissingRate.Days missing() {
        return missing;
    }

    /** The rates it used, by currency and date; empty while its figure is missing. */
    public List<RateBook.Rate> rates() {
        return missing.isEmpty() ? sorted(used) : List.of();
    }

    public static List<RateBook.Rate> sorted(Collection<RateBook.Rate> rates) {
        return rates.stream().distinct().sorted(Comparator.comparing(RateBook.Rate::currency)
                .thenComparing(RateBook.Rate::date).thenComparing(rate -> rate.source().name())).toList();
    }

    /** Rounded HALF_UP to the currency's minor unit (D-49). */
    public static BigDecimal round(BigDecimal amount, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return Money.normalize(amount.setScale(digits < 0 ? 4 : digits, RoundingMode.HALF_UP));
    }
}
