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
import com.example.financetracker.ledger.RuleViolationException.Violation;
import com.example.financetracker.ledger.Today;
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

    /** The codes of the split rule's violations (D-12), with the member each is about where there is one. */
    public static final String SHARES_UNDER_EQUAL = "SHARES_UNDER_EQUAL";
    public static final String NOT_ACTIVE_MEMBER = "NOT_ACTIVE_MEMBER";
    public static final String NO_SHARE = "NO_SHARE";
    public static final String DUPLICATE_SHARE = "DUPLICATE_SHARE";
    public static final String SUM_NOT_WHOLE = "SUM_NOT_WHOLE";
    /** A start date after today (D-27). */
    public static final String START_DATE = "START_DATE";

    private static final String LEDGER = """
            SELECT l.id, l.name, l.base_currency, l.split_rule, m.role, m.id AS member_id, l.created_at, l.start_date
            FROM ledger l JOIN ledger_member m ON m.ledger_id = l.id
            WHERE l.id = :ledgerId AND m.id = :memberId""";
    private static final String MEMBERS = """
            SELECT id, display_name, role, status, join_date, user_sub IS NOT NULL AS has_account, share_bp, left_date
            FROM ledger_member WHERE ledger_id = :ledgerId""";

    private final JdbcClient jdbc;
    private final LedgerAccess access;
    private final LedgerCategoryRepository categories;
    private final Today today;

    FamilyLedgerService(JdbcClient jdbc, LedgerAccess access, LedgerCategoryRepository categories, Today today) {
        this.jdbc = jdbc;
        this.access = access;
        this.categories = categories;
        this.today = today;
    }

    /**
     * Creates a family ledger, with its creator as its one member: OWNER, ACTIVE, joined on the ledger's start date
     * (D-15, D-27). The personal categories the creator chose become its category dictionary (D-11, merged since F4a):
     * a family category with each one's code, name and type; the creator's postings on the personal one move to it,
     * and the personal category goes. Their entries keep their versions: only the category's id changes, and a form
     * still holding the old one gets 422 for a category that doesn't exist. Family ledgers created before F4a were not
     * merged and keep their copies.
     *
     * @param personal the creator's personal ledger
     * @param startDate the first day records may be dated (D-27); null for today. Never after today.
     * @param displayName the name the other members will see (D-3)
     * @param categoryIds the creator's categories to copy
     * @throws NotFoundException if one of the categories isn't in the creator's personal ledger
     * @throws RuleViolationException if the start date is after today
     */
    @Transactional
    public FamilyLedgerView create(LedgerScope personal, String name, String baseCurrency, LocalDate startDate,
            String displayName, SplitRule rule, List<Long> categoryIds) {
        if (personal.type() != LedgerType.PERSONAL) {
            throw new IllegalArgumentException("A family ledger is created from its creator's personal ledger");
        }
        LocalDate now = today.date();
        LocalDate starts = startDate == null ? now : startDate;
        if (starts.isAfter(now)) {
            throw RuleViolationException.of(List.of(new Violation(START_DATE, null,
                    "the start date %s is in the future; a family budget starts today or earlier".formatted(starts))));
        }
        Set<Long> wanted = new LinkedHashSet<>(categoryIds);
        List<LedgerCategory> seed = wanted.isEmpty() ? List.of() : categories.lockAll(personal, wanted);
        if (seed.size() < wanted.size()) {
            Set<Long> found = new HashSet<>(seed.stream().map(LedgerCategory::id).toList());
            long missing = wanted.stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
            throw new NotFoundException("Category " + missing + " not found");
        }
        long ledgerId = jdbc.sql("""
                INSERT INTO ledger (type, name, base_currency, split_rule, start_date)
                VALUES ('SHARED', :name, :currency, :rule, :starts)
                RETURNING id""")
                .param("name", name).param("currency", baseCurrency).param("rule", rule.name())
                .param("starts", starts)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date,
                                           share_bp)
                VALUES (:ledgerId, 'SHARED', :sub, :displayName, 'OWNER', 'ACTIVE', :starts, :share)""")
                .param("ledgerId", ledgerId).param("sub", personal.userId()).param("displayName", displayName)
                .param("starts", starts)
                .param("share", rule == SplitRule.CUSTOM ? 10_000 : null)
                .update();
        LedgerScope family = access.member(personal.userId(), ledgerId);
        seed.stream().sorted(Comparator.comparing(LedgerCategory::code)).forEach(category -> merge(personal, category,
                categories.save(new LedgerCategory(null, family.rowUserId(), ledgerId, category.code(),
                        category.name(), category.type(), null))));
        return get(family);
    }

    /**
     * Moves the member's postings from their personal category to the family category that replaces it, and deletes
     * the personal one (D-11): the creator's at creation, and a joining member's at acceptance (F5). The posting
     * trigger lets them use it: the member is ACTIVE by now (V7).
     */
    void merge(LedgerScope personal, LedgerCategory mine, LedgerCategory family) {
        jdbc.sql("""
                UPDATE posting SET category_id = :family
                WHERE category_id = :mine AND entry_id IN (SELECT id FROM journal_entry WHERE ledger_id = :ledgerId)""")
                .param("family", family.id()).param("mine", mine.id()).param("ledgerId", personal.ledgerId())
                .update();
        jdbc.sql("DELETE FROM category WHERE id = :mine AND ledger_id = :ledgerId")
                .param("mine", mine.id()).param("ledgerId", personal.ledgerId())
                .update();
    }

    @Transactional(readOnly = true)
    public FamilyLedgerView get(LedgerScope family) {
        return jdbc.sql(LEDGER).param("ledgerId", family.ledgerId()).param("memberId", family.memberId())
                .query((row, n) -> new FamilyLedgerView(row.getLong("id"), row.getString("name"),
                        row.getString("base_currency"), SplitRule.valueOf(row.getString("split_rule")),
                        MemberRole.valueOf(row.getString("role")), row.getLong("member_id"),
                        row.getTimestamp("created_at").toInstant(), row.getObject("start_date", LocalDate.class)))
                .single();
    }

    /**
     * Renames the ledger or changes its base currency; null leaves a field as it is. The base currency may change
     * only while the ledger has no records (D-13): shares and balances are in it.
     *
     * @param owner the ledger, as one of its owners
     * @throws ConflictException if the base currency changes after the first record
     */
    @Transactional
    public FamilyLedgerView update(LedgerScope owner, String name, String baseCurrency) {
        lock(owner);
        if (baseCurrency != null && !baseCurrency.equals(get(owner).baseCurrency()) && jdbc.sql(
                "SELECT EXISTS (SELECT FROM family_record WHERE ledger_id = :ledgerId)")
                .param("ledgerId", owner.ledgerId()).query(Boolean.class).single()) {
            throw new ConflictException("The base currency of a family budget can't change once it has a record: its "
                    + "shares and balances are in it");
        }
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
                VALUES (:ledgerId, 'SHARED', :displayName, 'MEMBER', 'ACTIVE', :today, :share) RETURNING id""")
                .param("ledgerId", owner.ledgerId()).param("displayName", displayName).param("today", today.date())
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
     * Changes the name the other members see for the member the scope stands for: an ACTIVE member with an account,
     * which is what {@code LedgerAccess.member} lets in (D-3). The same checks as for any display name.
     *
     * @param self the ledger, as the member who renames themselves
     * @throws ConflictException if another member who isn't FORMER has the name, whatever its case
     */
    @Transactional
    public FamilyMemberView renameSelf(LedgerScope self, String displayName) {
        lock(self);
        requireFreeName(self, displayName, self.memberId());
        jdbc.sql("UPDATE ledger_member SET display_name = :displayName WHERE id = :memberId AND ledger_id = :ledgerId")
                .param("displayName", displayName).param("memberId", self.memberId())
                .param("ledgerId", self.ledgerId())
                .update();
        return member(self, self.memberId());
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
        List<Violation> violations = new ArrayList<>();
        if (rule == SplitRule.EQUAL) {
            if (!shares.isEmpty()) {
                violations.add(new Violation(SHARES_UNDER_EQUAL, null, "an equal split takes no shares"));
            }
        } else {
            Map<Long, FamilyMemberView> active = new LinkedHashMap<>();
            members.stream().filter(m -> m.status() == MemberStatus.ACTIVE).forEach(m -> active.put(m.id(), m));
            shares.keySet().stream().filter(id -> !active.containsKey(id)).sorted()
                    .forEach(id -> violations.add(notActive(id)));
            active.values().stream().filter(m -> !shares.containsKey(m.id()))
                    .forEach(m -> violations.add(new Violation(NO_SHARE, m.id(),
                            "%s has no share".formatted(m.displayName()))));
            long total = shares.values().stream().mapToLong(Integer::longValue).sum();
            if (total != BasisPoints.WHOLE) {
                violations.add(sumNotWhole(total));
            }
        }
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
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

    /** A share for someone who isn't an ACTIVE member of the ledger, or no member at all. */
    public static Violation notActive(long memberId) {
        return new Violation(NOT_ACTIVE_MEMBER, memberId,
                "Member %d is not an active member of the family budget".formatted(memberId));
    }

    /** Shares that don't sum to exactly 100.00 %. */
    public static Violation sumNotWhole(long total) {
        return new Violation(SUM_NOT_WHOLE, null, "the shares sum to %s, not %s".formatted(BasisPoints.percent(total),
                BasisPoints.percent(BasisPoints.WHOLE)));
    }

    /** One member named twice. */
    public static Violation duplicate(long memberId) {
        return new Violation(DUPLICATE_SHARE, memberId, "Member %d has more than one share".formatted(memberId));
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
            throw new ConflictException("The family budget has a member named %s already".formatted(displayName));
        }
    }

    /**
     * @param ifAccount what to say if the member has an account
     */
    private static void requireMemberWithoutAccount(FamilyMemberView member, String ifAccount) {
        if (member.status() == MemberStatus.FORMER) {
            throw new ConflictException("A former member stays as they are");
        }
        if (member.status() == MemberStatus.LEFT) {
            throw new ConflictException(member.displayName() + " has left the family budget");
        }
        if (member.hasAccount()) {
            throw new ConflictException(member.displayName() + " has an account: " + ifAccount);
        }
    }

    private static FamilyMemberView memberView(ResultSet row, int n) throws SQLException {
        return new FamilyMemberView(row.getLong("id"), row.getString("display_name"),
                MemberRole.valueOf(row.getString("role")), MemberStatus.valueOf(row.getString("status")),
                row.getObject("join_date", LocalDate.class), row.getBoolean("has_account"),
                row.getObject("share_bp", Integer.class), row.getObject("left_date", LocalDate.class));
    }
}
