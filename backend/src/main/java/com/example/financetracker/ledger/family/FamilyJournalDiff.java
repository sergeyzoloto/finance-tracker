package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The family journal's changes read from a family record's versions (ADR 0006, section 13, D-1005, D-1075): what
 * {@code family_record_change} stored for the same change, field by field, with members and categories by id, so that
 * the journal's answer is the same whichever source a row comes from. A version holds the family fields only, never
 * the payer's side (D-16, D-1022), so no change read here can name it.
 */
final class FamilyJournalDiff {

    private FamilyJournalDiff() {
    }

    /**
     * The changes of a record's creation, in the order the journal wrote them. The content holds the shares by member
     * id, not in the order the split wrote them; that order was the members' join order for an equal or percentage
     * split, so the shares are listed in {@code joinOrder} (members not in it last, by id).
     */
    static List<Map<String, Object>> created(JsonNode record, String mainCurrency, List<Long> joinOrder) {
        List<Map<String, Object>> changes = new ArrayList<>();
        String currency = text(record, "currency");
        boolean settlement = "SETTLEMENT".equals(text(record, "type"));
        changes.add(change("date", null, null, text(record, "date")));
        if (!settlement) {
            changes.add(change("category", null, null, id(record, "category")));
        }
        changes.add(change("amount", null, null, amount(record.get("amount"), currency)));
        if (!settlement && decimal(record.get("amount")).signum() < 0) {
            changes.add(change("refund", null, null, "true"));
        }
        if (!Objects.equals(currency, mainCurrency)) {
            changes.add(change("currency", null, null, currency));
        }
        changes.add(change("payer", null, null, id(record, "payer")));
        if (settlement) {
            changes.add(change("payee", null, null, id(record, "payee")));
        } else {
            changes.add(change("splitMethod", null, null, text(record, "splitMethod")));
            Map<Long, JsonNode> shares = shares(record);
            List<Long> order = new ArrayList<>(joinOrder.stream().filter(shares::containsKey).toList());
            shares.keySet().stream().filter(m -> !order.contains(m)).forEach(order::add);
            order.forEach(member -> changes.add(change("share", member, null, amount(shares.get(member), currency))));
        }
        if (text(record, "comment") != null) {
            changes.add(change("comment", null, null, text(record, "comment")));
        }
        return changes;
    }

    /**
     * What changed between two consecutive versions, in the order the journal wrote an update's changes; empty when no
     * field the journal shows changed (a split's percentages alone, the import's reference), which is then no row.
     */
    static List<Map<String, Object>> changed(JsonNode before, JsonNode after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        String oldCurrency = text(before, "currency");
        String currency = text(after, "currency");
        field(changes, "date", text(before, "date"), text(after, "date"));
        field(changes, "category", id(before, "category"), id(after, "category"));
        if (decimal(before.get("amount")).compareTo(decimal(after.get("amount"))) != 0) {
            changes.add(change("amount", null, amount(before.get("amount"), oldCurrency),
                    amount(after.get("amount"), currency)));
        }
        field(changes, "currency", oldCurrency, currency);
        field(changes, "payer", id(before, "payer"), id(after, "payer"));
        field(changes, "payee", id(before, "payee"), id(after, "payee"));
        field(changes, "splitMethod", text(before, "splitMethod"), text(after, "splitMethod"));
        Map<Long, JsonNode> oldShares = shares(before);
        Map<Long, JsonNode> newShares = shares(after);
        TreeMap<Long, Boolean> everyone = new TreeMap<>();
        oldShares.keySet().forEach(m -> everyone.put(m, true));
        newShares.keySet().forEach(m -> everyone.put(m, true));
        for (long member : everyone.keySet()) {
            JsonNode old = oldShares.get(member);
            JsonNode now = newShares.get(member);
            if (old == null || now == null || decimal(old).compareTo(decimal(now)) != 0) {
                changes.add(change("share", member, old == null ? null : amount(old, oldCurrency),
                        now == null ? null : amount(now, currency)));
            }
        }
        field(changes, "comment", text(before, "comment"), text(after, "comment"));
        return changes;
    }

    /**
     * Adds the comment's change that an erasure (D-20, D-1018) took from both versions, when the journal's own row of the
     * same change names the comment: erased, it reads null to null, as the journal's row does.
     */
    static List<Map<String, Object>> withErasedComment(List<Map<String, Object>> changes) {
        if (changes.stream().noneMatch(c -> "comment".equals(c.get("field")))) {
            changes.add(change("comment", null, null, null));
        }
        return changes;
    }

    private static void field(List<Map<String, Object>> changes, String field, Object old, Object now) {
        if (!Objects.equals(old, now)) {
            changes.add(change(field, null, old, now));
        }
    }

    /** A change as the journal stores it (topic H): the same keys as {@code family_record_change}'s rows. */
    private static Map<String, Object> change(String field, Long member, Object old, Object now) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        if (member != null) {
            change.put("member", member);
        }
        change.put("old", old);
        change.put("new", now);
        return change;
    }

    /** The shares' amounts by member id (the content holds them by member id, D-1001). */
    private static Map<Long, JsonNode> shares(JsonNode record) {
        Map<Long, JsonNode> shares = new TreeMap<>();
        JsonNode all = record.get("shares");
        if (all != null) {
            all.forEach(share -> shares.put(share.path("member").asLong(), share.get("amount")));
        }
        return shares;
    }

    /** An amount as the journal writes it: without its sign (a refund's is negative), in the currency's minor unit. */
    private static String amount(JsonNode value, String currency) {
        return decimal(value).abs().setScale(ShareSplit.minorUnit(currency), RoundingMode.UNNECESSARY).toPlainString();
    }

    private static BigDecimal decimal(JsonNode value) {
        return value == null || value.isNull() ? BigDecimal.ZERO : new BigDecimal(value.asText());
    }

    private static String text(JsonNode record, String key) {
        JsonNode value = record.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long id(JsonNode record, String key) {
        JsonNode value = record.get(key);
        return value == null || value.isNull() ? null : value.asLong();
    }
}
