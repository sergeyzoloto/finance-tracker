package com.example.financetracker.ledger.history;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.financetracker.ledger.domain.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The canonical form of a record's content (D-1001) and its SHA-256: keys sorted, no nulls (absent and null are the
 * same), amounts as normalised decimal strings ({@code 10.5} and {@code 10.50} are equal), dates in ISO, lists in the
 * order given. Equal content gives the same text and the same hash, which is what makes "no change, no version"
 * (D-995) one comparison.
 */
public final class Canonical {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Canonical() {
    }

    /** The canonical text of a content built from maps, lists, strings, numbers, booleans, dates and decimals. */
    public static String text(Map<String, Object> content) {
        return write(tree(content));
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The canonical text of content read back from the database (jsonb text), whatever its key order. */
    public static String canonicalize(String json) {
        try {
            return write(sorted(MAPPER.readTree(json)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(String canonicalText) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonicalText.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hash(Map<String, Object> content) {
        return sha256(text(content));
    }

    private static JsonNode tree(Object value) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = f.objectNode();
            new TreeMap<String, Object>(cast(map)).forEach((k, v) -> {
                JsonNode child = tree(v);
                if (!child.isNull()) {
                    node.set(k, child);
                }
            });
            return node;
        }
        if (value instanceof List<?> list) {
            ArrayNode node = f.arrayNode();
            list.forEach(v -> node.add(tree(v)));
            return node;
        }
        return switch (value) {
            case null -> f.nullNode();
            case BigDecimal d -> f.textNode(Money.normalize(d).toPlainString());
            case LocalDate d -> f.textNode(d.toString());
            case Boolean b -> f.booleanNode(b);
            case Integer i -> f.numberNode(i.intValue());
            case Long l -> f.numberNode(l.longValue());
            case Enum<?> e -> f.textNode(e.name());
            default -> f.textNode(value.toString());
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
                names.add(it.next());
            }
            names.stream().sorted().forEach(name -> {
                JsonNode child = sorted(node.get(name));
                if (!child.isNull()) {
                    out.set(name, child);
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(sorted(child)));
            return out;
        }
        return node;
    }
}
