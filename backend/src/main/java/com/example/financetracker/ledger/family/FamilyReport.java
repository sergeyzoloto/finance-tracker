package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.rates.RateBook;

/**
 * The family report (E1; ADR 0003 topic J, "F6c plan"): the family's expenses and incomes by month and category, with
 * each member's contribution, from the records that aren't deleted, in each currency of its records (D-45, ADR 0004).
 * {@code total} is D-47's total in the main currency, for display only (F8b).
 *
 * @param from the first day counted, as asked; null for no bound
 * @param to the last day counted, as asked; null for no bound
 * @param members every member, by join order, as the balances name them
 * @param byCurrency the report in each currency: the main currency first, then every other currency of a record of the
 *        period, alphabetically (F8a)
 * @param total each member's totals together in the main currency, approximately (D-47; F8b, additive)
 */
public record FamilyReport(LocalDate from, LocalDate to, List<Member> members, List<CurrencyReport> byCurrency,
        Total total) {

    /**
     * D-47's total: each member's totals in every currency converted to the main currency, each month's amounts at the
     * month's month-end rate and the current month's at today's (D-49), by the rates of the member who reads, and
     * rounded once to its minor unit. For display only.
     *
     * @param totals in {@code members}' order; empty if a currency has no rate
     * @param rates the rates it used, each with its date, source and stale mark; empty if all is in the main currency
     * @param missingCurrencies the currencies without a rate, which leave no total; empty otherwise
     */
    public record Total(String currency, List<MemberTotal> totals, List<RateBook.Rate> rates,
            List<String> missingCurrencies) {
    }

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
