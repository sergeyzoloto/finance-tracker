package com.example.financetracker.ledger.family;

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
 * owner, and the split rule's fall back to EQUAL when the member who goes had a custom share. Every change locks the
 * family ledger's row first, as the other changes of its members do.
 * <p>
 * A member who goes becomes LEFT on today's date, a MEMBER (only ACTIVE members are owners) without a custom share. A
 * member with an account is detached first, through the posting service's writer: their links detached, the family
 * categories their personal ledger refers to copied into it as personal ones (D-33), their debt account an ordinary
 * liability. Nothing is posted, to them or to anyone else, so D-10 holds for the ACTIVE members as it did; the records
 * that involve the member who left are frozen, so their family balance stays what their debt account showed. Their
 * pending invites, and those for their place, are revoked. A member without an account whom nothing names, as payer,
 * payee or with a share, and whose custom share is 0 or none, is deleted instead, as before F6a.
 */
@Service
public class FamilyMembershipService {

    /** The code of the 409 for the last owner who would leave while another member with an account remains. */
    public static final String LAST_OWNER = "LAST_OWNER";

    private final JdbcClient jdbc;
    private final FamilyPostingService posting;
    private final Today today;

    FamilyMembershipService(JdbcClient jdbc, FamilyPostingService posting, Today today) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.today = today;
    }

    /**
     * The member the scope stands for leaves the family ledger (D-19, story B5).
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
            // Nobody with an account is left to see it (D-19).
            jdbc.sql("UPDATE ledger SET archived_at = now() WHERE id = :ledgerId")
                    .param("ledgerId", family.ledgerId()).update();
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
