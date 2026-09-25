package com.example.financetracker.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's counterparties: payees of entries, and borrowers and creditors on postings (rule 8). Callers pass the
 * user id, the Keycloak "sub" claim (rule 11). Counterparties are archived, never deleted (rule 12), and their names
 * are unique per user regardless of case.
 */
@Service
public class CounterpartyService {

    private final CounterpartyRepository counterparties;
    private final JdbcClient jdbc;

    CounterpartyService(CounterpartyRepository counterparties, JdbcClient jdbc) {
        this.counterparties = counterparties;
        this.jdbc = jdbc;
    }

    /** All the user's counterparties, archived ones included, by name regardless of case. */
    @Transactional(readOnly = true)
    public List<CounterpartyView> list(String userId) {
        return views(userId, null);
    }

    /**
     * @param kind null for unclassified
     * @throws ConflictException if the user has a counterparty of this name already, in any case
     */
    @Transactional
    public CounterpartyView create(String userId, String name, Counterparty.Kind kind) {
        Counterparty counterparty = save(new Counterparty(null, userId, name, kind, null));
        return new CounterpartyView(counterparty.id(), counterparty.name(), counterparty.kind(), false, null);
    }

    /**
     * @throws NotFoundException if the user has no such counterparty
     * @throws ConflictException if the new name is another of the user's counterparties', in any case
     */
    @Transactional
    public CounterpartyView update(String userId, long counterpartyId, CounterpartyChanges changes) {
        Counterparty counterparty = counterparties.findByIdAndUserId(counterpartyId, userId)
                .orElseThrow(() -> new NotFoundException("Counterparty " + counterpartyId + " not found"));
        save(new Counterparty(counterparty.id(), userId,
                changes.name() != null ? changes.name() : counterparty.name(),
                changes.changesKind() ? changes.kind() : counterparty.kind(),
                AccountService.archivedAt(counterparty.archivedAt(), changes.archived())));
        return views(userId, counterpartyId).getFirst();
    }

    private Counterparty save(Counterparty counterparty) {
        try {
            return counterparties.save(counterparty);
        } catch (DbActionExecutionException e) {
            if (e.getCause() instanceof DuplicateKeyException) {
                throw new ConflictException(
                        "You have a counterparty named %s already".formatted(counterparty.name()));
            }
            throw e;
        }
    }

    /**
     * The user's counterparties, or just the one with {@code counterpartyId}, each with the category of its most
     * recent entry as payee that has one: the latest by date, then by id, and within that entry its first
     * categorized posting.
     */
    private List<CounterpartyView> views(String userId, Long counterpartyId) {
        String onlyOne = counterpartyId == null ? "" : " AND e.payee_id = :counterpartyId";
        String onlyThatOne = counterpartyId == null ? "" : " AND c.id = :counterpartyId";
        var statement = jdbc.sql("""
                WITH last_category AS (
                    SELECT DISTINCT ON (e.payee_id) e.payee_id, p.category_id
                    FROM journal_entry e
                    JOIN posting p ON p.entry_id = e.id
                    WHERE e.user_id = :userId AND p.category_id IS NOT NULL%s
                    ORDER BY e.payee_id, e.entry_date DESC, e.id DESC, p.line_no
                )
                SELECT c.id, c.name, c.kind, c.archived_at IS NOT NULL AS archived, l.category_id AS last_category_id
                FROM counterparty c
                LEFT JOIN last_category l ON l.payee_id = c.id
                WHERE c.user_id = :userId%s
                ORDER BY lower(c.name), c.id""".formatted(onlyOne, onlyThatOne))
                .param("userId", userId);
        if (counterpartyId != null) {
            statement = statement.param("counterpartyId", counterpartyId);
        }
        return statement.query(CounterpartyService::view).list();
    }

    private static CounterpartyView view(ResultSet row, int rowNum) throws SQLException {
        String kind = row.getString("kind");
        return new CounterpartyView(row.getLong("id"), row.getString("name"),
                kind == null ? null : Counterparty.Kind.valueOf(kind), row.getBoolean("archived"),
                row.getObject("last_category_id", Long.class));
    }
}
