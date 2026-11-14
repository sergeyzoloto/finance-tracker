package com.example.financetracker.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * The demo's family budget as {@link DemoFamily} plans it (H1, F6c; D-691), without Spring or a database, for every
 * day of four years: within the family budget's dates, over the demo's 36 months, the same plan for the same day, and
 * what Sam owes small on every day. {@code DemoDataApiTests} records it through the services.
 */
class DemoFamilyTests {

    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
    private static final LocalDate AFTER_LAST = LocalDate.of(2030, 1, 1);

    @Test
    void coversTheDemosMonthsWithinTheBudgetsDatesOnEveryDay() {
        for (LocalDate today = FIRST; today.isBefore(AFTER_LAST); today = today.plusDays(1)) {
            LocalDate start = DemoLedger.firstDay(today);
            DemoFamily.Plan plan = DemoFamily.plan(start, today);

            LocalDate day = today;
            assertThat(plan.records()).allSatisfy(r -> assertThat(r.date()).isBetween(start, day));
            assertThat(plan.settlements()).allSatisfy(s -> assertThat(s.date()).isBetween(start, day));
            assertThat(plan.records()).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));
            assertThat(plan.settlements()).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));
            // The monthly shop and water bill in every month from the demo's second to the last whole one.
            Map<YearMonth, Long> shops = plan.records().stream().filter(r -> r.category().equals("GROCERIES")
                    && r.yourBasisPoints() == null).collect(Collectors.groupingBy(r -> YearMonth.from(r.date()),
                            Collectors.counting()));
            Map<YearMonth, Long> water = plan.records().stream().filter(r -> r.category().equals("UTILITIES"))
                    .collect(Collectors.groupingBy(r -> YearMonth.from(r.date()), Collectors.counting()));
            for (YearMonth month = YearMonth.from(start).plusMonths(1); month.isBefore(YearMonth.from(today));
                    month = month.plusMonths(1)) {
                assertThat(shops.get(month)).as("shops in %s, loaded on %s", month, today).isOne();
                assertThat(water.get(month)).as("water in %s, loaded on %s", month, today).isOne();
            }
            assertThat(plan.records()).as("loaded on %s", today).hasSizeBetween(76, 80);
            assertThat(plan.settlements()).as("loaded on %s", today).hasSizeBetween(13, 16);
            // Once a year where a person's would recur: the weekend away and the party; the bike once.
            assertThat(plan.records()).filteredOn(r -> r.currency().equals("USD")).as("loaded on %s", today)
                    .hasSizeBetween(2, 4);
            assertThat(plan.records()).filteredOn(r -> r.yourBasisPoints() != null).as("loaded on %s", today)
                    .hasSizeBetween(2, 4);
            assertThat(plan.records()).filteredOn(r -> r.type().equals("INCOME")).hasSize(1);
        }
    }

    @Test
    void isTheSamePlanForTheSameDay() {
        for (LocalDate today : List.of(LocalDate.of(2026, 11, 13), LocalDate.of(2028, 2, 29),
                LocalDate.of(2027, 12, 31))) {
            LocalDate start = DemoLedger.firstDay(today);
            assertThat(DemoFamily.plan(start, today)).isEqualTo(DemoFamily.plan(start, today));
        }
    }

    /** D-691: Sam pays back often enough that what Sam owes stays below a few hundred, in euros and in dollars. */
    @Test
    void whatSamOwesStaysSmallOnEveryDay() {
        SoftAssertions softly = new SoftAssertions();
        for (LocalDate today = FIRST; today.isBefore(AFTER_LAST); today = today.plusDays(7)) {
            LocalDate start = DemoLedger.firstDay(today);
            DemoFamily.Plan plan = DemoFamily.plan(start, today);
            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
                for (String currency : List.of("EUR", "USD")) {
                    softly.assertThat(DemoFamily.owed(plan.records(), plan.settlements(), day, currency))
                            .as("%s on %s, loaded on %s", currency, day, today)
                            .isBetween(new BigDecimal("-150"), new BigDecimal("300"));
                }
            }
        }
        softly.assertAll();
    }

    @Test
    void sharesAnExpenseOfEveryKindAndAnIncome() {
        DemoFamily.Plan plan = DemoFamily.plan(LocalDate.of(2023, 11, 1), LocalDate.of(2026, 11, 13));

        assertThat(plan.records()).extracting(DemoFamily.Record::type).contains("EXPENSE", "INCOME");
        assertThat(plan.records()).extracting(DemoFamily.Record::payer)
                .contains(DemoFamily.Who.YOU, DemoFamily.Who.PARTNER);
        assertThat(plan.records()).filteredOn(r -> r.yourBasisPoints() != null).hasSize(3)
                .allSatisfy(r -> assertThat(r.yourBasisPoints()).isEqualTo(6000));
        // The weekends stay in dollars (D-45), paid from the dollar account: no rate, no amount in euros; one a year.
        assertThat(plan.records()).filteredOn(r -> !r.currency().equals("EUR")).hasSize(3).allSatisfy(r -> {
            assertThat(r.currency()).isEqualTo("USD");
            assertThat(r.account()).isEqualTo("USD_ACCOUNT");
            assertThat(r.date().getMonthValue()).isEqualTo(8);
        });
        assertThat(plan.records()).filteredOn(r -> !r.currency().equals("EUR")).extracting(DemoFamily.Record::comment)
                .doesNotHaveDuplicates();
        // Sam pays back half of each weekend in dollars.
        assertThat(plan.settlements()).filteredOn(s -> s.currency().equals("USD")).hasSize(3)
                .allSatisfy(s -> assertThat(s.account()).isEqualTo("USD_ACCOUNT"));
        assertThat(plan.records()).filteredOn(r -> r.payer() == DemoFamily.Who.PARTNER)
                .allSatisfy(r -> assertThat(r.account()).isNull());
        assertThat(plan.records()).extracting(DemoFamily.Record::category)
                .allMatch(DemoFamily.CATEGORIES::contains);
        // Every amount a multiple of 0.10, so an equal split has no remainder.
        assertThat(plan.records()).allSatisfy(r -> assertThat(r.amountValue().movePointRight(1).stripTrailingZeros()
                .scale()).isNotPositive());
    }
}
