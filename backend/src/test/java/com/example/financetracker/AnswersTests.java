package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.financetracker.hygiene.fixtures.RawAnswerSearch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** D-119's false positive, with the timestamp of the run that found it (Ubuntu 26.04's run #6) fixed in the text. */
class AnswersTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A record's answer to Bob: the euro amount of Alice's side is nowhere in it; her timestamp holds "51.50". */
    private static final String BOBS_RECORD = """
            {"id": 7, "amount": "56.00", "currency": "USD", "updatedAt": "2026-10-07T12:38:51.500757Z",
             "shares": [{"memberId": 1, "amount": "28.00"}, {"memberId": 2, "amount": "28.00"}]}""";

    @Test
    void aTimestampIsNotAnAmount() throws Exception {
        JsonNode answer = JSON.readTree(BOBS_RECORD);
        // The assertion of FamilyCurrencyPostingApiTests:206 as it was, on the text of the answer: found.
        assertThat(RawAnswerSearch.serialisedTree(answer)).isTrue();
        // As it is now: the amount is not a value of the answer, nor a number in it; the currency is not mentioned.
        assertThat(Answers.values(answer)).doesNotContain("51.50");
        assertThat(Answers.numbers(answer)).doesNotContain("51.50").doesNotContain("51").contains("56.00", "28.00", "7");
        Answers.assertNoneMention(answer, "EUR");
        assertThat(Answers.mentions(answer, "USD")).isTrue();
    }

    @Test
    void anAmountThatIsInTheAnswerIsFound() throws Exception {
        JsonNode answer = JSON.readTree("""
                {"updatedAt": "2026-10-07T12:38:51.500757Z", "yourPayment": {"amount": "51.50", "currency": "EUR"}}""");
        assertThat(Answers.values(answer)).contains("51.50");
        assertThat(Answers.numbers(answer)).contains("51.50");
        assertThat(Answers.mentions(answer, "EUR")).isTrue();
        assertThat(Answers.mentions(answer, "yourPayment")).isTrue();
        assertThatThrownBy(() -> Answers.assertNoneMention(answer, "EUR")).isInstanceOf(AssertionError.class)
                .hasMessageContaining("mentions \"EUR\"");
    }

    @Test
    void anIdIsAWholeNumberAndNeverTheDigitsOfADateOrAToken() throws Exception {
        JsonNode answer = JSON.readTree("""
                {"id": 7, "code": "FAMILY_DEBT_123", "note": "Account 12 not found", "month": "2026-10",
                 "createdAt": "2026-10-07T12:38:51.500757Z", "sub": "6b4f1c2e-0a55-4c1e-9d3f-3f2a5b8c9d01"}""");
        assertThat(Answers.numbers(answer)).containsExactlyInAnyOrder("7", "123", "12");
        // 555 once matched inside …006555Z; 38, 51, 10 and 2026 are in the dates, 55 and 01 in the UUID.
        assertThat(Answers.numbers(answer)).doesNotContain("555", "38", "51", "10", "2026", "55", "01");
    }

    @Test
    void aNeedleThatReadsAsANumberOrADateIsRefused() throws Exception {
        JsonNode answer = JSON.readTree(BOBS_RECORD);
        for (String needle : new String[] {"51.50", "555", "2026-10-07", "12:38", "-500.00", "T", ""}) {
            assertThatThrownBy(() -> Answers.mentions(answer, needle)).as(needle)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
