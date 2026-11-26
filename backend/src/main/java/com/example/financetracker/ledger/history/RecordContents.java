package com.example.financetracker.ledger.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads a live record into its content (ADR 0006, 5.2, D-1001): ids, never names; everything the user can see or
 * change; none of the bookkeeping (versions, timestamps, the import's stamps, posting ids); a family record only with
 * its family fields, never the payer's side (D-16). The shapes are version 1 ({@link HistoryService#CONTENT_VERSION}).
 */
@Component
public class RecordContents {

    private final JdbcClient jdbc;

    RecordContents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One posting of an entry's content. */
    public record Line(long accountId, String currency, BigDecimal amount, Long categoryId, Long counterpartyId) {
    }

    /** A personal entry's content; the same builder for a live entry and for one about to be saved. */
    public static Map<String, Object> entry(LocalDate date, String kind, Long payeeId, String memo, String externalRef,
            List<Line> lines) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("date", date);
        content.put("kind", kind);
        content.put("payee", payeeId);
        content.put("memo", memo);
        content.put("externalRef", externalRef);
        List<Object> postings = new ArrayList<>();
        for (Line line : lines) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("account", line.accountId());
            p.put("currency", line.currency());
            p.put("amount", line.amount());
            p.put("category", line.categoryId());
            p.put("counterparty", line.counterpartyId());
            postings.add(p);
        }
        content.put("postings", postings);
        return content;
    }

    /** An entry as the repository saved it: the same content as {@link #load} reads, with no query. */
    public static Map<String, Object> of(com.example.financetracker.ledger.JournalEntry entry) {
        return entry(entry.entryDate(), entry.kind().name(), entry.payeeId(), entry.memo(), entry.externalRef(),
                entry.postings().stream()
                        .map(p -> new Line(p.accountId(), p.currency(), p.amount(), p.categoryId(),
                                p.counterpartyId()))
                        .toList());
    }

    /** The record as the database holds it now, or empty if the row isn't there (for a family record, deleted is a flag). */
    public Optional<Map<String, Object>> load(RecordType type, long ledgerId, long id) {
        return switch (type) {
            case ENTRY -> loadEntry(ledgerId, id);
            case ACCOUNT -> jdbc.sql("""
                    SELECT code, name, type, default_currency, requires_counterparty, is_system,
                           archived_at IS NOT NULL AS archived
                    FROM account WHERE id = :id AND ledger_id = :ledgerId""")
                    .param("id", id).param("ledgerId", ledgerId)
                    .query((row, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", row.getString("code"));
                        m.put("name", row.getString("name"));
                        m.put("type", row.getString("type"));
                        m.put("defaultCurrency", row.getString("default_currency"));
                        m.put("requiresCounterparty", row.getBoolean("requires_counterparty"));
                        m.put("system", row.getBoolean("is_system"));
                        m.put("archived", row.getBoolean("archived"));
                        return m;
                    }).optional();
            case CATEGORY, FAMILY_CATEGORY -> jdbc.sql("""
                    SELECT code, name, type, archived_at IS NOT NULL AS archived
                    FROM category WHERE id = :id AND ledger_id = :ledgerId""")
                    .param("id", id).param("ledgerId", ledgerId)
                    .query((row, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", row.getString("code"));
                        m.put("name", row.getString("name"));
                        m.put("type", row.getString("type"));
                        m.put("archived", row.getBoolean("archived"));
                        return m;
                    }).optional();
            case COUNTERPARTY -> jdbc.sql("""
                    SELECT name, kind, archived_at IS NOT NULL AS archived
                    FROM counterparty WHERE id = :id AND ledger_id = :ledgerId""")
                    .param("id", id).param("ledgerId", ledgerId)
                    .query((row, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", row.getString("name"));
                        m.put("kind", row.getString("kind"));
                        m.put("archived", row.getBoolean("archived"));
                        return m;
                    }).optional();
            case FAMILY_RECORD -> loadFamilyRecord(ledgerId, id);
        };
    }

    /**
     * Every live record of a personal type in the ledger, as {@link #load} would read each one (the same rows, the same
     * shapes), in a fixed number of statements: for the restore plan, which must not query per record (HIST-4 part 2a).
     * Derived rows are no records (HistoryJobs.liveIds).
     */
    public Map<Long, Map<String, Object>> loadAll(RecordType type, long ledgerId) {
        Map<Long, Map<String, Object>> all = new LinkedHashMap<>();
        switch (type) {
            case ENTRY -> loadAllEntries(ledgerId, all);
            case ACCOUNT -> jdbc.sql("""
                    SELECT id, code, name, type, default_currency, requires_counterparty, is_system,
                           archived_at IS NOT NULL AS archived
                    FROM account WHERE ledger_id = :ledgerId AND family_ledger_id IS NULL
                      AND code <> 'UNSPECIFIED_PAYMENTS' ORDER BY id""")
                    .param("ledgerId", ledgerId)
                    .query((row, n) -> all.put(row.getLong("id"), accountContent(row))).list();
            case CATEGORY -> jdbc.sql("""
                    SELECT id, code, name, type, archived_at IS NOT NULL AS archived
                    FROM category WHERE ledger_id = :ledgerId AND user_id IS NOT NULL ORDER BY id""")
                    .param("ledgerId", ledgerId)
                    .query((row, n) -> all.put(row.getLong("id"), categoryContent(row))).list();
            case COUNTERPARTY -> jdbc.sql("""
                    SELECT id, name, kind, archived_at IS NOT NULL AS archived
                    FROM counterparty WHERE ledger_id = :ledgerId ORDER BY id""")
                    .param("ledgerId", ledgerId)
                    .query((row, n) -> all.put(row.getLong("id"), counterpartyContent(row))).list();
            default -> throw new IllegalArgumentException("Not a personal record type: " + type);
        }
        return all;
    }

    private void loadAllEntries(long ledgerId, Map<Long, Map<String, Object>> all) {
        Map<Long, List<Line>> lines = new java.util.HashMap<>();
        jdbc.sql("""
                SELECT p.entry_id, p.account_id, p.currency, p.amount, p.category_id, p.counterparty_id
                FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE e.ledger_id = :ledgerId AND e.kind NOT LIKE 'FAMILY\\_%'
                ORDER BY p.entry_id, p.line_no""")
                .param("ledgerId", ledgerId)
                .query((row, n) -> lines.computeIfAbsent(row.getLong("entry_id"), k -> new ArrayList<>()).add(
                        new Line(row.getLong("account_id"), row.getString("currency"),
                                row.getBigDecimal("amount"), row.getObject("category_id", Long.class),
                                row.getObject("counterparty_id", Long.class))))
                .list();
        jdbc.sql("""
                SELECT id, entry_date, kind, payee_id, memo, external_ref FROM journal_entry
                WHERE ledger_id = :ledgerId AND kind NOT LIKE 'FAMILY\\_%' ORDER BY id""")
                .param("ledgerId", ledgerId)
                .query((row, n) -> all.put(row.getLong("id"), entry(
                        row.getObject("entry_date", LocalDate.class), row.getString("kind"),
                        row.getObject("payee_id", Long.class), row.getString("memo"), row.getString("external_ref"),
                        lines.getOrDefault(row.getLong("id"), List.of()))))
                .list();
    }

    /** An account row's content, as {@link #load} builds it. */
    static Map<String, Object> accountContent(java.sql.ResultSet row) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", row.getString("code"));
        m.put("name", row.getString("name"));
        m.put("type", row.getString("type"));
        m.put("defaultCurrency", row.getString("default_currency"));
        m.put("requiresCounterparty", row.getBoolean("requires_counterparty"));
        m.put("system", row.getBoolean("is_system"));
        m.put("archived", row.getBoolean("archived"));
        return m;
    }

    static Map<String, Object> categoryContent(java.sql.ResultSet row) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", row.getString("code"));
        m.put("name", row.getString("name"));
        m.put("type", row.getString("type"));
        m.put("archived", row.getBoolean("archived"));
        return m;
    }

    static Map<String, Object> counterpartyContent(java.sql.ResultSet row) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", row.getString("name"));
        m.put("kind", row.getString("kind"));
        m.put("archived", row.getBoolean("archived"));
        return m;
    }

    private Optional<Map<String, Object>> loadEntry(long ledgerId, long id) {
        Optional<Object[]> head = jdbc.sql("""
                SELECT entry_date, kind, payee_id, memo, external_ref FROM journal_entry
                WHERE id = :id AND ledger_id = :ledgerId""")
                .param("id", id).param("ledgerId", ledgerId)
                .query((row, n) -> new Object[] {row.getObject("entry_date", LocalDate.class), row.getString("kind"),
                        row.getObject("payee_id", Long.class), row.getString("memo"), row.getString("external_ref")})
                .optional();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        List<Line> lines = jdbc.sql("""
                SELECT account_id, currency, amount, category_id, counterparty_id FROM posting
                WHERE entry_id = :id ORDER BY line_no""")
                .param("id", id)
                .query((row, n) -> new Line(row.getLong("account_id"), row.getString("currency"),
                        row.getBigDecimal("amount"), row.getObject("category_id", Long.class),
                        row.getObject("counterparty_id", Long.class)))
                .list();
        Object[] h = head.get();
        return Optional.of(entry((LocalDate) h[0], (String) h[1], (Long) h[2], (String) h[3], (String) h[4], lines));
    }

    private Optional<Map<String, Object>> loadFamilyRecord(long ledgerId, long id) {
        Optional<Map<String, Object>> head = jdbc.sql("""
                SELECT type, record_date, category_id, payer_member_id, payee_member_id, base_amount, currency,
                       split_method, comment, deleted_at IS NOT NULL AS deleted, external_ref
                FROM family_record WHERE id = :id AND ledger_id = :ledgerId""")
                .param("id", id).param("ledgerId", ledgerId)
                .query((row, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", row.getString("type"));
                    m.put("date", row.getObject("record_date", LocalDate.class));
                    m.put("category", row.getObject("category_id", Long.class));
                    m.put("payer", row.getLong("payer_member_id"));
                    m.put("payee", row.getObject("payee_member_id", Long.class));
                    m.put("amount", row.getBigDecimal("base_amount"));
                    m.put("currency", row.getString("currency"));
                    m.put("splitMethod", row.getString("split_method"));
                    m.put("comment", row.getString("comment"));
                    m.put("deleted", row.getBoolean("deleted"));
                    m.put("externalRef", row.getString("external_ref"));
                    return m;
                }).optional();
        if (head.isEmpty()) {
            return head;
        }
        List<Object> shares = jdbc.sql("""
                SELECT member_id, amount, share_bp FROM family_share WHERE record_id = :id ORDER BY member_id""")
                .param("id", id)
                .query((row, n) -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("member", row.getLong("member_id"));
                    s.put("amount", row.getBigDecimal("amount"));
                    s.put("basisPoints", row.getObject("share_bp", Integer.class));
                    return (Object) s;
                }).list();
        head.get().put("shares", shares);
        return head;
    }
}
