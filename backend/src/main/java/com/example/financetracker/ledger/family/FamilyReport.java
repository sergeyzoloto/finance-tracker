package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.example.financetracker.ledger.domain.CategoryType;

/**
 * The family report (E1; ADR 0003 topic J, "F6c plan"): the family's expenses and incomes by month and category, with
 * each member's contribution, in the family ledger's base currency, from the records that aren't deleted.
 *
 * @param from the first day counted, as asked; null for no bound
 * @param to the last day counted, as asked; null for no bound
 * @param members every member, by join order, as the balances name them
 * @param rows by month, then expenses before incomes, then by category name
 * @param totals each member's totals over the period, in {@code members}' order
 */
public record FamilyReport(String currency, LocalDate from, LocalDate to, List<Member> members, List<Row> rows,
        List<MemberTotal> totals) {

    /** @param you whether this is the member who reads */
    public record Member(long memberId, String displayName, MemberStatus status, boolean hasAccount, boolean you) {
    }

    /**
     * What one family category took in or paid out in one month (of the records' dates).
     *
     * @param total the records' base amounts, which their shares add up to
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
