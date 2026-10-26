package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.importer.ImportFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import com.example.financetracker.ledger.importer.ImportReport.Reconciliation;
import com.example.financetracker.ledger.importer.ImportReport.RowDifference;
import com.example.financetracker.ledger.importer.ImportReport.RowLevel;
import com.example.financetracker.ledger.importer.Reconciler.Key;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation in plain Java (D-231 as D-255 and D-256 amend it): per row, the computed columns against what the
 * typed columns compute and what the import builds against them; per month-end, movements from the day before the
 * file's first row date, FAMILY_DEBT with the equity per currency, explained drift.
 */
class ReconcilerTests {

    private static final LocalDate FEB_29 = LocalDate.of(2024, 2, 29);
    private static final LocalDate MAR_8 = LocalDate.of(2024, 3, 8);
    private static final Set<String> CODES = Set.of("CASH", "EURO_ACCOUNT", "FAMILY_DEBT", "UNALLOCATED", "RESERVE");

    private static Map<Key, NavigableMap<LocalDate, BigDecimal>> changes(String... changes) {
        Map<Key, NavigableMap<LocalDate, BigDecimal>> app = new HashMap<>();
        for (String change : changes) {
            String[] p = change.split(" ");
            app.computeIfAbsent(new Key(p[0], p[1]), k -> new TreeMap<>())
                    .merge(LocalDate.parse(p[2]), new BigDecimal(p[3]), BigDecimal::add);
        }
        return app;
    }

    private static final RowLevel NO_ROWS = new RowLevel(0, 0, 0, 0, List.of(), List.of());

    private static Reconciliation reconcile(Map<Key, NavigableMap<LocalDate, BigDecimal>> app, List<Drift> drift) {
        // The file: a salary, a family expense of 10.01 (5.005 on FAMILY_DEBT), cash moved in February and March.
        Map<Key, NavigableMap<LocalDate, BigDecimal>> file = changes("EURO_ACCOUNT EUR 2024-01-02 1000",
                "UNALLOCATED EUR 2024-01-02 1000", "UNALLOCATED EUR 2024-01-10 -5.005", "FAMILY_DEBT EUR 2024-01-10 -5.005",
                "EURO_ACCOUNT EUR 2024-01-10 -10.01", "CASH EUR 2024-02-05 50", "EURO_ACCOUNT EUR 2024-02-05 -50",
                "CASH EUR 2024-03-08 -20", "EURO_ACCOUNT EUR 2024-03-08 20");
        return Reconciler.reconcile(app, file, drift, CODES, LocalDate.of(2024, 1, 2), MAR_8, NO_ROWS);
    }

    /** The ledger as the import leaves it: an opening before the file's first date, which movements leave out. */
    private static final String[] LEDGER = {"EURO_ACCOUNT EUR 2023-12-31 500", "RESERVE EUR 2023-12-31 500",
            "EURO_ACCOUNT EUR 2024-01-02 1000", "UNALLOCATED EUR 2024-01-02 1000", "UNALLOCATED EUR 2024-01-10 -5.00",
            "FAMILY_DEBT EUR 2024-01-10 -5.01", "EURO_ACCOUNT EUR 2024-01-10 -10.01", "CASH EUR 2024-02-05 50",
            "EURO_ACCOUNT EUR 2024-02-05 -50", "CASH EUR 2024-03-08 -20", "EURO_ACCOUNT EUR 2024-03-08 20"};

    /** D-255: FAMILY_DEBT, UNALLOCATED and RESERVE are one key per currency; any other account its own. */
    @Test
    void theFamilyDebtAndTheEquityAreOneSumPerCurrency() {
        assertThat(Reconciler.key("FAMILY_DEBT", "RUB")).isEqualTo(new Key(Reconciler.POOL, "RUB"));
        assertThat(Reconciler.key("UNALLOCATED", "RUB")).isEqualTo(new Key(Reconciler.POOL, "RUB"));
        assertThat(Reconciler.key("RESERVE", "EUR")).isEqualTo(new Key(Reconciler.POOL, "EUR"));
        assertThat(Reconciler.key("LOANS_ASSET", "RUB")).isEqualTo(new Key("LOANS_ASSET", "RUB"));
    }

    /**
     * D-256: movements from the day before the first row date, at each month-end and the cutoff; a family share's
     * rounding (D-12) between FAMILY_DEBT and UNALLOCATED cancels out in the pool.
     */
    @Test
    void movementsAtEveryMonthEndAndTheCutoff() {
        Reconciliation r = reconcile(changes(LEDGER), List.of());
        assertThat(r.passed()).isTrue();
        assertThat(r.from()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(r.cutoff()).isEqualTo(MAR_8);
        assertThat(r.lines()).extracting(l -> l.account() + " " + l.currency())
                .containsExactly("CASH EUR", Reconciler.POOL + " EUR", "EURO_ACCOUNT EUR");
        // Three points per key: January, February and the cutoff.
        assertThat(r.lines()).allSatisfy(l -> assertThat(l.compared()).isEqualTo(3));
        assertThat(r.pointsCompared()).isEqualTo(9);
        assertThat(r.lines().get(2).lastExcel()).isEqualByComparingTo("959.99");
        assertThat(r.lines().get(2).lastApp()).isEqualByComparingTo("959.99");
    }

    @Test
    void anUnexplainedDifferenceFailsFromItsDateAndADriftExplainsIt() {
        List<String> ledger = new ArrayList<>(List.of(LEDGER));
        ledger.add("CASH EUR 2024-02-10 0.02");
        Reconciliation failed = reconcile(changes(ledger.toArray(String[]::new)), List.of());
        assertThat(failed.passed()).isFalse();
        assertThat(failed.lines()).filteredOn(l -> l.account().equals("CASH")).singleElement().satisfies(l -> {
            assertThat(l.failed()).isEqualTo(2);
            assertThat(l.firstFailure()).isEqualTo(FEB_29);
        });
        assertThat(failed.failures()).extracting(ReconcilerTests::point)
                .containsExactly("2024-02-29 CASH EUR 0.02", "2024-03-08 CASH EUR 0.02");

        Reconciliation explained = reconcile(changes(ledger.toArray(String[]::new)), List.of(new Drift("CASH", "EUR",
                LocalDate.of(2024, 2, 10), new BigDecimal("0.02"), Drift.Reason.NO_ROW)));
        assertThat(explained.passed()).isTrue();
        assertThat(explained.explained()).extracting(e -> e.account() + " " + e.reason() + " " + e.amount())
                .containsExactly("CASH NO_ROW 0.02");
        // Within a cent it passes, as before (D-63).
        ledger.set(ledger.size() - 1, "CASH EUR 2024-02-10 0.01");
        assertThat(reconcile(changes(ledger.toArray(String[]::new)), List.of()).passed()).isTrue();
    }

    /** An account of the file's that moved in a currency the file has no row in is listed; FX_EXCHANGE isn't. */
    @Test
    void anAccountThatMovedInACurrencyTheFileHasntIsListed() {
        List<String> ledger = new ArrayList<>(List.of(LEDGER));
        ledger.add("EURO_ACCOUNT RUB 2024-02-01 100");
        ledger.add("FX_EXCHANGE RUB 2024-02-01 -100");
        ledger.add("EURO_ACCOUNT USD 2023-12-01 5");
        Reconciliation r = reconcile(changes(ledger.toArray(String[]::new)), List.of());
        assertThat(r.appOnly()).containsExactly("EURO_ACCOUNT RUB");
    }

    /** A row that fails at the row level fails the reconciliation, however the month-ends add up. */
    @Test
    void aFailingRowFailsTheReconciliation() {
        RowLevel rows = new RowLevel(1, 1, 0, 1, List.of(new RowDifference("7", 8, "CASH EUR", BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.ZERO, new BigDecimal("9"))), List.of());
        Reconciliation r = Reconciler.reconcile(Map.of(), Map.of(), List.of(), CODES, LocalDate.of(2024, 1, 2),
                MAR_8, rows);
        assertThat(r.passed()).isFalse();
    }

    // --- The rows ---------------------------------------------------------------------------------------------------

    private static Map<Long, TransactionRow> rows(byte[] csv) {
        Map<Long, TransactionRow> rows = new HashMap<>();
        Workbook.transactions(new ImportFile("t.csv", csv)).rows().forEach(r -> rows.put(r.id(), r));
        return rows;
    }

    /** D-231 (a): a computed column the typed ones don't give, such as a Sum changed without Excel computing again. */
    @Test
    void aRowsComputedColumnsAreWhatItsTypedColumnsCompute() {
        Map<Long, TransactionRow> clean = rows(bytes("transactions.csv"));
        assertThat(clean.values()).allSatisfy(row -> assertThat(Reconciler.columns(row)).isEmpty());
        // Row 1's Sum changed to 1000.5, its computed columns left at 1000.
        TransactionRow changed = rows(ImportFixtures.withField(bytes("transactions.csv"), "1", 3, "1000.5")).get(1L);
        assertThat(Reconciler.columns(changed)).extracting(RowDifference::what)
                .containsExactly(TransactionRow.DEBIT_CHANGE, TransactionRow.CREDIT_CHANGE);
        // Excel's CSV keeps 10 significant digits of a computed number, and its residues are none.
        byte[] cut = ImportFixtures.withField(ImportFixtures.withField(bytes("transactions.csv"), "19", 23,
                "-123.4567890"), "19", 28, "-1.42109E-14");
        assertThat(Reconciler.columns(rows(cut).get(19L))).isEmpty();
        // D-263: the CSV cut a typed Sum to 10 digits, from which Excel computed the whole half.
        byte[] tenDigits = ImportFixtures.withField(ImportFixtures.withField(bytes("transactions.csv"), "2", 3,
                "133.3333333"), "2", 23, "-66.66666667");
        tenDigits = ImportFixtures.withField(ImportFixtures.withField(tenDigits, "2", 24, "-133.3333333"), "2", 26,
                "-66.66666667");
        assertThat(Reconciler.columns(rows(tenDigits).get(2L))).isEmpty();
        // D-263: a typed Sum shown rounded to cents, its whole value in the computed columns: no difference of a
        // column, a row shown rounded.
        byte[] shown = ImportFixtures.withField(ImportFixtures.withField(bytes("transactions.csv"), "21", 3, "1.50"),
                "21", 23, "-1.504");
        shown = ImportFixtures.withField(shown, "21", 24, "-1.504");
        TransactionRow rounded = rows(shown).get(21L);
        assertThat(Reconciler.columns(rounded)).isEmpty();
        assertThat(Reconciler.shownRounded(rounded)).isTrue();
        // But a Sum of no decimals is no rounding of Excel's: row 1 at 999 against its 1000 is a change.
        byte[] whole = ImportFixtures.withField(bytes("transactions.csv"), "1", 3, "999");
        assertThat(Reconciler.columns(rows(whole).get(1L))).isNotEmpty();
        // A side's account moved without its code, CODE_CREDIT and COUNT_CREDIT telling another account.
        byte[] moved = ImportFixtures.withField(bytes("transactions.csv"), "25", 22, "CASH_RUB");
        assertThat(Reconciler.columns(rows(moved).get(25L))).extracting(RowDifference::what)
                .containsExactly(TransactionRow.COUNT_CREDIT + " isn't what the row computes");
    }

    /** D-231 (a): what the import builds of a row against what the row's columns move, less its drift. */
    @Test
    void whatTheImportBuildsAgainstTheRowsColumns() {
        Map<Long, TransactionRow> rows = rows(bytes("transactions.csv"));
        TransactionRow family = rows.get(2L);
        // Row 2: a family expense of 10.01 from the euro account; the pool moves by the whole of it.
        Map<Key, BigDecimal> built = Map.of(new Key("EURO_ACCOUNT", "EUR"), new BigDecimal("-10.01"),
                new Key(Reconciler.POOL, "EUR"), new BigDecimal("-10.01"));
        RowLevel ok = Reconciler.rows(List.of(family), List.of(new Reconciler.Built(family, built, List.of())),
                CODES, new ArrayList<>());
        assertThat(ok.failed()).isZero();
        assertThat(ok.built()).isEqualTo(1);
        // A cent more on the account fails, unless a drift explains it.
        Map<Key, BigDecimal> off = Map.of(new Key("EURO_ACCOUNT", "EUR"), new BigDecimal("-10.02"),
                new Key(Reconciler.POOL, "EUR"), new BigDecimal("-10.01"));
        RowLevel failed = Reconciler.rows(List.of(family), List.of(new Reconciler.Built(family, off, List.of())),
                CODES, new ArrayList<>());
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(failed.failures()).singleElement().satisfies(d -> {
            assertThat(d.excelId()).isEqualTo("2");
            assertThat(d.what()).isEqualTo("EURO_ACCOUNT EUR");
            assertThat(d.unexplained()).isEqualByComparingTo("-0.01");
        });
        RowLevel explained = Reconciler.rows(List.of(family), List.of(new Reconciler.Built(family, off, List.of(
                new Drift("EURO_ACCOUNT", "EUR", family.date(), new BigDecimal("-0.01"), Drift.Reason.MINOR_UNIT)))),
                CODES, new ArrayList<>());
        assertThat(explained.failed()).isZero();
        assertThat(explained.explainedRows()).isEqualTo(1);
        assertThat(explained.explained()).extracting(e -> e.reason() + " " + e.account() + " " + e.amount())
                .containsExactly("MINOR_UNIT EURO_ACCOUNT -0.01");
        // What the import builds on an account the workbook hasn't, FX_EXCHANGE, isn't compared.
        Map<Key, BigDecimal> exchange = new HashMap<>(built);
        exchange.put(new Key("FX_EXCHANGE", "EUR"), BigDecimal.TEN);
        assertThat(Reconciler.rows(List.of(family), List.of(new Reconciler.Built(family, exchange, List.of())),
                CODES, new ArrayList<>()).failed()).isZero();
    }

    /** A failure's point as one line. */
    private static String point(ImportReport.ReconciliationPoint p) {
        return p.date() + " " + p.account() + " " + p.currency() + " " + p.unexplained().toPlainString();
    }
}
