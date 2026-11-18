package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;

import com.example.financetracker.ledger.history.Canonical;
import com.example.financetracker.ledger.history.RecordContents;
import com.example.financetracker.ledger.history.RecordType;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The state-at-revision oracle of ADR 0006 (5.5, D-1004): a test keeps a full copy of a ledger's live records at a
 * revision and, later, compares it with "the latest version with a revision up to N of each record" (a DELETE is no
 * state). The live records are read by the same loaders that make the versions, but the comparison is between a copy
 * taken then and the history read now, so a version that was never written, or written wrong, shows.
 */
final class HistoryOracle {

    /** A ledger's live records, canonical, at a revision. */
    record Snapshot(long ledgerId, long revision, Map<String, String> state) {
    }

    private HistoryOracle() {
    }

    static Snapshot capture(JdbcClient jdbc, RecordContents contents, long ledgerId) {
        long revision = jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(ledgerId).query(Long.class).single();
        Map<String, String> state = new TreeMap<>();
        for (RecordType type : RecordType.values()) {
            for (long id : liveIds(jdbc, type, ledgerId)) {
                contents.load(type, ledgerId, id).ifPresent(content -> {
                    if (!Boolean.TRUE.equals(content.get("deleted"))) {
                        state.put(type + ":" + id, Canonical.text(content));
                    }
                });
            }
        }
        return new Snapshot(ledgerId, revision, state);
    }

    /** What the history says the ledger held at {@code snapshot.revision()}: equal to the copy taken then. */
    static void verify(JdbcClient jdbc, Snapshot snapshot) {
        Map<String, String> history = new TreeMap<>();
        jdbc.sql("""
                SELECT v.record_type, v.record_id, v.op, v.content::text AS content
                FROM record_version v
                WHERE v.ledger_id = ? AND v.revision <= ?
                  AND v.seq = (SELECT max(w.seq) FROM record_version w
                               WHERE w.ledger_id = v.ledger_id AND w.record_type = v.record_type
                                 AND w.record_id = v.record_id AND w.revision <= ?)""")
                .param(snapshot.ledgerId()).param(snapshot.revision()).param(snapshot.revision())
                .query((row, n) -> {
                    if (!"DELETE".equals(row.getString("op"))) {
                        history.put(row.getString("record_type") + ":" + row.getLong("record_id"),
                                Canonical.canonicalize(row.getString("content")));
                    }
                    return null;
                }).list();
        assertThat(history).as("the ledger %d at revision %d, from its versions", snapshot.ledgerId(),
                snapshot.revision()).isEqualTo(snapshot.state());
    }

    private static Iterable<Long> liveIds(JdbcClient jdbc, RecordType type, long ledgerId) {
        String sql = switch (type) {
            case ENTRY -> "SELECT id FROM journal_entry WHERE ledger_id = ? AND kind NOT LIKE 'FAMILY\\_%'";
            case ACCOUNT -> "SELECT id FROM account WHERE ledger_id = ? AND family_ledger_id IS NULL "
                    + "AND code <> 'UNSPECIFIED_PAYMENTS'";
            case CATEGORY -> "SELECT id FROM category WHERE ledger_id = ? AND user_id IS NOT NULL";
            case FAMILY_CATEGORY -> "SELECT id FROM category WHERE ledger_id = ? AND user_id IS NULL";
            case COUNTERPARTY -> "SELECT id FROM counterparty WHERE ledger_id = ?";
            case FAMILY_RECORD -> "SELECT id FROM family_record WHERE ledger_id = ?";
        };
        return jdbc.sql(sql).param(ledgerId).query(Long.class).list();
    }
}
