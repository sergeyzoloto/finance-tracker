package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.example.financetracker.ledger.domain.CategoryType;

/**
 * The family report (E1; ADR 0003 topic J, "F6c plan"): the family's expenses and incomes by month and category, with
 * each member's contribution, from the records that aren't deleted, in each currency of its records (D-45, ADR 0004).
 * No total across currencies is computed here (D-47 is F8b's, for display only).
 *
 * @param currency the family's main currency, whose report {@code rows} and {@code totals} are; deprecated since F8a
 *        for {@code byCurrency}
 * @param from the first day counted, as asked; null for no bound
 * @param to the last day counted, as asked; null for no bound
 * @param members every member, by join order, as the balances name them
 * @param rows the main currency's rows; deprecated since F8a for {@code byCurrency}
 * @param totals the main currency's totals; deprecated since F8a for {@code byCurrency}
 * @param byCurrency the report in each currency: the main currency first, then every other currency of a record of the
 *        period, alphabetically (F8a, additive)
 */
public record FamilyReport(@Deprecated String currency, LocalDate from, LocalDate to, List<Member> members,
        @Deprecated List<Row> rows, @Deprecated List<MemberTotal> totals, List<CurrencyReport> byCurrency) {

    /**
     * The report in one currency, from its records only.
     *
     * @param rows by month, then expenses before incomes, then by category name
     * @param totals each member's totals over the period, in {@code members}' order
     */
    public record CurrencyReport(String currency, List<Row> rows, List<MemberTotal> totals) {
    }

    /** @param you whether this is the member who reads */
    public record Member(long memberId, String displayName, MemberStatus status, boolean hasAccount, boolean you) {
    }

    /**
     * What one family category took in or paid out in one month (of the records' dates).
     *
     * @param total the records' amounts, which their shares add up to
     * @param members each member who has a share or paid (an expense) or received (an income), in join order
     */
    public record Row(YearMonth month, long categoryId, String categoryName, CategoryType categoryType,
            boolean archived, BigDecimal total, List<Contribution> members) {
    }

    /**
     * One member's contribution to a row.
     *
     * @param share their share of the row's records
     * @param paid what they paid of an expense category's records, or received of an income category's
     */
    public record Contribution(long memberId, BigDecimal share, BigDecimal paid) {
    }

    /**
     * One member's totals over the period. {@code net} is how much their balance moved: the expense shares less what
     * they paid, the incomes they received less their income shares, the settlements they received less what they
     * paid; positive means they owe the family more. Over every record it is their balance (D-1).
     */
    public record MemberTotal(long memberId, BigDecimal expenseShares, BigDecimal expensesPaid,
            BigDecimal incomeShares, BigDecimal incomesReceived, BigDecimal settlementsPaid,
            BigDecimal settlementsReceived, BigDecimal net) {
    }
}
