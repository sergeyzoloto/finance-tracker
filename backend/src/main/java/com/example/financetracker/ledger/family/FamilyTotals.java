package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.rates.ConvertedSum;
import com.example.financetracker.ledger.rates.RateBook;
import com.example.financetracker.ledger.rates.RateService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * D-47's totals: each member's balances and report totals in the family's main currency, for display only. They are
 * converted by the rules of every displayed conversion (D-49, D-90, D-91; {@link RateBook}) with the rates of the
 * member who reads, never anyone else's manual rates, and rounded once, at the end, to the main currency's minor unit.
 * If any currency they need has no rate, there is no total, and the answer names the missing currencies. A total is
 * never stored, posted or used in a settlement (D-46): balances, settlements and posting stay per currency.
 * <ul>
 * <li>The balances' total uses the latest available rate: the one that applies today.
 * <li>The report's total converts each month's amounts at that month's month-end rate; the current month, and any later
 * one, at today's.
 * </ul>
 */
@Component
public class FamilyTotals {

    private final JdbcClient jdbc;
    private final RateService rates;
    private final Today today;

    FamilyTotals(JdbcClient jdbc, RateService rates, Today today) {
        this.jdbc = jdbc;
        this.rates = rates;
        this.today = today;
    }

    /** Each member's balance in the main currency, as of today, from their balance in each currency. */
    FamilyBalances.Total balances(LedgerScope family, String main, List<FamilyBalances.CurrencyBalances> byCurrency) {
        LocalDate now = today.date(family);
        RateBook book = rates.rateBook(family.userId(),
                byCurrency.stream().map(FamilyBalances.CurrencyBalances::currency).toList(), now, now);
        Map<Long, ConvertedSum> sums = new LinkedHashMap<>();
        for (FamilyBalances.CurrencyBalances balances : byCurrency) {
            for (FamilyBalances.MemberBalance member : balances.members()) {
                sums.computeIfAbsent(member.memberId(), id -> new ConvertedSum(book, main))
                        .add(member.balance(), balances.currency(), now);
            }
        }
        Set<String> missing = missing(sums.values());
        if (!missing.isEmpty()) {
            return new FamilyBalances.Total(main, now, List.of(), List.of(), List.copyOf(missing));
        }
        List<FamilyBalances.MemberTotal> members = new ArrayList<>();
        sums.forEach((memberId, sum) -> members.add(new FamilyBalances.MemberTotal(memberId, sum.total())));
        return new FamilyBalances.Total(main, now, members, used(sums.values()), List.of());
    }

    /**
     * Each member's report totals in the main currency over the records dated {@code from} to {@code to}: each
     * month's amounts in each currency at the month's month-end rate, the current month and later ones at today's.
     *
     * @param members every member, in the report's order
     */
    FamilyReport.Total report(LedgerScope family, String main, LocalDate from, LocalDate to,
            List<FamilyReport.Member> members) {
        LocalDate now = today.date(family);
        record Amount(LocalDate month, String currency, long memberId, String field, BigDecimal amount) {
        }
        List<Amount> amounts = jdbc.sql("""
                SELECT date_trunc('month', r.record_date)::date AS month, r.currency, s.member_id,
                       CASE r.type WHEN 'EXPENSE' THEN 'expenseShares' ELSE 'incomeShares' END AS field,
                       sum(s.amount) AS amount
                FROM family_share s JOIN family_record r ON r.id = s.record_id AND r.ledger_id = s.ledger_id
                WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL AND r.record_date BETWEEN :from AND :to
                GROUP BY 1, 2, 3, 4
                UNION ALL
                SELECT date_trunc('month', r.record_date)::date, r.currency, r.payer_member_id,
                       CASE r.type WHEN 'EXPENSE' THEN 'expensesPaid' WHEN 'INCOME' THEN 'incomesReceived'
                                   ELSE 'settlementsPaid' END,
                       sum(r.base_amount)
                FROM family_record r
                WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL AND r.record_date BETWEEN :from AND :to
                GROUP BY 1, 2, 3, 4
                UNION ALL
                SELECT date_trunc('month', r.record_date)::date, r.currency, r.payee_member_id, 'settlementsReceived',
                       sum(r.base_amount)
                FROM family_record r
                WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL AND r.record_date BETWEEN :from AND :to
                  AND r.payee_member_id IS NOT NULL
                GROUP BY 1, 2, 3, 4""")
                .param("ledgerId", family.ledgerId()).param("from", from).param("to", to)
                .query((row, n) -> new Amount(row.getObject("month", LocalDate.class), row.getString("currency"),
                        row.getLong("member_id"), row.getString("field"), row.getBigDecimal("amount")))
                .list();
        Set<String> currencies = new TreeSet<>(List.of(main));
        LocalDate first = now;
        for (Amount amount : amounts) {
            currencies.add(amount.currency());
            LocalDate day = day(amount.month(), now);
            first = day.isBefore(first) ? day : first;
        }
        RateBook book = rates.rateBook(family.userId(), currencies, first, now);
        Map<Long, Map<String, ConvertedSum>> sums = new LinkedHashMap<>();
        for (FamilyReport.Member member : members) {
            Map<String, ConvertedSum> fields = new LinkedHashMap<>();
            for (String field : List.of("expenseShares", "expensesPaid", "incomeShares", "incomesReceived",
                    "settlementsPaid", "settlementsReceived", "net")) {
                fields.put(field, new ConvertedSum(book, main));
            }
            sums.put(member.memberId(), fields);
        }
        for (Amount amount : amounts) {
            Map<String, ConvertedSum> fields = sums.get(amount.memberId());
            if (fields == null) {
                continue;
            }
            LocalDate day = day(amount.month(), now);
            fields.get(amount.field()).add(amount.amount(), amount.currency(), day);
            // The balance's movement (D-1): shares and receipts in, payments and income shares out.
            boolean moves = switch (amount.field()) {
                case "expenseShares", "incomesReceived", "settlementsReceived" -> true;
                default -> false;
            };
            fields.get("net").add(moves ? amount.amount() : amount.amount().negate(), amount.currency(), day);
        }
        List<ConvertedSum> all = sums.values().stream().flatMap(fields -> fields.values().stream()).toList();
        Set<String> missing = missing(all);
        if (!missing.isEmpty()) {
            return new FamilyReport.Total(main, List.of(), List.of(), List.copyOf(missing));
        }
        List<FamilyReport.MemberTotal> totals = new ArrayList<>();
        sums.forEach((memberId, f) -> totals.add(new FamilyReport.MemberTotal(memberId,
                f.get("expenseShares").total(), f.get("expensesPaid").total(), f.get("incomeShares").total(),
                f.get("incomesReceived").total(), f.get("settlementsPaid").total(), f.get("settlementsReceived").total(),
                f.get("net").total())));
        return new FamilyReport.Total(main, totals, used(all), List.of());
    }

    /** The day whose rate a month's amounts take: its last day, or today for the current month and later ones. */
    private static LocalDate day(LocalDate month, LocalDate now) {
        LocalDate end = YearMonth.from(month).atEndOfMonth();
        return end.isBefore(now) ? end : now;
    }

    private static Set<String> missing(Iterable<ConvertedSum> sums) {
        Set<String> missing = new TreeSet<>();
        sums.forEach(sum -> sum.missing().toList().forEach(rate -> missing.add(rate.currency())));
        return missing;
    }

    private static List<RateBook.Rate> used(Iterable<ConvertedSum> sums) {
        Set<RateBook.Rate> used = new LinkedHashSet<>();
        sums.forEach(sum -> used.addAll(sum.rates()));
        return ConvertedSum.sorted(used);
    }
}
