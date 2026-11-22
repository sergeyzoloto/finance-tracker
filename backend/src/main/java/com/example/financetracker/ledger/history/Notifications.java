package com.example.financetracker.ledger.history;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The family notifications of a person (ADR 0006, section 13, D-1077, D-1087, D-1088): references, never text, to
 * the ACTIVE member rows of their sub across their family ledgers.
 */
@Service
public class Notifications {

    private final JdbcClient jdbc;

    Notifications(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** How many notifications the user hasn't read, over their ACTIVE memberships: one statement on the unread index. */
    public long unreadCount(String userId) {
        return jdbc.sql("""
                SELECT count(*) FROM notification n JOIN ledger_member m ON m.id = n.recipient_member_id
                WHERE m.user_sub = :userId AND m.status = 'ACTIVE' AND n.read_at IS NULL""")
                .param("userId", userId).query(Long.class).single();
    }
}
