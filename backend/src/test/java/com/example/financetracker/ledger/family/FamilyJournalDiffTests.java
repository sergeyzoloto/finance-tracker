package com.example.financetracker.ledger.family;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** The family journal's changes from two versions (ADR 0006, section 13): the same rows family_record_change held. */
class FamilyJournalDiffTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String EXPENSE = """
            {"amount": "30", "category": 7, "currency": "EUR", "date": "2026-09-10", "deleted": false,
             "externalRef": "X1", "payer": 1, "shares": [{"amount": "15", "basisPoints": 5000, "member": 1},
             {"amount": "15", "basisPoints": 5000, "member": 2}], "splitMethod": "EQUAL", "type": "EXPENSE"}""";

    private static JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static List<String> text(List<Map<String, Object>> changes) {
        return changes.stream().map(c -> c.get("field") + (c.containsKey("member") ? " of " + c.get("member") : "")
                + " " + c.get("old") + "→" + c.get("new")).toList();
    }

    @Test
    void aCreationNamesEveryFamilyFieldByIdAndTheCurrencyOnlyWhenNotTheMainOne() throws Exception {
        assertThat(text(FamilyJournalDiff.created(node(EXPENSE), "EUR", List.of(1L, 2L)))).containsExactly("date null→2026-09-10",
                "category null→7", "amount null→30.00", "payer null→1", "splitMethod null→EQUAL",
                "share of 1 null→15.00", "share of 2 null→15.00");
        assertThat(text(FamilyJournalDiff.created(node(EXPENSE.replace("\"EUR\"", "\"JPY\"")), "EUR", List.of(1L, 2L))))
                .contains("amount null→30", "currency null→JPY");
        assertThat(text(FamilyJournalDiff.created(node("""
                {"amount": "-5", "category": 7, "currency": "EUR", "date": "2026-09-10", "payer": 1, "shares": [],
                 "splitMethod": "EQUAL", "type": "EXPENSE", "comment": "back"}"""), "EUR", List.of(1L, 2L))))
                .containsExactly("date null→2026-09-10", "category null→7", "amount null→5.00", "refund null→true",
                        "payer null→1", "splitMethod null→EQUAL", "comment null→back");
        assertThat(text(FamilyJournalDiff.created(node("""
                {"amount": "20", "currency": "EUR", "date": "2026-09-10", "payer": 1, "payee": 2,
                 "type": "SETTLEMENT"}"""), "EUR", List.of(1L, 2L))))
                .containsExactly("date null→2026-09-10", "amount null→20.00", "payer null→1", "payee null→2");
    }

    @Test
    void aCreationListsTheSharesInJoinOrderNotByIdAndAnErasedCommentAsNullToNull() throws Exception {
        JsonNode three = node("""
                {"amount": "30", "category": 7, "currency": "EUR", "date": "2026-09-10", "payer": 5,
                 "shares": [{"amount": "10", "member": 3}, {"amount": "10", "member": 5}, {"amount": "10", "member": 9}],
                 "splitMethod": "EQUAL", "type": "EXPENSE"}""");
        List<Map<String, Object>> changes = FamilyJournalDiff.created(three, "EUR", List.of(9L, 3L));
        assertThat(text(changes)).contains("share of 9 null→10.00", "share of 3 null→10.00", "share of 5 null→10.00");
        assertThat(text(changes).stream().filter(c -> c.startsWith("share")).toList()).containsExactly(
                "share of 9 null→10.00", "share of 3 null→10.00", "share of 5 null→10.00");
        assertThat(text(FamilyJournalDiff.withErasedComment(changes))).last().isEqualTo("comment null→null");
    }

    @Test
    void anUpdateListsEachChangedFieldWithIds() throws Exception {
        JsonNode after = node(EXPENSE.replace("\"2026-09-10\"", "\"2026-09-11\"").replace("\"category\": 7",
                "\"category\": 8").replace("\"payer\": 1", "\"payer\": 2").replace("\"amount\": \"30\"",
                "\"amount\": \"40\", \"comment\": \"new\"").replace("\"amount\": \"15\", \"basisPoints\": 5000, "
                + "\"member\": 2", "\"amount\": \"25\", \"basisPoints\": 5000, \"member\": 2"));
        assertThat(text(FamilyJournalDiff.changed(node(EXPENSE), after))).containsExactly("date 2026-09-10→2026-09-11",
                "category 7→8", "amount 30.00→40.00", "payer 1→2", "share of 2 15.00→25.00", "comment null→new");
    }

    @Test
    void aChangeOfCurrencyWritesEachAmountInItsOwnCurrency() throws Exception {
        JsonNode after = node(EXPENSE.replace("\"EUR\"", "\"JPY\"").replace("\"30\"", "\"3000\"")
                .replace("\"15\"", "\"1500\""));
        assertThat(text(FamilyJournalDiff.changed(node(EXPENSE), after))).containsExactly("amount 30.00→3000",
                "currency EUR→JPY", "share of 1 15.00→1500", "share of 2 15.00→1500");
    }

    @Test
    void sharesAddedOrRemovedAndNothingForWhatTheJournalDoesNotShow() throws Exception {
        JsonNode one = node("""
                {"amount": "30", "category": 7, "currency": "EUR", "date": "2026-09-10", "deleted": false,
                 "externalRef": "X1", "payer": 1, "shares": [{"amount": "30", "member": 1}], "splitMethod": "EQUAL",
                 "type": "EXPENSE"}""");
        assertThat(text(FamilyJournalDiff.changed(node(EXPENSE), one))).containsExactly("share of 1 15.00→30.00",
                "share of 2 15.00→null");
        // Equal amounts written differently, another import reference, other percentages: no change, so no row.
        JsonNode same = node(EXPENSE.replace("\"30\"", "\"30.00\"").replace("X1", "X2").replace("5000", "4000"));
        assertThat(FamilyJournalDiff.changed(node(EXPENSE), same)).isEmpty();
        assertThat(FamilyJournalDiff.changed(node(EXPENSE), node(EXPENSE))).isEmpty();
    }
}
