package com.example.financetracker.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The demo's family budget as {@link DemoFamily} plans it (H1, F6c), without Spring or a database, for every day of two
 * years: the same records and settlements whatever the day, all within the family budget's dates. {@code
 * DemoDataApiTests} records it through the services.
 */
class DemoFamilyTests {

    @Test
    void theSamePlanWithinTheBudgetsDatesOnEveryDay() {
        DemoFamily.Plan first = null;
        for (LocalDate today = LocalDate.of(2026, 1, 1); today.isBefore(LocalDate.of(2028, 1, 1));
                today = today.plusDays(1)) {
            LocalDate start = today.minusMonths(DemoLedger.MONTHS).withDayOfMonth(1);
            DemoFamily.Plan plan = DemoFamily.plan(start, today);

            assertThat(plan.records()).hasSize(15);
            assertThat(plan.settlements()).hasSize(3);
            LocalDate day = today;
            assertThat(plan.records()).allSatisfy(r -> assertThat(r.date()).isBetween(start, day));
            assertThat(plan.settlements()).allSatisfy(s -> assertThat(s.date()).isBetween(start, day));
            assertThat(plan.records()).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));
            if (first == null) {
                first = plan;
            }
            assertThat(withoutDates(plan)).isEqualTo(withoutDates(first));
        }
    }

    @Test
    void sharesAnExpenseOfEveryKindAndAnIncome() {
        DemoFamily.Plan plan = DemoFamily.plan(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 2));

        assertThat(plan.records()).extracting(DemoFamily.Record::type).contains("EXPENSE", "INCOME");
        assertThat(plan.records()).extracting(DemoFamily.Record::payer)
                .contains(DemoFamily.Who.YOU, DemoFamily.Who.PARTNER);
        assertThat(plan.records()).filteredOn(r -> r.yourBasisPoints() != null).hasSize(1);
        // The one in dollars gives its amount in euros, so that loading the demo needs no rate.
        assertThat(plan.records()).filteredOn(r -> !r.currency().equals("EUR"))
                .singleElement().satisfies(r -> assertThat(r.baseAmountValue()).isEqualByComparingTo("205.10"));
        assertThat(plan.records()).filteredOn(r -> r.payer() == DemoFamily.Who.PARTNER)
                .allSatisfy(r -> assertThat(r.account()).isNull());
        assertThat(plan.records()).extracting(DemoFamily.Record::category)
                .allMatch(DemoFamily.CATEGORIES::contains);
        assertThat(plan.settlements().stream().map(DemoFamily.Settlement::amountValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("145.00");
    }

    private static List<String> withoutDates(DemoFamily.Plan plan) {
        return java.util.stream.Stream.concat(
                plan.records().stream().map(r -> r.type() + " " + r.category() + " " + r.amount() + " " + r.currency()
                        + " " + r.payer() + " " + r.account() + " " + r.comment() + " " + r.yourBasisPoints()),
                plan.settlements().stream().map(s -> "settlement " + s.amount() + " " + s.comment()))
                .sorted().toList();
    }
}
