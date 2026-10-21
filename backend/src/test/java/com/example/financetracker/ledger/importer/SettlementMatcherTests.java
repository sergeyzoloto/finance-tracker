package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

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

    private static Row row(String id, String date, String amount, boolean pays, String side) {
        return new Row(id, LocalDate.parse(date), "EUR", new BigDecimal(amount), pays, side);
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

    /** D-198: several rows of his on one date that add up to one settlement of the owner's, the only such set. */
    @Test
    void severalRowsOfHisMatchOneSettlementOnTheirDate() {
        var result = SettlementMatcher.match(
                List.of(row("1", "2024-06-12", "25", false), row("2", "2024-06-12", "15", false),
                        row("3", "2024-06-12", "7", false)),
                List.of(), List.of(settlement(7, "2024-06-12", "40", false)));
        assertThat(result.groups()).singleElement().satisfies(g -> {
            assertThat(g.rows()).extracting(Row::excelId).containsExactly("1", "2");
            assertThat(g.settlement().id()).isEqualTo(7);
        });
        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatched()).extracting(Row::excelId).containsExactly("3");
    }

    @Test
    void anAmbiguousSetOfHisRowsMatchesNothing() {
        // 10 + 30 and 15 + 25 both make 40.
        var result = SettlementMatcher.match(
                List.of(row("1", "2024-06-12", "10", false), row("2", "2024-06-12", "30", false),
                        row("3", "2024-06-12", "15", false), row("4", "2024-06-12", "25", false)),
                List.of(), List.of(settlement(7, "2024-06-12", "40", false)));
        assertThat(result.groups()).isEmpty();
        assertThat(result.unmatched()).hasSize(4);
    }

    @Test
    void rowsThatOnlyPartlyAddUpOrLieOnAnotherDateMatchNothing() {
        // 25 + 14 isn't 40; and 25 + 15 a day apart isn't one date (no wider window, D-198).
        var partial = SettlementMatcher.match(List.of(row("1", "2024-06-12", "25", false), row("2", "2024-06-12", "14",
                false)), List.of(), List.of(settlement(7, "2024-06-12", "40", false)));
        assertThat(partial.groups()).isEmpty();
        var apart = SettlementMatcher.match(List.of(row("1", "2024-06-12", "25", false), row("2", "2024-06-13", "15",
                false)), List.of(), List.of(settlement(7, "2024-06-12", "40", false)));
        assertThat(apart.groups()).isEmpty();
        // Nor rows of the other direction.
        var other = SettlementMatcher.match(List.of(row("1", "2024-06-12", "25", false), row("2", "2024-06-12", "15",
                true)), List.of(), List.of(settlement(7, "2024-06-12", "40", false)));
        assertThat(other.groups()).isEmpty();
    }

    /** Every order of a list, for results that mustn't depend on it. */
    private static <T> List<List<T>> orders(List<T> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<T>> all = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T first = rest.remove(i);
            for (List<T> order : orders(rest)) {
                List<T> one = new ArrayList<>(List.of(first));
                one.addAll(order);
                all.add(one);
            }
        }
        return all;
    }

    /**
     * D-202, D3c-accept's date: the owner's 33 and 5; his 28 and 5 on his deposit, 5 on his card. The first tier alone
     * would give the 5 the first of his 5s by ID and leave 28 + 5 across two accounts; the date as a whole has one way
     * that covers all three rows, whatever their order and IDs.
     */
    @Test
    void aTiedDateIsResolvedAsAWhole() {
        for (boolean cardFirst : List.of(false, true)) {
            Row deposit28 = row("8690", "2025-01-06", "28", false, "ING_DEPOSIT");
            Row deposit5 = row(cardFirst ? "8692" : "8691", "2025-01-06", "5", false, "ING_DEPOSIT");
            Row card5 = row(cardFirst ? "8691" : "8692", "2025-01-06", "5", false, "ING_DEBIT_CARD");
            Candidate owner33 = new Candidate(15, LocalDate.parse("2025-01-06"), "EUR", new BigDecimal("33"), false, null,
                    "5685");
            Candidate owner5 = new Candidate(16, LocalDate.parse("2025-01-06"), "EUR", new BigDecimal("5"), false, null,
                    "7066");
            for (List<Row> rows : orders(List.of(deposit28, deposit5, card5))) {
                for (List<Candidate> candidates : orders(List.of(owner33, owner5))) {
                    var result = SettlementMatcher.match(rows, List.of(), candidates);
                    assertThat(result.groups()).singleElement().satisfies(g -> {
                        assertThat(g.rows()).containsExactlyInAnyOrder(deposit28, deposit5);
                        assertThat(g.settlement()).isEqualTo(owner33);
                    });
                    assertThat(result.matches()).singleElement().satisfies(m -> {
                        assertThat(m.excelId()).isEqualTo(card5.excelId());
                        assertThat(m.settlements()).containsExactly(owner5);
                        assertThat(m.tier()).isEqualTo(1);
                    });
                    assertThat(result.byDate()).containsExactlyInAnyOrder("8690", "8691", "8692");
                    assertThat(result.unmatched()).isEmpty();
                    assertThat(result.ambiguous()).isEmpty();
                }
            }
        }
    }

    /** D-202: two of his 5s on two accounts and one settlement of 5: either could be its side. */
    @Test
    void aDateTakenInTwoWaysIsAmbiguous() {
        Row deposit = row("1", "2025-01-06", "5", false, "ING_DEPOSIT");
        Row card = row("2", "2025-01-06", "5", false, "ING_DEBIT_CARD");
        Row other = row("3", "2025-01-07", "9", false, "ING_DEPOSIT");
        Candidate five = settlement(7, "2025-01-06", "5", false);
        var result = SettlementMatcher.match(List.of(deposit, card, other), List.of(),
                List.of(five, settlement(8, "2025-01-07", "9", false)));
        assertThat(result.ambiguous()).singleElement().satisfies(a -> {
            assertThat(a.rows()).containsExactly(deposit, card);
            assertThat(a.settlements()).containsExactly(five);
        });
        // Neither goes on to the third tier, nor counts as without a counterpart; the next day is as before.
        assertThat(result.unmatched()).isEmpty();
        assertThat(result.matches()).extracting(SettlementMatcherTests::describe).containsExactly("3->[8] t1");
        assertThat(result.byDate()).isEmpty();
    }

    /** D-202: rows of one amount on one side, and settlements of one amount, are interchangeable: not ambiguous. */
    @Test
    void interchangeableRowsAreNotAmbiguous() {
        var result = SettlementMatcher.match(
                List.of(row("1", "2025-03-03", "100", true, "CASH"), row("2", "2025-03-03", "100", true, "CASH")),
                List.of(), List.of(settlement(7, "2025-03-03", "100", true), settlement(8, "2025-03-03", "100", true)));
        assertThat(result.ambiguous()).isEmpty();
        assertThat(result.matches()).extracting(Match::tier).containsExactly(1, 1);
        assertThat(result.matches()).flatExtracting(Match::settlements).extracting(Candidate::id)
                .containsExactlyInAnyOrder(7L, 8L);
        assertThat(result.byDate()).containsExactlyInAnyOrder("1", "2");
    }

    /** D-202 leaves a date without a tie to the tiers: D-198's only set matches even across two accounts. */
    @Test
    void aDateWithoutATieIsAsBefore() {
        var result = SettlementMatcher.match(
                List.of(row("1", "2024-06-12", "25", false, "CASH"), row("2", "2024-06-12", "15", false, "CARD"),
                        row("3", "2024-06-12", "7", false, "CASH")),
                List.of(), List.of(settlement(7, "2024-06-12", "40", false)));
        assertThat(result.groups()).singleElement()
                .satisfies(g -> assertThat(g.rows()).extracting(Row::excelId).containsExactly("1", "2"));
        assertThat(result.byDate()).isEmpty();
        assertThat(result.ambiguous()).isEmpty();
        assertThat(result.unmatched()).extracting(Row::excelId).containsExactly("3");
    }

    /** D-202's bound: a tied date with more than 12 of his rows is ambiguous as a whole; 12 are searched. */
    @Test
    void aTiedDateBeyondTheBoundIsAmbiguous() {
        List<Row> thirteen = IntStream.rangeClosed(1, SettlementMatcher.MOST_ON_A_DATE + 1)
                .mapToObj(i -> row(String.valueOf(i), "2025-05-05", "5", false, i % 2 == 0 ? "CASH" : "CARD")).toList();
        var beyond = SettlementMatcher.match(thirteen, List.of(), List.of(settlement(7, "2025-05-05", "5", false)));
        assertThat(beyond.ambiguous()).singleElement()
                .satisfies(a -> assertThat(a.rows()).hasSize(SettlementMatcher.MOST_ON_A_DATE + 1));
        assertThat(beyond.matches()).isEmpty();

        // Twelve rows on one side against twelve settlements of their amounts (1, 10 and 100, of which no set adds up to
        // another): searched, and all matched.
        List<String> amounts = List.of("1", "10", "100");
        List<Row> twelve = new ArrayList<>(IntStream.rangeClosed(1, SettlementMatcher.MOST_ON_A_DATE)
                .mapToObj(i -> row(String.valueOf(i), "2025-05-05", amounts.get(i % 3), false, "CASH")).toList());
        Collections.reverse(twelve);
        List<Candidate> settlements = IntStream.rangeClosed(1, SettlementMatcher.MOST_ON_A_DATE)
                .mapToObj(i -> settlement(100 + i, "2025-05-05", amounts.get(i % 3), false)).toList();
        var within = SettlementMatcher.match(twelve, List.of(), settlements);
        assertThat(within.ambiguous()).isEmpty();
        assertThat(within.byDate()).hasSize(SettlementMatcher.MOST_ON_A_DATE);
        assertThat(within.matches()).hasSize(SettlementMatcher.MOST_ON_A_DATE);
    }
}
