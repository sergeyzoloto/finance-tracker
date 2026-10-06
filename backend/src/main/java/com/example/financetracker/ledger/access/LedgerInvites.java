package com.example.financetracker.ledger.access;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.Today;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The way into a family ledger before there is a membership (D-17, D-18; ADR 0003, topics C and G): an invite, found by
 * its token's hash. Next to {@link LedgerAccess}, which decides access by membership, this decides it by invite: the
 * only reads of a family ledger for someone who isn't its member are here ({@link #preview}), and they are what the
 * invite shows before it is accepted; joining makes the membership, after which LedgerAccess gives the scope.
 * <p>
 * The token is 32 random bytes from a {@link SecureRandom}, base64url without padding; only its SHA-256 is stored. A
 * token that is unknown, expired, revoked, used or declined gets one answer, {@link #INVALID}, with the same status
 * (404) every time. Nothing here logs a token, puts one in an exception, or returns one.
 */
@Service
public class LedgerInvites {

    /** The one answer for a token that lets nobody in (D-17). */
    public static final String INVALID = "This invite is not valid. Ask for a new one";

    /** The seat of a claim was taken meanwhile, by another invite. */
    public static final String SEAT_TAKEN = "Someone has taken this place in the family budget already. Ask for a new "
            + "invite";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A member's family balance in each currency from the records dated before a day (ADR 0003, topic D; D-45): their
     * expense shares − the expenses they paid + the incomes they received − their income shares − the settlements they
     * paid + the settlements they received; the ledger's main currency always, and each other currency of a record
     * before the day.
     */
    private static final String BALANCES_BEFORE = """
            SELECT c.currency,
                   coalesce((SELECT sum(CASE r.type WHEN 'EXPENSE' THEN s.amount ELSE -s.amount END)
                             FROM family_share s JOIN family_record r ON r.id = s.record_id
                             WHERE s.member_id = :memberId AND r.ledger_id = :ledgerId AND r.deleted_at IS NULL
                               AND r.record_date < :before AND r.currency = c.currency), 0)
                   - coalesce((SELECT sum(CASE r.type WHEN 'INCOME' THEN -r.base_amount ELSE r.base_amount END)
                               FROM family_record r
                               WHERE r.payer_member_id = :memberId AND r.ledger_id = :ledgerId AND r.deleted_at IS NULL
                                 AND r.record_date < :before AND r.currency = c.currency), 0)
                   + coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payee_member_id = :memberId AND r.ledger_id = :ledgerId AND r.deleted_at IS NULL
                                 AND r.record_date < :before AND r.currency = c.currency), 0) AS balance
            FROM (SELECT base_currency AS currency, 0 AS main FROM ledger WHERE id = :ledgerId
                  UNION SELECT currency, 1 FROM family_record
                        WHERE ledger_id = :ledgerId AND deleted_at IS NULL AND record_date < :before
                          AND currency <> (SELECT base_currency FROM ledger WHERE id = :ledgerId)) c
            ORDER BY c.main, c.currency""";

    /**
     * A pending invite.
     *
     * @param seatMemberId the seat a claim takes; null for a new member
     * @param joinDate the seat's join date; null for a new member, who joins on the day they accept (D-18)
     */
    public record Invite(long id, long ledgerId, Long seatMemberId, LocalDate joinDate, long createdByMemberId,
            Instant expiresAt) {

        public boolean claim() {
            return seatMemberId != null;
        }
    }

    /**
     * What a pending invite shows its holder before they accept: the family ledger's name and base currency, who
     * invites, the seat's name and opening balance for a claim, and the family's categories. No member id, sub,
     * account or record.
     *
     * @param today today (the api's, {@link Today}), a new member's join date
     * @param seatBalances a claim's seat's family balance in each currency from the records before its join date,
     *        which the user takes on as an opening balance (D-18, D-34, D-45): positive when the seat owes the family;
     *        the main currency first, then each other currency of a record before it; null for a new member
     */
    public record Preview(String ledgerName, String baseCurrency, String invitedBy, String seatName, LocalDate today,
            List<FamilyCategory> categories, Map<String, BigDecimal> seatBalances) {
    }

    /** A category of the family ledger, as the invite shows it: no id. */
    public record FamilyCategory(String code, String name, String type, boolean archived) {
    }

    /** How the user stands in the invite's family ledger. */
    public enum Standing {
        /** Not a member: the invite may let them in. */
        NONE,
        /** An ACTIVE member already, the ledger's creator among them. */
        ACTIVE,
        /** A member who left, whom an invite for a new member brings back (F6a, D-26). */
        LEFT
    }

    private final JdbcClient jdbc;
    private final LedgerAccess access;
    private final Today today;

    LedgerInvites(JdbcClient jdbc, LedgerAccess access, Today today) {
        this.jdbc = jdbc;
        this.access = access;
        this.today = today;
    }

    /** A new token, for the link only. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The SHA-256 of the token, which is all that is stored of it; null for what can't be one. */
    public static byte[] hash(String token) {
        if (token == null || token.isBlank() || token.length() > LONGEST) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    /**
     * The pending invite the token stands for, unlocked: for a look before accepting.
     *
     * @throws NotFoundException {@link #INVALID}, for a token that is unknown, expired, revoked, used or declined
     */
    public Invite pending(String token) {
        return find(hash(token), "").orElseThrow(LedgerInvites::invalid);
    }

    /**
     * The pending invite the token stands for, with its family ledger's row locked FOR UPDATE and then the invite's,
     * until the transaction ends: the order in which owners' changes lock them too. Checked again after the locks, so
     * that of two acceptances of one token, or an acceptance and a revocation, the second sees the first.
     *
     * @throws NotFoundException {@link #INVALID}, as {@link #pending}
     */
    public Invite lock(String token) {
        byte[] hash = hash(token);
        Invite found = find(hash, "").orElseThrow(LedgerInvites::invalid);
        jdbc.sql("SELECT id FROM ledger WHERE id = :ledgerId FOR UPDATE").param("ledgerId", found.ledgerId())
                .query(Long.class).optional();
        return find(hash, " FOR UPDATE").orElseThrow(LedgerInvites::invalid);
    }

    /** What the invite shows before it is accepted. */
    public Preview preview(Invite invite) {
        record Ledger(String name, String baseCurrency, String invitedBy, String seatName) {
        }
        Ledger ledger = jdbc.sql("""
                SELECT l.name, l.base_currency, c.display_name AS invited_by, s.display_name AS seat_name
                FROM ledger l
                JOIN ledger_member c ON c.ledger_id = l.id AND c.id = :creatorId
                LEFT JOIN ledger_member s ON s.ledger_id = l.id AND s.id = :seatId
                WHERE l.id = :ledgerId AND l.type = 'SHARED'""")
                .param("ledgerId", invite.ledgerId()).param("creatorId", invite.createdByMemberId())
                .param("seatId", invite.seatMemberId())
                .query((row, n) -> new Ledger(row.getString("name"), row.getString("base_currency"),
                        row.getString("invited_by"), row.getString("seat_name")))
                .single();
        List<FamilyCategory> categories = jdbc.sql("""
                SELECT code, name, type, archived_at IS NOT NULL AS archived FROM category
                WHERE ledger_id = :ledgerId ORDER BY name, code""")
                .param("ledgerId", invite.ledgerId())
                .query((row, n) -> new FamilyCategory(row.getString("code"), row.getString("name"),
                        row.getString("type"), row.getBoolean("archived")))
                .list();
        Map<String, BigDecimal> seatBalances = invite.claim()
                ? balancesBefore(invite.ledgerId(), invite.seatMemberId(), invite.joinDate()) : null;
        return new Preview(ledger.name(), ledger.baseCurrency(), ledger.invitedBy(), ledger.seatName(), today.date(),
                categories, seatBalances);
    }

    /** The user's membership in the invite's family ledger, if any; a FORMER one has no sub any more (D-20). */
    public Standing standing(String userId, Invite invite) {
        return jdbc.sql("SELECT status FROM ledger_member WHERE ledger_id = :ledgerId AND user_sub = :userId")
                .param("ledgerId", invite.ledgerId()).param("userId", userId)
                .query(String.class).optional()
                .map(status -> status.equals("ACTIVE") ? Standing.ACTIVE : Standing.LEFT)
                .orElse(Standing.NONE);
    }

    /** Whether a claim's seat is still a member without an account, ACTIVE: nobody took it meanwhile. */
    public boolean seatFree(Invite invite) {
        return invite.claim() && jdbc.sql("""
                SELECT EXISTS (SELECT FROM ledger_member
                               WHERE id = :seatId AND ledger_id = :ledgerId AND status = 'ACTIVE' AND user_sub IS NULL)""")
                .param("seatId", invite.seatMemberId()).param("ledgerId", invite.ledgerId())
                .query(Boolean.class).single();
    }

    /**
     * The user's membership in the invite's family ledger that is LEFT, which an invite for a new member brings back
     * (D-26), or null.
     */
    public Long leftMembership(String userId, Invite invite) {
        return jdbc.sql("""
                SELECT id FROM ledger_member WHERE ledger_id = :ledgerId AND user_sub = :userId AND status = 'LEFT'""")
                .param("ledgerId", invite.ledgerId()).param("userId", userId)
                .query(Long.class).optional().orElse(null);
    }

    /**
     * The family balance of the user's LEFT membership in each currency from the records dated before the day (D-26,
     * D-45): what the correction of their return starts from. Read for the invite's holder, who isn't an ACTIVE member.
     */
    public Map<String, BigDecimal> leftMemberBalances(Invite invite, long memberId, LocalDate before) {
        return balancesBefore(invite.ledgerId(), memberId, before);
    }

    private Map<String, BigDecimal> balancesBefore(long ledgerId, long memberId, LocalDate before) {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        jdbc.sql(BALANCES_BEFORE).param("ledgerId", ledgerId).param("memberId", memberId).param("before", before)
                .query(row -> {
                    balances.put(row.getString("currency"), row.getBigDecimal("balance"));
                });
        return balances;
    }

    /**
     * Whether a member who isn't FORMER has the name, whatever its case, other than the seat the invite claims or the
     * returning user's own membership (D-3).
     */
    public boolean nameTaken(Invite invite, String displayName, Long ownMembership) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT FROM ledger_member
                               WHERE ledger_id = :ledgerId AND status <> 'FORMER' AND lower(display_name) = lower(:name)
                                 AND id IS DISTINCT FROM :seatId AND id IS DISTINCT FROM :own)""")
                .param("ledgerId", invite.ledgerId()).param("name", displayName)
                .param("seatId", invite.seatMemberId()).param("own", ownMembership)
                .query(Boolean.class).single();
    }

    /**
     * Lets the user in by the invite, which {@link #lock} locked, and marks it used by their membership: a claim gives
     * the seat the user's sub, the chosen name and the invite's join date, and the seat keeps its history (D-18); a new
     * member is a MEMBER, ACTIVE, joined today, with a share of 0 under a CUSTOM split rule (D-12). The caller checked
     * the user's {@link #standing}, the seat and the name first; the database refuses what slipped past them.
     *
     * @return the family ledger, as the new member
     * @throws ConflictException if the seat was taken after all
     */
    public LedgerScope join(String userId, Invite invite, String displayName) {
        long memberId;
        if (invite.claim()) {
            int claimed = jdbc.sql("""
                    UPDATE ledger_member SET user_sub = :userId, display_name = :name, join_date = :joinDate
                    WHERE id = :seatId AND ledger_id = :ledgerId AND status = 'ACTIVE' AND user_sub IS NULL""")
                    .param("userId", userId).param("name", displayName).param("joinDate", invite.joinDate())
                    .param("seatId", invite.seatMemberId()).param("ledgerId", invite.ledgerId())
                    .update();
            if (claimed != 1) {
                throw new ConflictException(SEAT_TAKEN);
            }
            memberId = invite.seatMemberId();
        } else {
            memberId = jdbc.sql("""
                    INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date,
                                               share_bp)
                    SELECT id, 'SHARED', :userId, :name, 'MEMBER', 'ACTIVE', :today,
                           CASE split_rule WHEN 'CUSTOM' THEN 0 END
                    FROM ledger WHERE id = :ledgerId
                    RETURNING id""")
                    .param("userId", userId).param("name", displayName).param("ledgerId", invite.ledgerId())
                    .param("today", today.date())
                    .query(Long.class).single();
        }
        jdbc.sql("""
                UPDATE ledger_invite SET used_at = now(), used_by_member_id = :memberId
                WHERE id = :inviteId AND ledger_id = :ledgerId""")
                .param("memberId", memberId).param("inviteId", invite.id()).param("ledgerId", invite.ledgerId())
                .update();
        return access.member(userId, invite.ledgerId());
    }

    /**
     * Brings the user's LEFT membership back by the invite for a new member, which {@link #lock} locked (D-26; ADR
     * 0003, topic G): ACTIVE again, joined today, with the chosen name, a MEMBER with a share of 0 under a CUSTOM split
     * rule; the invite used by it. The database lets a LEFT member's join date change only so (V5).
     *
     * @return the family ledger, as the member who returned
     * @throws ConflictException if the membership isn't LEFT any more
     */
    public LedgerScope rejoin(String userId, Invite invite, String displayName) {
        if (invite.claim()) {
            throw new IllegalArgumentException("A member who left comes back as a new member, never into a seat");
        }
        Long memberId = jdbc.sql("""
                UPDATE ledger_member m
                SET status = 'ACTIVE', left_date = NULL, join_date = :today, display_name = :name, role = 'MEMBER',
                    share_bp = (SELECT CASE split_rule WHEN 'CUSTOM' THEN 0 END FROM ledger WHERE id = m.ledger_id)
                WHERE ledger_id = :ledgerId AND user_sub = :userId AND status = 'LEFT'
                RETURNING id""")
                .param("today", today.date()).param("name", displayName).param("ledgerId", invite.ledgerId())
                .param("userId", userId)
                .query(Long.class).optional()
                .orElseThrow(() -> new ConflictException("You are not a member who left this family budget"));
        jdbc.sql("""
                UPDATE ledger_invite SET used_at = now(), used_by_member_id = :memberId
                WHERE id = :inviteId AND ledger_id = :ledgerId""")
                .param("memberId", memberId).param("inviteId", invite.id()).param("ledgerId", invite.ledgerId())
                .update();
        return access.member(userId, invite.ledgerId());
    }

    /** Declines the invite, which {@link #lock} locked: it is used up, and only the time is kept (D-17). */
    public void decline(Invite invite) {
        jdbc.sql("UPDATE ledger_invite SET declined_at = now() WHERE id = :inviteId AND ledger_id = :ledgerId")
                .param("inviteId", invite.id()).param("ledgerId", invite.ledgerId())
                .update();
    }

    /** The longest token looked up; a token is 43 characters, and a longer one is unknown. */
    private static final int LONGEST = 200;

    /** The pending invite with the token's hash, if there is one. */
    private Optional<Invite> find(byte[] hash, String lock) {
        if (hash == null) {
            return Optional.empty();
        }
        record Row(Invite invite, boolean pending) {
        }
        return jdbc.sql("""
                SELECT id, ledger_id, seat_member_id, join_date, created_by_member_id, expires_at,
                       revoked_at IS NULL AND used_at IS NULL AND declined_at IS NULL AND expires_at > now() AS pending
                FROM ledger_invite WHERE token_hash = :hash""" + lock)
                .param("hash", hash)
                .query((row, n) -> new Row(new Invite(row.getLong("id"), row.getLong("ledger_id"),
                        row.getObject("seat_member_id", Long.class), row.getObject("join_date", LocalDate.class),
                        row.getLong("created_by_member_id"), row.getTimestamp("expires_at").toInstant()),
                        row.getBoolean("pending")))
                .optional()
                .filter(Row::pending)
                .map(Row::invite);
    }

    private static NotFoundException invalid() {
        return new NotFoundException(INVALID);
    }
}
