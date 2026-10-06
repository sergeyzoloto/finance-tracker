package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.CategoryType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The family report (E1; ADR 0003 topic J, "F6c plan"): a family ledger's expenses and incomes by month and category,
 * each member's share of them and what they paid or received, and each member's totals with the settlements, in each
 * currency of the records (D-45, ADR 0004). It reads the records and their shares (ADR 0003 topic D) on every call and stores nothing (rule 13).
 * Every method takes the family ledger's {@link LedgerScope} of the member who reads, from {@code LedgerAccess.member}.
 */
@Service
public class FamilyReportService {

    /** The bounds of a period left open: PostgreSQL's dates cover them. */
    private static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    private final JdbcClient jdbc;

    FamilyReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The report of the records dated {@code from} to {@code to}, both included; a bound left out (null) is open. A
     * deleted record doesn't count, as in the balances; a member who left or deleted their data keeps their place in
     * the rows of their records, under the name the members list gives them.
     *
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FamilyReport report(LedgerScope family, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        String main = jdbc.sql("SELECT base_currency FROM ledger WHERE id = :ledgerId")
                .param("ledgerId", family.ledgerId()).query(String.class).single();
        LocalDate first = from == null ? EARLIEST : from;
        LocalDate last = to == null ? LATEST : to;
        List<String> currencies = new ArrayList<>(List.of(main));
        currencies.addAll(jdbc.sql("""
                SELECT DISTINCT currency FROM family_record
                WHERE ledger_id = :ledgerId AND deleted_at IS NULL AND currency <> :main
                  AND record_date BETWEEN :from AND :to
                ORDER BY currency""")
                .param("ledgerId", family.ledgerId()).param("main", main).param("from", first).param("to", last)
                .query(String.class).list());
        List<FamilyReport.Member> members = members(family);
        Map<Long, Category> categories = categories(family);
        List<FamilyReport.CurrencyReport> byCurrency = new ArrayList<>();
        for (String currency : currencies) {
            byCurrency.add(section(family, currency, first, last, members, categories));
        }
        FamilyReport.CurrencyReport inMain = byCurrency.getFirst();
        return new FamilyReport(main, from, to, members, inMain.rows(), inMain.totals(), byCurrency);
    }

    /** The report of the records in one currency. */
    private FamilyReport.CurrencyReport section(LedgerScope family, String currency, LocalDate from, LocalDate to,
            List<FamilyReport.Member> members, Map<Long, Category> categories) {
        int scale = ShareSplit.minorUnit(currency);
        Map<Long, Integer> joinOrder = new HashMap<>();
        members.forEach(member -> joinOrder.put(member.memberId(), joinOrder.size()));
        Map<RowKey, Map<Long, BigDecimal[]>> rows = new LinkedHashMap<>();
        Map<Long, BigDecimal[]> totals = new LinkedHashMap<>();
        members.forEach(member -> totals.put(member.memberId(), zeros(Total.values().length)));
        for (Line line : lines(family, currency, from, to)) {
            BigDecimal[] total = totals.get(line.memberId());
            switch (line.type()) {
                case "EXPENSE" -> {
                    add(total, Total.EXPENSE_SHARES, line.share());
                    add(total, Total.EXPENSES_PAID, line.paid());
                }
                case "INCOME" -> {
                    add(total, Total.INCOME_SHARES, line.share());
                    add(total, Total.INCOMES_RECEIVED, line.paid());
                }
                case "SETTLEMENT" -> {
                    add(total, Total.SETTLEMENTS_PAID, line.paid());
                    add(total, Total.SETTLEMENTS_RECEIVED, line.received());
                }
                default -> throw new IllegalStateException("A record of type " + line.type());
            }
            if (line.categoryId() != null) {
                BigDecimal[] contribution = rows
                        .computeIfAbsent(new RowKey(line.month(), line.categoryId()), key -> new HashMap<>())
                        .computeIfAbsent(line.memberId(), key -> zeros(2));
                contribution[0] = contribution[0].add(line.share());
                contribution[1] = contribution[1].add(line.paid());
            }
        }

        List<FamilyReport.Row> report = new ArrayList<>();
        rows.forEach((key, byMember) -> {
            Category category = categories.get(key.categoryId());
            List<FamilyReport.Contribution> contributions = byMember.entrySet().stream()
                    .filter(entry -> entry.getValue()[0].signum() != 0 || entry.getValue()[1].signum() != 0)
                    .sorted(Comparator.comparing(entry -> joinOrder.get(entry.getKey())))
                    .map(entry -> new FamilyReport.Contribution(entry.getKey(), money(entry.getValue()[0], scale),
                            money(entry.getValue()[1], scale)))
                    .toList();
            // Every record has one payer or receiver, who paid or received its whole amount.
            BigDecimal total = contributions.stream().map(FamilyReport.Contribution::paid)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            report.add(new FamilyReport.Row(key.month(), key.categoryId(), category.name(), category.type(),
                    category.archived(), money(total, scale), contributions));
        });
        report.sort(Comparator.comparing(FamilyReport.Row::month)
                .thenComparing(row -> row.categoryType().name())
                .thenComparing(FamilyReport.Row::categoryName)
                .thenComparing(FamilyReport.Row::categoryId));

        List<FamilyReport.MemberTotal> memberTotals = totals.entrySet().stream().map(entry -> {
            BigDecimal[] t = entry.getValue();
            BigDecimal net = t[Total.EXPENSE_SHARES.ordinal()].subtract(t[Total.EXPENSES_PAID.ordinal()])
                    .subtract(t[Total.INCOME_SHARES.ordinal()]).add(t[Total.INCOMES_RECEIVED.ordinal()])
                    .subtract(t[Total.SETTLEMENTS_PAID.ordinal()]).add(t[Total.SETTLEMENTS_RECEIVED.ordinal()]);
            return new FamilyReport.MemberTotal(entry.getKey(), money(t[Total.EXPENSE_SHARES.ordinal()], scale),
                    money(t[Total.EXPENSES_PAID.ordinal()], scale), money(t[Total.INCOME_SHARES.ordinal()], scale),
                    money(t[Total.INCOMES_RECEIVED.ordinal()], scale),
                    money(t[Total.SETTLEMENTS_PAID.ordinal()], scale),
                    money(t[Total.SETTLEMENTS_RECEIVED.ordinal()], scale), money(net, scale));
        }).toList();
        return new FamilyReport.CurrencyReport(currency, report, memberTotals);
    }

    /** Every member, by join order, as {@code FamilyRecordService.balances} lists them. */
    private List<FamilyReport.Member> members(LedgerScope family) {
        return jdbc.sql("""
                SELECT id, display_name, status, user_sub IS NOT NULL AS has_account FROM ledger_member
                WHERE ledger_id = :ledgerId ORDER BY join_date, id""")
                .param("ledgerId", family.ledgerId())
                .query((row, n) -> new FamilyReport.Member(row.getLong("id"), row.getString("display_name"),
                        MemberStatus.valueOf(row.getString("status")), row.getBoolean("has_account"),
                        row.getLong("id") == family.memberId()))
                .list();
    }

    private Map<Long, Category> categories(LedgerScope family) {
        Map<Long, Category> categories = new HashMap<>();
        jdbc.sql("SELECT id, name, type, archived_at IS NOT NULL AS archived FROM category WHERE ledger_id = :ledgerId")
                .param("ledgerId", family.ledgerId())
                .query(row -> {
                    categories.put(row.getLong("id"), new Category(row.getString("name"),
                            CategoryType.valueOf(row.getString("type")), row.getBoolean("archived")));
                });
        return categories;
    }

    /**
     * One line per month, record type, category and member, of the records in the currency: their shares, what they paid (an expense or a settlement)
     * or received (an income, as its payer), and what they received of settlements.
     */
    private List<Line> lines(LedgerScope family, String currency, LocalDate from, LocalDate to) {
        // The date is truncated as a timestamp without time zone, as ReportService.cashFlow does, so that the month
        // doesn't depend on the session's time zone.
        return jdbc.sql("""
                WITH r AS (SELECT r.id, r.type, r.category_id, r.payer_member_id, r.payee_member_id, r.base_amount,
                                  date_trunc('month', r.record_date::timestamp)::date AS month
                           FROM family_record r
                           WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL AND r.currency = :currency
                             AND r.record_date BETWEEN :from AND :to)
                SELECT r.month, r.type, r.category_id, s.member_id,
                       sum(s.amount) AS share, 0::numeric AS paid, 0::numeric AS received
                FROM r JOIN family_share s ON s.record_id = r.id
                GROUP BY r.month, r.type, r.category_id, s.member_id
                UNION ALL
                SELECT r.month, r.type, r.category_id, r.payer_member_id, 0, sum(r.base_amount), 0
                FROM r GROUP BY r.month, r.type, r.category_id, r.payer_member_id
                UNION ALL
                SELECT r.month, r.type, r.category_id, r.payee_member_id, 0, 0, sum(r.base_amount)
                FROM r WHERE r.type = 'SETTLEMENT' GROUP BY r.month, r.type, r.category_id, r.payee_member_id""")
                .param("ledgerId", family.ledgerId()).param("currency", currency)
                .param("from", from)
                .param("to", to)
                .query((row, n) -> new Line(YearMonth.from(row.getObject("month", LocalDate.class)),
                        row.getString("type"), row.getObject("category_id", Long.class), row.getLong("member_id"),
                        row.getBigDecimal("share"), row.getBigDecimal("paid"), row.getBigDecimal("received")))
                .list();
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] values = new BigDecimal[n];
        Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }

    private static void add(BigDecimal[] totals, Total which, BigDecimal amount) {
        totals[which.ordinal()] = totals[which.ordinal()].add(amount);
    }

    private static BigDecimal money(BigDecimal amount, int scale) {
        return amount.setScale(scale, RoundingMode.UNNECESSARY);
    }

    private enum Total {
        EXPENSE_SHARES, EXPENSES_PAID, INCOME_SHARES, INCOMES_RECEIVED, SETTLEMENTS_PAID, SETTLEMENTS_RECEIVED
    }

    private record RowKey(YearMonth month, long categoryId) {
    }

    private record Category(String name, CategoryType type, boolean archived) {
    }

    private record Line(YearMonth month, String type, Long categoryId, long memberId, BigDecimal share,
            BigDecimal paid, BigDecimal received) {
    }
}
