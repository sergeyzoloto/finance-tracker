package com.example.financetracker.ledger.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A currency without a rate on some days, so that amounts on those days can't be converted.
 *
 * @param currency the currency without a rate: the amounts' own, or the currency they are converted to
 * @param from the first of those days
 * @param to the last of those days
 * @param days how many days, from {@code from} to {@code to}, need a rate and have none
 */
public record MissingRate(String currency, LocalDate from, LocalDate to, int days) {

    /** The days without a rate per currency, collected while converting. */
    public static final class Days {

        private final Map<String, SortedSet<LocalDate>> byCurrency = new TreeMap<>();

        public void add(String currency, LocalDate day) {
            byCurrency.computeIfAbsent(currency, c -> new TreeSet<>()).add(day);
        }

        public Days addAll(Days other) {
            other.byCurrency.forEach((currency, days) -> days.forEach(day -> add(currency, day)));
            return this;
        }

        public boolean isEmpty() {
            return byCurrency.isEmpty();
        }

        /** Ordered by currency. */
        public List<MissingRate> toList() {
            return byCurrency.entrySet().stream()
                    .map(e -> new MissingRate(e.getKey(), e.getValue().first(), e.getValue().last(),
                            e.getValue().size()))
                    .toList();
        }
    }
}
