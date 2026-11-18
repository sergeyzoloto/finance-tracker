package com.example.financetracker.ledger.history;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
        jdbc.sql("""
                INSERT INTO activity_event (actor_sub, ledger_id, kind, subject_type, subject_id, details)
                VALUES (:sub, :ledgerId, :kind, :subjectType, :subjectId, CAST(:details AS jsonb))""")
                .param("sub", actorSub).param("ledgerId", ledgerId).param("kind", kind)
                .param("subjectType", subjectType).param("subjectId", subjectId).param("details", text(details))
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
