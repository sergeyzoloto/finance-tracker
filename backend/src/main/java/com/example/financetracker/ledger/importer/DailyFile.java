package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.financetracker.ledger.importer.ImportReport.Problem;

/**
 * One of the owner's daily files: the balance file (each account's balance per currency at the end of each day) or the
 * change file (each day's change, with the monthly family lumps). The first column that matters is Date (dd.MM.yyyy);
 * every other column is an account's code and a currency, {@code CODE_CUR}, but for the lumps, {@code PL_family_…_CUR},
 * which are found by their prefix and never by the name between (D-64, D-143), and the partner's Month and Year, which
 * are ignored. Cells are decimals, with Excel's floating-point residues in scientific notation (-1.42109E-14) read
 * exactly; an empty cell is 0. Rows dated after the cutoff, the latest transaction of both ledgers, are neither read
 * nor shown (D-146): the files run on with padding to the end of the next year.
 *
 * @param values by date, then by column; a column that appears twice adds up
 */
record DailyFile(ImportFile file, List<String> columns, NavigableMap<LocalDate, Map<String, BigDecimal>> values) {

    static final String DATE = "Date";
    /** The prefix of the monthly family lumps' columns (D-64), whatever the name after it. */
    static final String LUMP_PREFIX = "PL_family_";
    private static final Set<String> IGNORED = Set.of("Month", "Year");
    private static final Pattern ACCOUNT = Pattern.compile("(.+)_([A-Z]{3})");

    /** A column's account code (with typos fixed, {@link Workbook#code}) and currency. */
    record Column(String code, String currency) {
    }

    /**
     * Reads the file up to the cutoff. A file that can't be read, a column of another shape and a cell that isn't a
     * number are errors, and leave the file empty; no error holds a value of the file.
     */
    static DailyFile read(ImportFile file, LocalDate cutoff, List<Problem> errors) {
        CsvTable table = CsvTable.read(file, List.of(DATE));
        List<Problem> problems = new ArrayList<>(table.problems());
        NavigableMap<LocalDate, Map<String, BigDecimal>> values = new TreeMap<>();
        List<String> columns = new ArrayList<>();
        if (problems.isEmpty()) {
            // The header in the file's order: the row's columns map each name to its index.
            List<String> header = table.rows().isEmpty() ? List.of() : table.rows().getFirst().columns().entrySet()
                    .stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
            for (String column : header) {
                if (column.equals(DATE) || IGNORED.contains(column)) {
                    continue;
                }
                if (!ACCOUNT.matcher(column).matches()) {
                    problems.add(new Problem(file.name(), 1, "a column is neither Date nor an account's code and "
                            + "currency (CODE_CUR)"));
                }
                columns.add(column);
            }
        }
        if (problems.isEmpty()) {
            for (CsvTable.Row row : table.rows()) {
                if (row.isBlank()) {
                    continue;
                }
                LocalDate date;
                try {
                    date = ExcelValues.date(row.get(DATE));
                } catch (IllegalArgumentException e) {
                    problems.add(new Problem(file.name(), row.number(), "Date: " + e.getMessage()));
                    continue;
                }
                if (cutoff != null && date.isAfter(cutoff)) {
                    continue;
                }
                Map<String, BigDecimal> cells = new HashMap<>();
                for (String column : columns) {
                    String cell = row.get(column);
                    try {
                        cells.merge(column, cell.isEmpty() ? BigDecimal.ZERO : ExcelValues.amount(cell),
                                BigDecimal::add);
                    } catch (IllegalArgumentException e) {
                        problems.add(new Problem(file.name(), row.number(), column + ": " + e.getMessage()));
                    }
                }
                if (values.put(date, cells) != null) {
                    problems.add(new Problem(file.name(), row.number(), "the date is in an earlier row too"));
                }
            }
        }
        errors.addAll(problems);
        return problems.isEmpty() ? new DailyFile(file, List.copyOf(columns), Collections.unmodifiableNavigableMap(values))
                : new DailyFile(file, List.of(), Collections.emptyNavigableMap());
    }

    /** The account columns, by column, with the lumps left out. */
    Map<String, Column> accountColumns() {
        Map<String, Column> accounts = new LinkedHashMap<>();
        for (String column : columns) {
            Matcher matcher = ACCOUNT.matcher(column);
            if (!column.startsWith(LUMP_PREFIX) && matcher.matches()) {
                accounts.put(column, new Column(Workbook.code(matcher.group(1)), matcher.group(2)));
            }
        }
        return accounts;
    }

    /** The lumps' columns by their currency, whatever the name between the prefix and the currency. */
    Map<String, String> lumpColumns() {
        Map<String, String> lumps = new LinkedHashMap<>();
        for (String column : columns) {
            Matcher matcher = ACCOUNT.matcher(column);
            if (column.startsWith(LUMP_PREFIX) && matcher.matches()) {
                lumps.put(column, matcher.group(2));
            }
        }
        return lumps;
    }

    BigDecimal value(LocalDate date, String column) {
        Map<String, BigDecimal> cells = values.get(date);
        return cells == null ? BigDecimal.ZERO : cells.getOrDefault(column, BigDecimal.ZERO);
    }

    boolean isEmpty() {
        return values.isEmpty();
    }
}
