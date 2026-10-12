package com.example.financetracker.ledger.importer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.importer.ImportReport.Problem;

/**
 * The partner's transactions file reduced to what may leave his laptop (D-78, D-140): his family rows (Family = "да",
 * an item other than "-") and, of each, the ID, the date, the item, the Family flag, and the side on UNALLOCATED with
 * its currency and amount, in {@link PartnerRow#COLUMNS}. The import page does the same in the browser
 * ({@code frontend/src/importFilter.ts}) before the upload; this one is the command line's (ImportRunner) and the
 * tests'. Plain Java.
 */
final class PartnerFilter {

    private PartnerFilter() {
    }

    /**
     * The filtered file, as CSV with semicolons.
     *
     * @param unallocatedName the name of the UNALLOCATED account, as the owner's accounts file spells it (the partner's
     *        is the same, in any case)
     * @throws IllegalArgumentException if the file can't be read as the partner's transactions
     */
    static byte[] filter(ImportFile partnerTransactions, String unallocatedName) {
        CsvTable table = CsvTable.read(partnerTransactions, List.of(TransactionRow.ID, TransactionRow.DATE,
                TransactionRow.ITEM, TransactionRow.SUM, TransactionRow.CURRENCY, TransactionRow.DEBIT,
                TransactionRow.CREDIT, TransactionRow.FAMILY, TransactionRow.CUR_DEBIT, TransactionRow.SUM_DEBIT,
                TransactionRow.CUR_CREDIT, TransactionRow.SUM_CREDIT));
        if (!table.problems().isEmpty()) {
            throw new IllegalArgumentException(table.problems().stream().map(Problem::message)
                    .collect(Collectors.joining("; ")));
        }
        String unallocated = unallocatedName.toLowerCase(Locale.ROOT);
        List<String> lines = new ArrayList<>();
        lines.add(String.join(";", PartnerRow.COLUMNS));
        for (CsvTable.Row row : table.rows()) {
            if (row.isBlank() || !TransactionRow.FAMILY_YES.equals(row.get(TransactionRow.FAMILY))
                    || EntryMapper.NO_CATEGORY.equals(row.get(TransactionRow.ITEM))) {
                continue;
            }
            String side = "";
            String currency = row.get(TransactionRow.CURRENCY);
            String sum = row.get(TransactionRow.SUM);
            if (row.get(TransactionRow.DEBIT).toLowerCase(Locale.ROOT).equals(unallocated)) {
                side = PartnerRow.DEBIT;
                if (!row.get(TransactionRow.CUR_DEBIT).isEmpty()) {
                    currency = row.get(TransactionRow.CUR_DEBIT);
                    sum = row.get(TransactionRow.SUM_DEBIT);
                }
            } else if (row.get(TransactionRow.CREDIT).toLowerCase(Locale.ROOT).equals(unallocated)) {
                side = PartnerRow.CREDIT;
                if (!row.get(TransactionRow.CUR_CREDIT).isEmpty()) {
                    currency = row.get(TransactionRow.CUR_CREDIT);
                    sum = row.get(TransactionRow.SUM_CREDIT);
                }
            }
            lines.add(String.join(";", List.of(row.get(TransactionRow.ID), row.get(TransactionRow.DATE),
                    row.get(TransactionRow.ITEM), TransactionRow.FAMILY_YES, side, currency, sum).stream()
                    .map(PartnerFilter::quoted).toList()));
        }
        return (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A CSV field: quoted where it holds a semicolon, a quote or a line break. */
    static String quoted(String value) {
        return value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }
}
