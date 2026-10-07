package com.example.financetracker.hygiene.fixtures;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * What {@code TestHygieneTests} must refuse: an answer turned into text, to be searched. It is excluded from the real
 * rules and imported alone by the tests of the rules.
 */
public final class RawAnswerSearch {

    private RawAnswerSearch() {
    }

    public static boolean serialisedTree(JsonNode answer) {
        return answer.toString().contains("51.50");
    }

    public static boolean prettyTree(JsonNode answer) {
        return answer.toPrettyString().contains("51.50");
    }

    public static boolean rawBody(MvcTestResult result) throws IOException {
        return result.getResponse().getContentAsString().contains("51.50");
    }

    public static boolean rawBytes(MockHttpServletResponse response) throws IOException {
        return response.getContentAsByteArray().length > 0;
    }

    public static boolean written(ObjectMapper json, JsonNode answer) throws IOException {
        return json.writeValueAsString(answer).contains("51.50");
    }
}
