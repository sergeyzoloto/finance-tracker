package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * D-178: a member's settlement rows matched to the family budget's settlements that the other member's import (or the
 * app) recorded, in plain Java. His rows never create a settlement: each finds one of the same currency and direction,
 * <ol>
 * <li>the same date and amount;
 * <li>else several on its date whose amounts add up to it, when exactly one such set exists;
 * <li>else the same amount within {@link #DAYS} days, when the pair is unambiguous: the row has that one settlement,
 * and the settlement that one row.
 * </ol>
 * His conversions of the family debt are matched by both legs, each leg's currency, amount and direction, whichever
 * currency the row's Sum is written in, within {@link #DAYS} days and unambiguous. What matches nothing stays
 * unmatched, for the import to report.
 */
final class SettlementMatcher {

    /** D-178's third tier: how many days apart a row and its settlement may be. */
    static final int DAYS = 7;
    /** The second tier's largest set of settlements on one date. */
    private static final int MOST_IN_A_SET = 6;
    /** Above this many candidates on one date, the second tier doesn't search their sets. */
    private static final int MOST_CANDIDATES = 14;

    /**
     * A settlement row of his, as the amount his family debt moved by.
     *
     * @param pays he paid the other member: his family debt went down
     */
    record Row(String excelId, LocalDate date, String currency, BigDecimal amount, boolean pays) {
    }

    /**
     * A settlement of the family budget between him and the other member that his side isn't on yet.
     *
     * @param pays he is its payer
     * @param conversion for a leg of the other member's conversion (D-71), what names the conversion; else null
     */
    record Candidate(long id, LocalDate date, String currency, BigDecimal amount, boolean pays, String conversion) {
    }

    /** One leg of a conversion. */
    record Leg(String currency, BigDecimal amount, boolean pays) {
    }

    /** A conversion row of his: its two legs. */
    record ConversionRow(String excelId, LocalDate date, List<Leg> legs) {
    }

    /**
     * @param tier 1, 2 or 3 for a settlement row, 0 for a conversion
     */
    record Match(String excelId, LocalDate date, List<Candidate> settlements, int tier) {
    }

    record Result(List<Match> matches, List<Row> unmatched, List<ConversionRow> unmatchedConversions) {
    }

    private SettlementMatcher() {
    }

    static Result match(List<Row> rows, List<ConversionRow> conversions, List<Candidate> candidates) {
        List<Match> matches = new ArrayList<>();
        List<Candidate> open = new ArrayList<>(candidates);
        open.sort(Comparator.comparing(Candidate::date).thenComparingLong(Candidate::id));

        // Conversions first: their legs are the other member's conversion's, never a settlement row's.
        List<ConversionRow> unmatchedConversions = new ArrayList<>();
        Map<String, List<Candidate>> legs = new LinkedHashMap<>();
        open.stream().filter(c -> c.conversion() != null)
                .forEach(c -> legs.computeIfAbsent(c.conversion(), k -> new ArrayList<>()).add(c));
        Map<ConversionRow, List<String>> fits = new LinkedHashMap<>();
        Map<String, Integer> fitted = new LinkedHashMap<>();
        for (ConversionRow row : conversions) {
            List<String> found = legs.entrySet().stream()
                    .filter(e -> sameLegs(row, e.getValue()))
                    .map(Map.Entry::getKey).toList();
            fits.put(row, found);
            found.forEach(key -> fitted.merge(key, 1, Integer::sum));
        }
        fits.forEach((row, found) -> {
            if (found.size() == 1 && fitted.get(found.getFirst()) == 1) {
                List<Candidate> both = legs.get(found.getFirst());
                matches.add(new Match(row.excelId(), row.date(), List.copyOf(both), 0));
                open.removeAll(both);
            } else {
                unmatchedConversions.add(row);
            }
        });
        open.removeIf(c -> c.conversion() != null);

        List<Row> left = new ArrayList<>(rows);
        left.sort(Comparator.comparing(Row::date).thenComparing(r -> numeric(r.excelId())));

        // 1. The same date and amount, in order where several are alike.
        for (Row row : new ArrayList<>(left)) {
            Candidate same = open.stream()
                    .filter(c -> c.date().equals(row.date()) && alike(row, c) && c.amount().compareTo(row.amount()) == 0)
                    .findFirst().orElse(null);
            if (same != null) {
                matches.add(new Match(row.excelId(), row.date(), List.of(same), 1));
                open.remove(same);
                left.remove(row);
            }
        }

        // 2. Several on its date that add up to it, when exactly one such set does.
        for (Row row : new ArrayList<>(left)) {
            List<Candidate> sameDay = open.stream().filter(c -> c.date().equals(row.date()) && alike(row, c))
                    .toList();
            if (sameDay.size() < 2 || sameDay.size() > MOST_CANDIDATES) {
                continue;
            }
            List<List<Candidate>> sets = new ArrayList<>();
            subsets(sameDay, 0, new ArrayList<>(), BigDecimal.ZERO, row.amount(), sets);
            if (sets.size() == 1) {
                matches.add(new Match(row.excelId(), row.date(), List.copyOf(sets.getFirst()), 2));
                open.removeAll(sets.getFirst());
                left.remove(row);
            }
        }

        // 3. The same amount within DAYS days, when unambiguous both ways; again while it finds pairs.
        boolean found = true;
        while (found) {
            found = false;
            for (Row row : new ArrayList<>(left)) {
                List<Candidate> near = open.stream().filter(c -> near(row, c)).toList();
                if (near.size() != 1) {
                    continue;
                }
                Candidate only = near.getFirst();
                long rowsNear = left.stream().filter(r -> near(r, only)).count();
                if (rowsNear == 1) {
                    matches.add(new Match(row.excelId(), row.date(), List.of(only), 3));
                    open.remove(only);
                    left.remove(row);
                    found = true;
                }
            }
        }
        return new Result(List.copyOf(matches), List.copyOf(left), List.copyOf(unmatchedConversions));
    }

    private static boolean alike(Row row, Candidate c) {
        return c.conversion() == null && c.currency().equals(row.currency()) && c.pays() == row.pays();
    }

    private static boolean near(Row row, Candidate c) {
        return alike(row, c) && c.amount().compareTo(row.amount()) == 0
                && Math.abs(ChronoUnit.DAYS.between(row.date(), c.date())) <= DAYS;
    }

    private static boolean sameLegs(ConversionRow row, List<Candidate> legs) {
        if (legs.size() != row.legs().size()
                || legs.stream().anyMatch(c -> Math.abs(ChronoUnit.DAYS.between(row.date(), c.date())) > DAYS)) {
            return false;
        }
        Set<Long> used = new HashSet<>();
        for (Leg leg : row.legs()) {
            Candidate same = legs.stream().filter(c -> !used.contains(c.id()) && c.currency().equals(leg.currency())
                    && c.amount().compareTo(leg.amount()) == 0 && c.pays() == leg.pays()).findFirst().orElse(null);
            if (same == null) {
                return false;
            }
            used.add(same.id());
        }
        return true;
    }

    /** Every set of 2 to {@link #MOST_IN_A_SET} of {@code from}, from {@code start} on, whose amounts add up to {@code target}. */
    private static void subsets(List<Candidate> from, int start, List<Candidate> chosen, BigDecimal sum,
            BigDecimal target, List<List<Candidate>> sets) {
        if (chosen.size() >= 2 && sum.compareTo(target) == 0) {
            sets.add(List.copyOf(chosen));
        }
        if (chosen.size() == MOST_IN_A_SET || sets.size() > 1) {
            return;
        }
        for (int i = start; i < from.size(); i++) {
            chosen.add(from.get(i));
            subsets(from, i + 1, chosen, sum.add(from.get(i).amount()), target, sets);
            chosen.removeLast();
        }
    }

    private static long numeric(String excelId) {
        return Objects.requireNonNullElse(excelId.matches("[0-9]{1,18}") ? Long.parseLong(excelId) : null, 0L);
    }
}
