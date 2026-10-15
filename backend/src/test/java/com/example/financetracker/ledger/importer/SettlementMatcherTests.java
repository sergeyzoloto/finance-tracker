package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.importer.SettlementMatcher.Candidate;
import com.example.financetracker.ledger.importer.SettlementMatcher.ConversionRow;
import com.example.financetracker.ledger.importer.SettlementMatcher.Leg;
import com.example.financetracker.ledger.importer.SettlementMatcher.Match;
import com.example.financetracker.ledger.importer.SettlementMatcher.Row;
import org.junit.jupiter.api.Test;

/** D-178's tiers and conversions, in plain Java. */
class SettlementMatcherTests {

    private static Row row(String id, String date, String amount, boolean pays) {
        return new Row(id, LocalDate.parse(date), "EUR", new BigDecimal(amount), pays);
    }

    private static Candidate settlement(long id, String date, String amount, boolean pays) {
        return new Candidate(id, LocalDate.parse(date), "EUR", new BigDecimal(amount), pays, null);
    }

    private static String describe(Match match) {
        return match.excelId() + "->" + match.settlements().stream().map(c -> String.valueOf(c.id())).toList() + " t"
                + match.tier();
    }

    @Test
    void theFirstTierIsTheSameDateAmountCurrencyAndDirection() {
        var result = SettlementMatcher.match(List.of(row("1", "2024-02-10", "40", false)), List.of(),
                List.of(settlement(7, "2024-02-10", "40", true), settlement(8, "2024-02-10", "40", false)));
        assertThat(result.matches()).extracting(SettlementMatcherTests::describe).containsExactly("1->[8] t1");
        assertThat(result.unmatched()).isEmpty();
    }

    @Test
    void theSecondTierIsOneRowAgainstSeveralOnItsDateThatAddUpToIt() {
        var result = SettlementMatcher.match(List.of(row("1", "2024-06-12", "40", false)), List.of(),
                List.of(settlement(7, "2024-06-12", "25", false), settlement(8, "2024-06-12", "15", false),
                        settlement(9, "2024-06-12", "3", false)));
        assertThat(result.matches()).extracting(SettlementMatcherTests::describe).containsExactly("1->[7, 8] t2");
    }

    @Test
    void theSecondTierTakesOnlyTheOneSetThatAddsUp() {
        // 10 + 30 and 15 + 25 both make 40: ambiguous, so nothing matches.
        var result = SettlementMatcher.match(List.of(row("1", "2024-06-12", "40", false)), List.of(),
                List.of(settlement(7, "2024-06-12", "10", false), settlement(8, "2024-06-12", "30", false),
                        settlement(9, "2024-06-12", "15", false), settlement(10, "2024-06-12", "25", false)));
        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatched()).extracting(Row::excelId).containsExactly("1");
    }

    @Test
    void theThirdTierIsTheSameAmountWithinSevenDaysWhenUnambiguous() {
        var result = SettlementMatcher.match(
                List.of(row("1", "2024-02-23", "33", true), row("2", "2024-03-02", "12", false),
                        row("3", "2024-03-20", "50", false)),
                List.of(),
                List.of(settlement(7, "2024-02-20", "33", true), settlement(8, "2024-02-28", "12", false),
                        settlement(9, "2024-03-10", "50", false)));
        // Row 3 is 10 days from its settlement: too far.
        assertThat(result.matches()).extracting(SettlementMatcherTests::describe).containsExactly("1->[7] t3",
                "2->[8] t3");
        assertThat(result.unmatched()).extracting(Row::excelId).containsExactly("3");
    }

    @Test
    void theThirdTierLeavesAnAmbiguousPairAlone() {
        // Two rows of 20 within 7 days of one settlement of 20: neither is the one.
        var result = SettlementMatcher.match(List.of(row("1", "2024-02-01", "20", true), row("2", "2024-02-03", "20",
                true)), List.of(), List.of(settlement(7, "2024-02-05", "20", true)));
        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatched()).hasSize(2);
    }

    @Test
    void aConversionMatchesByBothLegsWhicheverCurrencyItsSumIsWrittenIn() {
        ConversionRow his = new ConversionRow("110", LocalDate.parse("2022-06-05"), List.of(
                new Leg("USD", new BigDecimal("10"), true), new Leg("EUR", new BigDecimal("9"), false)));
        List<Candidate> legs = List.of(
                new Candidate(7, LocalDate.parse("2022-06-05"), "EUR", new BigDecimal("9"), false, "xls:3:10"),
                new Candidate(8, LocalDate.parse("2022-06-05"), "USD", new BigDecimal("10"), true, "xls:3:10"),
                new Candidate(9, LocalDate.parse("2022-06-05"), "USD", new BigDecimal("10"), true, null));
        var result = SettlementMatcher.match(List.of(), List.of(his), legs);
        assertThat(result.matches()).extracting(SettlementMatcherTests::describe).containsExactly("110->[7, 8] t0");
        assertThat(result.unmatchedConversions()).isEmpty();
    }

    @Test
    void aConversionWhoseLegsDifferStaysUnmatched() {
        ConversionRow his = new ConversionRow("110", LocalDate.parse("2022-06-05"), List.of(
                new Leg("USD", new BigDecimal("10"), true), new Leg("EUR", new BigDecimal("9.50"), false)));
        var result = SettlementMatcher.match(List.of(), List.of(his), List.of(
                new Candidate(7, LocalDate.parse("2022-06-05"), "EUR", new BigDecimal("9"), false, "xls:3:10"),
                new Candidate(8, LocalDate.parse("2022-06-05"), "USD", new BigDecimal("10"), true, "xls:3:10")));
        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedConversions()).extracting(ConversionRow::excelId).containsExactly("110");
    }
}
