package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.example.financetracker.ledger.family.ShareSplit;
import com.example.financetracker.ledger.importer.ImportReport.CurrencyWithoutFamilyRows;
import com.example.financetracker.ledger.importer.ImportReport.ExplainedAmount;
import com.example.financetracker.ledger.importer.ImportReport.Reconciliation;
import com.example.financetracker.ledger.importer.ImportReport.ReconciliationLine;
import com.example.financetracker.ledger.importer.ImportReport.ReconciliationPoint;

/**
 * The reconciliation of the owner's balances with his daily balance file (D-63), in plain Java: what
 * {@link ImportReport.Reconciliation} says. The app's side is each account's displayed balance by date, as the import
 * leaves the ledger; Excel's is the daily balance file up to the cutoff (D-146); the explained difference is the import
 * rules' {@link Drift} (D-68).
 */
final class Reconciler {

    static final String EQUITY = "EQUITY";
    static final String FAMILY_DEBT = "FAMILY_DEBT";
    /** D-147: in a currency without family rows, FAMILY_DEBT and the equity are one sum. */
    static final String EQUITY_AND_FAMILY_DEBT = "EQUITY+FAMILY_DEBT";
    private static final Set<String> EQUITY_CODES = Set.of("UNALLOCATED", "RESERVE");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int MAX_FAILURES = 500;

    /** A row's date, currency and amount (above 0), as D-169 adds them up per month. */
    record Carried(LocalDate date, String currency, BigDecimal amount) {
    }

    /** D-169's months, and the drift of those it explains. */
    record OwnerHalves(List<ImportReport.OwnerHalf> months, List<Drift> drift) {
    }

    /** D-169's limit per income of the month: half a cent. */
    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

    /**
     * D-169: per month and currency, OWNER_HALF = the owner's D-84 rows less half of the partner's family incomes they
     * carry, exactly. Within half a cent per income it is explained: FAMILY_DEBT by +OWNER_HALF and UNALLOCATED by
     * -OWNER_HALF (Excel's debt moved by the owner's rows where the partner's records assume the exact half), on the
     * month's latest date of either. Otherwise, or in a month without such an income, the whole of it stays
     * unexplained.
     */
    static OwnerHalves ownerHalf(List<Carried> ownerRows, List<Carried> partnerIncomes) {
        record Month(LocalDate first, String currency) {
        }
        Map<Month, BigDecimal> owner = new TreeMap<>(Comparator.comparing(Month::first).thenComparing(Month::currency));
        Map<Month, BigDecimal> partner = new HashMap<>();
        Map<Month, Integer> incomes = new HashMap<>();
        Map<Month, LocalDate> latest = new HashMap<>();
        for (Carried row : ownerRows) {
            Month month = new Month(row.date().withDayOfMonth(1), row.currency());
            owner.merge(month, row.amount(), BigDecimal::add);
            latest.merge(month, row.date(), (a, b) -> a.isAfter(b) ? a : b);
        }
        for (Carried row : partnerIncomes) {
            Month month = new Month(row.date().withDayOfMonth(1), row.currency());
            owner.putIfAbsent(month, BigDecimal.ZERO);
            partner.merge(month, row.amount(), BigDecimal::add);
            incomes.merge(month, 1, Integer::sum);
            latest.merge(month, row.date(), (a, b) -> a.isAfter(b) ? a : b);
        }
        List<ImportReport.OwnerHalf> months = new ArrayList<>();
        List<Drift> drift = new ArrayList<>();
        owner.forEach((month, own) -> {
            BigDecimal partners = partner.getOrDefault(month, BigDecimal.ZERO);
            int count = incomes.getOrDefault(month, 0);
            BigDecimal half = own.subtract(partners.divide(BigDecimal.TWO));
            boolean explained = count > 0 && half.abs().compareTo(HALF_CENT.multiply(BigDecimal.valueOf(count))) <= 0;
            months.add(new ImportReport.OwnerHalf(month.first(), month.currency(), strip(own), strip(partners), count,
                    strip(half), explained));
            if (explained && half.signum() != 0) {
                LocalDate date = latest.get(month);
                drift.add(new Drift(FAMILY_DEBT, month.currency(), date, half, Drift.Reason.OWNER_HALF));
                drift.add(new Drift("UNALLOCATED", month.currency(), date, half.negate(), Drift.Reason.OWNER_HALF));
            }
        });
        return new OwnerHalves(List.copyOf(months), List.copyOf(drift));
    }

    /** An account and a currency, as the reconciliation compares them. */
    record Key(String account, String currency) implements Comparable<Key> {

        @Override
        public int compareTo(Key other) {
            return Comparator.comparing(Key::account).thenComparing(Key::currency).compare(this, other);
        }
    }

    private Reconciler() {
    }

    /**
     * The key of an account code and currency: UNALLOCATED and RESERVE are the equity (D-67), and in a currency without
     * family rows the equity and FAMILY_DEBT are one (D-147).
     */
    static Key key(String code, String currency, Set<String> familyCurrencies) {
        String account = EQUITY_CODES.contains(code) ? EQUITY : code;
        if (!familyCurrencies.contains(currency) && (account.equals(EQUITY) || account.equals(FAMILY_DEBT))) {
            account = EQUITY_AND_FAMILY_DEBT;
        }
        return new Key(account, currency);
    }

    /**
     * @param app each account code's displayed change by date and currency (the family budget's debt account as
     *        FAMILY_DEBT), as the ledger holds it after the import
     * @param balance the daily balance file, up to the cutoff
     * @param drift every explained difference of the rows in the ledger
     * @param familyCurrencies the currencies of the family records on either side (D-147's are the others)
     * @param comparedCodes the account codes the app compares: those of the accounts file, the system ones left out
     * @param cutoff the latest transaction of both files (D-146)
     * @param tailFrom the first day of the older export's month (D-69)
     * @param ownerHalf D-169's months, whose drift is in {@code drift} already, for the report
     * @param familyDebtUpTo D-175: the family debt is compared up to this day only, the earlier of the other seats'
     *        latest committed cutoffs; its later points wait for the other member's import. Null for no such day
     */
    static Reconciliation reconcile(Map<Key, NavigableMap<LocalDate, BigDecimal>> app, DailyFile balance,
            List<Drift> drift, Set<String> familyCurrencies, Set<String> comparedCodes, LocalDate cutoff,
            LocalDate tailFrom, List<ImportReport.OwnerHalf> ownerHalf, LocalDate familyDebtUpTo) {
        // Excel's balances by key, its columns summed per key.
        Map<String, Key> columnKeys = new HashMap<>();
        balance.accountColumns().forEach((column, account) -> columnKeys.put(column,
                key(account.code(), account.currency(), familyCurrencies)));
        Set<Key> keys = new TreeSet<>(columnKeys.values());

        // The app's balances and the drift, by key.
        Map<Key, NavigableMap<LocalDate, BigDecimal>> appByKey = new HashMap<>();
        Set<String> appOnly = new TreeSet<>();
        Map<String, NavigableMap<LocalDate, BigDecimal>> appFamilyDebt = new HashMap<>();
        app.forEach((raw, changes) -> {
            Key key = key(raw.account(), raw.currency(), familyCurrencies);
            if (raw.account().equals(FAMILY_DEBT)) {
                changes.forEach((date, change) -> appFamilyDebt.computeIfAbsent(raw.currency(), c -> new TreeMap<>())
                        .merge(date, change, BigDecimal::add));
            }
            if (!keys.contains(key)) {
                if (comparedCodes.contains(raw.account()) || raw.account().equals(FAMILY_DEBT)) {
                    BigDecimal total = changes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (total.signum() != 0) {
                        appOnly.add(raw.account() + " " + raw.currency());
                    }
                }
                return;
            }
            NavigableMap<LocalDate, BigDecimal> merged = appByKey.computeIfAbsent(key, k -> new TreeMap<>());
            changes.forEach((date, change) -> merged.merge(date, change, BigDecimal::add));
        });
        Map<Key, NavigableMap<LocalDate, BigDecimal>> driftByKey = new HashMap<>();
        Map<Key, Map<Drift.Reason, NavigableMap<LocalDate, BigDecimal>>> driftByReason = new HashMap<>();
        for (Drift d : drift) {
            Key key = key(d.code(), d.currency(), familyCurrencies);
            driftByKey.computeIfAbsent(key, k -> new TreeMap<>()).merge(d.date(), d.amount(), BigDecimal::add);
            driftByReason.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(d.reason(), r -> new TreeMap<>())
                    .merge(d.date(), d.amount(), BigDecimal::add);
        }

        // The points: every month-end of the file up to the cutoff, and the cutoff itself, which is in the tail.
        List<LocalDate> points = new ArrayList<>();
        for (LocalDate date : balance.values().keySet()) {
            if (date.plusDays(1).getDayOfMonth() == 1 || date.equals(cutoff)) {
                points.add(date);
            }
        }
        LocalDate lastCompared = points.stream().filter(date -> date.isBefore(tailFrom)).reduce((a, b) -> b)
                .orElse(null);

        List<ReconciliationLine> lines = new ArrayList<>();
        List<ReconciliationPoint> failures = new ArrayList<>();
        List<ReconciliationPoint> tail = new ArrayList<>();
        List<ReconciliationPoint> waiting = new ArrayList<>();
        int compared = 0;
        for (Key key : keys) {
            boolean familyDebt = key.account().equals(FAMILY_DEBT) || key.account().equals(EQUITY_AND_FAMILY_DEBT);
            int keyCompared = 0;
            int keyWaiting = 0;
            int failed = 0;
            LocalDate firstFailure = null;
            BigDecimal worst = BigDecimal.ZERO;
            BigDecimal lastExcel = null;
            BigDecimal lastApp = null;
            BigDecimal lastExplained = null;
            for (LocalDate date : points) {
                BigDecimal excel = BigDecimal.ZERO;
                for (Map.Entry<String, Key> column : columnKeys.entrySet()) {
                    if (column.getValue().equals(key)) {
                        excel = excel.add(balance.value(date, column.getKey()));
                    }
                }
                // D-199: Excel's balance as read, without D-63's cut to cents, against the app's exact one.
                BigDecimal appBalance = upTo(appByKey.get(key), date);
                BigDecimal explained = upTo(driftByKey.get(key), date);
                BigDecimal unexplained = appBalance.subtract(excel).subtract(explained);
                ReconciliationPoint point = new ReconciliationPoint(date, key.account(), key.currency(), excel,
                        strip(appBalance), strip(explained), strip(unexplained));
                if (!date.isBefore(tailFrom)) {
                    if (appBalance.subtract(excel).signum() != 0) {
                        tail.add(point);
                    }
                    continue;
                }
                if (familyDebt && familyDebtUpTo != null && date.isAfter(familyDebtUpTo)) {
                    // D-175: the other member hasn't imported this far yet.
                    keyWaiting++;
                    if (unexplained.abs().compareTo(TOLERANCE) > 0 && waiting.size() < MAX_FAILURES) {
                        waiting.add(point);
                    }
                    continue;
                }
                keyCompared++;
                if (unexplained.abs().compareTo(TOLERANCE) > 0) {
                    failed++;
                    firstFailure = firstFailure == null ? date : firstFailure;
                    if (failures.size() < MAX_FAILURES) {
                        failures.add(point);
                    }
                }
                worst = worst.max(unexplained.abs());
                if (date.equals(lastCompared)) {
                    lastExcel = excel;
                    lastApp = strip(appBalance);
                    lastExplained = strip(explained);
                }
            }
            compared += keyCompared;
            lines.add(new ReconciliationLine(key.account(), key.currency(), keyCompared, failed, firstFailure,
                    strip(worst), lastExcel, lastApp, lastExplained, keyWaiting));
        }

        // D-147: in a currency without family rows, the app's FAMILY_DEBT is 0 at the last compared point.
        List<CurrencyWithoutFamilyRows> withoutFamilyRows = new ArrayList<>();
        Set<String> freeCurrencies = new TreeSet<>();
        keys.stream().filter(k -> k.account().equals(EQUITY_AND_FAMILY_DEBT)).forEach(k -> freeCurrencies.add(k.currency()));
        for (String currency : freeCurrencies) {
            BigDecimal appDebt = lastCompared == null ? BigDecimal.ZERO : upTo(appFamilyDebt.get(currency), lastCompared);
            BigDecimal excelDebt = BigDecimal.ZERO;
            if (lastCompared != null) {
                for (Map.Entry<String, DailyFile.Column> column : balance.accountColumns().entrySet()) {
                    if (column.getValue().code().equals(FAMILY_DEBT) && column.getValue().currency().equals(currency)) {
                        excelDebt = excelDebt.add(balance.value(lastCompared, column.getKey()));
                    }
                }
            }
            withoutFamilyRows.add(new CurrencyWithoutFamilyRows(currency, strip(appDebt),
                    excelDebt.setScale(scale(currency), RoundingMode.HALF_UP), appDebt.signum() == 0));
        }

        List<ExplainedAmount> explained = new ArrayList<>();
        if (lastCompared != null) {
            new TreeMap<>(driftByReason).forEach((key, reasons) -> reasons.forEach((reason, byDate) -> {
                BigDecimal amount = upTo(byDate, lastCompared);
                if (amount.signum() != 0) {
                    explained.add(new ExplainedAmount(key.account(), key.currency(), reason.name(), strip(amount)));
                }
            }));
        }
        boolean passed = lastCompared != null && failures.isEmpty()
                && withoutFamilyRows.stream().allMatch(CurrencyWithoutFamilyRows::ok);
        return new Reconciliation(passed, cutoff, tailFrom, compared, lastCompared, lines, failures, tail,
                withoutFamilyRows, explained, List.copyOf(appOnly), List.copyOf(ownerHalf), familyDebtUpTo,
                waiting);
    }

    private static BigDecimal upTo(NavigableMap<LocalDate, BigDecimal> changes, LocalDate date) {
        return changes == null ? BigDecimal.ZERO
                : changes.headMap(date, true).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int scale(String currency) {
        try {
            return ShareSplit.minorUnit(currency);
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    private static BigDecimal strip(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 2 ? stripped.setScale(2) : stripped;
    }
}
