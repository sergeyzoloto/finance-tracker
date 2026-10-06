package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.Today;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.MemberRole;
import com.example.financetracker.ledger.family.posting.FamilyPostingService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A membership's lifecycle in a family ledger (F6a; D-19, D-26, ADR 0003 topic J's "F6a plan"): leaving, removal by an
 * owner, the split rule's fall back to EQUAL when the member who goes had a custom share, making another member an
 * owner, and what "Delete all my data" would do to the user's family ledgers (D-20). Returning by an invite is
 * {@link FamilyInviteService}'s. Every change locks the
 * family ledger's row first, as the other changes of its members do.
 * <p>
 * A member who goes becomes LEFT on today's date, a MEMBER (only ACTIVE members are owners) without a custom share. A
 * member with an account is detached first, through the posting service's writer: their links detached, the family
 * categories their personal ledger refers to copied into it as personal ones (D-33), their debt account an ordinary
 * liability. Nothing is posted, to them or to anyone else, so D-10 holds for the ACTIVE members as it did; the records
 * that involve the member who left are frozen, so their family balance stays what their debt account showed. Their
 * pending invites, and those for their place, are revoked. A member without an account whom nothing names, as payer,
 * payee or with a share, and whose custom share is 0 or none, is deleted instead, as before F6a. When the last member
 * with an account goes, the family ledger is deleted with its records, as {@code release_family_memberships} deletes it
 * for "Delete all my data" (D-36, F6b; F6a archived it).
 */
@Service
public class FamilyMembershipService {

    /** The code of the 409 for the last owner who would leave while another member with an account remains. */
    public static final String LAST_OWNER = "LAST_OWNER";

    /** What "Delete all my data" does to a family ledger of the user's (D-20). */
    public enum Outcome {
        /** The family ledger stays as it is, without the user. */
        STAYS,
        /** The user is its last owner: the ACTIVE member with an account who joined earliest becomes one. */
        OWNERSHIP_PASSES,
        /** No other ACTIVE member with an account remains: it is deleted with its records. */
        DELETED
    }

    /**
     * What "Delete all my data" does to one family ledger the user is an ACTIVE member of (D-20), as the confirmation
     * screen lists it: their role and balance there, and what becomes of the ledger.
     *
     * @param balance what the user owes the family ledger, in its main currency: positive if they owe, negative if
     *        they are owed; deprecated since F8a for {@code balances}
     * @param newOwner the display name of who becomes an owner, for {@link Outcome#OWNERSHIP_PASSES}; else null
     * @param pendingInvites the user's invites of it that stop working
     * @param splitRuleReset whether its custom split rule goes back to equal shares
     * @param balances what the user owes the family ledger in each currency of its records, the main currency first, as
     *        its balances list them (D-20, D-45; additive, F8a)
     */
    public record MembershipImpact(long ledgerId, String name, MemberRole role, String baseCurrency,
            @Deprecated BigDecimal balance, Outcome outcome, String newOwner, int pendingInvites,
            boolean splitRuleReset, List<CurrencyAmount> balances) {
    }

    /**
     * The user's family ledgers as "Delete all my data" touches them, by name, and how many they left: their name in
     * those becomes "Former member" and their comments are erased, which is all the user learns of them now.
     */
    public record Memberships(List<MembershipImpact> memberships, long left) {
    }

    private final JdbcClient jdbc;
    private final FamilyPostingService posting;
    private final FamilyRecordService records;
    private final Today today;

    FamilyMembershipService(JdbcClient jdbc, FamilyPostingService posting, FamilyRecordService records, Today today) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.records = records;
        this.today = today;
    }

    /**
     * An owner makes another ACTIVE member with an account an owner (D-3: several owners; D-15). Before leaving, the
     * last owner hands ownership over this way (D-19).
     *
     * @param owner the family ledger, as one of its owners
     * @return the member, an owner now
     * @throws NotFoundException if the ledger has no such member
     * @throws ConflictException if the member has no account, isn't ACTIVE, or is an owner already
     */
    @Transactional
    public FamilyMemberView makeOwner(LedgerScope owner, long memberId) {
        if (owner.role() != MemberRole.OWNER) {
            throw new IllegalArgumentException("Only an owner makes another member an owner");
        }
        jdbc.sql("SELECT id FROM ledger WHERE id = :ledgerId FOR UPDATE").param("ledgerId", owner.ledgerId())
                .query(Long.class).single();
        FamilyMemberView member = member(owner, memberId);
        if (member.status() == MemberStatus.FORMER) {
            throw new ConflictException("A former member stays as they are");
        }
        if (member.status() == MemberStatus.LEFT) {
            throw new ConflictException(member.displayName() + " has left the family budget");
        }
        if (!member.hasAccount()) {
            throw new ConflictException(member.displayName() + " has no account: only a member with an account can be "
                    + "an owner");
        }
        if (member.role() == MemberRole.OWNER) {
            throw new ConflictException(member.displayName() + " is an owner already");
        }
        jdbc.sql("UPDATE ledger_member SET role = 'OWNER' WHERE id = :memberId AND ledger_id = :ledgerId")
                .param("memberId", memberId).param("ledgerId", owner.ledgerId())
                .update();
        return member(owner, memberId);
    }

    /**
     * What "Delete all my data" would do to the user's family ledgers (D-20; ADR 0003 topic J's "F6a plan"), as
     * {@code release_family_memberships} does it: for each family ledger they are an ACTIVE member of, their role and
     * balance, and whether it is deleted (no other ACTIVE member with an account), passes to the ACTIVE member with an
     * account who joined earliest (they are its last owner), or stays; how many of their invites stop working; whether
     * the custom split rule goes back to equal shares. Nothing of a ledger they left but how many there are.
     *
     * @param personal the user's personal ledger
     * @param families the user's family ledgers, from {@code LedgerAccess.families}
     */
    @Transactional(readOnly = true)
    public Memberships deletionPreview(LedgerScope personal, List<LedgerScope> families) {
        List<MembershipImpact> impacts = new ArrayList<>();
        for (LedgerScope family : families) {
            record Ledger(String name, String baseCurrency, SplitRule rule, Integer share, boolean others,
                    boolean otherOwner, String earliest, int pending) {
            }
            Ledger ledger = jdbc.sql("""
                    SELECT l.name, l.base_currency, l.split_rule, me.share_bp,
                           EXISTS (SELECT FROM ledger_member o WHERE o.ledger_id = l.id AND o.id <> me.id
                                   AND o.status = 'ACTIVE' AND o.user_sub IS NOT NULL) AS others,
                           EXISTS (SELECT FROM ledger_member o WHERE o.ledger_id = l.id AND o.id <> me.id
                                   AND o.role = 'OWNER') AS other_owner,
                           (SELECT o.display_name FROM ledger_member o WHERE o.ledger_id = l.id AND o.id <> me.id
                                   AND o.status = 'ACTIVE' AND o.user_sub IS NOT NULL
                            ORDER BY o.join_date, o.id LIMIT 1) AS earliest,
                           (SELECT count(*) FROM ledger_invite i WHERE i.ledger_id = l.id
                                   AND i.created_by_member_id = me.id AND i.revoked_at IS NULL AND i.used_at IS NULL
                                   AND i.declined_at IS NULL AND i.expires_at > now()) AS pending
                    FROM ledger l JOIN ledger_member me ON me.ledger_id = l.id AND me.id = :memberId
                    WHERE l.id = :ledgerId""")
                    .param("ledgerId", family.ledgerId()).param("memberId", family.memberId())
                    .query((row, n) -> new Ledger(row.getString("name"), row.getString("base_currency"),
                            SplitRule.valueOf(row.getString("split_rule")), row.getObject("share_bp", Integer.class),
                            row.getBoolean("others"), row.getBoolean("other_owner"), row.getString("earliest"),
                            row.getInt("pending")))
                    .single();
            Outcome outcome = !ledger.others() ? Outcome.DELETED
                    : family.role() == MemberRole.OWNER && !ledger.otherOwner() ? Outcome.OWNERSHIP_PASSES
                    : Outcome.STAYS;
            List<CurrencyAmount> balances = records.balances(family).byCurrency().stream()
                    .map(inCurrency -> new CurrencyAmount(inCurrency.currency(), inCurrency.members().stream()
                            .filter(FamilyBalances.MemberBalance::you).findFirst().orElseThrow().balance()))
                    .toList();
            impacts.add(new MembershipImpact(family.ledgerId(), ledger.name(), family.role(), ledger.baseCurrency(),
                    balances.getFirst().amount(), outcome, outcome == Outcome.OWNERSHIP_PASSES ? ledger.earliest()
                            : null, ledger.pending(), outcome != Outcome.DELETED && ledger.rule() == SplitRule.CUSTOM
                                    && ledger.share() != null && ledger.share() > 0, balances));
        }
        impacts.sort(Comparator.comparing(MembershipImpact::name, String.CASE_INSENSITIVE_ORDER));
        long left = jdbc.sql("""
                SELECT count(*) FROM ledger_member WHERE user_sub = :sub AND ledger_type = 'SHARED' AND status = 'LEFT'""")
                .param("sub", personal.userId()).query(Long.class).single();
        return new Memberships(impacts, left);
    }

    private FamilyMemberView member(LedgerScope family, long memberId) {
        return jdbc.sql("""
                SELECT id, display_name, role, status, join_date, user_sub IS NOT NULL AS has_account, share_bp, left_date,
                       claimed_seat
                FROM ledger_member WHERE ledger_id = :ledgerId AND id = :memberId""")
                .param("ledgerId", family.ledgerId()).param("memberId", memberId)
                .query((row, n) -> new FamilyMemberView(row.getLong("id"), row.getString("display_name"),
                        MemberRole.valueOf(row.getString("role")), MemberStatus.valueOf(row.getString("status")),
                        row.getObject("join_date", LocalDate.class), row.getBoolean("has_account"),
                        row.getObject("share_bp", Integer.class), row.getObject("left_date", LocalDate.class), row.getBoolean("claimed_seat")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Member " + memberId + " not found"));
    }

    /**
     * The member the scope stands for leaves the family ledger (D-19, story B5). The last member with an account who
     * leaves deletes it (D-36).
     *
     * @param self the family ledger, as the member who leaves
     * @throws ConflictException with {@link #LAST_OWNER} if they are its last owner while another member with an
     *         account remains (D-19)
     */
    @Transactional
    public void leave(LedgerScope self) {
        end(self, self.memberId());
    }

    /**
     * An owner removes a member, with or without an account (D-15, D-19); themselves, as {@link #leave}.
     *
     * @param owner the family ledger, as one of its owners
     * @throws NotFoundException if the ledger has no such member
     * @throws ConflictException if the member has left already or is FORMER, or as {@link #leave}
     */
    @Transactional
    public void remove(LedgerScope owner, long memberId) {
        if (owner.role() != MemberRole.OWNER) {
            throw new IllegalArgumentException("Only an owner removes a member");
        }
        end(owner, memberId);
    }

    private void end(LedgerScope family, long memberId) {
        SplitRule rule = SplitRule.valueOf(jdbc.sql("SELECT split_rule FROM ledger WHERE id = :ledgerId FOR UPDATE")
                .param("ledgerId", family.ledgerId()).query(String.class).single());
        record Member(String name, MemberRole role, MemberStatus status, boolean hasAccount, Integer share) {
        }
        Member member = jdbc.sql("""
                SELECT display_name, role, status, user_sub IS NOT NULL AS has_account, share_bp
                FROM ledger_member WHERE ledger_id = :ledgerId AND id = :memberId""")
                .param("ledgerId", family.ledgerId()).param("memberId", memberId)
                .query((row, n) -> new Member(row.getString("display_name"), MemberRole.valueOf(row.getString("role")),
                        MemberStatus.valueOf(row.getString("status")), row.getBoolean("has_account"),
                        row.getObject("share_bp", Integer.class)))
                .optional()
                .orElseThrow(() -> new NotFoundException("Member " + memberId + " not found"));
        if (member.status() == MemberStatus.FORMER) {
            throw new ConflictException("A former member stays as they are");
        }
        if (member.status() == MemberStatus.LEFT) {
            throw new ConflictException(member.name() + " has left the family budget already");
        }
        boolean self = memberId == family.memberId();
        boolean othersWithAccount = jdbc.sql("""
                SELECT EXISTS (SELECT FROM ledger_member
                               WHERE ledger_id = :ledgerId AND id <> :memberId AND status = 'ACTIVE'
                                 AND user_sub IS NOT NULL)""")
                .param("ledgerId", family.ledgerId()).param("memberId", memberId).query(Boolean.class).single();
        if (member.role() == MemberRole.OWNER && othersWithAccount && !jdbc.sql("""
                SELECT EXISTS (SELECT FROM ledger_member
                               WHERE ledger_id = :ledgerId AND id <> :memberId AND role = 'OWNER')""")
                .param("ledgerId", family.ledgerId()).param("memberId", memberId).query(Boolean.class).single()) {
            throw new ConflictException((self ? "You are" : member.name() + " is") + " the family budget's last owner: "
                    + "make another member with an account an owner first", LAST_OWNER);
        }
        if (!member.hasAccount() && (member.share() == null || member.share() == 0) && !named(family, memberId)) {
            // Nothing names them, so nothing would freeze or read "a member who left": they go, with the invites
            // for their place (V9).
            jdbc.sql("DELETE FROM ledger_member WHERE id = :memberId AND ledger_id = :ledgerId")
                    .param("memberId", memberId).param("ledgerId", family.ledgerId())
                    .update();
            return;
        }
        if (member.hasAccount()) {
            posting.detach(family, memberId);
        }
        jdbc.sql("""
                UPDATE ledger_invite SET revoked_at = now()
                WHERE ledger_id = :ledgerId AND (created_by_member_id = :memberId OR seat_member_id = :memberId)
                  AND revoked_at IS NULL AND used_at IS NULL AND declined_at IS NULL AND expires_at > now()""")
                .param("ledgerId", family.ledgerId()).param("memberId", memberId)
                .update();
        if (rule == SplitRule.CUSTOM && member.share() != null && member.share() > 0) {
            // Nobody else may decide what the others' shares become (topic B): equal shares, journaled as a change
            // of the ledger's, not of a member's (topic H), as release_family_memberships does for D-20.
            jdbc.sql("UPDATE ledger SET split_rule = 'EQUAL' WHERE id = :ledgerId")
                    .param("ledgerId", family.ledgerId()).update();
            jdbc.sql("UPDATE ledger_member SET share_bp = NULL WHERE ledger_id = :ledgerId AND share_bp IS NOT NULL")
                    .param("ledgerId", family.ledgerId()).update();
            jdbc.sql("""
                    INSERT INTO family_record_change (ledger_id, about_member_id, action, changes)
                    VALUES (:ledgerId, :memberId, 'SPLIT_RULE_RESET',
                            jsonb_build_array(jsonb_build_object('field', 'splitRule', 'old', 'CUSTOM', 'new', 'EQUAL')))""")
                    .param("ledgerId", family.ledgerId()).param("memberId", memberId)
                    .update();
        }
        jdbc.sql("""
                UPDATE ledger_member SET status = 'LEFT', role = 'MEMBER', share_bp = NULL, left_date = :today
                WHERE id = :memberId AND ledger_id = :ledgerId""")
                .param("today", today.date()).param("memberId", memberId).param("ledgerId", family.ledgerId())
                .update();
        if (!othersWithAccount) {
            // Nobody with an account is left to see it: it is deleted with its records, as "Delete all my data" deletes
            // it (D-36, D-20; V10). The member was detached above, so nothing of theirs refers to it any more.
            jdbc.sql("SELECT delete_family_ledger(:ledgerId)").param("ledgerId", family.ledgerId())
                    .query().listOfRows();
        }
    }

    /** Whether a record names the member as payer, payee or with a share, deleted records included (their journal). */
    private boolean named(LedgerScope family, long memberId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT FROM family_record
                               WHERE ledger_id = :ledgerId AND (payer_member_id = :memberId OR payee_member_id = :memberId))
                    OR EXISTS (SELECT FROM family_share WHERE ledger_id = :ledgerId AND member_id = :memberId)""")
                .param("ledgerId", family.ledgerId()).param("memberId", memberId).query(Boolean.class).single();
    }
}
