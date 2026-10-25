package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What an export of a period holds (D4a): its rows, what it skipped as no single row, its file's name (D-241) and
 * D-242's check.
 *
 * @param from the period's first day as asked; null for the whole period
 * @param to its last day as asked; null for the whole period
 * @param first the earliest date among the rows; null without rows
 * @param last the latest
 * @param rows how many rows the file has, the header not counted
 * @param skipped the records not written, as no single row can stand for them, by kind
 * @param fileName {@code Transactions_<from>_<to>.csv}: the period's days, or for the whole period the rows' first and
 *        last date; {@code Transactions.csv} for a whole period without rows
 */
public record ExportSummary(LocalDate from, LocalDate to, LocalDate first, LocalDate last, int rows,
        List<SkippedCount> skipped, String fileName, Check check) {

    public record SkippedCount(String kind, int count) {
    }

    /**
     * D-242: for every account and currency, the balance at the period's end less the balance the day before it starts
     * is what the exported rows, the other members' records and the skipped records move on it, as the ledger posts
     * them (rule 4's displayed balances).
     *
     * @param ok whether that holds for every account and currency
     */
    public record Check(boolean ok, List<AccountCheck> accounts) {
    }

    /**
     * @param account the account's code
     * @param opening its displayed balance at the end of the day before the period; 0 for the whole period
     * @param closing at the end of its last day
     * @param exported what the exported rows' entries move
     * @param others what the other members' records move: his shares of them
     * @param skipped what the skipped records move
     */
    public record AccountCheck(String account, String name, String currency, BigDecimal opening, BigDecimal closing,
            BigDecimal exported, BigDecimal others, BigDecimal skipped, boolean ok) {
    }
}
