package com.example.financetracker.ledger.family;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.access.MemberRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Family ledgers (D-2, ADR 0003, topics B and F), their settings, split rule and members. Every method takes the
 * {@link LedgerScope} that LedgerAccess resolved: the family ledger's, from {@code member} or, for what only owners
 * may do, {@code owner}; creating one takes the creator's personal ledger. Nothing here returns a sub (D-3).
 * <p>
 * Changes to a ledger's members or split rule lock its row first, so that they happen one after the other; the
 * database's deferred trigger checks the split rule at commit.
 */
@Service
public class FamilyLedgerService {

    private static final String LEDGER = """
            SELECT l.id, l.name, l.base_currency, l.split_rule, m.role, m.id AS member_id, l.created_at
            FROM ledger l JOIN ledger_member m ON m.ledger_id = l.id
            WHERE l.id = :ledgerId AND m.id = :memberId""";
    private static final String MEMBERS = """
            SELECT id, display_name, role, status, join_date, user_sub IS NOT NULL AS has_account, share_bp
            FROM ledger_member WHERE ledger_id = :ledgerId""";

    private final JdbcClient jdbc;
    private final LedgerAccess access;
    private final LedgerCategoryRepository categories;

    FamilyLedgerService(JdbcClient jdbc, LedgerAccess access, LedgerCategoryRepository categories) {
        this.jdbc = jdbc;
        this.access = access;
        this.categories = categories;
    }

    /**
     * Creates a family ledger, with its creator as its one member: OWNER, ACTIVE, joined today (D-15). Its category
     * dictionary starts with copies of the code, name and type of the creator's categories that the creator chose
     * (D-11); those personal categories and their postings stay as they are until F4a merges them.
     *
     * @param personal the creator's personal ledger
     * @param displayName the name the other members will see (D-3)
     * @param categoryIds the creator's categories to copy
     * @throws NotFoundException if one of the categories isn't in the creator's personal ledger
     */
    @Transactional
    public FamilyLedgerView create(LedgerScope personal, String name, String baseCurrency, String displayName,
            SplitRule rule, List<Long> categoryIds) {
        if (personal.type() != LedgerType.PERSONAL) {
            throw new IllegalArgumentException("A family ledger is created from its creator's personal ledger");
        }
        Set<Long> wanted = new LinkedHashSet<>(categoryIds);
        List<LedgerCategory> seed = wanted.isEmpty() ? List.of() : categories.lockAll(personal, wanted);
        if (seed.size() < wanted.size()) {
            Set<Long> found = new HashSet<>(seed.stream().map(LedgerCategory::id).toList());
            long missing = wanted.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw new NotFoundException("Category " + missing + " not found");
        }
        long ledgerId = jdbc.sql("""
                INSERT INTO ledger (type, name, base_currency, split_rule) VALUES ('SHARED', :name, :currency, :rule)
                RETURNING id""")
                .param("name", name).param("currency", baseCurrency).param("rule", rule.name())
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date,
                                           share_bp)
                VALUES (:ledgerId, 'SHARED', :sub, :displayName, 'OWNER', 'ACTIVE', current_date, :share)""")
                .param("ledgerId", ledgerId).param("sub", personal.userId()).param("displayName", displayName)
                .param("share", rule == SplitRule.CUSTOM ? 10_000 : null)
                .update();
        LedgerScope family = access.member(personal.userId(), ledgerId);
        seed.stream().sorted(Comparator.comparing(LedgerCategory::code)).forEach(category -> categories.save(
                new LedgerCategory(null, family.rowUserId(), ledgerId, category.code(), category.name(),
                        category.type(), null)));
        return get(family);
    }

    @Transactional(readOnly = true)
    public FamilyLedgerView get(LedgerScope family) {
        return jdbc.sql(LEDGER).param("ledgerId", family.ledgerId()).param("memberId", family.memberId())
                .query((row, n) -> new FamilyLedgerView(row.getLong("id"), row.getString("name"),
                        row.getString("base_currency"), SplitRule.valueOf(row.getString("split_rule")),
                        MemberRole.valueOf(row.getString("role")), row.getLong("member_id"),
                        row.getTimestamp("created_at").toInstant()))
                .single();
    }

    /**
     * Renames the ledger or changes its base currency; null leaves a field as it is. The base currency may change
     * while the ledger has no records (D-13), and none can exist before F4a, which adds that check.
     *
     * @param owner the ledger, as one of its owners
     */
    @Transactional
    public FamilyLedgerView update(LedgerScope owner, String name, String baseCurrency) {
        jdbc.sql("""
                UPDATE ledger SET name = coalesce(:name, name), base_currency = coalesce(:currency, base_currency)
                WHERE id = :ledgerId""")
                .param("name", name).param("currency", baseCurrency).param("ledgerId", owner.ledgerId())
                .update();
        return get(owner);
    }

    /** The ledger's members, by join date. */
    @Transactional(readOnly = true)
    public List<FamilyMemberView> members(LedgerScope family) {
        return jdbc.sql(MEMBERS + " ORDER BY join_date, id").param("ledgerId", family.ledgerId())
                .query(FamilyLedgerService::memberView)
                .list();
    }

    /**
     * Adds a member without an account (B1), who joins today. Under a CUSTOM split rule their share is 0 until an
     * owner changes the rule; equal shares follow the members by themselves.
     *
     * @param owner the ledger, as one of its owners
     * @throws ConflictException if a member who isn't FORMER has that name already, whatever its case
     */
    @Transactional
    public FamilyMemberView addMember(LedgerScope owner, String displayName) {
        SplitRule rule = lock(owner);
        requireFreeName(owner, displayName, null);
        long memberId = jdbc.sql("""
                INSERT INTO ledger_member (ledger_id, ledger_type, display_name, role, status, join_date, share_bp)
                VALUES (:ledgerId, 'SHARED', :displayName, 'MEMBER', 'ACTIVE', current_date, :share) RETURNING id""")
                .param("ledgerId", owner.ledgerId()).param("displayName", displayName)
                .param("share", rule == SplitRule.CUSTOM ? 0 : null)
                .query(Long.class).single();
        return member(owner, memberId);
    }

    /**
     * Renames a member without an account. A member with an account chooses their own name (D-3), and a FORMER one
     * stays "Former member" (D-20).
     *
     * @param owner the ledger, as one of its owners
     * @throws NotFoundException if the ledger has no such member
     * @throws ConflictException if the member has an account or is FORMER, or another has the name
     */
    @Transactional
    public FamilyMemberView renameMember(LedgerScope owner, long memberId, String displayName) {
        lock(owner);
        requireMemberWithoutAccount(member(owner, memberId), "they choose their own name");
        requireFreeName(owner, displayName, memberId);
        jdbc.sql("UPDATE ledger_member SET display_name = :displayName WHERE id = :memberId AND ledger_id = :ledgerId")
                .param("displayName", displayName).param("memberId", memberId).param("ledgerId", owner.ledgerId())
                .update();
        return member(owner, memberId);
    }

    /**
     * Removes a member without an account. Allowed while the member has no shares, which F4a checks once records
     * exist; under a CUSTOM split rule the member's share must be 0 first, so that the others' still sum to 10000.
     *
     * @param owner the ledger, as one of its owners
     * @throws NotFoundException if the ledger has no such member
     * @throws ConflictException if the member has an account or is FORMER, or a custom share above 0
     */
    @Transactional
    public void removeMember(LedgerScope owner, long memberId) {
        lock(owner);
        FamilyMemberView member = member(owner, memberId);
        requireMemberWithoutAccount(member, "only members without an account can be removed so far");
        if (member.share() != null && member.share() > 0) {
            throw new ConflictException(("%s has a share of %d basis points in the custom split rule; change the rule "
                    + "to give them 0 first").formatted(member.displayName(), member.share()));
        }
        jdbc.sql("DELETE FROM ledger_member WHERE id = :memberId AND ledger_id = :ledgerId")
                .param("memberId", memberId).param("ledgerId", owner.ledgerId())
                .update();
    }

    /**
     * Sets the default split rule (D-12). Under CUSTOM, {@code shares} gives each member that is ACTIVE (not LEFT or
     * FORMER) a share in basis points, and they sum to exactly 10000; under EQUAL it is empty.
     *
     * @param owner the ledger, as one of its owners
     * @param shares by member id
     * @return the members with their new shares
     * @throws RuleViolationException listing every way the shares don't fit the rule and the members
     */
    @Transactional
    public List<FamilyMemberView> setSplitRule(LedgerScope owner, SplitRule rule, Map<Long, Integer> shares) {
        lock(owner);
        List<FamilyMemberView> members = members(owner);
        List<String> violations = new ArrayList<>();
        if (rule == SplitRule.EQUAL) {
            if (!shares.isEmpty()) {
                violations.add("an equal split takes no shares");
            }
        } else {
            Map<Long, FamilyMemberView> active = new LinkedHashMap<>();
            members.stream().filter(m -> m.status() == MemberStatus.ACTIVE).forEach(m -> active.put(m.id(), m));
            shares.keySet().stream().filter(id -> !active.containsKey(id)).sorted()
                    .forEach(id -> violations.add("member %d is not an active member of the family ledger"
                            .formatted(id)));
            active.values().stream().filter(m -> !shares.containsKey(m.id()))
                    .forEach(m -> violations.add("%s (member %d) has no share".formatted(m.displayName(), m.id())));
            long total = shares.values().stream().mapToLong(Integer::longValue).sum();
            if (total != 10_000) {
                violations.add("the shares sum to %d basis points, not 10000".formatted(total));
            }
        }
        if (!violations.isEmpty()) {
            throw new RuleViolationException(violations);
        }
        jdbc.sql("UPDATE ledger SET split_rule = :rule WHERE id = :ledgerId")
                .param("rule", rule.name()).param("ledgerId", owner.ledgerId())
                .update();
        for (FamilyMemberView member : members) {
            jdbc.sql("UPDATE ledger_member SET share_bp = :share WHERE id = :memberId AND ledger_id = :ledgerId")
                    .param("share", rule == SplitRule.CUSTOM ? shares.get(member.id()) : null)
                    .param("memberId", member.id()).param("ledgerId", owner.ledgerId())
                    .update();
        }
        return members(owner);
    }

    /** Locks the ledger's row until the transaction ends, and returns its split rule. */
    private SplitRule lock(LedgerScope family) {
        return SplitRule.valueOf(jdbc.sql("SELECT split_rule FROM ledger WHERE id = :ledgerId FOR UPDATE")
                .param("ledgerId", family.ledgerId()).query(String.class).single());
    }

    private FamilyMemberView member(LedgerScope family, long memberId) {
        return jdbc.sql(MEMBERS + " AND id = :memberId").param("ledgerId", family.ledgerId())
                .param("memberId", memberId)
                .query(FamilyLedgerService::memberView)
                .optional()
                .orElseThrow(() -> new NotFoundException("Member " + memberId + " not found"));
    }

    private void requireFreeName(LedgerScope family, String displayName, Long except) {
        boolean taken = jdbc.sql("""
                SELECT EXISTS (SELECT FROM ledger_member
                               WHERE ledger_id = :ledgerId AND status <> 'FORMER' AND lower(display_name) = lower(:name)
                                 AND id IS DISTINCT FROM :except)""")
                .param("ledgerId", family.ledgerId()).param("name", displayName).param("except", except)
                .query(Boolean.class).single();
        if (taken) {
            throw new ConflictException("The family ledger has a member named %s already".formatted(displayName));
        }
    }

    /**
     * @param ifAccount what to say if the member has an account
     */
    private static void requireMemberWithoutAccount(FamilyMemberView member, String ifAccount) {
        if (member.status() == MemberStatus.FORMER) {
            throw new ConflictException("A former member stays as they are");
        }
        if (member.hasAccount()) {
            throw new ConflictException(member.displayName() + " has an account: " + ifAccount);
        }
    }

    private static FamilyMemberView memberView(ResultSet row, int n) throws SQLException {
        return new FamilyMemberView(row.getLong("id"), row.getString("display_name"),
                MemberRole.valueOf(row.getString("role")), MemberStatus.valueOf(row.getString("status")),
                row.getObject("join_date", LocalDate.class), row.getBoolean("has_account"),
                row.getObject("share_bp", Integer.class));
    }
}
