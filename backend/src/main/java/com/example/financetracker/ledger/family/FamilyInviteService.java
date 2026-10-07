package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.LedgerCategory;
import com.example.financetracker.ledger.LedgerCategoryRepository;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.RuleViolationException.Violation;
import com.example.financetracker.ledger.access.LedgerInvites;
import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.access.LedgerInvites.Invite;
import com.example.financetracker.ledger.access.LedgerInvites.Preview;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.family.FamilyInviteView.InviteKind;
import com.example.financetracker.ledger.family.FamilyInviteView.InviteStatus;
import com.example.financetracker.ledger.family.FamilyRecordView.MemberRef;
import com.example.financetracker.ledger.family.InviteLookup.CategoryChoice;
import com.example.financetracker.ledger.family.InviteLookup.CategoryKept;
import com.example.financetracker.ledger.family.InviteLookup.CategoryMerge;
import com.example.financetracker.ledger.family.InviteLookup.FamilyCategory;
import com.example.financetracker.ledger.family.posting.FamilyPostingService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invites to a family ledger and taking a seat (B2, B3, B4; D-17, D-18; ADR 0003, topic G). Owners create, list and
 * revoke invites with the family ledger's scope (D-15). The signed-in user who holds a link looks the invite up,
 * accepts or declines it with their personal ledger's scope: before they are a member, {@link LedgerInvites} finds the
 * invite by its token and lets them in, and from then on the new membership's scope does the rest.
 * <p>
 * Accepting, in one transaction: a new member joins today as a MEMBER (D-18), or a claim gives the seat the user's sub,
 * the chosen name and the invite's join date, with all its history; the user's categories with a family category's
 * code and type merge into it, which keeps its name, and the ones they bring become family categories (D-11 as amended
 * after the F4e review), their postings re-pointed as at creation; then the posting service posts every record from
 * the join date and the balance before it (D-10 holds at commit).
 */
@Service
public class FamilyInviteService {

    /** How long an invite lasts by default, and at most, in hours (D-17). */
    public static final int DEFAULT_HOURS = 72;
    public static final int MAX_HOURS = 168;
    /** Pending invites a family ledger may have at once. */
    public static final int MAX_PENDING = 20;

    /** The codes of the invites' violations. */
    public static final String JOIN_DATE = "JOIN_DATE";
    public static final String SEAT = "SEAT";
    public static final String CATEGORY = FamilyRecordService.CATEGORY;
    /**
     * The code of the 409 for a return while the returning member's former debt account holds entries of their own
     * dated after the join date (D-37).
     */
    public static final String ENTRIES_AFTER_RETURN = "ENTRIES_AFTER_RETURN";

    private static final String INVITES = """
            SELECT i.id, i.seat_member_id, s.display_name AS seat_name, coalesce(i.join_date, u.join_date) AS join_date,
                   i.created_by_member_id, c.display_name AS created_by_name, i.created_at, i.expires_at,
                   i.revoked_at, i.used_at, i.used_by_member_id, u.display_name AS used_by_name, i.declined_at,
                   i.expires_at <= now() AS expired
            FROM ledger_invite i
            JOIN ledger_member c ON c.id = i.created_by_member_id AND c.ledger_id = i.ledger_id
            LEFT JOIN ledger_member s ON s.id = i.seat_member_id AND s.ledger_id = i.ledger_id
            LEFT JOIN ledger_member u ON u.id = i.used_by_member_id AND u.ledger_id = i.ledger_id
            WHERE i.ledger_id = :ledgerId""";

    /** An invite as its creation answers it: with the token, which nothing else ever holds. */
    public record CreatedInvite(FamilyInviteView invite, String token) {
    }

    private final JdbcClient jdbc;
    private final LedgerInvites invites;
    private final FamilyLedgerService families;
    private final LedgerCategoryRepository categories;
    private final FamilyPostingService posting;
    private final Today today;

    FamilyInviteService(JdbcClient jdbc, LedgerInvites invites, FamilyLedgerService families,
            LedgerCategoryRepository categories, FamilyPostingService posting, Today today) {
        this.jdbc = jdbc;
        this.invites = invites;
        this.families = families;
        this.categories = categories;
        this.posting = posting;
        this.today = today;
    }

    // --- The owners' side ---

    /**
     * Creates an invite: for a new member, or with {@code seatMemberId} to take the place of a member without an
     * account from {@code joinDate}, on or after the start date and not after today (D-18); today if it is left out.
     *
     * @param owner the family ledger, as one of its owners
     * @param hours how long it lasts: 1 to 168, 72 if null (D-17)
     * @throws NotFoundException if the seat isn't a member of the ledger
     * @throws ConflictException if the seat has an account or is FORMER, or the ledger has its most pending invites
     * @throws RuleViolationException for a join date that doesn't fit the kind or the ledger
     */
    @Transactional
    public CreatedInvite create(LedgerScope owner, Long seatMemberId, LocalDate joinDate, Integer hours) {
        LocalDate startDate = jdbc.sql("SELECT start_date FROM ledger WHERE id = :ledgerId FOR UPDATE")
                .param("ledgerId", owner.ledgerId())
                .query(LocalDate.class)
                .single();
        LocalDate now = today.date(owner);
        int lifetime = hours == null ? DEFAULT_HOURS : hours;
        if (lifetime < 1 || lifetime > MAX_HOURS) {
            throw new IllegalArgumentException("An invite lasts from 1 to %d hours".formatted(MAX_HOURS));
        }
        List<Violation> violations = new ArrayList<>();
        if (seatMemberId != null) {
            FamilyMemberView seat = families.members(owner).stream().filter(m -> m.id() == seatMemberId).findFirst()
                    .orElseThrow(() -> new NotFoundException("Member " + seatMemberId + " not found"));
            if (seat.status() == MemberStatus.LEFT) {
                throw new ConflictException(seat.displayName() + " has left the family budget, so nobody takes their "
                        + "place");
            }
            if (seat.status() != MemberStatus.ACTIVE) {
                throw new ConflictException("A former member's place can't be taken");
            }
            if (seat.hasAccount()) {
                throw new ConflictException(seat.displayName() + " has an account already, so nobody takes their place");
            }
            // Left out, it is today in the owner's zone, as for a start date (D-101); the page sends it only when it differs
            // from /api/me's today, which is this one.
            joinDate = joinDate == null ? now : joinDate;
            if (joinDate.isBefore(startDate) || joinDate.isAfter(now)) {
                violations.add(new Violation(JOIN_DATE, seatMemberId, ("the join date %s is not between the family "
                        + "budget's start date %s and today, %s").formatted(joinDate, startDate, now)));
            }
        } else if (joinDate != null) {
            violations.add(new Violation(JOIN_DATE, null, "a new member joins on the day they accept; only taking the "
                    + "place of a member without an account takes a join date"));
        }
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }
        long pending = jdbc.sql("""
                SELECT count(*) FROM ledger_invite
                WHERE ledger_id = :ledgerId AND revoked_at IS NULL AND used_at IS NULL AND declined_at IS NULL
                  AND expires_at > now()""")
                .param("ledgerId", owner.ledgerId()).query(Long.class).single();
        if (pending >= MAX_PENDING) {
            throw new ConflictException(("The family budget has %d pending invites, the most it can have; revoke one "
                    + "first").formatted(MAX_PENDING));
        }
        String token = LedgerInvites.newToken();
        long inviteId = jdbc.sql("""
                INSERT INTO ledger_invite (ledger_id, token_hash, seat_member_id, join_date, created_by_member_id,
                                           expires_at)
                VALUES (:ledgerId, :hash, :seatId, :joinDate, :memberId, now() + make_interval(hours => :hours))
                RETURNING id""")
                .param("ledgerId", owner.ledgerId()).param("hash", LedgerInvites.hash(token))
                .param("seatId", seatMemberId).param("joinDate", joinDate).param("memberId", owner.memberId())
                .param("hours", lifetime)
                .query(Long.class).single();
        return new CreatedInvite(invite(owner, inviteId), token);
    }

    /** The family ledger's invites, newest first, whatever their status. */
    @Transactional(readOnly = true)
    public List<FamilyInviteView> list(LedgerScope owner) {
        return jdbc.sql(INVITES + " ORDER BY i.created_at DESC, i.id DESC").param("ledgerId", owner.ledgerId())
                .query(FamilyInviteService::view)
                .list();
    }

    /**
     * Revokes a pending invite: its link lets nobody in any more.
     *
     * @param owner the family ledger, as one of its owners
     * @throws NotFoundException if the family ledger has no such invite
     * @throws ConflictException if it isn't pending
     */
    @Transactional
    public void revoke(LedgerScope owner, long inviteId) {
        jdbc.sql("SELECT id FROM ledger WHERE id = :ledgerId FOR UPDATE").param("ledgerId", owner.ledgerId())
                .query(Long.class).single();
        FamilyInviteView invite = jdbc.sql(INVITES + " AND i.id = :inviteId FOR UPDATE OF i")
                .param("ledgerId", owner.ledgerId()).param("inviteId", inviteId)
                .query(FamilyInviteService::view)
                .optional()
                .orElseThrow(() -> new NotFoundException("Invite " + inviteId + " not found"));
        if (invite.status() != InviteStatus.PENDING) {
            throw new ConflictException("Only a pending invite can be revoked, and this one is "
                    + invite.status().name().toLowerCase());
        }
        jdbc.sql("UPDATE ledger_invite SET revoked_at = now() WHERE id = :inviteId AND ledger_id = :ledgerId")
                .param("inviteId", inviteId).param("ledgerId", owner.ledgerId())
                .update();
    }

    private FamilyInviteView invite(LedgerScope owner, long inviteId) {
        return jdbc.sql(INVITES + " AND i.id = :inviteId").param("ledgerId", owner.ledgerId())
                .param("inviteId", inviteId)
                .query(FamilyInviteService::view)
                .single();
    }

    // --- The invited user's side ---

    /**
     * What the invite shows the user who holds its link (D-17), and what becomes of their categories (D-11).
     *
     * @param personal the user's personal ledger
     * @param accountName the name of the user's account, to prefill their display name (D-3)
     * @throws NotFoundException {@link LedgerInvites#INVALID}, for a token that lets nobody in
     * @throws ConflictException if the user can't accept it: a member already, or the seat is taken
     */
    @Transactional(readOnly = true)
    public InviteLookup lookup(LedgerScope personal, String token, String accountName) {
        requirePersonal(personal);
        Invite invite = invites.pending(token);
        Long returning = requireAcceptable(personal, invite);
        Preview preview = invites.preview(personal.userId(), invite);
        Matches matches = matches(personal, preview.categories());
        String main = preview.baseCurrency();
        // Per currency (D-45): the correction is the balance before the join date less what the former debt account
        // shows, in each currency of either.
        List<CurrencyAmount> corrections = null;
        if (returning != null) {
            Map<String, BigDecimal> balances = invites.leftMemberBalances(invite, returning, preview.today());
            Map<String, BigDecimal> shown = posting.formerDebtBalances(personal, invite.ledgerId(), returning,
                    preview.today());
            Map<String, BigDecimal> differences = new LinkedHashMap<>(balances);
            shown.forEach((currency, amount) -> differences.merge(currency, amount.negate(), BigDecimal::add));
            corrections = amounts(differences, main);
        }
        List<CurrencyAmount> openings = preview.seatBalances() == null ? null
                : amounts(preview.seatBalances(), main);
        List<InviteLookup.EntryAfterReturn> after = returning == null ? null
                : posting.entriesAfterReturn(personal, invite.ledgerId(), returning, preview.today()).stream()
                        .map(e -> new InviteLookup.EntryAfterReturn(e.entryId(), e.date(), Money.normalize(e.amount()),
                                e.currency(), e.memo()))
                        .toList();
        return new InviteLookup(preview.ledgerName(), preview.baseCurrency(), preview.invitedBy(),
                invite.claim() ? InviteKind.CLAIM : InviteKind.NEW_MEMBER, preview.seatName(),
                invite.claim() ? invite.joinDate() : preview.today(), invite.expiresAt(),
                preview.categories().stream().filter(c -> !c.archived())
                        .map(c -> new FamilyCategory(c.code(), c.name(), c.type())).toList(),
                matches.merges(), matches.kept(), matches.mayBring(), accountName,
                returning != null, after, openings, corrections);
    }

    /**
     * The amounts that aren't 0, at each currency's minor unit, the main currency first, then by currency.
     */
    private static List<CurrencyAmount> amounts(Map<String, BigDecimal> byCurrency, String main) {
        return byCurrency.entrySet().stream().filter(entry -> entry.getValue().signum() != 0)
                .sorted(Comparator.comparing((Map.Entry<String, BigDecimal> entry) -> !entry.getKey().equals(main))
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new CurrencyAmount(entry.getKey(), entry.getValue()
                        .setScale(ShareSplit.minorUnit(entry.getKey()), RoundingMode.UNNECESSARY)))
                .toList();
    }

    /**
     * Accepts the invite, as described for the class.
     *
     * @param personal the user's personal ledger
     * @param displayName the name the other members will see (D-3), unique among those who aren't FORMER
     * @param categoryIds the user's own categories to bring into the family's dictionary
     * @return the family ledger, as the new member sees it
     * @throws NotFoundException {@link LedgerInvites#INVALID}, for a token that lets nobody in
     * @throws ConflictException if the user can't accept it, or another member has the name
     * @throws RuleViolationException if a category isn't one the user may bring
     */
    @Transactional
    public FamilyLedgerView accept(LedgerScope personal, String token, String displayName, List<Long> categoryIds) {
        requirePersonal(personal);
        Invite invite = invites.lock(token);
        Long returning = requireAcceptable(personal, invite);
        if (invites.nameTaken(invite, displayName, returning)) {
            throw new ConflictException("The family budget has a member named %s already".formatted(displayName));
        }
        Preview preview = invites.preview(personal.userId(), invite);
        if (returning != null) {
            int entries = posting.entriesAfterReturn(personal, invite.ledgerId(), returning, preview.today()).size();
            if (entries > 0) {
                throw new ConflictException(("Your former debt to this family budget has %d %s dated after today that "
                        + "belong to none of its records. Move %s to another account or delete %s, then accept again")
                        .formatted(entries, entries == 1 ? "entry" : "entries", entries == 1 ? "it" : "them",
                                entries == 1 ? "it" : "them"), ENTRIES_AFTER_RETURN);
            }
        }
        Map<String, LedgerInvites.FamilyCategory> family = new HashMap<>();
        preview.categories().forEach(c -> family.put(c.code(), c));
        Set<Long> wanted = new LinkedHashSet<>(categoryIds);
        List<LedgerCategory> brought = wanted.isEmpty() ? List.of() : categories.lockAll(personal, wanted);
        List<Violation> violations = new ArrayList<>();
        Set<Long> found = new HashSet<>();
        for (LedgerCategory category : brought) {
            found.add(category.id());
            LedgerInvites.FamilyCategory same = family.get(category.code());
            if (category.archivedAt() != null) {
                violations.add(new Violation(CATEGORY, null, "your category %s is archived".formatted(category.code())));
            } else if (same != null && !same.type().equals(category.type().name())) {
                violations.add(new Violation(CATEGORY, null, ("the family budget's category %s is %s, and yours is %s: "
                        + "yours stays private").formatted(category.code(), same.type(), category.type())));
            }
        }
        wanted.stream().filter(id -> !found.contains(id))
                .forEach(id -> violations.add(new Violation(CATEGORY, null, "category %d is not one of yours"
                        .formatted(id))));
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }

        LedgerScope member = returning != null ? invites.rejoin(personal.userId(), invite, displayName)
                : invites.join(personal.userId(), invite, displayName);
        Map<String, LedgerCategory> familyCategories = new HashMap<>();
        categories.findAll(member).forEach(c -> familyCategories.put(c.code(), c));
        // Same code and type: merged into the family's, which keeps its name (D-11 as amended).
        List<LedgerCategory> mine = new ArrayList<>(categories.findAll(personal));
        mine.sort(Comparator.comparing(LedgerCategory::code));
        for (LedgerCategory category : mine) {
            LedgerCategory same = familyCategories.get(category.code());
            if (same != null && same.type() == category.type()) {
                families.merge(personal, category, same);
            }
        }
        // Brought: a family category with its code, name and type, which its postings move to, as at creation.
        brought.stream().filter(c -> !familyCategories.containsKey(c.code()))
                .sorted(Comparator.comparing(LedgerCategory::code))
                .forEach(category -> families.merge(personal, category, categories.save(new LedgerCategory(null,
                        member.rowUserId(), member.ledgerId(), category.code(), category.name(), category.type(),
                        null))));
        if (returning != null) {
            posting.rejoin(member);
        } else {
            posting.join(member);
        }
        return families.get(member);
    }

    /**
     * Declines the invite: its link is used up, and only the time is kept (D-17). Only someone who could accept it
     * declines it, so that a member who opens a link meant for someone else doesn't use it up.
     *
     * @throws NotFoundException {@link LedgerInvites#INVALID}, for a token that lets nobody in
     * @throws ConflictException as {@link #accept} for a user who can't accept it
     */
    @Transactional
    public void decline(LedgerScope personal, String token) {
        requirePersonal(personal);
        Invite invite = invites.lock(token);
        requireAcceptable(personal, invite);
        invites.decline(invite);
    }

    /**
     * Whether the user may use the invite: not a member yet, or a member who left and comes back by an invite for a new
     * member (D-26; D-29's 409 for a member who left is their way back now).
     *
     * @return the user's LEFT membership if they come back, else null
     * @throws ConflictException if the user is a member of the ledger already, its creator among them, has left it
     *         and the invite claims a seat (members are never merged, D-18), or a claim's seat was taken meanwhile
     */
    private Long requireAcceptable(LedgerScope personal, Invite invite) {
        Long returning = null;
        switch (invites.standing(personal.userId(), invite)) {
            case ACTIVE -> throw new ConflictException("You are a member of this family budget already");
            case LEFT -> {
                if (invite.claim()) {
                    throw new ConflictException("You were a member of this family budget before, so you can't take "
                            + "someone else's place; an invite as a new member brings you back");
                }
                returning = invites.leftMembership(personal.userId(), invite);
            }
            case NONE -> {
            }
        }
        if (invite.claim() && !invites.seatFree(invite)) {
            throw new ConflictException(LedgerInvites.SEAT_TAKEN);
        }
        return returning;
    }

    private static void requirePersonal(LedgerScope personal) {
        if (personal.type() != LedgerType.PERSONAL) {
            throw new IllegalArgumentException("An invite is looked up, accepted and declined from a personal ledger");
        }
    }

    /** What becomes of the user's categories (D-11 as amended after the F4e review). */
    private record Matches(List<CategoryMerge> merges, List<CategoryKept> kept, List<CategoryChoice> mayBring) {
    }

    private Matches matches(LedgerScope personal, List<LedgerInvites.FamilyCategory> familyCategories) {
        Map<String, LedgerInvites.FamilyCategory> family = new HashMap<>();
        familyCategories.forEach(c -> family.put(c.code(), c));
        List<CategoryMerge> merges = new ArrayList<>();
        List<CategoryKept> kept = new ArrayList<>();
        List<CategoryChoice> mayBring = new ArrayList<>();
        for (LedgerCategory category : categories.findAll(personal)) {
            LedgerInvites.FamilyCategory same = family.get(category.code());
            String type = category.type().name();
            if (same == null) {
                if (category.archivedAt() == null) {
                    mayBring.add(new CategoryChoice(category.id(), category.code(), category.name(), type));
                }
            } else if (same.type().equals(type)) {
                merges.add(new CategoryMerge(category.id(), category.code(), category.name(), same.name(), type));
            } else {
                kept.add(new CategoryKept(category.id(), category.code(), category.name(), type, same.type()));
            }
        }
        return new Matches(merges, kept, mayBring);
    }

    private static FamilyInviteView view(ResultSet row, int n) throws SQLException {
        Long seatId = row.getObject("seat_member_id", Long.class);
        Long usedBy = row.getObject("used_by_member_id", Long.class);
        Instant revoked = instant(row, "revoked_at");
        Instant used = instant(row, "used_at");
        Instant declined = instant(row, "declined_at");
        InviteStatus status = revoked != null ? InviteStatus.REVOKED
                : used != null ? InviteStatus.ACCEPTED
                : declined != null ? InviteStatus.DECLINED
                : row.getBoolean("expired") ? InviteStatus.EXPIRED : InviteStatus.PENDING;
        return new FamilyInviteView(row.getLong("id"), seatId == null ? InviteKind.NEW_MEMBER : InviteKind.CLAIM,
                seatId == null ? null : new MemberRef(seatId, row.getString("seat_name")),
                row.getObject("join_date", LocalDate.class),
                new MemberRef(row.getLong("created_by_member_id"), row.getString("created_by_name")),
                instant(row, "created_at"), instant(row, "expires_at"), status,
                usedBy == null ? null : new MemberRef(usedBy, row.getString("used_by_name")), used, declined, revoked);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
