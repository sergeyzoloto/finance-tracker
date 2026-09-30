package com.example.financetracker.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
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
 * ledger's and the demo's own, with made-up ids.
 */
class DemoLedgerTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    private final Chart chart = Chart.read();

    @Test
    void hasAnEntryOfEveryKindTheAppSupports() {
        Map<EntryKind, Long> byKind = DemoLedger.entries(TODAY, chart).stream()
                .collect(Collectors.groupingBy(EntryCommand::kind, () -> new EnumMap<>(EntryKind.class),
                        Collectors.counting()));

        // Every kind a user makes an entry of; the family kinds are the family budget's to post (F4a).
        assertThat(byKind.keySet()).isEqualTo(EnumSet.copyOf(EnumSet.allOf(EntryKind.class).stream()
                .filter(EntryKind::isCommand).toList()));
        assertThat(byKind).isEqualTo(Map.of(EntryKind.OPENING_BALANCE, 5L, EntryKind.INCOME, 12L,
                EntryKind.EXPENSE, 80L, EntryKind.SHARED_EXPENSE, 12L, EntryKind.TRANSFER, 23L,
                EntryKind.LOAN_GIVEN, 1L, EntryKind.LOAN_REPAID, 2L, EntryKind.CURRENCY_EXCHANGE, 2L,
                EntryKind.MANUAL, 1L));
    }

    @Test
    void runsFromOpeningBalancesOnTheFirstOfAMonthAboutSixMonthsBackToTodayWhateverDayItIsLoadedOn() {
        for (LocalDate today = LocalDate.of(2026, 1, 1); today.isBefore(LocalDate.of(2028, 1, 1));
                today = today.plusDays(1)) {
            List<EntryCommand> entries = DemoLedger.entries(today, chart);
            LocalDate end = today;
            LocalDate start = entries.getFirst().entryDate();

            assertThat(start.getDayOfMonth()).isOne();
            assertThat(start).isAfter(today.minusMonths(7)).isBefore(today.minusMonths(5));
            assertThat(entries.subList(0, 5)).allMatch(e -> e.kind() == EntryKind.OPENING_BALANCE);
            assertThat(entries).filteredOn(e -> e.kind() == EntryKind.OPENING_BALANCE)
                    .allMatch(e -> e.entryDate().equals(start));
            // The first month has entries besides the opening balances, so the ledger's months are its entries'.
            assertThat(YearMonth.from(entries.get(5).entryDate())).isEqualTo(YearMonth.from(start));
            assertThat(entries).allMatch(e -> !e.entryDate().isAfter(end));
            assertThat(entries.getLast().entryDate()).isEqualTo(today);
            assertThat(entries).isSortedAccordingTo((a, b) -> a.entryDate().compareTo(b.entryDate()));
        }
    }

    /** Monthly entries land once in every month the ledger covers in full, even when loaded on the 31st. */
    @Test
    void everyMonthCoveredInFullHasEachMonthlyEntryOnce() {
        for (LocalDate today = LocalDate.of(2026, 1, 1); today.isBefore(LocalDate.of(2028, 1, 1));
                today = today.plusDays(1)) {
            Map<YearMonth, Map<String, Long>> memosByMonth = DemoLedger.entries(today, chart).stream()
                    .collect(Collectors.groupingBy(e -> YearMonth.from(e.entryDate()), TreeMap::new,
                            Collectors.groupingBy(EntryCommand::memo, Collectors.counting())));
            YearMonth first = YearMonth.from(DemoLedger.entries(today, chart).getFirst().entryDate());
            for (YearMonth month = first.plusMonths(1); month.isBefore(YearMonth.from(today));
                    month = month.plusMonths(1)) {
                assertThat(memosByMonth.get(month)).as("%s, loaded on %s", month, today)
                        .containsEntry("Salary", 1L)
                        .containsEntry("Rent", 1L)
                        .containsEntry("Credit card bill", 1L)
                        .containsEntry("Account fee", 1L)
                        .containsEntry("Weekly shop", 2L);
            }
            assertThat(memosByMonth.values()).as("loaded on %s", today)
                    .allMatch(memos -> memos.getOrDefault("Salary", 0L) <= 1);
        }
    }

    @Test
    void isTheSameLedgerOnEveryDayApartFromTheDates() {
        Function<LocalDate, List<String>> withoutDates = today -> DemoLedger.entries(today, chart).stream()
                .map(e -> e.kind() + " " + e.memo() + " " + e.payeeId() + " " + e.postings(chart.context()))
                .sorted()
                .toList();

        assertThat(withoutDates.apply(LocalDate.of(2027, 2, 28))).isEqualTo(withoutDates.apply(TODAY))
                .isEqualTo(withoutDates.apply(LocalDate.of(2028, 3, 31)));
        assertThat(DemoLedger.entries(TODAY, chart)).isEqualTo(DemoLedger.entries(TODAY, chart));
    }

    @Test
    void everyEntryKeepsTheLedgersRules() {
        LedgerValidator validator = new LedgerValidator();
        SoftAssertions softly = new SoftAssertions();
        for (EntryCommand entry : DemoLedger.entries(TODAY, chart)) {
            softly.assertThat(validator.violations(entry.draft(chart.context()), chart.references()))
                    .as("%s %s", entry.kind(), entry.memo()).isEmpty();
        }
        softly.assertAll();
    }

    /**
     * No money account goes below zero at the end of any day, the card and the loan are never overpaid, and the
     * partner never owes more than their share. The balances at the end are plausible for the person the demo shows.
     */
    @Test
    void balancesStayPlausibleThroughout() {
        Map<String, BigDecimal> balances = new TreeMap<>();
        List<EntryCommand> entries = DemoLedger.entries(TODAY, chart);
        SoftAssertions softly = new SoftAssertions();
        for (int i = 0; i < entries.size(); i++) {
            for (PostingLine posting : entries.get(i).postings(chart.context())) {
                balances.merge(chart.code(posting.accountId()) + " " + posting.currency(), posting.amount(),
                        BigDecimal::add);
            }
            boolean endOfDay = i == entries.size() - 1
                    || !entries.get(i + 1).entryDate().equals(entries.get(i).entryDate());
            if (endOfDay) {
                LocalDate day = entries.get(i).entryDate();
                for (String asset : List.of("CURRENT_ACCOUNT EUR", "SAVINGS_ACCOUNT EUR", "CASH EUR",
                        "USD_ACCOUNT USD", "LOANS_ASSET EUR")) {
                    softly.assertThat(balances.getOrDefault(asset, BigDecimal.ZERO)).as("%s on %s", asset, day)
                            .isNotNegative();
                }
                // Liabilities are credits: the card is owed, the family budget owes the user.
                softly.assertThat(balances.get("CREDIT_CARD EUR")).as("card on %s", day).isNotPositive();
                softly.assertThat(balances.getOrDefault("FAMILY_DEBT EUR", BigDecimal.ZERO))
                        .as("family budget on %s", day).isNotNegative();
            }
        }
        softly.assertAll();

        assertThat(balances).containsAllEntriesOf(Map.of(
                "CURRENT_ACCOUNT EUR", new BigDecimal("6054.12"),
                "SAVINGS_ACCOUNT EUR", new BigDecimal("9829.55"),
                "CASH EUR", new BigDecimal("253.25"),
                "USD_ACCOUNT USD", new BigDecimal("1247.00"),
                "CREDIT_CARD EUR", new BigDecimal("-94.69"),
                "LOANS_ASSET EUR", new BigDecimal("50.00"),
                "FAMILY_DEBT EUR", new BigDecimal("88.60")));
    }

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
