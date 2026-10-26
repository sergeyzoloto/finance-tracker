package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.example.financetracker.ledger.importer.ExcelRow.Item;
import com.example.financetracker.ledger.importer.ExcelRow.Side;
import com.example.financetracker.ledger.importer.ImportReport.ExplainedAmount;
import com.example.financetracker.ledger.importer.ImportReport.Reconciliation;
import com.example.financetracker.ledger.importer.ImportReport.ReconciliationLine;
import com.example.financetracker.ledger.importer.ImportReport.ReconciliationPoint;
import com.example.financetracker.ledger.importer.ImportReport.RowDifference;
import com.example.financetracker.ledger.importer.ImportReport.RowLevel;

/**
 * The reconciliation of a user's ledger with his transactions file (D-231 as D-255 and D-256 amend it), in plain Java:
 * what {@link ImportReport.Reconciliation} says. It uses the file only, at two levels:
 * <ul>
 * <li>Each row ({@link #rows}): the workbook's computed columns against what {@link ExcelRow} computes from the row's
 * typed ones, and what the import builds of the row against those columns, the difference being the row's
 * {@link Drift} (D-68).
 * <li>Each month-end from the file's first row date to the cutoff ({@link #reconcile}): each account's displayed change
 * in the ledger since the day before the file's first row date, against what the file's rows move on it, the
 * difference being the drift of the rows and of the ledger's entries no row stands for (NO_ROW). The family debt, the
 * equity's UNALLOCATED and RESERVE are one sum per currency (D-255), in which every share of another member's record
 * cancels out.
 * </ul>
 */
final class Reconciler {

    /** D-255: FAMILY_DEBT, UNALLOCATED and RESERVE, compared as one sum per currency. */
    static final String POOL = "EQUITY+FAMILY_DEBT";
    static final String FAMILY_DEBT = "FAMILY_DEBT";
    private static final Set<String> POOLED = Set.of(FAMILY_DEBT, "UNALLOCATED", Workbook.RESERVE);
    /** A month-end's or a row's difference that fails, in absolute value: more than a cent (D-63). */
    static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    /**
     * A row's unexplained difference between what the import builds and its computed columns: more than this fails
     * (D-263). Its computed columns are cut to 10 significant digits in the CSV (D4a).
     */
    static final BigDecimal ROW_TOLERANCE = new BigDecimal("0.0001");
    /**
     * A computed number the same as the typed columns give within this part of its size (D-263): the CSV keeps 10
     * significant digits of a number, of a typed Sum too, and Excel computes from the whole of it.
     */
    private static final BigDecimal CSV_PRECISION = new BigDecimal("0.000000001");
    private static final int MAX_FAILURES = 500;

    /** An account (or the pool) and a currency, as the reconciliation compares them. */
    record Key(String account, String currency) implements Comparable<Key> {

        @Override
        public int compareTo(Key other) {
            return Comparator.comparing(Key::account).thenComparing(Key::currency).compare(this, other);
        }
    }

    private Reconciler() {
    }

    /** The key of an account's code and a currency: FAMILY_DEBT, UNALLOCATED and RESERVE are one (D-255). */
    static Key key(String code, String currency) {
        return new Key(POOLED.contains(code) ? POOL : code, currency);
    }

    /** What the row moves as the workbook computed it, from its own columns, by key. */
    static Map<Key, BigDecimal> fileEffect(TransactionRow row) {
        TransactionRow.Computed c = row.computed();
        Map<Key, BigDecimal> effect = new TreeMap<>();
        effect.merge(key(c.debitCode(), row.debitCurrencyOrRow()), c.debitChange(), BigDecimal::add);
        effect.merge(key(c.creditCode(), row.creditCurrencyOrRow()), c.creditChange(), BigDecimal::add);
        BigDecimal family = c.familyExpense().add(c.familyIncome());
        if (family.signum() != 0) {
            effect.merge(key(FAMILY_DEBT, c.familyCurrency()), family, BigDecimal::add);
        }
        return effect;
    }

    // --- The rows (D-231 a) ------------------------------------------------------------------------------------------

    /** What the import builds of one row, by key; null for a row it builds nothing of because it can't (an error). */
    record Built(TransactionRow row, Map<Key, BigDecimal> effect, List<Drift> drift) {
    }

    /**
     * D-231 (a), per row: the computed columns against what {@link ExcelRow} computes from the row's typed columns and
     * its codes and types (a changed Sum, account or currency shows here), then for each row the import maps, what it
     * builds against what the row's columns move, the difference being the row's drift. A difference no rule explains
     * is reported by the row's ID.
     *
     * @param rows every row of the file up to the cutoff, the app's included
     * @param built what the import builds of each Excel row it maps
     * @param compared the account codes of the file: what the import builds on any other account (FX_EXCHANGE) isn't
     *        the workbook's
     */
    static RowLevel rows(List<TransactionRow> rows, List<Built> built, Set<String> compared, List<Drift> shownRounded) {
        List<RowDifference> failures = new ArrayList<>();
        int failed = 0;
        java.util.Set<String> failedRows = new java.util.HashSet<>();
        java.util.Set<String> rounded = new java.util.HashSet<>();
        for (TransactionRow row : rows) {
            if (shownRounded(row)) {
                rounded.add(row.excelId());
            }
            for (RowDifference difference : columns(row)) {
                failedRows.add(row.excelId());
                if (failures.size() < MAX_FAILURES) {
                    failures.add(difference);
                }
            }
        }
        Map<String, BigDecimal> explained = new TreeMap<>();
        int explainedRows = 0;
        for (Built b : built) {
            Map<Key, BigDecimal> file = fileEffect(b.row());
            Map<Key, BigDecimal> drift = new TreeMap<>();
            for (Drift d : b.drift()) {
                drift.merge(key(d.code(), d.currency()), d.amount(), BigDecimal::add);
            }
            Set<Key> keys = new TreeSet<>(file.keySet());
            b.effect().keySet().stream().filter(k -> k.account().equals(POOL) || compared.contains(k.account()))
                    .forEach(keys::add);
            boolean any = false;
            List<Drift> rowDrift = new ArrayList<>(b.drift());
            for (Key key : keys) {
                BigDecimal app = b.effect().getOrDefault(key, BigDecimal.ZERO);
                BigDecimal excel = file.getOrDefault(key, BigDecimal.ZERO);
                BigDecimal because = drift.getOrDefault(key, BigDecimal.ZERO);
                BigDecimal unexplained = app.subtract(excel).subtract(because);
                if (rounded.contains(b.row().excelId()) && unexplained.signum() != 0
                        && unexplained.abs().compareTo(halfUnit(b.row())) <= 0) {
                    // D-263: the typed amount the CSV shows rounded; the computed columns keep Excel's whole one.
                    Drift d = new Drift(key.account(), key.currency(), b.row().date(), unexplained,
                            Drift.Reason.SHOWN_ROUNDED);
                    shownRounded.add(d);
                    rowDrift.add(d);
                    because = because.add(unexplained);
                    unexplained = BigDecimal.ZERO;
                }
                if (unexplained.abs().compareTo(ROW_TOLERANCE) > 0) {
                    failedRows.add(b.row().excelId());
                    if (failures.size() < MAX_FAILURES) {
                        failures.add(new RowDifference(b.row().excelId(), b.row().row(),
                                key.account() + " " + key.currency(), strip(excel), strip(app), strip(because),
                                strip(unexplained)));
                    }
                }
                any |= because.signum() != 0;
            }
            if (any) {
                explainedRows++;
                for (Drift d : rowDrift) {
                    Key key = key(d.code(), d.currency());
                    explained.merge(d.reason().name() + " " + key.account() + " " + key.currency(), d.amount(),
                            BigDecimal::add);
                }
            }
        }
        failed = failedRows.size();
        List<ExplainedAmount> byReason = new ArrayList<>();
        explained.forEach((name, amount) -> {
            if (amount.signum() != 0) {
                String[] parts = name.split(" ", 3);
                byReason.add(new ExplainedAmount(parts[1], parts[2], parts[0], strip(amount)));
            }
        });
        return new RowLevel(rows.size(), built.size(), explainedRows, failed, failures, byReason);
    }

    /** The row's computed columns that aren't what its typed columns compute (D-237's rules), as differences. */
    static List<RowDifference> columns(TransactionRow row) {
        TransactionRow.Computed c = row.computed();
        ExcelRow excel = excel(row);
        List<RowDifference> differences = new ArrayList<>();
        int scale = typedScale(row);
        number(row, TransactionRow.DEBIT_CHANGE, c.debitChange(), excel.debitChange(), scale, differences);
        number(row, TransactionRow.CREDIT_CHANGE, c.creditChange(), excel.creditChange(), scale, differences);
        number(row, TransactionRow.FAMILY_EXP, c.familyExpense(), excel.familyExpense(), scale, differences);
        number(row, TransactionRow.FAMILY_INC, c.familyIncome(), excel.familyIncome(), scale, differences);
        number(row, TransactionRow.CHECK_CORR, c.checkCorrection(), excel.checkCorrection(), scale, differences);
        number(row, TransactionRow.LOAN_SUM, c.loanSum(), excel.loanSum(), scale, differences);
        text(row, TransactionRow.CUR_FAMILY, c.familyCurrency(), excel.debitCurrencyOrRow(), differences);
        text(row, TransactionRow.COUNT_DEBIT, account(c.debitAccount()), c.debitCode() + "_"
                + excel.debitCurrencyOrRow(), differences);
        text(row, TransactionRow.COUNT_CREDIT, account(c.creditAccount()), c.creditCode() + "_"
                + excel.creditCurrencyOrRow(), differences);
        text(row, TransactionRow.LOAN_CUR, c.loanCurrency(), excel.loanCurrency(), differences);
        text(row, TransactionRow.EXP_ITEM_CODE, c.expItemCode(), row.itemCode(), differences);
        return differences;
    }

    /** The row as {@link ExcelRow} computes it from its typed columns and its codes and types. */
    private static ExcelRow excel(TransactionRow row) {
        TransactionRow.Computed c = row.computed();
        return new ExcelRow(row.excelId(), row.date(), new Item(row.item(), row.itemCode(), c.itemType()), row.sum(),
                row.currency(), "", "", new Side(row.debit(), c.debitCode(), c.debitType()),
                new Side(row.credit(), c.creditCode(), c.creditType()), row.flagged(), row.debitCurrency(),
                row.debitSum(), row.creditCurrency(), row.creditSum(), "");
    }

    /** COUNT_DEBIT or COUNT_CREDIT with its code's typo fixed: {@code CODE_CUR}. */
    private static String account(String count) {
        int cut = count.lastIndexOf('_');
        return cut < 0 ? count : Workbook.code(count.substring(0, cut)) + count.substring(cut);
    }

    /** The decimals of the row's typed amounts as the file shows them: Sum, SUM_DEBIT and SUM_CREDIT. */
    private static int typedScale(TransactionRow row) {
        int scale = 0;
        for (BigDecimal amount : java.util.Arrays.asList(row.sum(), row.debitSum(), row.creditSum())) {
            if (amount != null) {
                scale = Math.max(scale, amount.scale());
            }
        }
        return scale;
    }

    /** Half a unit of the last decimal the row's typed amounts show. */
    private static BigDecimal halfUnit(TransactionRow row) {
        return BigDecimal.ONE.movePointLeft(typedScale(row)).divide(BigDecimal.TWO);
    }

    /** D-263: the row has a computed number that is its typed one's whole value, which the CSV shows rounded. */
    static boolean shownRounded(TransactionRow row) {
        TransactionRow.Computed c = row.computed();
        ExcelRow excel = excel(row);
        int scale = typedScale(row);
        BigDecimal[][] pairs = {{c.debitChange(), excel.debitChange()}, {c.creditChange(), excel.creditChange()},
            {c.checkCorrection(), excel.checkCorrection()}, {c.loanSum(), excel.loanSum()}};
        for (BigDecimal[] pair : pairs) {
            if (!same(pair[0], pair[1]) && shown(pair[0], pair[1], scale)) {
                return true;
            }
        }
        return false;
    }

    /** Equal, or within a billionth of their size: the CSV's 10 significant digits (D-263). */
    private static boolean same(BigDecimal file, BigDecimal computed) {
        BigDecimal size = file.abs().max(computed.abs()).max(BigDecimal.ONE);
        return file.subtract(computed).abs().compareTo(size.multiply(CSV_PRECISION)) <= 0;
    }

    /**
     * Whether the file's number, rounded half up to the typed amounts' decimals, is what they compute (D-263): only for
     * typed amounts of two decimals or more, as Excel's cells show them; a Sum of 10 against a computed 10.01 is a Sum
     * changed by hand.
     */
    private static boolean shown(BigDecimal file, BigDecimal computed, int scale) {
        return scale >= 2 && file.setScale(scale, java.math.RoundingMode.HALF_UP).compareTo(computed) == 0;
    }

    private static void number(TransactionRow row, String column, BigDecimal file, BigDecimal computed, int scale,
            List<RowDifference> differences) {
        if (same(file, computed) || shown(file, computed, scale)) {
            return;
        }
        differences.add(new RowDifference(row.excelId(), row.row(), column, strip(file), strip(computed),
                BigDecimal.ZERO.setScale(2), strip(file.subtract(computed))));
    }

    private static void text(TransactionRow row, String column, String file, String computed,
            List<RowDifference> differences) {
        if (!Objects.equals(emptyAsNull(file), emptyAsNull(computed))) {
            differences.add(new RowDifference(row.excelId(), row.row(), column + " isn't what the row computes", null,
                    null, null, null));
        }
    }

    private static String emptyAsNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    // --- The month-ends (D-231 b) ------------------------------------------------------------------------------------

    /**
     * @param app each account code's displayed change by date and currency (a family budget's debt account as
     *        FAMILY_DEBT), as the ledger holds it after the import
     * @param file what the file's rows move, by key and date, from their computed columns ({@link #fileEffect})
     * @param drift every explained difference: the rows', D-223's APP_EDITED, D-188's SETTLEMENT_DATE and NO_ROW
     * @param compared the account codes of the file; the ledger's other accounts (FX_EXCHANGE, OPENING_BALANCE and
     *        the like) aren't the workbook's
     * @param from the file's first row date: movements are from the day before it (D-256)
     * @param cutoff the file's last row date, or "Import up to" if earlier (D-165)
     * @param rows D-231 (a)'s result, which fails the reconciliation too
     */
    static Reconciliation reconcile(Map<Key, NavigableMap<LocalDate, BigDecimal>> app,
            Map<Key, NavigableMap<LocalDate, BigDecimal>> file, List<Drift> drift, Set<String> compared,
            LocalDate from, LocalDate cutoff, RowLevel rows) {
        Map<Key, NavigableMap<LocalDate, BigDecimal>> appByKey = new HashMap<>();
        app.forEach((raw, changes) -> {
            Key key = key(raw.account(), raw.currency());
            NavigableMap<LocalDate, BigDecimal> merged = appByKey.computeIfAbsent(key, k -> new TreeMap<>());
            changes.forEach((date, change) -> merged.merge(date, change, BigDecimal::add));
        });
        Map<Key, NavigableMap<LocalDate, BigDecimal>> fileByKey = new TreeMap<>();
        file.forEach((raw, changes) -> {
            NavigableMap<LocalDate, BigDecimal> merged = fileByKey.computeIfAbsent(key(raw.account(), raw.currency()),
                    k -> new TreeMap<>());
            changes.forEach((date, change) -> merged.merge(date, change, BigDecimal::add));
        });
        Set<Key> keys = new TreeSet<>(fileByKey.keySet());
        Set<String> appOnly = new TreeSet<>();
        appByKey.forEach((key, changes) -> {
            if (!keys.contains(key) && (key.account().equals(POOL) || compared.contains(key.account()))
                    && between(changes, from, cutoff).signum() != 0) {
                appOnly.add(key.account() + " " + key.currency());
            }
        });
        Map<Key, NavigableMap<LocalDate, BigDecimal>> driftByKey = new HashMap<>();
        Map<Key, Map<Drift.Reason, NavigableMap<LocalDate, BigDecimal>>> driftByReason = new TreeMap<>();
        for (Drift d : drift) {
            Key key = key(d.code(), d.currency());
            driftByKey.computeIfAbsent(key, k -> new TreeMap<>()).merge(d.date(), d.amount(), BigDecimal::add);
            driftByReason.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(d.reason(), r -> new TreeMap<>())
                    .merge(d.date(), d.amount(), BigDecimal::add);
        }

        // Every month-end from the file's first month up to the cutoff, and the cutoff itself.
        List<LocalDate> points = new ArrayList<>();
        if (from != null && cutoff != null && !cutoff.isBefore(from)) {
            for (YearMonth month = YearMonth.from(from); month.atEndOfMonth().isBefore(cutoff);
                    month = month.plusMonths(1)) {
                points.add(month.atEndOfMonth());
            }
            points.add(cutoff);
        }

        List<ReconciliationLine> lines = new ArrayList<>();
        List<ReconciliationPoint> failures = new ArrayList<>();
        int pointsCompared = 0;
        for (Key key : keys) {
            int failed = 0;
            LocalDate firstFailure = null;
            BigDecimal worst = BigDecimal.ZERO;
            BigDecimal lastExcel = null;
            BigDecimal lastApp = null;
            BigDecimal lastExplained = null;
            for (LocalDate date : points) {
                BigDecimal excel = between(fileByKey.get(key), from, date);
                BigDecimal appMovement = between(appByKey.get(key), from, date);
                BigDecimal explained = between(driftByKey.get(key), from, date);
                BigDecimal unexplained = appMovement.subtract(excel).subtract(explained);
                pointsCompared++;
                if (unexplained.abs().compareTo(TOLERANCE) > 0) {
                    failed++;
                    firstFailure = firstFailure == null ? date : firstFailure;
                    if (failures.size() < MAX_FAILURES) {
                        failures.add(new ReconciliationPoint(date, key.account(), key.currency(), strip(excel),
                                strip(appMovement), strip(explained), strip(unexplained)));
                    }
                }
                worst = worst.max(unexplained.abs());
                lastExcel = strip(excel);
                lastApp = strip(appMovement);
                lastExplained = strip(explained);
            }
            lines.add(new ReconciliationLine(key.account(), key.currency(), points.size(), failed, firstFailure,
                    strip(worst), lastExcel, lastApp, lastExplained));
        }

        List<ExplainedAmount> explained = new ArrayList<>();
        if (cutoff != null) {
            driftByReason.forEach((key, reasons) -> new TreeMap<>(reasons).forEach((reason, byDate) -> {
                BigDecimal amount = between(byDate, from, cutoff);
                if (amount.signum() != 0) {
                    explained.add(new ExplainedAmount(key.account(), key.currency(), reason.name(), strip(amount)));
                }
            }));
        }
        boolean passed = !points.isEmpty() && failures.isEmpty() && rows.failed() == 0;
        return new Reconciliation(passed, from, cutoff, pointsCompared, points.isEmpty() ? null : cutoff, lines, failures,
                explained, List.copyOf(appOnly), rows);
    }

    /** The sum of the changes dated from {@code from} to {@code to}, both included. */
    private static BigDecimal between(NavigableMap<LocalDate, BigDecimal> changes, LocalDate from, LocalDate to) {
        if (changes == null || from == null || to == null || to.isBefore(from)) {
            return BigDecimal.ZERO;
        }
        return changes.subMap(from, true, to, true).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal strip(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 2 ? stripped.setScale(2) : stripped;
    }
}
