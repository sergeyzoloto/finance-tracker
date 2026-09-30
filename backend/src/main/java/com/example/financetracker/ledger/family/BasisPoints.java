package com.example.financetracker.ledger.family;

/**
 * Shares in basis points, out of 10000 (D-12), as the family budget's messages show them: percentages with two
 * decimals, as the interface does. Integer arithmetic only.
 */
public final class BasisPoints {

    /** A whole: 100.00 %. */
    public static final int WHOLE = 10_000;

    private BasisPoints() {
    }

    /** 3333 → "33.33 %", 10000 → "100.00 %", 5 → "0.05 %". */
    public static String percent(long basisPoints) {
        long abs = Math.abs(basisPoints);
        return "%s%d.%02d %%".formatted(basisPoints < 0 ? "-" : "", abs / 100, abs % 100);
    }
}
