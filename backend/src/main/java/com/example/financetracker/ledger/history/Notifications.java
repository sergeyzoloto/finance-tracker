package com.example.financetracker.ledger.history;

import java.time.Instant;
import java.sql.Types;
import java.util.List;

import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.access.LedgerScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The family notifications of a person (ADR 0006, section 13, D-1077, D-1087, D-1088): references, never text, to
 * the ACTIVE member rows of their sub across their family ledgers. The writers run in the caller's transaction
 * (D-1105), so a notification and the change it describes stand or fall together; the recipients are the ledger's
 * ACTIVE members with an account at that instant, never the actor nor the author (D-1108).
 */
@Service
public class Notifications {

    /** A notification's kind (V20's CHECK). */
    public enum Kind { FAMILY_CHANGES, FAMILY_IMPORTED, MEMBER_JOINED, MEMBER_LEFT, SEAT_CLAIMED }

    /** @param name the member's name now, "Former member" for a FORMER or missing one */
    public record Author(Long memberId, String name) {
    }

    /** One notification, its text built now (D-1107). */
    public record NotificationView(long id, String kind, Instant at, boolean read, long ledgerId, Author author,
            Long fromRevision, Long toRevision, Integer changesCount, String text) {
    }

    /** A page of them, newest first. */
    public record NotificationPage(List<NotificationView> content, int page, int size, long total, int totalPages) {
    }

    private static final String RECIPIENTS = """
            SELECT m.id FROM ledger_member m JOIN ledger l ON l.id = m.ledger_id AND l.type = 'SHARED'
            WHERE m.ledger_id = :ledgerId AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL
              AND m.id <> :actor AND m.id <> :author""";

    private final JdbcClient jdbc;
    private final HistoryService history;
    private final boolean familyOn;

    Notifications(JdbcClient jdbc, HistoryService history, @Value("${app.family.enabled:false}") boolean familyOn) {
        this.jdbc = jdbc;
        this.history = history;
        this.familyOn = familyOn;
    }

    /** How many notifications the user hasn't read, over their ACTIVE memberships: one statement on the unread index. */
    public long unreadCount(String userId) {
        return jdbc.sql("""
                SELECT count(*) FROM notification n JOIN ledger_member m ON m.id = n.recipient_member_id
                WHERE m.user_sub = :userId AND m.status = 'ACTIVE' AND n.read_at IS NULL""")
                .param("userId", userId).query(Long.class).single();
    }

    /**
     * After a family record's change by {@code author}, in its transaction: FAMILY_CHANGES with the revision this
     * transaction took on the ledger (none taken: nothing changed, no notification). An unread one of the same author
     * in the ledger is widened in place (D-1106) by one atomic UPDATE, which waits for the row's lock and re-checks
     * that it is still unread, so two merges never lose a count; a recipient without one gets a new row.
     */
    public void familyChanged(long ledgerId, long author) {
        if (familyOn) {
            history.revisionTaken(ledgerId).ifPresent(revision -> familyChanged(ledgerId, author, revision));
        }
    }

    /**
     * As {@link #familyChanged(long, long)}, at the given revision. The caller must hold the ledger's lock
     * ({@code FamilyRecordService.lockLedger}): the atomic UPDATE keeps a concurrent merge from losing a count, but
     * only the lock keeps two writers of one ledger from both finding no unread row and both inserting a first one for
     * the same author and recipient. A future caller that skips the lock can thus get two unread rows (each counted
     * correctly), never a lost count.
     */
    public void familyChanged(long ledgerId, long author, long revision) {
        if (!familyOn) {
            return;
        }
        jdbc.sql("""
                WITH r AS (%s),
                merged AS (
                    UPDATE notification n
                    SET from_revision = LEAST(n.from_revision, :revision),
                        to_revision = GREATEST(n.to_revision, :revision),
                        changes_count = n.changes_count + 1, created_at = now()
                    FROM r
                    WHERE n.recipient_member_id = r.id AND n.ledger_id = :ledgerId AND n.kind = 'FAMILY_CHANGES'
                      AND n.author_member_id = :author AND n.read_at IS NULL
                    RETURNING n.recipient_member_id)
                INSERT INTO notification (ledger_id, recipient_member_id, kind, author_member_id, from_revision,
                                          to_revision, changes_count)
                SELECT :ledgerId, r.id, 'FAMILY_CHANGES', :author, :revision, :revision, 1 FROM r
                WHERE r.id NOT IN (SELECT recipient_member_id FROM merged)""".formatted(RECIPIENTS))
                .param("ledgerId", ledgerId).param("actor", author).param("author", author)
                .param("revision", revision).update();
    }

    /**
     * After a family import committed, once per commit: FAMILY_IMPORTED at the revision it took on the ledger. An
     * import that took none (it changed only the importer's own side of the records, or nothing) tells no one.
     */
    public void familyImported(long ledgerId, long author) {
        if (familyOn) {
            history.revisionTaken(ledgerId).ifPresent(revision ->
                    insert(ledgerId, Kind.FAMILY_IMPORTED, author, author, revision));
        }
    }

    /**
     * A membership's notification (MEMBER_JOINED, MEMBER_LEFT, SEAT_CLAIMED): {@code author} is the member it is
     * about, {@code actor} who did it (the same, or the owner who removed them); neither is notified.
     */
    public void membership(long ledgerId, Kind kind, long actor, long author) {
        if (familyOn) {
            insert(ledgerId, kind, actor, author, null);
        }
    }

    /**
     * After the member the scope stands for accepted up to {@code head} (D-1167): their unread notifications of the
     * ledger's changes, imports and restores up to it are read. Their others stay as they are.
     */
    public void accepted(LedgerScope family, long head) {
        jdbc.sql("""
                UPDATE notification SET read_at = now()
                WHERE recipient_member_id = :memberId AND ledger_id = :ledgerId AND read_at IS NULL
                  AND kind IN ('FAMILY_CHANGES', 'FAMILY_IMPORTED', 'FAMILY_RESTORED') AND to_revision <= :head""")
                .param("memberId", family.memberId()).param("ledgerId", family.ledgerId()).param("head", head)
                .update();
    }

    private void insert(long ledgerId, Kind kind, long actor, long author, Long revision) {
        jdbc.sql("""
                INSERT INTO notification (ledger_id, recipient_member_id, kind, author_member_id, from_revision,
                                          to_revision)
                SELECT :ledgerId, r.id, :kind, :author, :revision, :revision FROM (%s) r""".formatted(RECIPIENTS))
                .param("ledgerId", ledgerId).param("kind", kind.name()).param("actor", actor).param("author", author)
                .param("revision", revision, Types.BIGINT).update();
    }

    /** The caller's notifications in the family budgets they are an ACTIVE member of, newest first. */
    public NotificationPage page(String userId, int page, int size) {
        long total = jdbc.sql("""
                SELECT count(*) FROM notification n JOIN ledger_member m ON m.id = n.recipient_member_id
                WHERE m.user_sub = :userId AND m.status = 'ACTIVE'""")
                .param("userId", userId).query(Long.class).single();
        List<NotificationView> content = jdbc.sql("""
                SELECT n.id, n.kind, n.created_at, n.read_at IS NOT NULL AS read, n.ledger_id, n.author_member_id,
                       CASE WHEN a.id IS NULL OR a.status = 'FORMER' THEN 'Former member' ELSE a.display_name END
                           AS author_name,
                       n.from_revision, n.to_revision, n.changes_count
                FROM notification n JOIN ledger_member m ON m.id = n.recipient_member_id
                     LEFT JOIN ledger_member a ON a.id = n.author_member_id
                WHERE m.user_sub = :userId AND m.status = 'ACTIVE'
                ORDER BY n.created_at DESC, n.id DESC LIMIT :size OFFSET :offset""")
                .param("userId", userId).param("size", size).param("offset", (long) page * size)
                .query((row, n) -> {
                    Long authorId = row.getObject("author_member_id", Long.class);
                    String name = authorId == null ? null : row.getString("author_name");
                    Integer count = row.getObject("changes_count", Integer.class);
                    String kind = row.getString("kind");
                    return new NotificationView(row.getLong("id"), kind,
                            row.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                            row.getBoolean("read"), row.getLong("ledger_id"),
                            authorId == null ? null : new Author(authorId, name),
                            row.getObject("from_revision", Long.class), row.getObject("to_revision", Long.class),
                            count, NotificationText.of(kind, name, count));
                }).list();
        return new NotificationPage(content, page, size, total, (int) ((total + size - 1) / size));
    }

    /** Marks one of the caller's notifications read; another user's, a missing one or a left ledger's: 404. */
    public void markRead(String userId, long id) {
        int marked = jdbc.sql("""
                UPDATE notification n SET read_at = coalesce(n.read_at, now())
                FROM ledger_member m
                WHERE n.id = :id AND m.id = n.recipient_member_id AND m.user_sub = :userId AND m.status = 'ACTIVE'""")
                .param("id", id).param("userId", userId).update();
        if (marked == 0) {
            throw new NotFoundException("Notification " + id + " not found");
        }
    }

    /** Marks every unread notification of the caller's read. */
    public void markAllRead(String userId) {
        jdbc.sql("""
                UPDATE notification n SET read_at = now()
                FROM ledger_member m
                WHERE m.id = n.recipient_member_id AND m.user_sub = :userId AND m.status = 'ACTIVE'
                  AND n.read_at IS NULL""")
                .param("userId", userId).update();
    }
}
