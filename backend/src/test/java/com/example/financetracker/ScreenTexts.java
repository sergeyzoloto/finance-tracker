package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

/**
 * D-384: no decision number in a text a screen can show. A decision number is "D-" and digits ("D-72", "(D-196,
 * D-219)"); stage names such as D3b or F8d aren't decisions.
 */
public final class ScreenTexts {

    /** "D-" followed by a digit, as a word of its own. */
    public static final Pattern DECISION = Pattern.compile("\\bD-\\d");

    private ScreenTexts() {
    }

    /** Every text leaf of an API answer, such as an import report's row errors, warnings and conflicts. */
    public static void assertNoDecisionNumber(JsonNode answer) {
        assertThat(Answers.values(answer)).as("texts of an answer a page shows (D-384)")
                .noneMatch(value -> DECISION.matcher(value).find());
    }
}
