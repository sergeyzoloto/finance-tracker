package com.example.financetracker.ledger.family.posting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.family.posting.PostedEntry.Line;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only code that writes into a personal ledger it has no {@link LedgerScope} for (D-8; ADR 0003, topic E): the
 * members' posted entries of a family ledger, their links, and the accounts they post to, their "Debt to family
 * budget" and "Payments without a specified account". Only {@link FamilyPostingService} uses it
 * ({@code ArchitectureTests}), with the family ledger's scope of the member who acts.
 * <p>
 * Before it writes an entry, it checks every line against the member's accounts that D-8 allows: their debt account for
 * this family ledger; UNALLOCATED with one of the family's categories, for a share; "Payments without a specified
 * account", for a payment or a settlement; OPENING_BALANCE, for an opening balance or a correction; and the payer's
 * own account for their payment, and their FX_EXCHANGE for one in another currency than the base currency (F4e), only
 * when the payer is the member who acts (D-14). Nothing else: not another user's
 * cards, other accounts or personal categories. Its one other operation is the detach of a member who leaves or is
 * removed ({@link #detach}, D-19), which creates personal categories in their ledger and moves references to them. The database's triggers (V7) check the same while
 * {@code app.writer} is {@code family-posting}, which the writer sets for its own statements only.
 */
@Component
class CrossLedgerWriter {

    static final String DEBT_CODE = "FAMILY_DEBT_";
    static final String PLACEHOLDER_CODE = "UNSPECIFIED_PAYMENTS";
    static final String PLACEHOLDER_NAME = "Payments without a specified account";
    static final String FX_CODE = "FX_EXCHANGE";

    private static final Set<LinkType> PAYMENTS = Set.of(LinkType.PAYMENT, LinkType.SETTLEMENT);
    /** The longest category code (V2). */
    private static final int CODE_LENGTH = 50;

    private final JdbcClient jdbc;

    CrossLedgerWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The personal ledger of an ACTIVE member with an account, and the accounts D-8 lets the writer post to there. */
    record MemberLedger(long ledgerId, String sub, Long debt, Long placeholder, Long unallocated, Long openingBalance,
            Long fxExchange) {
    }

    /** A link of a record that isn't detached, and its entry. */
    record Link(long id, Long entryId, long memberId, LinkType type, boolean systemOwned) {
    }

    /**
     * @throws IllegalStateException if the member isn't an ACTIVE member with an account of the family ledger
     */
    MemberLedger memberLedger(LedgerScope family, long memberId) {
        record Found(long ledgerId, String sub) {
        }
        Found found = jdbc.sql("""
                SELECT p.ledger_id, m.user_sub AS sub
                FROM ledger_member m
                JOIN ledger_member p ON p.user_sub = m.user_sub AND p.ledger_type = 'PERSONAL'
                WHERE m.ledger_id = :familyId AND m.id = :memberId AND m.status = 'ACTIVE'""")
                .param("familyId", family.ledgerId()).param("memberId", memberId)
                .query((row, n) -> new Found(row.getLong("ledger_id"), row.getString("sub")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Member %d of family ledger %d has no personal ledger".formatted(memberId, family.ledgerId())));
        return new MemberLedger(found.ledgerId(), found.sub(),
                accountId(family, found.ledgerId(), "family_ledger_id = :familyId", null),
                accountId(family, found.ledgerId(), "code = :code AND is_system", PLACEHOLDER_CODE),
                accountId(family, found.ledgerId(), "code = :code", "UNALLOCATED"),
                accountId(family, found.ledgerId(), "code = :code", "OPENING_BALANCE"),
                accountId(family, found.ledgerId(), "code = :code AND is_system", FX_CODE));
    }

    /**
     * The member's FX_EXCHANGE, the system account of every personal ledger (rule 4), for a side in another currency
     * than the base currency (rule 9).
     *
     * @throws IllegalStateException if their ledger has none
     */
    long fxExchange(LedgerScope family, long memberId) {
        Long fx = memberLedger(family, memberId).fxExchange();
        if (fx == null) {
            throw new IllegalStateException("Member %d's personal ledger has no FX_EXCHANGE".formatted(memberId));
        }
        return fx;
    }

    /**
     * The member's OPENING_BALANCE, the system account of every personal ledger, for an opening balance (F5, D-18).
     *
     * @throws IllegalStateException if their ledger has none
     */
    long openingBalance(LedgerScope family, long memberId) {
        Long opening = memberLedger(family, memberId).openingBalance();
        if (opening == null) {
            throw new IllegalStateException("Member %d's personal ledger has no OPENING_BALANCE".formatted(memberId));
        }
        return opening;
    }

    /**
     * The member's "Debt to family budget" for this family ledger, created on first need: a system LIABILITY in the
     * family's base currency, code {@code FAMILY_DEBT_<ledger id>}, named after the family (topic E). If the member
     * has an account of their own with that code, the next free {@code _2}, {@code _3} and so on.
     */
    long debtAccount(LedgerScope family, long memberId) {
        MemberLedger member = memberLedger(family, memberId);
        if (member.debt() != null) {
            return member.debt();
        }
        record Family(String name, String baseCurrency) {
        }
        Family ledger = jdbc.sql("SELECT name, base_currency FROM ledger WHERE id = :familyId")
                .param("familyId", family.ledgerId())
                .query((row, n) -> new Family(row.getString("name"), row.getString("base_currency")))
                .single();
        String name = "Debt to family budget: " + ledger.name();
        for (int n = 1; n <= 100; n++) {
            String code = DEBT_CODE + family.ledgerId() + (n == 1 ? "" : "_" + n);
            asWriter(family, null, () -> jdbc.sql("""
                    INSERT INTO account (user_id, ledger_id, code, name, type, default_currency, is_system,
                                         family_ledger_id)
                    VALUES (:sub, :ledgerId, :code, :name, 'LIABILITY', :currency, TRUE, :familyId)
                    ON CONFLICT DO NOTHING""")
                    .param("sub", member.sub()).param("ledgerId", member.ledgerId()).param("code", code)
                    .param("name", name.length() > 100 ? name.substring(0, 100) : name)
                    .param("currency", ledger.baseCurrency()).param("familyId", family.ledgerId())
                    .update());
            Long created = accountId(family, member.ledgerId(), "family_ledger_id = :familyId", null);
            if (created != null) {
                return created;
            }
        }
        throw new IllegalStateException("No free code for the debt account of member " + memberId);
    }

    /**
     * The member's "Payments without a specified account", created on first need: a system ASSET, code
     * {@code UNSPECIFIED_PAYMENTS} (topic E).
     *
     * @throws ConflictException if the member has an account of their own with that code
     */
    long placeholder(LedgerScope family, long memberId) {
        MemberLedger member = memberLedger(family, memberId);
        if (member.placeholder() != null) {
            return member.placeholder();
        }
        asWriter(family, null, () -> jdbc.sql("""
                INSERT INTO account (user_id, ledger_id, code, name, type, is_system)
                VALUES (:sub, :ledgerId, :code, :name, 'ASSET', TRUE)
                ON CONFLICT DO NOTHING""")
                .param("sub", member.sub()).param("ledgerId", member.ledgerId()).param("code", PLACEHOLDER_CODE)
                .param("name", PLACEHOLDER_NAME)
                .update());
        Long created = accountId(family, member.ledgerId(), "code = :code AND is_system", PLACEHOLDER_CODE);
        if (created == null) {
            throw new ConflictException(("You have an account with the code %s; rename the family budget's "
                    + "\"%s\" needs that code").formatted(PLACEHOLDER_CODE, PLACEHOLDER_NAME));
        }
        return created;
    }

    /** The record's links that aren't detached. */
    List<Link> links(LedgerScope family, long recordId) {
        return jdbc.sql("""
                SELECT id, entry_id, member_id, link_type, system_owned FROM family_entry_link
                WHERE family_ledger_id = :familyId AND record_id = :recordId AND detached_at IS NULL
                ORDER BY id""")
                .param("familyId", family.ledgerId()).param("recordId", recordId)
                .query((row, n) -> new Link(row.getLong("id"), row.getObject("entry_id", Long.class),
                        row.getLong("member_id"), LinkType.valueOf(row.getString("link_type")),
                        row.getBoolean("system_owned")))
                .list();
    }

    /** The member's opening balance's link that isn't detached (F5), or null. */
    Link openingLink(LedgerScope family, long memberId) {
        return jdbc.sql("""
                SELECT id, entry_id, member_id, link_type, system_owned FROM family_entry_link
                WHERE family_ledger_id = :familyId AND member_id = :memberId AND link_type = 'OPENING_BALANCE'
                  AND detached_at IS NULL""")
                .param("familyId", family.ledgerId()).param("memberId", memberId)
                .query((row, n) -> new Link(row.getLong("id"), row.getObject("entry_id", Long.class),
                        row.getLong("member_id"), LinkType.valueOf(row.getString("link_type")),
                        row.getBoolean("system_owned")))
                .optional().orElse(null);
    }

    /**
     * Whether the link's entry has the date, the memo, the owner and the lines of the wanted one, so that re-posting
     * leaves it.
     */
    boolean holds(LedgerScope family, Link link, PostedEntry wanted) {
        if (link.entryId() == null) {
            return false;
        }
        List<LocalDate> dates = new ArrayList<>();
        List<String> memos = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        jdbc.sql("""
                SELECT e.entry_date, e.memo, p.account_id, p.currency, p.amount, p.category_id
                FROM family_entry_link l
                JOIN journal_entry e ON e.id = l.entry_id
                JOIN posting p ON p.entry_id = e.id
                WHERE l.id = :linkId AND l.family_ledger_id = :familyId
                ORDER BY p.line_no""")
                .param("linkId", link.id()).param("familyId", family.ledgerId())
                .query(row -> {
                    dates.add(row.getObject("entry_date", LocalDate.class));
                    memos.add(row.getString("memo"));
                    lines.add(new Line(row.getLong("account_id"), row.getString("currency"),
                            row.getBigDecimal("amount"), row.getObject("category_id", Long.class)));
                });
        return !dates.isEmpty() && wanted.sameAs(dates.getFirst(), memos.getFirst(), link.systemOwned(), lines);
    }

    /**
     * A payment's side of the payer: the account it is paid from, or "Payments without a specified account", the
     * payer's note on it, and the amount on that account in its currency (above 0; F4e).
     */
    record PaymentSide(Long accountId, boolean later, String memo, BigDecimal amount, String currency) {
    }

    /**
     * The side of the payment or settlement that the link names: the line that is neither on the debt account nor on
     * FX_EXCHANGE.
     */
    PaymentSide paymentSide(LedgerScope family, Link link) {
        if (!PAYMENTS.contains(link.type()) || link.entryId() == null) {
            throw new IllegalStateException("Link %d is not a payment's".formatted(link.id()));
        }
        return jdbc.sql("""
                SELECT a.id, a.code = :placeholder AND a.is_system AS later, e.memo, abs(p.amount) AS amount, p.currency
                FROM family_entry_link l
                JOIN journal_entry e ON e.id = l.entry_id
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                WHERE l.id = :linkId AND l.family_ledger_id = :familyId AND a.family_ledger_id IS NULL
                  AND NOT (a.code = :fx AND a.is_system)""")
                .param("placeholder", PLACEHOLDER_CODE).param("fx", FX_CODE).param("linkId", link.id())
                .param("familyId", family.ledgerId())
                .query((row, n) -> new PaymentSide(row.getLong("id"), row.getBoolean("later"), row.getString("memo"),
                        row.getBigDecimal("amount"), row.getString("currency")))
                .single();
    }

    /** Writes the entry into the member's personal ledger, with its link. */
    void write(LedgerScope family, PostedEntry entry) {
        MemberLedger member = memberLedger(family, entry.memberId());
        check(family, entry, member);
        asWriter(family, ownLedger(family, entry, member), () -> {
            long entryId = jdbc.sql("""
                    INSERT INTO journal_entry (user_id, ledger_id, entry_date, kind, memo)
                    VALUES (:sub, :ledgerId, :date, :kind, :memo) RETURNING id""")
                    .param("sub", member.sub()).param("ledgerId", member.ledgerId()).param("date", entry.date())
                    .param("kind", entry.kind().name()).param("memo", entry.memo())
                    .query(Long.class).single();
            insertLines(family, entryId, entry);
            return jdbc.sql("""
                    INSERT INTO family_entry_link (entry_id, family_ledger_id, member_id, record_id, link_type,
                                                   system_owned)
                    VALUES (:entryId, :familyId, :memberId, :recordId, :link, :systemOwned)""")
                    .param("entryId", entryId).param("familyId", family.ledgerId())
                    .param("memberId", entry.memberId()).param("recordId", entry.recordId())
                    .param("link", entry.link().name()).param("systemOwned", entry.systemOwned())
                    .update();
        });
    }

    /**
     * Replaces the link's entry's date, memo and lines with the wanted ones: a new version of the same entry. A payment
     * moved from "Payments without a specified account" to the payer's own account becomes theirs, and back (D-14).
     */
    void replace(LedgerScope family, Link link, PostedEntry entry) {
        MemberLedger member = memberLedger(family, entry.memberId());
        if (link.memberId() != entry.memberId() || link.type() != entry.link() || link.entryId() == null) {
            throw new IllegalStateException("Link %d is not for this entry".formatted(link.id()));
        }
        requireOwnIfTheirs(family, link);
        check(family, entry, member);
        asWriter(family, ownLedger(family, entry, member), () -> {
            jdbc.sql("""
                    UPDATE journal_entry SET entry_date = :date, memo = :memo, version = version + 1, updated_at = now()
                    WHERE id = :entryId AND ledger_id = :ledgerId""")
                    .param("date", entry.date()).param("memo", entry.memo()).param("entryId", link.entryId())
                    .param("ledgerId", member.ledgerId())
                    .update();
            if (link.systemOwned() != entry.systemOwned()) {
                jdbc.sql("""
                        UPDATE family_entry_link SET system_owned = :systemOwned
                        WHERE id = :linkId AND family_ledger_id = :familyId""")
                        .param("systemOwned", entry.systemOwned()).param("linkId", link.id())
                        .param("familyId", family.ledgerId())
                        .update();
            }
            jdbc.sql("DELETE FROM posting WHERE entry_id = :entryId").param("entryId", link.entryId()).update();
            insertLines(family, link.entryId(), entry);
            return null;
        });
    }

    /** Deletes the link and its entry, with the entry's postings. */
    void delete(LedgerScope family, Link link) {
        requireOwnIfTheirs(family, link);
        asWriter(family, null, () -> {
            if (link.entryId() != null) {
                jdbc.sql("""
                        DELETE FROM journal_entry e USING family_entry_link l
                        WHERE e.id = l.entry_id AND l.id = :linkId AND l.family_ledger_id = :familyId""")
                        .param("linkId", link.id()).param("familyId", family.ledgerId())
                        .update();
            }
            return jdbc.sql("DELETE FROM family_entry_link WHERE id = :linkId AND family_ledger_id = :familyId")
                    .param("linkId", link.id()).param("familyId", family.ledgerId())
                    .update();
        });
    }

    /**
     * Detaches the member from the family ledger (D-19, D-33; ADR 0003, topic E), while they are still ACTIVE: the
     * only other thing this writer does besides records' entries. In their personal ledger:
     * <ol>
     * <li>their links to the family ledger are detached and no longer system-owned, so the posted entries become
     * ordinary personal entries of theirs;
     * <li>for every family category their personal ledger refers to, a personal category with its code and type and
     * the family category's current name, unless they have one with that code and type already (the merged ones were
     * deleted, D-33); if their code is taken by a personal category of the other type (D-30), the copy's code gets
     * {@code _2}, {@code _3} and so on; then the references move to it;
     * <li>their debt account for the family ledger stops naming it and is no longer a system account: an ordinary
     * liability, named after the family, that keeps its balance.
     * </ol>
     * Afterwards no row of their personal ledger references the family ledger. Nothing is posted.
     *
     * @return the personal categories the references moved to, by the family category's id
     */
    Map<Long, Long> detach(LedgerScope family, long memberId) {
        MemberLedger member = memberLedger(family, memberId);
        asWriter(family, null, () -> jdbc.sql("""
                UPDATE family_entry_link SET detached_at = now(), system_owned = FALSE
                WHERE family_ledger_id = :familyId AND member_id = :memberId AND detached_at IS NULL""")
                .param("familyId", family.ledgerId()).param("memberId", memberId)
                .update());
        record Used(long id, String code, String name, String type) {
        }
        List<Used> used = jdbc.sql("""
                SELECT DISTINCT c.id, c.code, c.name, c.type
                FROM posting p
                JOIN journal_entry e ON e.id = p.entry_id
                JOIN category c ON c.id = p.category_id
                WHERE e.ledger_id = :personalId AND c.ledger_id = :familyId
                ORDER BY c.code""")
                .param("personalId", member.ledgerId()).param("familyId", family.ledgerId())
                .query((row, n) -> new Used(row.getLong("id"), row.getString("code"), row.getString("name"),
                        row.getString("type")))
                .list();
        Map<Long, Long> copies = new LinkedHashMap<>();
        for (Used category : used) {
            Long copy = jdbc.sql("""
                    SELECT id FROM category WHERE ledger_id = :personalId AND code = :code AND type = :type""")
                    .param("personalId", member.ledgerId()).param("code", category.code())
                    .param("type", category.type())
                    .query(Long.class).optional().orElse(null);
            for (int n = 1; copy == null && n <= 100; n++) {
                String suffix = n == 1 ? "" : "_" + n;
                String code = category.code().substring(0, Math.min(category.code().length(),
                        CODE_LENGTH - suffix.length())) + suffix;
                copy = jdbc.sql("""
                        INSERT INTO category (user_id, ledger_id, code, name, type)
                        VALUES (:sub, :personalId, :code, :name, :type)
                        ON CONFLICT DO NOTHING
                        RETURNING id""")
                        .param("sub", member.sub()).param("personalId", member.ledgerId()).param("code", code)
                        .param("name", category.name()).param("type", category.type())
                        .query(Long.class).optional().orElse(null);
            }
            if (copy == null) {
                throw new IllegalStateException("No free code for the copy of category " + category.code());
            }
            // The links are detached by now, so these are ordinary personal entries: the posting guard (V7) lets
            // anyone but the writer change them, and the writer only writes family kinds to family categories.
            jdbc.sql("""
                    UPDATE posting SET category_id = :copy
                    WHERE category_id = :familyCategory
                      AND entry_id IN (SELECT id FROM journal_entry WHERE ledger_id = :personalId)""")
                    .param("copy", copy).param("familyCategory", category.id())
                    .param("personalId", member.ledgerId())
                    .update();
            copies.put(category.id(), copy);
        }
        jdbc.sql("""
                UPDATE account SET family_ledger_id = NULL, is_system = FALSE
                WHERE ledger_id = :personalId AND family_ledger_id = :familyId""")
                .param("personalId", member.ledgerId()).param("familyId", family.ledgerId())
                .update();
        return copies;
    }

    /**
     * An entry on a member's own account is theirs (not system-owned): only that member, acting, changes or deletes it
     * (D-8, D-28). Another member's change never reaches it.
     *
     * @throws IllegalStateException if the link's entry is another member's own: a bug, never a user's mistake
     */
    private static void requireOwnIfTheirs(LedgerScope family, Link link) {
        if (!link.systemOwned() && link.memberId() != family.memberId()) {
            throw new IllegalStateException(("The family posting refuses to change the entry of link %d: it is member "
                    + "%d's own, on an account of theirs, and member %d acts").formatted(link.id(), link.memberId(),
                    family.memberId()));
        }
    }

    private void insertLines(LedgerScope family, long entryId, PostedEntry entry) {
        for (int i = 0; i < entry.lines().size(); i++) {
            Line line = entry.lines().get(i);
            jdbc.sql("""
                    INSERT INTO posting (entry_id, line_no, account_id, currency, amount, category_id)
                    VALUES (:entryId, :lineNo, :accountId, :currency, :amount, :categoryId)""")
                    .param("entryId", entryId).param("lineNo", i).param("accountId", line.accountId())
                    .param("currency", line.currency()).param("amount", line.amount())
                    .param("categoryId", line.categoryId())
                    .update();
        }
    }

    /**
     * Refuses an entry that D-8 doesn't allow: every line on one of the member's allowed accounts for the entry's link,
     * the kind that belongs to the link, a record of this family ledger, a posting to the debt account, and a memo only
     * on the payment of the member who acts: their own private note (F4c).
     *
     * @throws IllegalStateException naming the first line it refuses: a bug, never a user's mistake
     */
    private void check(LedgerScope family, PostedEntry entry, MemberLedger member) {
        EntryKind kind = switch (entry.link()) {
            case SHARE -> EntryKind.FAMILY_SHARE;
            case PAYMENT -> EntryKind.FAMILY_PAYMENT;
            case SETTLEMENT -> EntryKind.FAMILY_SETTLEMENT;
            case OPENING_BALANCE -> EntryKind.FAMILY_OPENING;
            case CORRECTION -> EntryKind.FAMILY_CORRECTION;
        };
        if (entry.kind() != kind) {
            throw refused(entry, "a %s link takes an entry of kind %s".formatted(entry.link(), kind));
        }
        if (entry.memo() != null && (entry.link() != LinkType.PAYMENT || entry.memberId() != family.memberId())) {
            throw refused(entry, "only the payer's own payment takes a note, their own");
        }
        if (entry.recordId() != null && !jdbc.sql("""
                SELECT EXISTS (SELECT FROM family_record WHERE id = :recordId AND ledger_id = :familyId)""")
                .param("recordId", entry.recordId()).param("familyId", family.ledgerId())
                .query(Boolean.class).single()) {
            throw refused(entry, "record %d is not of family ledger %d".formatted(entry.recordId(),
                    family.ledgerId()));
        }
        boolean own = ownAccountLine(family, entry, member).isPresent();
        if (entry.systemOwned() == own) {
            throw refused(entry, own ? "a payment with the payer's own account is theirs, not the family budget's"
                    : "only a payment with the payer's own account is not the family budget's");
        }
        boolean debt = false;
        for (Line line : entry.lines()) {
            long account = line.accountId();
            boolean allowed;
            if (Long.valueOf(account).equals(member.debt())) {
                allowed = line.categoryId() == null;
                debt = true;
            } else if (Long.valueOf(account).equals(member.unallocated())) {
                allowed = entry.link() == LinkType.SHARE && line.categoryId() != null
                        && familyCategory(family, line.categoryId());
            } else if (Long.valueOf(account).equals(member.placeholder())) {
                allowed = PAYMENTS.contains(entry.link()) && line.categoryId() == null;
            } else if (Long.valueOf(account).equals(member.openingBalance())) {
                allowed = (entry.link() == LinkType.OPENING_BALANCE || entry.link() == LinkType.CORRECTION)
                        && line.categoryId() == null;
            } else if (Long.valueOf(account).equals(member.fxExchange())) {
                // A side in another currency than the base currency, which only its member names (F4e, D-8).
                allowed = PAYMENTS.contains(entry.link()) && entry.memberId() == family.memberId()
                        && line.categoryId() == null;
            } else {
                allowed = !entry.systemOwned() && line.categoryId() == null
                        && ownAccountLine(family, entry, member).filter(id -> id == account).isPresent();
            }
            if (!allowed) {
                throw refused(entry, "account %d is not one it may post a %s to".formatted(account, entry.link()));
            }
        }
        if (!debt) {
            throw refused(entry, "it posts nothing to the member's debt account");
        }
    }

    /**
     * The payer's own account in a payment, if the payer is the member who acts (D-14) and the account is one they
     * pay with: an ASSET or LIABILITY of their personal ledger that isn't a debt account or the placeholder and doesn't
     * need a counterparty.
     */
    private Optional<Long> ownAccountLine(LedgerScope family, PostedEntry entry, MemberLedger member) {
        if (!PAYMENTS.contains(entry.link()) || entry.memberId() != family.memberId()) {
            return Optional.empty();
        }
        for (Line line : entry.lines()) {
            if (Long.valueOf(line.accountId()).equals(member.debt())
                    || Long.valueOf(line.accountId()).equals(member.placeholder())
                    || Long.valueOf(line.accountId()).equals(member.fxExchange())) {
                continue;
            }
            boolean payable = jdbc.sql("""
                    SELECT EXISTS (SELECT FROM account
                                   WHERE id = :accountId AND ledger_id = :ledgerId AND type IN ('ASSET', 'LIABILITY')
                                     AND family_ledger_id IS NULL AND NOT requires_counterparty
                                     AND code <> :placeholder)""")
                    .param("accountId", line.accountId()).param("ledgerId", member.ledgerId())
                    .param("placeholder", PLACEHOLDER_CODE)
                    .query(Boolean.class).single();
            return payable ? Optional.of(line.accountId()) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The personal ledger the database lets take the payer's own account and their FX_EXCHANGE: theirs, for their own
     * payment or side when they are the one who acts; on "Specify later" only FX_EXCHANGE is of theirs (F4e).
     */
    private Long ownLedger(LedgerScope family, PostedEntry entry, MemberLedger member) {
        return PAYMENTS.contains(entry.link()) && entry.memberId() == family.memberId() ? member.ledgerId() : null;
    }

    private boolean familyCategory(LedgerScope family, long categoryId) {
        return jdbc.sql("SELECT EXISTS (SELECT FROM category WHERE id = :categoryId AND ledger_id = :familyId)")
                .param("categoryId", categoryId).param("familyId", family.ledgerId())
                .query(Boolean.class).single();
    }

    private Long accountId(LedgerScope family, long ledgerId, String condition, String code) {
        var statement = jdbc.sql("SELECT id FROM account WHERE ledger_id = :ledgerId AND " + condition)
                .param("ledgerId", ledgerId);
        if (condition.contains(":familyId")) {
            statement = statement.param("familyId", family.ledgerId());
        }
        if (code != null) {
            statement = statement.param("code", code);
        }
        return statement.query(Long.class).optional().orElse(null);
    }

    /**
     * Runs the work with {@code app.writer} set to {@code family-posting}, and {@code app.own_ledger} to the personal
     * ledger that may take the payer's own account, if any; both are cleared again afterwards, so that the rest of the
     * transaction runs without them. A failure ends the transaction anyway.
     */
    private <T> T asWriter(LedgerScope family, Long ownLedger, Supplier<T> work) {
        jdbc.sql("SELECT set_config('app.writer', 'family-posting', true), set_config('app.own_ledger', :own, true)")
                .param("own", ownLedger == null ? "" : ownLedger.toString())
                .query().singleRow();
        T result = work.get();
        jdbc.sql("SELECT set_config('app.writer', '', true), set_config('app.own_ledger', '', true)")
                .query().singleRow();
        return result;
    }

    private static IllegalStateException refused(PostedEntry entry, String why) {
        return new IllegalStateException("The family posting refuses an entry for member %d: %s"
                .formatted(entry.memberId(), why));
    }
}
