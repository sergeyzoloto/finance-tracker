package com.example.financetracker.ledger.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.access.LedgerScope;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A record's versions as a page, newest first (ADR 0006, section 13, D-1086), from the ledger of the caller's scope
 * only: a record of another ledger has no version there and answers 404.
 */
@Service
public class RecordHistory {

    /** A page of a record's versions; {@code total} counts them all. */
    public record HistoryPage(List<VersionView> content, int page, int size, long total, int totalPages) {
    }

    /**
     * One version.
     *
     * @param op BASELINE, CREATE, UPDATE, DELETE, ARCHIVE or RESTORE
     * @param cause why it was written, such as EDIT or IMPORT
     * @param changes what differs from the version before it
     */
    public record VersionView(int seq, long revision, String op, Instant at, String cause, Actor actor,
            List<?> changes) {
    }

    /**
     * Who wrote a version.
     *
     * @param kind USER, SYSTEM or IMPORT
     * @param memberId the member, for a user; null otherwise
     * @param name the member's name as it is now, "Former member" for one who left; null for the system or the import
     */
    public record Actor(String kind, Long memberId, String name) {
    }

    /** A field of a personal record's content that differs: the values as the content holds them, ids by id. */
    public record FieldChange(String field, @JsonProperty("old") JsonNode oldValue,
            @JsonProperty("new") JsonNode newValue) {
    }

    /**
     * A version as stored, with the content of the version before it.
     *
     * @param before null for the record's first version
     * @param erasedComment whether the family journal's own row of the same change names a comment (D-20, D-1018)
     */
    public record VersionRow(String op, Long actorMember, JsonNode before, JsonNode after, boolean erasedComment,
            String currency) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    RecordHistory(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Whether the record has a version in the scope's ledger. */
    public boolean exists(LedgerScope scope, RecordType type, long recordId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT FROM record_version
                               WHERE ledger_id = :ledgerId AND record_type = :type AND record_id = :recordId)""")
                .param("ledgerId", scope.ledgerId()).param("type", type.name()).param("recordId", recordId)
                .query(Boolean.class).single();
    }

    /** A personal record's versions, each with the fields that differ from the version before it. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HistoryPage personal(LedgerScope scope, RecordType type, long recordId, int page, int size) {
        return page(scope, type, recordId, page, size, row -> fieldChanges(row.before(), row.after()));
    }

    /**
     * The record's versions, stable newest first (by time, then id); {@code changes} makes each version's changes.
     *
     * @throws NotFoundException if the record has no version in the scope's ledger
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HistoryPage page(LedgerScope scope, RecordType type, long recordId, int page, int size,
            Function<VersionRow, List<?>> changes) {
        long total = jdbc.sql("""
                SELECT count(*) FROM record_version
                WHERE ledger_id = :ledgerId AND record_type = :type AND record_id = :recordId""")
                .param("ledgerId", scope.ledgerId()).param("type", type.name()).param("recordId", recordId)
                .query(Long.class).single();
        if (total == 0) {
            throw new NotFoundException(switch (type) {
                case ENTRY -> "Entry";
                case ACCOUNT -> "Account";
                case CATEGORY, FAMILY_CATEGORY -> "Category";
                case COUNTERPARTY -> "Counterparty";
                case FAMILY_RECORD -> "Family record";
            } + " " + recordId + " not found");
        }
        List<VersionView> content = jdbc.sql("""
                SELECT v.id, v.seq, v.revision, v.op, v.at, v.cause, v.actor_kind, v.actor_member,
                       CASE WHEN v.actor_kind <> 'USER' THEN NULL
                            WHEN m.id IS NULL OR m.status = 'FORMER' THEN 'Former member'
                            ELSE m.display_name END AS actor_name,
                       p.content::text AS before, v.content::text AS after, v.content ->> 'currency' AS currency,
                       EXISTS (SELECT FROM family_record_change c
                               WHERE c.ledger_id = v.ledger_id AND c.record_id = v.record_id AND c.changed_at = v.at
                                 AND c.action = v.op AND c.changes @> '[{"field": "comment"}]') AS erased_comment
                FROM record_version v
                LEFT JOIN record_version p ON p.ledger_id = v.ledger_id AND p.record_type = v.record_type
                     AND p.record_id = v.record_id AND p.seq = v.seq - 1
                LEFT JOIN ledger_member m ON m.id = v.actor_member AND m.ledger_id = v.ledger_id
                WHERE v.ledger_id = :ledgerId AND v.record_type = :type AND v.record_id = :recordId
                ORDER BY v.at DESC, v.id DESC LIMIT :limit OFFSET :offset""")
                .param("ledgerId", scope.ledgerId()).param("type", type.name()).param("recordId", recordId)
                .param("limit", size).param("offset", (long) page * size)
                .query((row, n) -> {
                    String before = row.getString("before");
                    Long member = row.getObject("actor_member", Long.class);
                    VersionRow version = new VersionRow(row.getString("op"), member,
                            before == null ? null : tree(before), tree(row.getString("after")),
                            row.getBoolean("erased_comment"), row.getString("currency"));
                    String kind = row.getString("actor_kind");
                    return new VersionView(row.getInt("seq"), row.getLong("revision"), version.op(),
                            row.getTimestamp("at").toInstant(), row.getString("cause"),
                            new Actor(kind, "USER".equals(kind) ? member : null, row.getString("actor_name")),
                            changes.apply(version));
                }).list();
        return new HistoryPage(content, page, size, total, Math.toIntExact((total + size - 1) / size));
    }

    /**
     * The top-level keys of the canonical content whose values differ, in key order, with the old and the new value
     * (null where the key is missing); for the first version, every key with a null old value.
     */
    public static List<FieldChange> fieldChanges(JsonNode before, JsonNode after) {
        TreeSet<String> keys = new TreeSet<>();
        for (JsonNode node : new JsonNode[] {before, after}) {
            if (node != null) {
                for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
                    keys.add(names.next());
                }
            }
        }
        List<FieldChange> changes = new ArrayList<>();
        for (String key : keys) {
            JsonNode old = before == null ? null : before.get(key);
            JsonNode now = after.get(key);
            if (!Objects.equals(old, now)) {
                changes.add(new FieldChange(key, old, now));
            }
        }
        return changes;
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read a version", e);
        }
    }
}
