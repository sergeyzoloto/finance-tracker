package com.example.financetracker.ledger.access;

import java.util.Optional;

import com.example.financetracker.ledger.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The one place that decides which ledger a user may use (ADR 0003, topic C): by an ACTIVE membership in it, never by
 * the rows' {@code user_id} (D-2). It makes every {@link LedgerScope}. Callers pass the user id, the Keycloak "sub"
 * claim (rule 11); nothing here reads the security context.
 * <p>
 * A ledger that doesn't exist and one the user isn't an ACTIVE member of get the same {@link NotFoundException}, so
 * the answer doesn't tell them apart. A LEFT or FORMER membership gives no access (D-19, D-20).
 */
@Service
public class LedgerAccess {

    private static final String MEMBERSHIP = """
            SELECT m.ledger_id, m.ledger_type, m.id AS member_id, m.role
            FROM ledger_member m
            WHERE m.user_sub = :userId AND m.status = 'ACTIVE' AND %s""";

    private final JdbcClient jdbc;

    LedgerAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The user's personal ledger, which provisioning creates on their first request (D-4).
     *
     * @throws NotFoundException if the user has none
     */
    public LedgerScope personal(String userId) {
        return find(userId, "m.ledger_type = 'PERSONAL'", null)
                .orElseThrow(() -> new NotFoundException("Personal ledger not found"));
    }

    /**
     * The ledger with this id, if the user is an ACTIVE member of it. Family ledgers come in through here.
     *
     * @throws NotFoundException if there is no such ledger, or the user isn't an ACTIVE member of it
     */
    public LedgerScope member(String userId, long ledgerId) {
        return find(userId, "m.ledger_id = :ledgerId", ledgerId)
                .orElseThrow(() -> new NotFoundException("Ledger " + ledgerId + " not found"));
    }

    /**
     * The user's personal ledger, created with its one OWNER member if the user has none yet, by the database's
     * {@code personal_ledger_id}, which is safe when a user's first requests run in parallel. For provisioning, and
     * for the command-line importer, which may be the first to write for a sub.
     */
    public LedgerScope provisionPersonal(String userId) {
        jdbc.sql("SELECT personal_ledger_id(:userId)").param("userId", userId).query(Long.class).single();
        return personal(userId);
    }

    private Optional<LedgerScope> find(String userId, String condition, Long ledgerId) {
        var statement = jdbc.sql(MEMBERSHIP.formatted(condition)).param("userId", userId);
        if (ledgerId != null) {
            statement = statement.param("ledgerId", ledgerId);
        }
        return statement
                .query((row, n) -> new LedgerScope(row.getLong("ledger_id"),
                        LedgerType.valueOf(row.getString("ledger_type")), row.getLong("member_id"),
                        MemberRole.valueOf(row.getString("role")), userId))
                .optional();
    }
}
