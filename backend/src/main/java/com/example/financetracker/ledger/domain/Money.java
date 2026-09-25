package com.example.financetracker.ledger.domain;

import java.math.BigDecimal;

public final class Money {

    private Money() {
    }

    /**
     * The amount without trailing zeros beyond the cents: 190.0000 as stored becomes 190.00, and 0.0050 becomes 0.005.
     * Only the scale changes, never the value.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 2 ? stripped.setScale(2) : stripped;
    }

    static String format(BigDecimal amount) {
        return normalize(amount).toPlainString();
    }
}
