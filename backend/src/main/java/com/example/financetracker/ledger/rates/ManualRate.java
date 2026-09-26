package com.example.financetracker.ledger.rates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A rate the user enters, as units of {@code quote} for one unit of {@code base}. One of the two is EUR; a rate given
 * as euros for one unit of another currency is stored inverted, as units of that currency for one euro.
 */
public record ManualRate(LocalDate date, String base, String quote, BigDecimal rate) {

    /** exchange_rate.rate is NUMERIC(19, 8). */
    static final int MAX_DECIMALS = 8;
    private static final int MAX_INTEGER_DIGITS = 11;
    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    /** What is wrong with the rate, each a sentence without its full stop; empty if nothing is. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (date == null) {
            problems.add("the date is missing");
        }
        boolean currencies = true;
        for (String code : new String[] {base, quote}) {
            if (!isCurrency(code)) {
                problems.add("'%s' is not an ISO 4217 currency code, such as EUR".formatted(code == null ? "" : code));
                currencies = false;
            }
        }
        if (currencies && base.equals(quote)) {
            problems.add("base and quote are both %s".formatted(base));
        } else if (currencies && !base.equals(RateBook.EURO) && !quote.equals(RateBook.EURO)) {
            problems.add("one of base and quote must be EUR: rates between other currencies are computed through "
                    + "the euro");
        }
        if (rate == null) {
            problems.add("the rate is missing");
        } else if (rate.signum() <= 0) {
            problems.add("the rate must be more than 0");
        } else if (rate.stripTrailingZeros().scale() > MAX_DECIMALS) {
            problems.add("the rate has more than %d decimal places".formatted(MAX_DECIMALS));
        } else if (integerDigits(rate) > MAX_INTEGER_DIGITS) {
            problems.add("the rate is too large");
        } else if (currencies && quote.equals(RateBook.EURO) && !base.equals(quote)) {
            BigDecimal inverted = invert(rate);
            if (inverted.signum() == 0 || integerDigits(inverted) > MAX_INTEGER_DIGITS) {
                problems.add("the rate is out of range: %s EUR for one %s can't be stored as %s for one euro"
                        .formatted(rate.toPlainString(), base, base));
            }
        }
        return problems;
    }

    /** The currency other than EUR. Only for a rate without {@link #problems}. */
    public String currency() {
        return base.equals(RateBook.EURO) ? quote : base;
    }

    /** Units of {@link #currency} for one euro. Only for a rate without {@link #problems}. */
    public BigDecimal perEuro() {
        return base.equals(RateBook.EURO) ? rate : invert(rate);
    }

    private static BigDecimal invert(BigDecimal rate) {
        return BigDecimal.ONE.divide(rate, MAX_DECIMALS, RoundingMode.HALF_UP);
    }

    private static int integerDigits(BigDecimal value) {
        return Math.max(value.precision() - value.scale(), 0);
    }

    private static boolean isCurrency(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
