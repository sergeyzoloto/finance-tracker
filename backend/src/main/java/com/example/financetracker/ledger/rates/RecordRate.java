package com.example.financetracker.ledger.rates;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The rate that converts an amount in one currency into another on a day, for a family record (D-13; ADR 0003, topic
 * D): both currencies' euro rates, the latest on or before the day, through the euro as {@link RateBook} computes a
 * cross rate. Plain Java.
 *
 * @param fromPerEuro units of the original currency for one euro; 1 for EUR
 * @param toPerEuro units of the base currency for one euro; 1 for EUR
 * @param source MANUAL if either euro rate is the member's own, else ECB
 * @param date the day of the older of the two euro rates; the record's date is on or after it
 */
public record RecordRate(BigDecimal fromPerEuro, BigDecimal toPerEuro, RateSource source, LocalDate date) {

    /** The scale of {@link #rate()}, as {@code family_record.base_rate} stores it. */
    public static final int RATE_SCALE = 12;

    /**
     * The amount converted, rounded HALF_UP to the target currency's minor unit (rule 3): from the two euro rates, not
     * from the rounded {@link #rate()}.
     */
    public BigDecimal convert(BigDecimal amount, int scale) {
        return amount.multiply(toPerEuro).divide(fromPerEuro, MathContext.DECIMAL128).setScale(scale,
                RoundingMode.HALF_UP);
    }

    /** Units of the base currency for one unit of the original currency, rounded HALF_UP to {@link #RATE_SCALE}. */
    public BigDecimal rate() {
        return toPerEuro.divide(fromPerEuro, MathContext.DECIMAL128).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
}
