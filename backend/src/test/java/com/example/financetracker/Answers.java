package com.example.financetracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * How a test looks for something in an API answer (D-119). An answer is read as parsed values, never as the text of
 * its serialization: a number, an id, an amount or a date searched for as a substring of that text can match inside
 * another value. 51.50 was found in {@code "updatedAt":"2026-10-07T12:38:51.500757Z"} on a runner at the right
 * microsecond (Ubuntu 26.04, run #6), and an id of 555 in {@code …006555Z} before it (F8c).
 * <ul>
 *   <li>{@link #values}: every leaf of the answer, whole, to compare exactly.</li>
 *   <li>{@link #numbers}: the whole numbers and decimals in the leaves, for an id, an amount or a count that may sit
 *       inside a string ({@code FAMILY_DEBT_12}); a date or a timestamp and an opaque token (a UUID, a hash) give none,
 *       so that their digits are never taken for an id.</li>
 *   <li>{@link #mentions} and {@link #assertNoneMention}: a word (a name, a note, a code, a field's name) in a string
 *       value or a field name. A needle that is built of the characters of a number, a date or a timestamp is
 *       refused: it belongs to {@link #values} or {@link #numbers}.</li>
 * </ul>
 * Turning an answer into text ({@code JsonNode.toString()}, the response's {@code getContentAsString()}) is allowed
 * only here and in the test base classes; {@code TestHygieneTests} checks it.
 */
public final class Answers {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The characters of a number, a date and a timestamp (with its zone and its exponent). */
    private static final Pattern NUMBERLIKE = Pattern.compile("[0-9.,:+\\-TZeE ]*");

    private static final Pattern DATE_OR_TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}(-\\d{2}(T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})?)?)?");

    /** A UUID, a hash or a token: random characters whose digits mean nothing. */
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9_-]{32,}");

    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])\\d+(?:\\.\\d+)?(?!\\d)");

    private Answers() {
    }

    /** The body of a response, parsed, whatever its status. */
    public static JsonNode of(MvcTestResult result) throws IOException {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /**
     * The body as it was written, to compare whole answers with each other or to show one in a message. Never search
     * it for a number, an id, an amount or a date: that is {@link #values} and {@link #numbers}.
     */
    public static String rawText(MvcTestResult result) throws IOException {
        return result.getResponse().getContentAsString();
    }

    /**
     * A CSV answer, parsed as the import reads it (D4a's transactions file): each line's values, the header first,
     * and what its bytes say of its format.
     *
     * @param byteOrderMark whether it starts with UTF-8's byte order mark
     * @param crlf whether it ends in CRLF and every line ends so: no LF without its CR, none without its LF
     */
    public record Csv(boolean byteOrderMark, boolean crlf, List<List<String>> lines) {
    }

    /** The body as a CSV file separated by semicolons, parsed: never searched as text. */
    public static Csv csv(MvcTestResult result) throws IOException {
        byte[] bytes = result.getResponse().getContentAsByteArray();
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        boolean bom = text.startsWith("\uFEFF");
        String body = bom ? text.substring(1) : text;
        // Outside quotes every LF has its CR before it and every CR its LF after it.
        boolean crlf = body.endsWith("\r\n");
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' && (i == 0 || body.charAt(i - 1) != '\r')
                    || c == '\r' && (i + 1 == body.length() || body.charAt(i + 1) != '\n'))) {
                crlf = false;
            }
        }
        List<List<String>> lines = new ArrayList<>();
        try (org.apache.commons.csv.CSVParser parser = org.apache.commons.csv.CSVFormat.RFC4180.builder()
                .setDelimiter(';').get().parse(new java.io.StringReader(body))) {
            parser.forEach(record -> lines.add(record.toList()));
        }
        return new Csv(bom, crlf, lines);
    }

    /** The JSON text, parsed: for comparing a whole answer with {@code isEqualTo}, never as text. */
    public static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every leaf of the answer, as its text: strings whole, numbers and booleans as written. */
    public static List<String> values(JsonNode answer) {
        List<String> values = new ArrayList<>();
        walk(answer, null, (name, leaf) -> values.add(leaf.asText()), name -> { });
        return values;
    }

    /** Every field name in the answer, at any depth. */
    public static Set<String> names(JsonNode answer) {
        Set<String> names = new LinkedHashSet<>();
        walk(answer, null, (name, leaf) -> { }, names::add);
        return names;
    }

    /**
     * The numbers in the answer, each as the whole token it is: a number leaf, or the digits (with their decimals) of
     * a string such as "56.00", "FAMILY_DEBT_12" or "Account 12 not found". 12 is not found in "123", and 51.50 not
     * in "51.500757". Dates, timestamps, UUIDs and hashes contribute nothing.
     */
    public static List<String> numbers(JsonNode answer) {
        List<String> numbers = new ArrayList<>();
        walk(answer, null, (name, leaf) -> {
            String text = leaf.asText();
            if (leaf.isNumber()) {
                numbers.add(text);
            } else if (!DATE_OR_TIMESTAMP.matcher(text).matches() && !OPAQUE.matcher(text).matches()) {
                Matcher found = NUMBER.matcher(text);
                while (found.find()) {
                    numbers.add(found.group());
                }
            }
        }, name -> { });
        return numbers;
    }

    /** Whether a string value or a field name of the answer contains the word. */
    public static boolean mentions(JsonNode answer, String word) {
        requireAWord(word);
        boolean[] found = {false};
        walk(answer, null, (name, leaf) -> found[0] |= leaf.isTextual() && leaf.asText().contains(word),
                name -> found[0] |= name.contains(word));
        return found[0];
    }

    /** Whether any of the answers mentions the word. */
    public static boolean mentions(java.util.Collection<JsonNode> answers, String word) {
        return answers.stream().anyMatch(answer -> mentions(answer, word));
    }

    /** Fails, naming the answer, if it mentions any of the words. */
    public static void assertNoneMention(String description, JsonNode answer, String... words) {
        for (String word : words) {
            if (mentions(answer, word)) {
                throw new AssertionError("%s: the answer mentions \"%s\": %s".formatted(description, word, answer));
            }
        }
    }

    public static void assertNoneMention(JsonNode answer, String... words) {
        assertNoneMention("An answer", answer, words);
    }

    private static void requireAWord(String word) {
        if (word.isEmpty() || NUMBERLIKE.matcher(word).matches()) {
            throw new IllegalArgumentException("\"%s\" is a number, an id, an amount or a date: compare it with "
                    .formatted(word) + "Answers.values or Answers.numbers, never as a substring (D-119)");
        }
    }

    private static void walk(JsonNode node, String name, java.util.function.BiConsumer<String, JsonNode> leaves,
            java.util.function.Consumer<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                names.accept(field.getKey());
                walk(field.getValue(), field.getKey(), leaves, names);
            });
        } else if (node.isArray()) {
            node.forEach(element -> walk(element, name, leaves, names));
        } else if (!node.isNull() && !node.isMissingNode()) {
            leaves.accept(name, node);
        }
    }
}
