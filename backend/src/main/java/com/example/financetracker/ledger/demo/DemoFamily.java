package com.example.financetracker.ledger.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The demo's family budget (H1, D-23; ADR 0003 topic J, "F6c plan"), invented from scratch like {@link DemoLedger}:
 * the user and an invented partner without an account share six months of groceries, bills, a weekend away in US
 * dollars (a record in dollars, D-45, whose half the partner owes in dollars) and the sale of an old bike, and the partner pays the user back now and then. Plain Java: what to record,
 * not how. {@code DemoLedgerService} records it through the family budget's own services, so every rule holds for it as
 * for any family budget.
 * <p>
 * Deterministic apart from the dates: each month's records are a few days before the same day of an earlier month, and
 * never before the family budget's start.
 */
public final class DemoFamily {

    /** The family budget's name. */
    public static final String NAME = "Demo household";
    /** The invented partner, a member without an account. */
    public static final String PARTNER = "Sam";
    /** The creator's display name when their account has no name. */
    public static final String YOU = "Me";

    /**
     * The demo's personal categories the family budget brings, merged into its own (D-11): their postings move to the
     * family's, and the personal ones go.
     */
    public static final List<String> CATEGORIES = List.of("GROCERIES", "OTHER_INCOME", "TRAVEL", "UTILITIES");

    private static final String CURRENT = "CURRENT_ACCOUNT";
    private static final String DOLLARS = "USD_ACCOUNT";

    private DemoFamily() {
    }

    /** Who paid or received: the user, who has an account, or the partner, who hasn't. */
    public enum Who {
        YOU, PARTNER
    }

    /**
     * A family expense or income.
     *
     * @param account the code of the user's account that paid or received it, when the user did; null for the partner
     * @param currency the record's currency (D-45); the account's
     * @param yourBasisPoints the user's share in basis points of a record split by percentages; null for the rule's
     *        equal shares
     */
    public record Record(LocalDate date, String type, String category, String amount, String currency, Who payer,
            String account, String comment, Integer yourBasisPoints) {

        public BigDecimal amountValue() {
            return new BigDecimal(amount);
        }
    }

    /** The partner pays the user back, into the user's current account. */
    public record Settlement(LocalDate date, String amount, String account, String comment) {

        public BigDecimal amountValue() {
            return new BigDecimal(amount);
        }
    }

    /** What to record, in date order: records and settlements. */
    public record Plan(List<Record> records, List<Settlement> settlements) {
    }

    /**
     * The family budget's records and settlements, from {@code start}, the demo ledger's first day, to {@code today}.
     */
    public static Plan plan(LocalDate start, LocalDate today) {
        List<Record> records = new ArrayList<>();
        List<Settlement> settlements = new ArrayList<>();
        String[] shops = {"58.40", "64.90", "71.25", "52.80", "69.35", "61.70"};
        String[] water = {"38.60", "41.20", "36.90", "39.75", "42.10", "37.45"};
        for (int month = 0; month < DemoLedger.MONTHS; month++) {
            LocalDate base = today.minusMonths(DemoLedger.MONTHS - 1 - month);
            records.add(new Record(day(base, 2, start, today), "EXPENSE", "GROCERIES", shops[month], "EUR",
                    Who.YOU, CURRENT, month == 0 ? "The big shop for both of us" : null, null));
            records.add(new Record(day(base, 10, start, today), "EXPENSE", "UTILITIES", water[month], "EUR",
                    Who.PARTNER, null, null, null));
            switch (month) {
                case 1 -> settlements.add(new Settlement(day(base, 1, start, today), "40.00", CURRENT,
                        "Sam's part of the first months"));
                case 2 -> records.add(new Record(day(base, 6, start, today), "EXPENSE", "TRAVEL", "240.00", "USD",
                        Who.YOU, DOLLARS, "Two nights by the lake", null));
                case 3 -> {
                    records.add(new Record(day(base, 8, start, today), "EXPENSE", "GROCERIES", "86.50", "EUR",
                            Who.PARTNER, null, "Food for the birthday party, 60/40", 6000));
                    settlements.add(new Settlement(day(base, 1, start, today), "55.00", CURRENT, null));
                }
                case 4 -> records.add(new Record(day(base, 5, start, today), "INCOME", "OTHER_INCOME", "120.00", "EUR",
                        Who.YOU, CURRENT, "Sold the old bike", null));
                case 5 -> settlements.add(new Settlement(day(base, 1, start, today), "50.00", CURRENT, null));
                default -> {
                }
            }
        }
        records.sort(Comparator.comparing(Record::date));
        settlements.sort(Comparator.comparing(Settlement::date));
        return new Plan(List.copyOf(records), List.copyOf(settlements));
    }

    /** {@code daysBefore} days before {@code base}, within the family budget's dates. */
    private static LocalDate day(LocalDate base, int daysBefore, LocalDate start, LocalDate today) {
        LocalDate date = base.minusDays(daysBefore);
        if (date.isBefore(start)) {
            return start;
        }
        return date.isAfter(today) ? today : date;
    }
}
