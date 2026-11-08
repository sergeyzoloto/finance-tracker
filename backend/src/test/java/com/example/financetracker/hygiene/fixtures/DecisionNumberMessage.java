package com.example.financetracker.hygiene.fixtures;

/** Breaks D-384 on purpose: a message with a decision number, once whole and once built at run time. */
public final class DecisionNumberMessage {

    private DecisionNumberMessage() {
    }

    public static String skipped() {
        return "Sum is 0 (D-72)";
    }

    public static String conflict(long id) {
        return "Record " + id + ": only they change it (D-8, D-28)";
    }
}
