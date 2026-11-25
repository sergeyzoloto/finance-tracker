package com.example.financetracker.ledger.history;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.financetracker.ledger.access.LedgerScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The state of a ledger at a revision (ADR 0006, D-1008), read-only: per record, the latest version with a revision at
 * or before it; a record whose latest such version is a DELETE or ARCHIVE, or that has none, did not exist then.
 */
@Service
public class LedgerState {

    /** The record types of a personal ledger (D-1193). */
    static final List<RecordType> PERSONAL =
            List.of(RecordType.ENTRY, RecordType.ACCOUNT, RecordType.CATEGORY, RecordType.COUNTERPARTY);

    /** The latest version per record at or before :n, by the index (ledger_id, revision). */
    public static final String LATEST = """
            SELECT DISTINCT ON (record_type, record_id) record_type, record_id, op, content
            FROM record_version
            WHERE ledger_id = :l AND revision <= :n AND record_type IN (:types)
            ORDER BY record_type, record_id, revision DESC, seq DESC""";

    private final JdbcClient jdbc;

    LedgerState(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Each record that existed at the revision, by type and id, with its canonical content as stored. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<RecordType, Map<Long, String>> at(LedgerScope scope, long revision) {
        Map<RecordType, Map<Long, String>> state = new EnumMap<>(RecordType.class);
        PERSONAL.forEach(t -> state.put(t, new TreeMap<>()));
        jdbc.sql("SELECT record_type, record_id, content::text AS content FROM (" + LATEST
                        + ") v WHERE op NOT IN ('DELETE', 'ARCHIVE')")
                .param("l", scope.ledgerId()).param("n", revision).param("types", names())
                .query((row, n) -> state.get(RecordType.valueOf(row.getString("record_type")))
                        .put(row.getLong("record_id"), row.getString("content")))
                .list();
        return state;
    }

    /** How many records of each type existed at the revision; joins the caller's transaction if there is one. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<RecordType, Long> counts(LedgerScope scope, long revision) {
        Map<RecordType, Long> counts = new EnumMap<>(RecordType.class);
        PERSONAL.forEach(t -> counts.put(t, 0L));
        jdbc.sql("SELECT record_type, count(*) AS n FROM (" + LATEST
                        + ") v WHERE op NOT IN ('DELETE', 'ARCHIVE') GROUP BY record_type")
                .param("l", scope.ledgerId()).param("n", revision).param("types", names())
                .query((row, n) -> counts.put(RecordType.valueOf(row.getString("record_type")), row.getLong("n")))
                .list();
        return counts;
    }

    private static List<String> names() {
        return PERSONAL.stream().map(Enum::name).toList();
    }
}
