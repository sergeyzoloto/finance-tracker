package com.example.financetracker.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which entries {@link EntryService#search} finds. Every field is optional, and an entry must match all that are set;
 * within a list filter it must match any of its values (ENT-2, D-873).
 *
 * @param from the earliest entry date, inclusive
 * @param to the latest entry date, inclusive
 * @param accountIds accounts one of the entry's postings is to
 * @param categoryIds categories one of the entry's postings has, or for an entry without one its family record's
 *        (UX-9, D-478)
 * @param counterpartyIds the Payee filter's counterparties: the entry's payee, or the counterparty of one of its
 *        postings, or for an entry with neither its family record's payee that the reader may see by that
 *        counterparty's name, in any case (UX-11, D-523)
 * @param payees the Payee filter's names, each a name the entry's Payee cell shows, in any case (UX-12, D-548); trimmed,
 *        blanks dropped, each at most {@value #PAYEE_MAX} characters after trimming (D-554). With
 *        {@code counterpartyIds}, one filter: an entry matches either (D-873, D-888)
 * @param text a part of the entry's memo, its payee's name or its family record's payee that the reader may see, in
 *        any case
 * @param directions the summary's classification (D-880): income, expenses or transfers
 * @param origins personal (no live family link) or family (a live family link) (D-880)
 */
public record EntryFilter(LocalDate from, LocalDate to, List<Long> accountIds, List<Long> categoryIds,
        List<Long> counterpartyIds, List<String> payees, String text, Set<Direction> directions,
        Set<Origin> origins) {

    /** The longest payee name, counted after trimming. */
    public static final int PAYEE_MAX = 100;

    /** The most values one list filter takes (D-889). */
    public static final int VALUES_MAX = 50;

    /** The summary's rows an entry falls in (D-880): its net of categorized lines below 0, above 0, or 0. */
    public enum Direction {
        INCOME(-1), EXPENSES(1), TRANSFERS(0);

        /** The sign of the entry's net of categorized lines that this direction is. */
        public final int net;

        Direction(int net) {
            this.net = net;
        }
    }

    /** Whether an entry has a live family link (D-880). */
    public enum Origin { PERSONAL, FAMILY }

    public EntryFilter {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        accountIds = distinct(accountIds, "accountId");
        categoryIds = distinct(categoryIds, "categoryId");
        counterpartyIds = distinct(counterpartyIds, "counterpartyId");
        payees = distinct(payees == null ? null : payees.stream()
                .filter(p -> p != null && !p.isBlank()).map(String::strip).toList(), "payee");
        if (payees.stream().anyMatch(p -> p.length() > PAYEE_MAX)) {
            throw new IllegalArgumentException("'payee' must be at most " + PAYEE_MAX + " characters");
        }
        text = text == null || text.isBlank() ? null : text.strip();
        directions = directions == null ? Set.of() : Set.copyOf(directions);
        origins = origins == null ? Set.of() : Set.copyOf(origins);
    }

    /** The same filter without the filters of one dimension: the breakdown's own tab leaves them out (D-881). */
    public EntryFilter without(EntryBreakdown.By by) {
        return switch (by) {
            case CATEGORY -> new EntryFilter(from, to, accountIds, List.of(), counterpartyIds, payees, text, directions,
                    origins);
            case PAYEE -> new EntryFilter(from, to, accountIds, categoryIds, List.of(), List.of(), text, directions,
                    origins);
            case ACCOUNT -> new EntryFilter(from, to, List.of(), categoryIds, counterpartyIds, payees, text,
                    directions, origins);
        };
    }

    /** A filter of dates, text and single values, as before ENT-2. */
    public static EntryFilter of(LocalDate from, LocalDate to, Long accountId, Long categoryId, Long counterpartyId,
            String text, String payee) {
        return new EntryFilter(from, to, one(accountId), one(categoryId), one(counterpartyId), one(payee), text,
                null, null);
    }

    private static <T> List<T> one(T value) {
        return value == null ? List.of() : List.of(value);
    }

    private static <T> List<T> distinct(List<T> values, String name) {
        if (values == null) {
            return List.of();
        }
        List<T> list = values.stream().filter(Objects::nonNull).distinct().toList();
        if (list.size() > VALUES_MAX) {
            throw new IllegalArgumentException("'" + name + "' takes at most " + VALUES_MAX + " values");
        }
        return list;
    }
}
