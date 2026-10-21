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
 * <li>else several on its date whose amounts add up to it, when exactly one such set exists; and the other way round
 * (D-198), several rows of his on one date that add up to one settlement, when exactly one such set does;
 * <li>else the same amount within {@link #DAYS} days, when the pair is unambiguous: the row has that one settlement,
 * and the settlement that one row.
 * </ol>
 * His conversions of the family debt are matched by both legs, each leg's currency, amount and direction, whichever
 * currency the row's Sum is written in, within {@link #DAYS} days and unambiguous. What matches nothing stays
 * unmatched, for the import to report.
 * <p>
 * D-202: a date (in one currency and direction) where two or more of his rows have the amount of one settlement is
 * resolved as a whole before the tiers, never by taking the first row by ID: of every assignment of that date's rows and
 * settlements by the first two tiers and D-198 (a set's rows on one side, {@link Row#side}), the one that covers the most
 * of his rows is taken when it is the only one, up to interchangeable rows and settlements; with several, the rows they
 * cover are ambiguous, and so is every row of a date with more than {@link #MOST_ON_A_DATE} rows or settlements. Other
 * dates go through the tiers as before.
 */
final class SettlementMatcher {

    /** D-178's third tier: how many days apart a row and its settlement may be. */
    static final int DAYS = 7;
    /** The second tier's largest set of settlements on one date. */
    private static final int MOST_IN_A_SET = 6;
    /** Above this many candidates on one date, the second tier doesn't search their sets. */
    private static final int MOST_CANDIDATES = 14;
    /** D-202's bound: a tied date with more of his rows, or more settlements, than this is ambiguous as a whole. */
    static final int MOST_ON_A_DATE = 12;
    /** D-202: past this many assignments that cover the most rows, a tied date is ambiguous as a whole. */
    private static final int MOST_ASSIGNMENTS = 100_000;

    /**
     * A settlement row of his, as the amount his family debt moved by.
     *
     * @param pays he paid the other member: his family debt went down
     * @param side what his side would be on (the account and the counterparty) when the row is in its own currency on
     *        that account; null otherwise. D-198's rows are one side (the import refuses a set that isn't); D-202 counts
     *        only such sets, and only such rows against several settlements.
     */
    record Row(String excelId, LocalDate date, String currency, BigDecimal amount, boolean pays, String side) {

        Row(String excelId, LocalDate date, String currency, BigDecimal amount, boolean pays) {
            this(excelId, date, currency, amount, pays, null);
        }
    }

    /**
     * A settlement of the family budget between him and the other member that his side isn't on yet.
     *
     * @param pays he is its payer
     * @param conversion for a leg of the other member's conversion (D-71), what names the conversion; else null
     * @param excelId the other member's Excel ID of a settlement his import wrote, for messages; else null
     */
    record Candidate(long id, LocalDate date, String currency, BigDecimal amount, boolean pays, String conversion,
            String excelId) {

        Candidate(long id, LocalDate date, String currency, BigDecimal amount, boolean pays, String conversion) {
            this(id, date, currency, amount, pays, conversion, null);
        }
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

    /** D-198: several rows of his on one date that add up to one settlement. */
    record GroupMatch(List<Row> rows, Candidate settlement) {
    }

    /** D-202: rows of his on one date that the settlements of that date take in more than one way. */
    record Ambiguous(List<Row> rows, List<Candidate> settlements) {
    }

    /**
     * @param byDate the Excel IDs of his rows that D-202 matched, also in {@code matches} or {@code groups}
     */
    record Result(List<Match> matches, List<GroupMatch> groups, List<Row> unmatched,
            List<ConversionRow> unmatchedConversions, List<Ambiguous> ambiguous, Set<String> byDate) {
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
        List<GroupMatch> groups = new ArrayList<>();

        // D-202: dates where several of his rows have one settlement's amount, each as a whole.
        List<Ambiguous> ambiguous = new ArrayList<>();
        Set<String> byDate = new HashSet<>();
        Map<List<Object>, List<Row>> byDay = new LinkedHashMap<>();
        left.forEach(r -> byDay.computeIfAbsent(List.of(r.date(), r.currency(), r.pays()), k -> new ArrayList<>()).add(r));
        for (List<Row> day : byDay.values()) {
            Row first = day.getFirst();
            List<Candidate> settlements = open.stream()
                    .filter(c -> c.date().equals(first.date()) && alike(first, c)).toList();
            if (tied(day, settlements)) {
                resolveDate(day, settlements, matches, groups, ambiguous, byDate);
            }
        }
        ambiguous.forEach(a -> {
            left.removeAll(a.rows());
            open.removeAll(a.settlements());
        });
        matches.forEach(m -> open.removeAll(m.settlements()));
        groups.forEach(g -> open.remove(g.settlement()));
        left.removeIf(r -> byDate.contains(r.excelId()));

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

        // 2, the other way round (D-198): several rows of his on one date that add up to one settlement, when exactly
        // one set of them does.
        for (Candidate settlement : new ArrayList<>(open)) {
            List<Row> sameDay = left.stream().filter(r -> r.date().equals(settlement.date()) && alike(r, settlement))
                    .toList();
            if (sameDay.size() < 2 || sameDay.size() > MOST_CANDIDATES) {
                continue;
            }
            List<List<Row>> sets = new ArrayList<>();
            rowSets(sameDay, 0, new ArrayList<>(), BigDecimal.ZERO, settlement.amount(), sets);
            if (sets.size() == 1) {
                groups.add(new GroupMatch(List.copyOf(sets.getFirst()), settlement));
                open.remove(settlement);
                left.removeAll(sets.getFirst());
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
        return new Result(List.copyOf(matches), List.copyOf(groups), List.copyOf(left), List.copyOf(unmatchedConversions),
                List.copyOf(ambiguous), Set.copyOf(byDate));
    }

    /** D-202: two or more of his rows of the date have the amount of one of its settlements. */
    private static boolean tied(List<Row> day, List<Candidate> settlements) {
        return settlements.stream()
                .anyMatch(c -> day.stream().filter(r -> r.amount().compareTo(c.amount()) == 0).count() > 1);
    }

    /**
     * One way a set of his rows of the date takes a set of its settlements: by the first tier (1), the second (2), or
     * D-198 ({@link #MANY}). Rows and settlements as bits of their position on the date.
     */
    private record Pairing(int rows, int settlements, int tier) {
    }

    /** {@link Pairing#tier} of D-198's several rows against one settlement. */
    private static final int MANY = -1;

    /**
     * D-202: the date as a whole. The assignments that cover the most of his rows are taken when they are all one: alike
     * but for rows of his of one amount on one side, or settlements of one amount, which are interchangeable (his side
     * and balances come out the same). Otherwise every row and settlement they cover is ambiguous.
     */
    private static void resolveDate(List<Row> day, List<Candidate> settlements, List<Match> matches,
            List<GroupMatch> groups, List<Ambiguous> ambiguous, Set<String> byDate) {
        if (day.size() > MOST_ON_A_DATE || settlements.size() > MOST_ON_A_DATE) {
            ambiguous.add(new Ambiguous(List.copyOf(day), List.copyOf(settlements)));
            return;
        }
        List<List<Pairing>> best = new ArrayList<>();
        int[] most = {0};
        if (!assign(day.size(), pairings(day, settlements), 0, 0, 0, new ArrayList<>(), best, most)) {
            ambiguous.add(new Ambiguous(List.copyOf(day), List.copyOf(settlements)));
            return;
        }
        Set<List<String>> kinds = new HashSet<>();
        best.forEach(assignment -> kinds.add(kind(assignment, day, settlements)));
        if (kinds.size() > 1) {
            int rows = 0;
            int taken = 0;
            for (List<Pairing> assignment : best) {
                for (Pairing p : assignment) {
                    rows |= p.rows();
                    taken |= p.settlements();
                }
            }
            ambiguous.add(new Ambiguous(bits(day, rows), bits(settlements, taken)));
            return;
        }
        for (Pairing p : best.getFirst()) {
            List<Row> rows = bits(day, p.rows());
            List<Candidate> taken = bits(settlements, p.settlements());
            if (p.tier() == MANY) {
                groups.add(new GroupMatch(rows, taken.getFirst()));
            } else {
                matches.add(new Match(rows.getFirst().excelId(), rows.getFirst().date(), taken, p.tier()));
            }
            rows.forEach(r -> byDate.add(r.excelId()));
        }
    }

    /**
     * An assignment up to interchangeable rows and settlements: each pairing as its tier, its rows' amounts and sides
     * and its settlements' amounts, sorted. A row without a side is told apart by its Excel ID.
     */
    private static List<String> kind(List<Pairing> assignment, List<Row> day, List<Candidate> settlements) {
        return assignment.stream().map(p -> p.tier() + " " + bits(day, p.rows()).stream()
                        .map(r -> r.amount().stripTrailingZeros().toPlainString() + "@"
                                + (r.side() == null ? "#" + r.excelId() : r.side()))
                        .sorted().toList() + " " + bits(settlements, p.settlements()).stream()
                        .map(c -> c.amount().stripTrailingZeros().toPlainString()).sorted().toList())
                .sorted().toList();
    }

    /** Every pairing on the date: the first tier, the second (a row in its own currency), D-198 (rows of one side). */
    private static List<Pairing> pairings(List<Row> day, List<Candidate> settlements) {
        List<Pairing> pairings = new ArrayList<>();
        for (int r = 0; r < day.size(); r++) {
            Row row = day.get(r);
            for (int c = 0; c < settlements.size(); c++) {
                if (settlements.get(c).amount().compareTo(row.amount()) == 0) {
                    pairings.add(new Pairing(1 << r, 1 << c, 1));
                }
            }
            if (row.side() != null) {
                for (int set : sums(settlements.stream().map(Candidate::amount).toList(), row.amount())) {
                    pairings.add(new Pairing(1 << r, set, 2));
                }
            }
        }
        for (int c = 0; c < settlements.size(); c++) {
            for (int set : sums(day.stream().map(Row::amount).toList(), settlements.get(c).amount())) {
                List<Row> rows = bits(day, set);
                String side = rows.getFirst().side();
                if (side != null && rows.stream().allMatch(r -> side.equals(r.side()))) {
                    pairings.add(new Pairing(set, 1 << c, MANY));
                }
            }
        }
        return pairings;
    }

    /** The sets of 2 to {@link #MOST_IN_A_SET} of {@code amounts}, as bits, that add up to {@code target}. */
    private static List<Integer> sums(List<BigDecimal> amounts, BigDecimal target) {
        List<Integer> sets = new ArrayList<>();
        for (int set = 1; set < 1 << amounts.size(); set++) {
            int size = Integer.bitCount(set);
            if (size < 2 || size > MOST_IN_A_SET) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < amounts.size(); i++) {
                if ((set & 1 << i) != 0) {
                    sum = sum.add(amounts.get(i));
                }
            }
            if (sum.compareTo(target) == 0) {
                sets.add(set);
            }
        }
        return sets;
    }

    /**
     * Every assignment of disjoint pairings, row by row (the row left out, or a pairing whose first row it is), keeping
     * in {@code best} those that cover the most rows. False when they are more than {@link #MOST_ASSIGNMENTS}.
     */
    private static boolean assign(int rowCount, List<Pairing> pairings, int row, int usedRows, int usedSettlements,
            List<Pairing> chosen, List<List<Pairing>> best, int[] most) {
        if (row == rowCount) {
            int covered = Integer.bitCount(usedRows);
            if (covered > most[0]) {
                most[0] = covered;
                best.clear();
            }
            if (covered == most[0]) {
                best.add(List.copyOf(chosen));
            }
            return best.size() <= MOST_ASSIGNMENTS;
        }
        if ((usedRows & 1 << row) != 0) {
            return assign(rowCount, pairings, row + 1, usedRows, usedSettlements, chosen, best, most);
        }
        if (!assign(rowCount, pairings, row + 1, usedRows, usedSettlements, chosen, best, most)) {
            return false;
        }
        for (Pairing p : pairings) {
            if (Integer.numberOfTrailingZeros(p.rows()) == row && (p.rows() & usedRows) == 0
                    && (p.settlements() & usedSettlements) == 0) {
                chosen.add(p);
                boolean within = assign(rowCount, pairings, row + 1, usedRows | p.rows(),
                        usedSettlements | p.settlements(), chosen, best, most);
                chosen.removeLast();
                if (!within) {
                    return false;
                }
            }
        }
        return true;
    }

    private static <T> List<T> bits(List<T> from, int set) {
        List<T> chosen = new ArrayList<>();
        for (int i = 0; i < from.size(); i++) {
            if ((set & 1 << i) != 0) {
                chosen.add(from.get(i));
            }
        }
        return chosen;
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

    /** As {@link #subsets}, of his rows. */
    private static void rowSets(List<Row> from, int start, List<Row> chosen, BigDecimal sum, BigDecimal target,
            List<List<Row>> sets) {
        if (chosen.size() >= 2 && sum.compareTo(target) == 0) {
            sets.add(List.copyOf(chosen));
        }
        if (chosen.size() == MOST_IN_A_SET || sets.size() > 1) {
            return;
        }
        for (int i = start; i < from.size(); i++) {
            chosen.add(from.get(i));
            rowSets(from, i + 1, chosen, sum.add(from.get(i).amount()), target, sets);
            chosen.removeLast();
        }
    }

    private static long numeric(String excelId) {
        return Objects.requireNonNullElse(excelId.matches("[0-9]{1,18}") ? Long.parseLong(excelId) : null, 0L);
    }
}
