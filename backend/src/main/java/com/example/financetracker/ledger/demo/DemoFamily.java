package com.example.financetracker.ledger.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The demo's family budget (H1, D-23; ADR 0003 topic J, "F6c plan"), invented from scratch like {@link DemoLedger}:
 * the user and an invented partner without an account share the demo's three years (D-691): the monthly shop and
 * water bill, a weekend away in US dollars every summer (a record in dollars, D-45, whose half the partner owes in
 * dollars and pays back in dollars), the food for a birthday party every spring, split 60/40, and the sale of an old
 * bike once. The partner pays the user back every three months, so that what they owe stays small. Plain Java: what
 * to record, not how. {@code DemoLedgerService} records it through the family budget's own services, so every rule
 * holds for it as for any family budget.
 * <p>
 * The same day gives the same plan: the monthly records are a few days before the same day of an earlier month, the
 * yearly ones on a calendar date, all within the family budget's days; the amounts follow the calendar as the demo
 * ledger's do. Every amount is a multiple of 0.10, so an equal split has no remainder.
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
    /** The user's share of the birthday party's food, in basis points. */
    private static final int PARTY_SHARE = 6000;
    /** Settlements in euros are rounded down to this. */
    private static final BigDecimal ROUND_TO = new BigDecimal("10");

    /** The monthly shop and water bill by calendar month, January first, at this year's prices. */
    private static final String[] SHOPS = {"96.40", "91.80", "99.20", "94.60", "103.40", "112.90", "108.30",
            "101.70", "97.50", "100.80", "106.40", "124.20"};
    private static final String[] WATER = {"38.60", "36.90", "37.40", "39.80", "41.20", "44.70", "47.30", "46.10",
            "42.40", "39.20", "37.80", "38.10"};
    /** The price level by the calendar years since the record, as the demo ledger's. */
    private static final String[] PRICES = {"1.000", "0.955", "0.915", "0.875"};
    /** The summer weekend by the calendar years since it, in dollars. */
    private static final String[][] WEEKENDS = {{"Two nights by the lake", "260.00"},
            {"A weekend in the hills", "240.00"}, {"Two nights at the seaside", "228.00"},
            {"A weekend in a cabin", "210.00"}};

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

    /** The partner pays the user back, into the user's account in the settlement's currency. */
    public record Settlement(LocalDate date, String amount, String currency, String account, String comment) {

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
        for (int month = 0; month < DemoLedger.MONTHS; month++) {
            LocalDate base = today.minusMonths(DemoLedger.MONTHS - 1 - month);
            LocalDate shop = day(base, 2, start, today);
            records.add(new Record(shop, "EXPENSE", "GROCERIES", priced(shop, SHOPS, today), "EUR", Who.YOU, CURRENT,
                    month == 0 ? "The big shop for both of us" : null, null));
            LocalDate water = day(base, 10, start, today);
            records.add(new Record(water, "EXPENSE", "UTILITIES", priced(water, WATER, today), "EUR", Who.PARTNER,
                    null, null, null));
        }
        for (int year = start.getYear(); year <= today.getYear(); year++) {
            int back = Math.min(today.getYear() - year, 3);
            LocalDate party = LocalDate.of(year, 3, 22);
            if (within(party, start, today)) {
                records.add(new Record(party, "EXPENSE", "GROCERIES", tenths(new BigDecimal("86.50")
                        .multiply(new BigDecimal(PRICES[back]))), "EUR", Who.PARTNER, null,
                        "Food for the birthday party, 60/40", PARTY_SHARE));
            }
            LocalDate weekend = LocalDate.of(year, 8, 9);
            if (within(weekend, start, today)) {
                records.add(new Record(weekend, "EXPENSE", "TRAVEL", WEEKENDS[back][1], "USD", Who.YOU, DOLLARS,
                        WEEKENDS[back][0], null));
            }
        }
        // The bike, sold once, in the last year.
        LocalDate bike = day(today.minusMonths(5), 5, start, today);
        records.add(new Record(bike, "INCOME", "OTHER_INCOME", "120.00", "EUR", Who.YOU, CURRENT, "Sold the old bike",
                null));
        records.sort(Comparator.comparing(Record::date));
        return new Plan(List.copyOf(records), settlements(records, start, today));
    }

    /**
     * Sam's settlements: every three months what Sam owes in euros, rounded down to 10, and each September 20th what
     * Sam owes in dollars, all of it; none where Sam owes nothing.
     */
    private static List<Settlement> settlements(List<Record> records, LocalDate start, LocalDate today) {
        List<Settlement> settlements = new ArrayList<>();
        for (int month = 0; month < DemoLedger.MONTHS; month++) {
            if (month % 3 != 2) {
                continue;
            }
            LocalDate date = day(today.minusMonths(DemoLedger.MONTHS - 1 - month), 1, start, today);
            BigDecimal owed = owed(records, settlements, date, "EUR").divide(ROUND_TO, 0, RoundingMode.FLOOR)
                    .multiply(ROUND_TO);
            if (owed.signum() > 0) {
                settlements.add(new Settlement(date, owed.setScale(2).toPlainString(), "EUR", CURRENT,
                        settlements.isEmpty() ? "Sam's part of the first months" : null));
            }
        }
        for (int year = start.getYear(); year <= today.getYear(); year++) {
            LocalDate date = LocalDate.of(year, 9, 20);
            BigDecimal owed = within(date, start, today) ? owed(records, settlements, date, "USD") : BigDecimal.ZERO;
            if (owed.signum() > 0) {
                settlements.add(new Settlement(date, owed.toPlainString(), "USD", DOLLARS, "Sam's half of the weekend"));
            }
        }
        settlements.sort(Comparator.comparing(Settlement::date));
        return List.copyOf(settlements);
    }

    /**
     * What Sam owes the user in {@code currency} at the end of {@code day}, by the records and settlements up to it:
     * Sam's shares of what the user paid, less the user's shares of what Sam paid, less Sam's share of an income the
     * user received, less what Sam paid back. Negative when the user owes Sam.
     */
    public static BigDecimal owed(List<Record> records, List<Settlement> settlements, LocalDate day,
            String currency) {
        BigDecimal owed = BigDecimal.ZERO;
        for (Record r : records) {
            if (r.date().isAfter(day) || !r.currency().equals(currency)) {
                continue;
            }
            int yours = r.yourBasisPoints() == null ? 5000 : r.yourBasisPoints();
            BigDecimal samsShare = r.amountValue().multiply(BigDecimal.valueOf(10_000 - yours))
                    .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.DOWN);
            BigDecimal yourShare = r.amountValue().subtract(samsShare);
            if (r.type().equals("INCOME")) {
                owed = owed.subtract(samsShare);
            } else {
                owed = r.payer() == Who.YOU ? owed.add(samsShare) : owed.subtract(yourShare);
            }
        }
        for (Settlement s : settlements) {
            if (!s.date().isAfter(day) && s.currency().equals(currency)) {
                owed = owed.subtract(s.amountValue());
            }
        }
        return owed;
    }

    /** The calendar month's amount at the price level of the record's year, to the 0.10 below or above. */
    private static String priced(LocalDate date, String[] byMonth, LocalDate today) {
        return tenths(new BigDecimal(byMonth[date.getMonthValue() - 1])
                .multiply(new BigDecimal(PRICES[Math.min(today.getYear() - date.getYear(), 3)])));
    }

    private static String tenths(BigDecimal amount) {
        return amount.setScale(1, RoundingMode.HALF_UP).setScale(2).toPlainString();
    }

    private static boolean within(LocalDate date, LocalDate start, LocalDate today) {
        return !date.isBefore(start) && !date.isAfter(today);
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
