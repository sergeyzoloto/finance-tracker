package com.example.financetracker.ledger.history;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.example.financetracker.ledger.access.LedgerScope;
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The activity log (ADR 0006, 2.3, D-999, D-1007): what happens that isn't a record's change. By the Keycloak sub; no
 * email, no address, no browser. {@link #log} writes in the caller's transaction, so the event and what it describes
 * stand or fall together; {@link #logAlone} writes in a transaction of its own and never throws, for sign-in, sign-out
 * and expiry, which must not fail what they describe.
 */
@Service
public class ActivityLog {

    /** The kinds that {@link HistoryJobs} deletes after 13 months (D-1033, D-1042). */
    public static final java.util.List<String> SIGN_IN_KINDS =
            java.util.List.of("SIGN_IN", "SIGN_OUT", "SESSION_EXPIRED", "SESSION_ENDED");

    /** A member, the subject of a membership's event. */
    public static final String MEMBER = "MEMBER";

    /** A page of the activity list (D-1098), newest first. */
    public record ActivityPage(List<ActivityView> content, int page, int size, long total, int totalPages) {
    }

    /**
     * One event.
     *
     * @param ledgerId the family budget it happened in; null for what is about the person
     * @param subject the member a membership's event is about, by their name now; null for any other event
     * @param details the event's own values, such as a setting's old and new value
     */
    public record ActivityView(long id, Instant at, String kind, Long ledgerId, RecordHistory.Actor actor,
            Subject subject, JsonNode details) {
    }

    /** @param name the member's name now, "Former member" for one whose data is gone */
    public record Subject(long memberId, String name) {
    }

    private static final Logger log = LoggerFactory.getLogger(ActivityLog.class);

    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper json;

    ActivityLog(JdbcClient jdbc, PlatformTransactionManager transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    /** In the caller's transaction. {@code details} (old and new values, a reason) holds no email and no name. */
    public void log(String actorSub, Long ledgerId, String kind, String subjectType, Long subjectId,
            Map<String, Object> details) {
        log(actorSub, ledgerId, kind, subjectType, subjectId, null, details);
    }

    /** As {@link #log(String, Long, String, String, Long, Map)}, with the revision the event's change set took. */
    public void log(String actorSub, Long ledgerId, String kind, String subjectType, Long subjectId, Long revision,
            Map<String, Object> details) {
        jdbc.sql("""
                INSERT INTO activity_event (actor_sub, ledger_id, kind, subject_type, subject_id, revision, details)
                VALUES (:sub, :ledgerId, :kind, :subjectType, :subjectId, :revision, CAST(:details AS jsonb))""")
                .param("sub", actorSub).param("ledgerId", ledgerId).param("kind", kind)
                .param("subjectType", subjectType).param("subjectId", subjectId).param("revision", revision)
                .param("details", text(details))
                .update();
    }

    /** In a transaction of its own; a failure is logged and goes no further. */
    public void logAlone(String actorSub, String kind, Map<String, Object> details) {
        try {
            TransactionTemplate template = new TransactionTemplate(transactions);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> log(actorSub, null, kind, null, null, details));
        } catch (RuntimeException e) {
            log.warn("Could not write the {} event: {}", kind, e.getMessage());
        }
    }

    /**
     * The fields whose values differ, each as {@code {field, old, new}}, in the order of {@code before}; equal numbers
     * of another scale are no change. Empty if nothing changed, and then the caller writes no event.
     */
    public static List<Map<String, Object>> changes(Map<String, ?> before, Map<String, ?> after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        before.forEach((field, old) -> {
            Object now = after.get(field);
            boolean same = old instanceof BigDecimal a && now instanceof BigDecimal b
                    ? a.compareTo(b) == 0 : Objects.equals(old, now);
            if (!same) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("field", field);
                change.put("old", old);
                change.put("new", now);
                changes.add(change);
            }
        });
        return changes;
    }

    /**
     * The activity list (D-1098): the user's own events, and with the family switch on, every event of the family
     * budgets in {@code families} (the user's ACTIVE memberships), each by its ledger id. Newest first, stable.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ActivityPage page(String sub, List<LedgerScope> families, boolean familyOn, int page, int size) {
        // -1 is no ledger: it keeps the list non-empty.
        List<Long> ledgers = Stream.concat(Stream.of(-1L), families.stream().map(LedgerScope::ledgerId)).toList();
        String where = """
                WHERE (e.actor_sub = :sub AND (e.ledger_id IS NULL OR :familyOn)) OR e.ledger_id IN (:ledgers)""";
        long total = jdbc.sql("SELECT count(*) FROM activity_event e " + where)
                .param("sub", sub).param("familyOn", familyOn).param("ledgers", ledgers)
                .query(Long.class).single();
        List<ActivityView> content = jdbc.sql("""
                SELECT e.id, e.at, e.kind, e.ledger_id, e.subject_type, e.subject_id, e.details::text AS details,
                       e.kind = 'SPLIT_RULE_RESET' AS by_system, m.id AS actor_member,
                       CASE WHEN e.ledger_id IS NULL THEN NULL
                            WHEN m.id IS NULL OR m.status = 'FORMER' THEN 'Former member'
                            ELSE m.display_name END AS actor_name,
                       CASE WHEN s.id IS NULL OR s.status = 'FORMER' THEN 'Former member'
                            ELSE s.display_name END AS subject_name
                FROM activity_event e
                LEFT JOIN ledger_member m ON m.ledger_id = e.ledger_id AND m.user_sub = e.actor_sub
                LEFT JOIN ledger_member s
                       ON e.subject_type = 'MEMBER' AND s.id = e.subject_id AND s.ledger_id = e.ledger_id
                """ + where + " ORDER BY e.at DESC, e.id DESC LIMIT :limit OFFSET :offset")
                .param("sub", sub).param("familyOn", familyOn).param("ledgers", ledgers)
                .param("limit", size).param("offset", (long) page * size)
                .query((row, n) -> {
                    boolean system = row.getBoolean("by_system");
                    Long member = row.getObject("actor_member", Long.class);
                    RecordHistory.Actor actor = system ? new RecordHistory.Actor("SYSTEM", null, null)
                            : new RecordHistory.Actor("USER", member, row.getString("actor_name"));
                    long subjectId = row.getLong("subject_id");
                    String details = row.getString("details");
                    return new ActivityView(row.getLong("id"), row.getTimestamp("at").toInstant(),
                            row.getString("kind"), row.getObject("ledger_id", Long.class), actor,
                            !MEMBER.equals(row.getString("subject_type")) ? null
                                    : new Subject(subjectId, row.getString("subject_name")),
                            details == null ? null : tree(details));
                }).list();
        return new ActivityPage(content, page, size, total, Math.toIntExact((total + size - 1) / size));
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read an event", e);
        }
    }

    private String text(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
