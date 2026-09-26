package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;

/**
 * A figure of a report in one currency, added up from amounts in any currency, each converted on its own day. If an
 * amount can't be converted, the figure is missing: it is never added up from the amounts that can.
 */
final class ConvertedSum {

    /** Converted figures keep the money scale of NUMERIC(19, 4) (rule 3). */
    private static final int SCALE = 4;

    private final RateBook rates;
    private final String currency;
    private final MissingRate.Days missing = new MissingRate.Days();
    private BigDecimal total = BigDecimal.ZERO;

    ConvertedSum(RateBook rates, String currency) {
        this.rates = rates;
        this.currency = currency;
    }

    /** Adds the amount in {@code from} on {@code day}. Zero is zero in any currency and needs no rate. */
    ConvertedSum add(BigDecimal amount, String from, LocalDate day) {
        if (amount.signum() != 0) {
            rates.convert(amount, from, currency, day).ifPresentOrElse(
                    converted -> total = total.add(converted),
                    () -> missing.add(rates.missing(from, currency, day).orElseThrow(), day));
        }
        return this;
    }

    /**
     * The total, rounded HALF_UP to the money scale once all amounts are added, or null if an amount couldn't be
     * converted.
     */
    BigDecimal total() {
        return missing.isEmpty() ? round(total) : null;
    }

    MissingRate.Days missing() {
        return missing;
    }

    static BigDecimal round(BigDecimal amount) {
        return Money.normalize(amount.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** {@code a − b}, or null if either is missing. */
    static BigDecimal difference(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : Money.normalize(a.subtract(b));
    }
}
