package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import com.example.financetracker.ledger.importer.ImportReport.Reconciliation;
import com.example.financetracker.ledger.importer.Reconciler.Key;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation in plain Java (D-63, D-67, D-68, D-69, D-146, D-147): month-ends up to the cutoff, Excel rounded
 * half up, the equity together, explained drift, the tail shown but not failing, and a currency without family rows.
 */
class ReconcilerTests {

    private static final LocalDate FEB = LocalDate.of(2024, 2, 29);
    private static final LocalDate MAR_8 = LocalDate.of(2024, 3, 8);
    private static final Set<String> FAMILY = Set.of("EUR");

    /** Excel's balances: CASH, FAMILY_DEBT, UNALLOCATED and RESERVE in EUR and USD, with a float residue. */
    private static DailyFile balance(String jan, String feb) {
        String text = "Date;CASH_EUR;FAMILY_DEBT_EUR;UNALLOCATED_EUR;RESERVE_EUR;FAMILY_DEBT_USD;UNALLOCATED_USD;RESERVE_USD\r\n"
                + "31.12.2023;100;0;0;100;0;0;10\r\n"
                + "31.01.2024;" + jan + ";-5.005;-5.005;100;12.5;-12.5;10\r\n"
                + "01.02.2024;" + jan + ";-5.005;0;94.995;12.5;0;-2.5\r\n"
                + "29.02.2024;" + feb + ";-5.005;0;94.995;30;-17.5;-2.5\r\n"
                + "08.03.2024;50;0;0;0;0;0;10\r\n"
                + "31.03.2024;1;2;3;4;5;6;7\r\n";
        return DailyFile.read(new ImportFile("b.csv", text.getBytes(StandardCharsets.UTF_8)), MAR_8,
                new ArrayList<>());
    }

    private static Map<Key, NavigableMap<LocalDate, BigDecimal>> app(String... changes) {
        Map<Key, NavigableMap<LocalDate, BigDecimal>> app = new HashMap<>();
        for (String change : changes) {
            String[] p = change.split(" ");
            app.computeIfAbsent(new Key(p[0], p[1]), k -> new TreeMap<>())
                    .merge(LocalDate.parse(p[2]), new BigDecimal(p[3]), BigDecimal::add);
        }
        return app;
    }

    private static final String[] OPENING = {"CASH EUR 2023-12-31 100", "RESERVE EUR 2023-12-31 100",
            "RESERVE USD 2023-12-31 10"};

    private static String[] with(String... more) {
        List<String> all = new ArrayList<>(List.of(OPENING));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    /** A family expense of 10.01 the owner paid: the seat's share is 5.00, Excel's 5.005, explained by D-12. */
    private static final List<Drift> SHARES = List.of(
            new Drift("FAMILY_DEBT", "EUR", LocalDate.of(2024, 1, 3), new BigDecimal("0.005"), Drift.Reason.SHARES),
            new Drift("UNALLOCATED", "EUR", LocalDate.of(2024, 1, 3), new BigDecimal("-0.005"), Drift.Reason.SHARES));

    private static Reconciliation reconcile(DailyFile balance, Map<Key, NavigableMap<LocalDate, BigDecimal>> app,
            List<Drift> drift) {
        return Reconciler.reconcile(app, balance, drift, FAMILY, Set.of("CASH", "FAMILY_DEBT", "UNALLOCATED", "RESERVE"),
                MAR_8, LocalDate.of(2024, 3, 1), List.of());
    }

    @Test
    void passesWhenEveryDifferenceIsExplained() {
        var app = app(with("CASH EUR 2024-01-03 -10.01", "FAMILY_DEBT EUR 2024-01-03 -5.00",
                "UNALLOCATED EUR 2024-01-03 -5.01"));
        Reconciliation r = reconcile(balance("89.99", "89.99"), app, SHARES);
        assertThat(r.passed()).isTrue();
        // Three month-ends (the opening row's is one) in each of the four keys; 8 March is the tail.
        assertThat(r.pointsCompared()).isEqualTo(12);
        assertThat(r.lastCompared()).isEqualTo(FEB);
        assertThat(r.lines()).extracting(l -> l.account() + " " + l.currency()).containsExactly("CASH EUR",
                "EQUITY EUR", "EQUITY+FAMILY_DEBT USD", "FAMILY_DEBT EUR");
        assertThat(r.explained()).extracting(e -> e.account() + " " + e.reason() + " " + e.amount().toPlainString())
                .containsExactly("EQUITY SHARES -0.005", "FAMILY_DEBT SHARES 0.005");
        // D-147: USD has no family rows; its FAMILY_DEBT and equity are one sum, and the app's FAMILY_DEBT is 0 there.
        assertThat(r.withoutFamilyRows()).singleElement().satisfies(c -> {
            assertThat(c.currency()).isEqualTo("USD");
            assertThat(c.ok()).isTrue();
            assertThat(c.excelFamilyDebt()).isEqualByComparingTo("30");
        });
        // The tail shows 8 March, where the file and the app differ; nothing of 31 March is read (D-146).
        assertThat(r.tail()).extracting(p -> p.date() + " " + p.account()).contains("2024-03-08 CASH");
        assertThat(r.lines()).allSatisfy(l -> assertThat(l.failed()).isZero());
    }

    @Test
    void anUnexplainedDifferenceAboveOneCentFails() {
        var app = app(with("CASH EUR 2024-01-03 -10.01", "FAMILY_DEBT EUR 2024-01-03 -5.00",
                "UNALLOCATED EUR 2024-01-03 -5.01"));
        // Without the drift, 0.005 is within a cent.
        assertThat(reconcile(balance("89.99", "89.99"), app, List.of()).passed()).isTrue();
        Reconciliation r = reconcile(balance("89.99", "89.97"), app, SHARES);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).singleElement().satisfies(p -> {
            assertThat(p.date()).isEqualTo(FEB);
            assertThat(p.account()).isEqualTo("CASH");
            assertThat(p.unexplained()).isEqualByComparingTo("0.02");
        });
        assertThat(r.lines()).filteredOn(l -> l.account().equals("CASH")).singleElement()
                .satisfies(l -> assertThat(l.firstFailure()).isEqualTo(FEB));
        // Exactly a cent passes (D-68: above 0.01 fails).
        assertThat(reconcile(balance("89.99", "89.98"), app, SHARES).passed()).isTrue();
    }

    @Test
    void theAppsFamilyDebtInACurrencyWithoutFamilyRowsIsZeroAtTheLastMonthEnd() {
        var app = app(with("CASH EUR 2024-01-03 -10.01", "FAMILY_DEBT EUR 2024-01-03 -5.00",
                "UNALLOCATED EUR 2024-01-03 -5.01", "FAMILY_DEBT USD 2024-02-10 5", "USD_ACCOUNT USD 2024-02-10 -5"));
        Reconciliation r = reconcile(balance("89.99", "89.99"), app, SHARES);
        assertThat(r.passed()).isFalse();
        assertThat(r.withoutFamilyRows()).singleElement().satisfies(c -> {
            assertThat(c.ok()).isFalse();
            assertThat(c.appFamilyDebt()).isEqualByComparingTo("5");
        });
        // The dollar account isn't one of the accounts file's: not listed.
        assertThat(r.appOnly()).isEmpty();
    }

    @Test
    void anAccountWithABalanceAndNoColumnIsListed() {
        var app = app(with("CASH EUR 2024-01-03 -10.01", "FAMILY_DEBT EUR 2024-01-03 -5.00",
                "UNALLOCATED EUR 2024-01-03 -5.01", "CASH RUB 2024-01-05 7"));
        assertThat(reconcile(balance("89.99", "89.99"), app, SHARES).appOnly()).containsExactly("CASH RUB");
    }

    private static Reconciler.Carried eur(int month, int day, String amount) {
        return new Reconciler.Carried(LocalDate.of(2026, month, day), "EUR", new BigDecimal(amount));
    }

    /**
     * D-169: the owner's D-84 rows of a month less half of the partner's family incomes they carry, explained within
     * half a cent per income, else left whole.
     */
    @Test
    void theOwnersHandRoundedHalvesAreExplainedWithinHalfACentPerIncome() {
        Reconciler.OwnerHalves halves = Reconciler.ownerHalf(
                List.of(eur(1, 31, "5.01"), eur(1, 31, "7.50"),   // January: 5.005 typed up, 7.505 typed down
                        eur(2, 28, "6.01"),                       // February: 1.00 more than 5.005
                        eur(3, 31, "127.48"),                     // March: 77.48 more than 50.00
                        eur(4, 30, "0.01")),                      // April: no income of the partner's
                List.of(eur(1, 10, "10.01"), eur(1, 20, "15.01"), eur(2, 10, "10.01"), eur(3, 10, "100.00"),
                        eur(5, 10, "10.01")));                    // May: an income, no row of the owner's

        assertThat(halves.months()).extracting(h -> h.month().getMonthValue() + " " + h.ownerHalf() + " "
                + h.incomes() + " " + h.explained())
                .containsExactly("1 0.00 2 true", "2 1.005 1 false", "3 77.48 1 false", "4 0.01 0 false",
                        "5 -5.005 1 false");
        // January's two half cents cancel out: nothing to explain.
        assertThat(halves.drift()).isEmpty();

        Reconciler.OwnerHalves one = Reconciler.ownerHalf(List.of(eur(6, 30, "5.01")), List.of(eur(6, 5, "10.01")));
        assertThat(one.months().getFirst().explained()).isTrue();
        assertThat(one.drift()).containsExactly(
                new Drift("FAMILY_DEBT", "EUR", LocalDate.of(2026, 6, 30), new BigDecimal("0.005"),
                        Drift.Reason.OWNER_HALF),
                new Drift("UNALLOCATED", "EUR", LocalDate.of(2026, 6, 30), new BigDecimal("-0.005"),
                        Drift.Reason.OWNER_HALF));
        // A cent typed for one income is above half a cent: unexplained, whole.
        assertThat(Reconciler.ownerHalf(List.of(eur(7, 31, "5.02")), List.of(eur(7, 5, "10.01"))).drift()).isEmpty();
    }

    /** D-169's drift explains what the owner's rounding leaves in his balances, at every month-end after it. */
    @Test
    void anExplainedOwnerHalfPassesTheReconciliation() {
        // Two incomes of 10.01 whose halves (10.01) the owner typed as 10.00: Excel's debt is 0.01 above the app's.
        var app = app(with("CASH EUR 2024-01-03 -10.01", "FAMILY_DEBT EUR 2024-01-03 -5.02",
                "UNALLOCATED EUR 2024-01-03 -4.99"));
        Reconciler.OwnerHalves halves = Reconciler.ownerHalf(
                List.of(new Reconciler.Carried(LocalDate.of(2024, 1, 20), "EUR", new BigDecimal("10.00"))),
                List.of(new Reconciler.Carried(LocalDate.of(2024, 1, 5), "EUR", new BigDecimal("10.01")),
                        new Reconciler.Carried(LocalDate.of(2024, 1, 6), "EUR", new BigDecimal("10.01"))));
        assertThat(halves.months()).singleElement().satisfies(h -> {
            assertThat(h.ownerHalf()).isEqualByComparingTo("-0.01");
            assertThat(h.explained()).isTrue();
        });
        Reconciliation without = reconcile(balance("89.99", "89.99"), app, SHARES);
        assertThat(without.passed()).isFalse();
        assertThat(without.failures()).extracting(p -> p.date() + " " + p.account() + " " + p.unexplained())
                .containsExactly("2024-01-31 EQUITY 0.015", "2024-02-29 EQUITY 0.015", "2024-01-31 FAMILY_DEBT -0.015",
                        "2024-02-29 FAMILY_DEBT -0.015");
        List<Drift> drift = new ArrayList<>(SHARES);
        drift.addAll(halves.drift());
        Reconciliation with = reconcile(balance("89.99", "89.99"), app, drift);
        assertThat(with.passed()).isTrue();
        assertThat(with.explained()).extracting(e -> e.account() + " " + e.reason() + " " + e.amount().toPlainString())
                .contains("EQUITY OWNER_HALF 0.01", "FAMILY_DEBT OWNER_HALF -0.01");
    }
}
