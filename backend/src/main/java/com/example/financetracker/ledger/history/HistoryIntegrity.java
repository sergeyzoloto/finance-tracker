package com.example.financetracker.ledger.history;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The integrity check of the history (ADR 0006, 5.5, D-1004): for every live record that has versions, the latest
 * version's content equals the live content; for every record whose live row is gone, the latest version is a DELETE.
 * A record without a version is not a fault yet (the baseline job writes it, D-1020); one whose latest version differs
 * is drift, which only a write by the image before HIST-1 can cause (D-1040) and the baseline job or the next change
 * reconciles. The check compares by hash, recomputed from the stored content where the stored hash differs (erasing a
 * leaver's comment changes a content without its hash).
 */
@Service
public class HistoryIntegrity {

    /** One record that doesn't match its history. */
    public record Problem(RecordType type, long recordId, String what) {
    }

    /** What a check of one ledger found. */
    public record Result(int checked, int unversioned, List<Problem> problems) {
    }

    private final JdbcClient jdbc;
    private final RecordContents contents;
    private final HistoryJobs jobs;

    HistoryIntegrity(JdbcClient jdbc, RecordContents contents, HistoryJobs jobs) {
        this.jdbc = jdbc;
        this.contents = contents;
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public Result check(long ledgerId) {
        int checked = 0;
        int unversioned = 0;
        List<Problem> problems = new ArrayList<>();
        for (RecordType type : RecordType.values()) {
            for (long id : jobs.liveIds(type, ledgerId)) {
                checked++;
                var latest = jdbc.sql("""
                        SELECT content_hash, content::text AS content, op FROM record_version
                        WHERE ledger_id = :l AND record_type = :t AND record_id = :id ORDER BY seq DESC LIMIT 1""")
                        .param("l", ledgerId).param("t", type.name()).param("id", id)
                        .query((row, n) -> new Object[] {row.getBytes("content_hash"), row.getString("content"),
                                row.getString("op")})
                        .optional();
                if (latest.isEmpty()) {
                    unversioned++;
                    continue;
                }
                var live = contents.load(type, ledgerId, id);
                if (live.isEmpty()) {
                    continue;
                }
                byte[] liveHash = Canonical.hash(live.get());
                byte[] stored = (byte[]) latest.get()[0];
                if (!Arrays.equals(liveHash, stored)
                        && !Arrays.equals(liveHash, Canonical.sha256(Canonical.canonicalize((String) latest.get()[1])))) {
                    problems.add(new Problem(type, id, "the latest version differs from the live record"));
                }
                boolean deletedFlag = Boolean.TRUE.equals(live.get().get("deleted"));
                if ("DELETE".equals(latest.get()[2]) && !deletedFlag) {
                    problems.add(new Problem(type, id, "the latest version is a delete but the record is live"));
                }
            }
            for (long id : jobs.goneIds(type, ledgerId)) {
                problems.add(new Problem(type, id, "the record is gone but its latest version isn't a delete"));
            }
        }
        return new Result(checked, unversioned, problems);
    }
}
