package com.example.financetracker.ledger.history;

import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.WallClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The jobs of the history (ADR 0006): the baseline (D-1020), which writes a version for every live record that has none
 * or whose latest version no longer equals it, and the daily deletion of sign-in events older than 13 months (D-1042).
 * Both run at startup, in the background, and the deletion every day at 05:10 UTC; both are idempotent. Off where
 * {@code app.history.jobs.enabled} is false (the tests call them).
 */
@Service
public class HistoryJobs {

    private static final Logger log = LoggerFactory.getLogger(HistoryJobs.class);

    private final JdbcClient jdbc;
    private final HistoryService history;
    private final RecordContents contents;
    private final org.springframework.transaction.PlatformTransactionManager transactions;

    HistoryJobs(JdbcClient jdbc, HistoryService history, RecordContents contents,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.history = history;
        this.contents = contents;
        this.transactions = transactions;
    }

    /** What a baseline run did; {@code failed} counts the chunks that failed and were left for the next run. */
    public record BaselineResult(int ledgers, int baselines, int reconciled, int deletes, long millis, int failed) {
    }

    /** Records per transaction: short, so that a user's change to a record waits for little (ADR 0006, 5.6). */
    private static final int CHUNK = 250;

    /**
     * Writes a BASELINE version for every live record without one, a RECONCILE version for every one whose live
     * content differs from its latest version (a write by the image before HIST-1, D-1040) and a DELETE version for
     * every record whose latest version is not a DELETE and whose live row is gone. Per ledger and type, in
     * transactions of {@value #CHUNK} records; a chunk that fails (a user's change raced it) is logged and left for the
     * next run. A second run writes nothing.
     */
    public BaselineResult baseline() {
        long started = System.nanoTime();
        List<Long> ledgers = jdbc.sql("SELECT id FROM ledger ORDER BY id").query(Long.class).list();
        int[] total = new int[4];
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        for (long ledger : ledgers) {
            for (RecordType type : RecordType.values()) {
                List<Long> live = liveIds(type, ledger);
                for (int from = 0; from < live.size(); from += CHUNK) {
                    List<Long> chunk = live.subList(from, Math.min(live.size(), from + CHUNK));
                    run(template, total, () -> baselineChunk(ledger, type, chunk));
                }
                List<Long> gone = goneIds(type, ledger);
                if (!gone.isEmpty()) {
                    run(template, total, () -> goneChunk(ledger, type, gone));
                }
            }
        }
        BaselineResult result = new BaselineResult(ledgers.size(), total[0], total[1], total[2],
                (System.nanoTime() - started) / 1_000_000, total[3]);
        log.info("History baseline: {} ledgers, {} baseline versions, {} reconciled, {} deleted records, {} failed "
                + "chunks, {} ms", result.ledgers(), result.baselines(), result.reconciled(), result.deletes(),
                result.failed(), result.millis());
        return result;
    }

    private void run(org.springframework.transaction.support.TransactionTemplate template, int[] total,
            java.util.function.Supplier<int[]> work) {
        try {
            int[] counts = template.execute(status -> work.get());
            total[0] += counts[0];
            total[1] += counts[1];
            total[2] += counts[2];
        } catch (RuntimeException e) {
            total[3]++;
            log.warn("A chunk of the history baseline failed and is left for the next run: {}", e.getMessage());
        }
    }

    private int[] baselineChunk(long ledger, RecordType type, List<Long> ids) {
        int baselines = 0;
        int reconciled = 0;
        try (HistoryContext.Frame frame = HistoryContext.open(Cause.BASELINE, null)) {
            for (long id : ids) {
                Latest latest = latest(ledger, type, id);
                var live = contents.load(type, ledger, id);
                if (live.isEmpty()) {
                    continue;
                }
                if (latest == null) {
                    baselines += history.baseline(ledger, type, id, live.get(), false) ? 1 : 0;
                } else if (!java.util.Arrays.equals(latest.hash, Canonical.hash(live.get()))) {
                    reconciled += history.baseline(ledger, type, id, live.get(), true) ? 1 : 0;
                }
            }
        }
        return new int[] {baselines, reconciled, 0};
    }

    private int[] goneChunk(long ledger, RecordType type, List<Long> ids) {
        int deletes = 0;
        try (HistoryContext.Frame frame = HistoryContext.open(Cause.BASELINE, null)) {
            for (long id : ids) {
                deletes += history.baselineGone(ledger, type, id) ? 1 : 0;
            }
        }
        return new int[] {0, 0, deletes};
    }

    private record Latest(byte[] hash, String op) {
    }

    private Latest latest(long ledger, RecordType type, long id) {
        return jdbc.sql("""
                SELECT content_hash, op FROM record_version
                WHERE ledger_id = :l AND record_type = :t AND record_id = :id ORDER BY seq DESC LIMIT 1""")
                .param("l", ledger).param("t", type.name()).param("id", id)
                .query((row, n) -> new Latest(row.getBytes("content_hash"), row.getString("op"))).optional().orElse(null);
    }

    /** The ids of the live records of a type in a ledger: derived rows are no records. */
    List<Long> liveIds(RecordType type, long ledger) {
        String sql = switch (type) {
            case ENTRY -> "SELECT id FROM journal_entry WHERE ledger_id = :l AND kind NOT LIKE 'FAMILY\\_%' ORDER BY id";
            case ACCOUNT -> "SELECT id FROM account WHERE ledger_id = :l AND family_ledger_id IS NULL "
                    + "AND code <> 'UNSPECIFIED_PAYMENTS' ORDER BY id";
            case CATEGORY -> "SELECT id FROM category WHERE ledger_id = :l AND user_id IS NOT NULL ORDER BY id";
            case FAMILY_CATEGORY -> "SELECT id FROM category WHERE ledger_id = :l AND user_id IS NULL ORDER BY id";
            case COUNTERPARTY -> "SELECT id FROM counterparty WHERE ledger_id = :l ORDER BY id";
            case FAMILY_RECORD -> "SELECT id FROM family_record WHERE ledger_id = :l ORDER BY id";
        };
        return jdbc.sql(sql).param("l", ledger).query(Long.class).list();
    }

    /** Records with versions whose live row is gone and whose latest version isn't a DELETE. */
    List<Long> goneIds(RecordType type, long ledger) {
        List<Long> ids = new ArrayList<>();
        jdbc.sql("""
                SELECT v.record_id FROM record_version v
                WHERE v.ledger_id = :l AND v.record_type = :t
                  AND v.seq = (SELECT max(seq) FROM record_version w
                               WHERE w.ledger_id = v.ledger_id AND w.record_type = v.record_type
                                 AND w.record_id = v.record_id)
                  AND v.op <> 'DELETE'""")
                .param("l", ledger).param("t", type.name())
                .query(Long.class).list()
                .stream().filter(id -> contents.load(type, ledger, id).isEmpty()).forEach(ids::add);
        return ids;
    }

    /**
     * Deletes the sign-in, sign-out and expiry events older than 13 months (D-1033, D-1042). The delete runs with
     * app.writer set to history-purge, which the immutability guard of V19 allows for this table.
     *
     * @return how many events were deleted
     */
    @Transactional
    public int purgeSignInEvents() {
        jdbc.sql("SELECT set_config('app.writer', 'history-purge', true)").query(String.class).single();
        int deleted = jdbc.sql("""
                DELETE FROM activity_event
                WHERE ledger_id IS NULL AND kind IN (:kinds) AND at < now() - interval '13 months'""")
                .param("kinds", ActivityLog.SIGN_IN_KINDS).update();
        jdbc.sql("SELECT set_config('app.writer', '', true)").query(String.class).single();
        if (deleted > 0) {
            log.info("Deleted {} sign-in events older than 13 months", deleted);
        }
        return deleted;
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "app.history.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final HistoryJobs jobs;
        private final TaskScheduler scheduler;

        Schedule(HistoryJobs jobs, TaskScheduler scheduler) {
            this.jobs = jobs;
            this.scheduler = scheduler;
        }

        /** In the background, so that the application serves while the baseline runs. */
        @EventListener(ApplicationReadyEvent.class)
        void atStartup() {
            scheduler.schedule(this::run, WallClock.now());
        }

        @Scheduled(cron = "0 10 5 * * *", zone = "UTC")
        void daily() {
            purge();
        }

        private void run() {
            try {
                jobs.baseline();
            } catch (RuntimeException e) {
                log.warn("The history baseline failed; the lazy baseline covers the gap, the next start runs it again", e);
            }
            purge();
        }

        private void purge() {
            try {
                jobs.purgeSignInEvents();
            } catch (RuntimeException e) {
                log.warn("Could not delete old sign-in events; the next run tries again", e);
            }
        }
    }
}
