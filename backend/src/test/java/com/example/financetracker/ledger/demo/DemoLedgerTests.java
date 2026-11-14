package com.example.financetracker.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.IncomeCommand;
import com.example.financetracker.ledger.domain.LedgerContext;
import com.example.financetracker.ledger.domain.LedgerReferences;
import com.example.financetracker.ledger.domain.LedgerReferences.AccountInfo;
import com.example.financetracker.ledger.domain.LedgerReferences.CategoryInfo;
import com.example.financetracker.ledger.domain.LedgerValidator;
import com.example.financetracker.ledger.domain.PostingLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The demo ledger as {@link DemoLedger} generates it, without Spring or a database: its accounts are the starter
 * ledger's and the demo's own, with made-up ids. Figures that depend on the day the demo is loaded on are worked out
 * on {@link #TODAY}; the rules that hold on every day are checked over every day of four years (2026 to 2029, a leap
 * year among them), or on the days of {@link #LOAD_DAYS} where a check needs the whole ledger's balances.
 */
class DemoLedgerTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
    private static final LocalDate AFTER_LAST = LocalDate.of(2030, 1, 1);
    /** A 31st, 28 and 29 February, 1 January, 31 December, and a few ordinary days. */
    private static final List<LocalDate> LOAD_DAYS = List.of(LocalDate.of(2026, 10, 31), LocalDate.of(2027, 2, 28),
            LocalDate.of(2028, 2, 29), LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 3, 31),
            LocalDate.of(2027, 7, 15), LocalDate.of(2028, 9, 1), LocalDate.of(2029, 6, 30), TODAY);
    /** The entries every month has once (D-689), by memo; the weekly shop twice. */
    private static final List<String> MONTHLY = List.of("Membership", "Salary", "Rent", "Credit card bill",
            "Monthly savings", "Monthly transit pass", "Dinner out", "Cash withdrawal",
            "Partner's share of the groceries", "Vegetables and bread", "Electricity and heating",
            "Streaming subscription", "Design app subscription", "Coffee and cake", "Internet and phone",
            "Lunch with colleagues", "Account fee");

    private final Chart chart = Chart.read();

    @Test
    void hasAnEntryOfEveryKindTheAppSupports() {
        Map<EntryKind, Long> byKind = DemoLedger.entries(TODAY, chart).stream()
                .collect(Collectors.groupingBy(EntryCommand::kind, () -> new EnumMap<>(EntryKind.class),
                        Collectors.counting()));

        // Every kind a user makes an entry of; the family kinds are the family budget's to post (F4a).
        assertThat(byKind.keySet()).isEqualTo(EnumSet.copyOf(EnumSet.allOf(EntryKind.class).stream()
                .filter(EntryKind::isCommand).toList()));
        assertThat(byKind).isEqualTo(BY_KIND_ON_TODAY);
    }

    /**
     * D-689: 36 months, this month included, from opening balances on the first day of the first month to today, so
     * that the two calendar years before the load day's year are covered in full, whatever day it is loaded on.
     */
    @Test
    void runsFromTheFirstOfAMonth35MonthsBackToTodayWhateverDayItIsLoadedOn() {
        for (LocalDate today = FIRST; today.isBefore(AFTER_LAST); today = today.plusDays(1)) {
            List<EntryCommand> entries = DemoLedger.entries(today, chart);
            LocalDate end = today;
            LocalDate start = entries.getFirst().entryDate();

            assertThat(start).as("loaded on %s", today).isEqualTo(DemoLedger.firstDay(today));
            assertThat(start.getDayOfMonth()).isOne();
            assertThat(YearMonth.from(start)).as("loaded on %s", today)
                    .isBetween(YearMonth.from(today).minusMonths(36), YearMonth.from(today).minusMonths(35));
            assertThat(start).as("loaded on %s", today).isBeforeOrEqualTo(LocalDate.of(today.getYear() - 2, 1, 1));
            assertThat(entries.subList(0, 6)).allMatch(e -> e.kind() == EntryKind.OPENING_BALANCE);
            assertThat(entries).filteredOn(e -> e.kind() == EntryKind.OPENING_BALANCE)
                    .allMatch(e -> e.entryDate().equals(start));
            // The first month has entries besides the opening balances, so the ledger's months are its entries'.
            assertThat(YearMonth.from(entries.get(6).entryDate())).isEqualTo(YearMonth.from(start));
            assertThat(entries).allMatch(e -> !e.entryDate().isAfter(end));
            assertThat(entries.getLast().entryDate()).isEqualTo(today);
            assertThat(entries).isSortedAccordingTo((a, b) -> a.entryDate().compareTo(b.entryDate()));
        }
    }

    /**
     * Monthly entries land once in every month the ledger covers in full, even when loaded on the 31st, and so in
     * every month of the two calendar years before the load day's year (D-689).
     */
    @Test
    void everyMonthCoveredInFullHasEachMonthlyEntryOnce() {
        for (LocalDate today = FIRST; today.isBefore(AFTER_LAST); today = today.plusDays(1)) {
            List<EntryCommand> entries = DemoLedger.entries(today, chart);
            Map<YearMonth, Map<String, Long>> memosByMonth = entries.stream()
                    .collect(Collectors.groupingBy(e -> YearMonth.from(e.entryDate()), TreeMap::new,
                            Collectors.groupingBy(EntryCommand::memo, Collectors.counting())));
            YearMonth first = YearMonth.from(entries.getFirst().entryDate());
            YearMonth twoYearsBack = YearMonth.of(today.getYear() - 2, 1);
            YearMonth from = first.isBefore(twoYearsBack) ? first.plusMonths(1) : twoYearsBack;
            for (YearMonth month = from; month.isBefore(YearMonth.from(today)); month = month.plusMonths(1)) {
                Map<String, Long> memos = memosByMonth.get(month);
                for (String memo : MONTHLY) {
                    assertThat(memos).as("%s in %s, loaded on %s", memo, month, today).containsEntry(memo, 1L);
                }
                assertThat(memos).as("%s, loaded on %s", month, today).containsEntry("Weekly shop", 2L);
            }
            assertThat(memosByMonth.values()).as("loaded on %s", today)
                    .allMatch(memos -> memos.getOrDefault("Salary", 0L) <= 1);
        }
    }

    /** The same day gives the same ledger; another day may give other amounts, which follow the calendar (D-690). */
    @Test
    void isTheSameLedgerForTheSameDay() {
        Function<LocalDate, List<String>> described = today -> DemoLedger.entries(today, chart).stream()
                .map(e -> e.entryDate() + " " + e.kind() + " " + e.memo() + " " + e.payeeId() + " "
                        + e.postings(chart.context()))
                .toList();

        for (LocalDate today : LOAD_DAYS) {
            assertThat(described.apply(today)).as("loaded on %s", today).isEqualTo(described.apply(today));
            assertThat(DemoLedger.entries(today, chart)).isEqualTo(DemoLedger.entries(today, chart));
        }
        // The salary is this year's level today: loaded a year later, the same month's is a year's raise lower.
        YearMonth lastMonth = YearMonth.from(TODAY).minusMonths(1);
        assertThat(salaryIn(DemoLedger.entries(TODAY.plusYears(1), chart), lastMonth))
                .isLessThan(salaryIn(DemoLedger.entries(TODAY, chart), lastMonth));
    }

    /**
     * D-690: for every whole month that has its whole month a year earlier in the ledger, the expenses in euros differ
     * from that month's by 1 % to 35 %, and so does the salary; on every load day of four years.
     */
    @Test
    void everyMonthDiffersFromTheSameMonthAYearEarlier() {
        SoftAssertions softly = new SoftAssertions();
        for (LocalDate today = FIRST; today.isBefore(AFTER_LAST); today = today.plusDays(1)) {
            List<EntryCommand> entries = DemoLedger.entries(today, chart);
            Map<YearMonth, BigDecimal> expenses = expensesInEuros(entries);
            YearMonth first = YearMonth.from(entries.getFirst().entryDate()).plusMonths(1);
            for (YearMonth month = first.plusMonths(12); month.isBefore(YearMonth.from(today));
                    month = month.plusMonths(1)) {
                BigDecimal now = expenses.get(month);
                BigDecimal before = expenses.get(month.minusMonths(12));
                BigDecimal change = now.subtract(before).abs().divide(before, 4, RoundingMode.HALF_UP);
                softly.assertThat(change).as("%s against a year earlier, loaded on %s: %s and %s", month, today, now,
                        before).isBetween(new BigDecimal("0.0100"), new BigDecimal("0.3500"));
                softly.assertThat(salaryIn(entries, month)).as("salary in %s, loaded on %s", month, today)
                        .isNotEqualByComparingTo(salaryIn(entries, month.minusMonths(12)));
            }
        }
        softly.assertAll();
    }

    @Test
    void everyEntryKeepsTheLedgersRules() {
        LedgerValidator validator = new LedgerValidator();
        SoftAssertions softly = new SoftAssertions();
        for (LocalDate today : LOAD_DAYS) {
            for (EntryCommand entry : DemoLedger.entries(today, chart)) {
                softly.assertThat(validator.violations(entry.draft(chart.context()), chart.references()))
                        .as("%s %s %s, loaded on %s", entry.entryDate(), entry.kind(), entry.memo(), today).isEmpty();
            }
        }
        softly.assertAll();
    }

    /**
     * No money account goes below zero at the end of any day, the card and the loan are never overpaid, and the
     * partner never owes the user less than nothing; on every load day of {@link #LOAD_DAYS}. The balances at the end
     * are plausible for the person the demo shows; on {@link #TODAY} these.
     */
    @Test
    void balancesStayPlausibleThroughout() {
        SoftAssertions softly = new SoftAssertions();
        for (LocalDate today : LOAD_DAYS) {
            Map<String, BigDecimal> balances = new TreeMap<>();
            List<EntryCommand> entries = DemoLedger.entries(today, chart);
            for (int i = 0; i < entries.size(); i++) {
                for (PostingLine posting : entries.get(i).postings(chart.context())) {
                    balances.merge(chart.code(posting.accountId()) + " " + posting.currency(), posting.amount(),
                            BigDecimal::add);
                }
                boolean endOfDay = i == entries.size() - 1
                        || !entries.get(i + 1).entryDate().equals(entries.get(i).entryDate());
                if (endOfDay) {
                    checkDay(softly, balances, entries.get(i).entryDate(), today);
                }
            }
            softly.assertThat(balances.get("CURRENT_ACCOUNT EUR")).as("current at the end, loaded on %s", today)
                    .isBetween(new BigDecimal("1500"), new BigDecimal("6000"));
            softly.assertThat(balances.get("SAVINGS_ACCOUNT EUR")).as("savings at the end, loaded on %s", today)
                    .isBetween(new BigDecimal("30000"), new BigDecimal("65000"));
            softly.assertThat(balances.get("CASH EUR")).as("cash at the end, loaded on %s", today)
                    .isLessThan(new BigDecimal("400"));
            softly.assertThat(balances.get("USD_ACCOUNT USD")).as("dollars at the end, loaded on %s", today)
                    .isLessThan(new BigDecimal("3500"));
            if (today.equals(TODAY)) {
                softly.assertThat(balances).containsAllEntriesOf(BALANCES_ON_TODAY);
            }
        }
        softly.assertAll();
    }

    /**
     * With the demo family's own payments and settlements on the current and the dollar account (H1, D-691), neither
     * goes below zero on any day either.
     */
    @Test
    void theFamilysPaymentsLeaveTheAccountsAboveZero() {
        SoftAssertions softly = new SoftAssertions();
        for (LocalDate today : LOAD_DAYS) {
            List<EntryCommand> entries = DemoLedger.entries(today, chart);
            DemoFamily.Plan family = DemoFamily.plan(entries.getFirst().entryDate(), today);
            TreeMap<LocalDate, Map<String, BigDecimal>> changes = new TreeMap<>();
            for (EntryCommand entry : entries) {
                for (PostingLine posting : entry.postings(chart.context())) {
                    changes.computeIfAbsent(entry.entryDate(), d -> new HashMap<>())
                            .merge(chart.code(posting.accountId()), posting.amount(), BigDecimal::add);
                }
            }
            for (DemoFamily.Record record : family.records()) {
                if (record.payer() == DemoFamily.Who.YOU) {
                    BigDecimal amount = record.type().equals("INCOME") ? record.amountValue()
                            : record.amountValue().negate();
                    changes.computeIfAbsent(record.date(), d -> new HashMap<>()).merge(record.account(), amount,
                            BigDecimal::add);
                }
            }
            for (DemoFamily.Settlement settlement : family.settlements()) {
                changes.computeIfAbsent(settlement.date(), d -> new HashMap<>()).merge(settlement.account(),
                        settlement.amountValue(), BigDecimal::add);
            }
            Map<String, BigDecimal> balances = new HashMap<>();
            changes.forEach((day, change) -> {
                change.forEach((code, amount) -> balances.merge(code, amount, BigDecimal::add));
                for (String code : List.of("CURRENT_ACCOUNT", "USD_ACCOUNT")) {
                    softly.assertThat(balances.getOrDefault(code, BigDecimal.ZERO))
                            .as("%s on %s, loaded on %s", code, day, today).isNotNegative();
                }
            });
        }
        softly.assertAll();
    }

    private static void checkDay(SoftAssertions softly, Map<String, BigDecimal> balances, LocalDate day,
            LocalDate today) {
        for (String asset : List.of("CURRENT_ACCOUNT EUR", "SAVINGS_ACCOUNT EUR", "CASH EUR", "USD_ACCOUNT USD",
                "LOANS_ASSET EUR")) {
            softly.assertThat(balances.getOrDefault(asset, BigDecimal.ZERO)).as("%s on %s, loaded on %s", asset, day,
                    today).isNotNegative();
        }
        // Liabilities are credits: the card is owed, the family budget owes the user.
        softly.assertThat(balances.getOrDefault("CREDIT_CARD EUR", BigDecimal.ZERO))
                .as("card on %s, loaded on %s", day, today).isNotPositive();
        softly.assertThat(balances.getOrDefault("FAMILY_DEBT EUR", BigDecimal.ZERO))
                .as("family budget on %s, loaded on %s", day, today).isNotNegative();
    }

    /** Each calendar month's expenses in euros: the categorized lines of EXPENSE categories, refunds lowering them. */
    private Map<YearMonth, BigDecimal> expensesInEuros(List<EntryCommand> entries) {
        Map<YearMonth, BigDecimal> expenses = new TreeMap<>();
        for (EntryCommand entry : entries) {
            for (PostingLine posting : entry.postings(chart.context())) {
                if (posting.categoryId() != null && posting.currency().equals("EUR")
                        && chart.categories().get(posting.categoryId()).type() == CategoryType.EXPENSE) {
                    expenses.merge(YearMonth.from(entry.entryDate()), posting.amount(), BigDecimal::add);
                }
            }
        }
        return expenses;
    }

    private static BigDecimal salaryIn(List<EntryCommand> entries, YearMonth month) {
        return entries.stream().filter(e -> e.memo().equals("Salary") && YearMonth.from(e.entryDate()).equals(month))
                .map(e -> ((IncomeCommand) e).amount()).findFirst().orElseThrow();
    }

    /** The entries by kind of the demo loaded on {@link #TODAY}. */
    private static final Map<EntryKind, Long> BY_KIND_ON_TODAY = Map.of(EntryKind.OPENING_BALANCE, 6L,
            EntryKind.INCOME, 61L, EntryKind.EXPENSE, 465L, EntryKind.SHARED_EXPENSE, 72L, EntryKind.TRANSFER, 144L,
            EntryKind.LOAN_GIVEN, 1L, EntryKind.LOAN_REPAID, 2L, EntryKind.CURRENCY_EXCHANGE, 8L, EntryKind.MANUAL, 1L);
    /** The balances at the end of the demo loaded on {@link #TODAY}, without the demo family. */
    private static final Map<String, BigDecimal> BALANCES_ON_TODAY = Map.of(
            "CURRENT_ACCOUNT EUR", new BigDecimal("4296.06"),
            "SAVINGS_ACCOUNT EUR", new BigDecimal("51876.65"),
            "CASH EUR", new BigDecimal("176.97"),
            "USD_ACCOUNT USD", new BigDecimal("1171.26"),
            "CREDIT_CARD EUR", new BigDecimal("-79.71"),
            "LOANS_ASSET EUR", new BigDecimal("50.00"),
            "FAMILY_DEBT EUR", new BigDecimal("40.09"));

    /** The starter ledger's accounts and categories and the demo's own, with made-up ids. */
    private record Chart(Map<String, Long> accountIds, Map<Long, AccountInfo> accounts, Map<String, Long> categoryIds,
            Map<Long, CategoryInfo> categories, Map<String, Long> counterpartyIds) implements DemoLedger.Ids {

        static Chart read() {
            JsonNode seed;
            try {
                seed = new ObjectMapper().readTree(new ClassPathResource("seed/starter-ledger.json").getInputStream());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            Map<String, Long> accountIds = new HashMap<>();
            Map<Long, AccountInfo> accounts = new HashMap<>();
            seed.get("accounts").forEach(a -> {
                long id = 100 + accountIds.size();
                accountIds.put(a.get("code").asText(), id);
                accounts.put(id, new AccountInfo(a.get("code").asText(), AccountType.valueOf(a.get("type").asText()),
                        a.path("requiresCounterparty").asBoolean(false)));
            });
            DemoLedger.ACCOUNTS.forEach(a -> {
                long id = 100 + accountIds.size();
                accountIds.put(a.code(), id);
                accounts.put(id, new AccountInfo(a.code(), a.type(), false));
            });
            Map<String, Long> categoryIds = new HashMap<>();
            Map<Long, CategoryInfo> categories = new HashMap<>();
            seed.get("categories").forEach(c -> {
                long id = 200 + categoryIds.size();
                categoryIds.put(c.get("code").asText(), id);
                categories.put(id, new CategoryInfo(c.get("code").asText(),
                        CategoryType.valueOf(c.get("type").asText())));
            });
            DemoLedger.CATEGORIES.forEach(c -> {
                long id = 200 + categoryIds.size();
                categoryIds.put(c.code(), id);
                categories.put(id, new CategoryInfo(c.code(), c.type()));
            });
            Map<String, Long> counterpartyIds = new HashMap<>();
            DemoLedger.COUNTERPARTIES.forEach(c -> counterpartyIds.put(c.name(), 300L + counterpartyIds.size()));
            return new Chart(accountIds, accounts, categoryIds, categories, counterpartyIds);
        }

        LedgerContext context() {
            Map<AccountRole, Long> roles = new EnumMap<>(AccountRole.class);
            for (AccountRole role : AccountRole.values()) {
                roles.put(role, accountIds.get(role.defaultCode()));
            }
            return new LedgerContext(roles, new BigDecimal("0.50"));
        }

        LedgerReferences references() {
            return new LedgerReferences(accounts, categories, Set.copyOf(counterpartyIds.values()));
        }

        String code(long accountId) {
            return accounts.get(accountId).code();
        }

        @Override
        public long account(String code) {
            return accountIds.get(code);
        }

        @Override
        public long category(String code) {
            return categoryIds.get(code);
        }

        @Override
        public long counterparty(String name) {
            return counterpartyIds.get(name);
        }
    }
}
