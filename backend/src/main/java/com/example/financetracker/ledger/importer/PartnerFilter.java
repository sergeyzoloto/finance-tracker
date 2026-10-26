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
     * @param unallocatedName the name of the UNALLOCATED account, as the owner's own file spells it (the partner's is
     *        the same, in any case)
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
        // D-260: the item's code and type, where his file has D-235's columns.
        boolean items = !table.rows().isEmpty() && table.rows().getFirst().columns().keySet()
                .containsAll(PartnerRow.ITEM_COLUMNS) || table.header().containsAll(PartnerRow.ITEM_COLUMNS);
        List<String> header = new ArrayList<>(PartnerRow.COLUMNS);
        if (items) {
            header.addAll(PartnerRow.ITEM_COLUMNS);
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.join(";", header));
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
            List<String> values = new ArrayList<>(List.of(row.get(TransactionRow.ID), row.get(TransactionRow.DATE),
                    row.get(TransactionRow.ITEM), TransactionRow.FAMILY_YES, side, currency, sum));
            if (items) {
                values.add(row.get(TransactionRow.CODE_ITEM));
                values.add(row.get(TransactionRow.TYPE));
            }
            lines.add(String.join(";", values.stream().map(PartnerFilter::quoted).toList()));
        }
        return (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The name of UNALLOCATED as the user's own transactions file spells it (D-260): Debit or Credit of a row whose
     * CODE_DEBIT or CODE_CREDIT is UNALLOCATED; null if no row has it.
     */
    static String unallocatedName(ImportFile transactions) {
        String code = com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED.defaultCode();
        for (TransactionRow row : Workbook.transactions(transactions).rows()) {
            if (row.computed() != null && code.equals(row.computed().debitCode())) {
                return row.debit();
            }
            if (row.computed() != null && code.equals(row.computed().creditCode())) {
                return row.credit();
            }
        }
        return null;
    }

    /** A CSV field: quoted where it holds a semicolon, a quote or a line break. */
    static String quoted(String value) {
        return value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }
}
