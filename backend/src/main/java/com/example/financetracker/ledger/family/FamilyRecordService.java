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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.RuleViolationException.Violation;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.access.MemberRole;
import com.example.financetracker.ledger.family.FamilyChangeView.Change;
import com.example.financetracker.ledger.family.FamilyRecordView.CategoryRef;
import com.example.financetracker.ledger.family.FamilyRecordView.MemberRef;
import com.example.financetracker.ledger.family.FamilyRecordView.ShareView;
import com.example.financetracker.ledger.family.RecordSplit.ShareInput;
import com.example.financetracker.ledger.family.ShareSplit.Share;
import com.example.financetracker.ledger.family.ShareSplit.Weight;
import com.example.financetracker.ledger.family.posting.FamilyPostingService;
import com.example.financetracker.ledger.family.posting.FamilyPostingService.Payment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A family ledger's records (C1, C3; D-6, D-12, D-14, D-16; ADR 0003, topics D and H): family expenses in the base
 * currency (F4a), their shares, the members' balances and the change journal. Every method takes the family ledger's
 * {@link LedgerScope} of the member who acts, from {@code LedgerAccess.member}; the payment's account comes from their
 * personal ledger's. Each change is journaled and posted into the members' personal ledgers by
 * {@link FamilyPostingService} in the same transaction.
 * <p>
 * The rules: a record is dated on or after the ledger's start date (D-27, 409); its category is one of the family's,
 * not archived, of its type; its amount is above 0 in the currency's minor unit; a member with an account names only
 * themselves as payer, and says how they paid; shares go only to ACTIVE members, and a member with an account only
 * from their join date on, and they sum to 10000 basis points or to the amount (422, each violation with its code and
 * member). The author and the owners change the family fields; the payer with an account deletes, or else the author or
 * an owner; payment fields don't change before F4c; and nobody changes a record that involves a member who left or
 * deleted their data (D-19, frozen: 409).
 */
@Service
public class FamilyRecordService {

    /** The codes of the violations of a record's rules, with the member each is about where there is one. */
    public static final String CATEGORY = "CATEGORY";
    public static final String AMOUNT = "AMOUNT";
    public static final String PAYER = "PAYER";
    public static final String PAYMENT = "PAYMENT";
    public static final String JOINED_AFTER = "JOINED_AFTER";
    public static final String SHARE = "SHARE";
    public static final String AMOUNTS_DONT_ADD_UP = "AMOUNTS_DONT_ADD_UP";
    public static final String NO_MEMBERS = "NO_MEMBERS";

    private static final String RECORDS = """
            SELECT r.id, r.type, r.record_date, r.category_id, r.base_amount, r.comment, r.payer_member_id,
                   r.split_method, r.author_member_id, r.created_at, r.updated_by_member_id, r.updated_at, r.version
            FROM family_record r
            WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL""";

    private final JdbcClient jdbc;
    private final FamilyPostingService posting;
    private final ObjectMapper json;

    FamilyRecordService(JdbcClient jdbc, FamilyPostingService posting, ObjectMapper json) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.json = json;
    }

    /**
     * Records a family expense, and posts it into the members' personal ledgers.
     *
     * @param family the family ledger, as the member who enters it: its author
     * @param personal the author's personal ledger, which the payment's account is in
     * @throws ConflictException if it is dated before the ledger's start date (D-27)
     * @throws RuleViolationException listing every other rule it breaks
     */
    @Transactional
    public FamilyRecordView create(LedgerScope family, LedgerScope personal, NewFamilyRecord request) {
        if (personal.type() != LedgerType.PERSONAL || !personal.userId().equals(family.userId())) {
            throw new IllegalArgumentException("The payment's account is in the author's own personal ledger");
        }
        Ledger ledger = lockLedger(family);
        requireStarted(ledger, request.date());
        int scale = ShareSplit.minorUnit(ledger.baseCurrency());
        Map<Long, Member> members = members(family);
        List<Violation> violations = new ArrayList<>();
        checkCategory(family, request.categoryId(), violations);
        checkAmount(request.amount(), ledger.baseCurrency(), scale, violations);
        Member payer = members.get(request.payerMemberId());
        Payment payment = payment(family, personal, request, payer, violations);
        List<Share> shares = violations.stream().anyMatch(v -> v.code().equals(AMOUNT)) ? List.of()
                : split(request.split(), request.amount(), scale, request.date(), request.payerMemberId(), ledger,
                        members, violations);
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }

        String method = method(request.split(), ledger);
        long recordId = jdbc.sql("""
                INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id,
                    original_amount, original_currency, base_amount, split_method, comment, author_member_id,
                    updated_by_member_id)
                VALUES (:ledgerId, 'EXPENSE', :date, :categoryId, :payerId, :amount, :currency, :amount, :method,
                        :comment, :memberId, :memberId)
                RETURNING id""")
                .param("ledgerId", family.ledgerId()).param("date", request.date())
                .param("categoryId", request.categoryId()).param("payerId", request.payerMemberId())
                .param("amount", request.amount().setScale(scale, RoundingMode.UNNECESSARY))
                .param("currency", ledger.baseCurrency()).param("method", method)
                .param("comment", request.comment()).param("memberId", family.memberId())
                .query(Long.class).single();
        for (Share share : shares) {
            insertShare(family, recordId, share);
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(change("date", null, null, request.date().toString()));
        changes.add(change("category", null, null, request.categoryId()));
        changes.add(change("amount", null, null, text(request.amount(), scale)));
        changes.add(change("payer", null, null, request.payerMemberId()));
        changes.add(change("splitMethod", null, null, method));
        for (Share share : shares) {
            changes.add(change("share", share.memberId(), null, text(share.amount(), scale)));
        }
        if (request.comment() != null) {
            changes.add(change("comment", null, null, request.comment()));
        }
        journal(family, recordId, "CREATE", changes);
        posting.post(family, recordId, payment);
        return get(family, recordId);
    }

    /** The family ledger's records that aren't deleted, newest first: by date, then by id. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FamilyRecordPage page(LedgerScope family, int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM family_record WHERE ledger_id = :ledgerId AND deleted_at IS NULL")
                .param("ledgerId", family.ledgerId()).query(Long.class).single();
        List<RecordRow> rows = jdbc.sql(RECORDS + " ORDER BY r.record_date DESC, r.id DESC LIMIT :limit OFFSET :offset")
                .param("ledgerId", family.ledgerId()).param("limit", size).param("offset", (long) page * size)
                .query(FamilyRecordService::recordRow)
                .list();
        return new FamilyRecordPage(views(family, rows), page, size, total, Math.toIntExact((total + size - 1) / size));
    }

    /** @throws NotFoundException if the family ledger has no such record, or it is deleted */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FamilyRecordView get(LedgerScope family, long recordId) {
        RecordRow row = jdbc.sql(RECORDS + " AND r.id = :recordId").param("ledgerId", family.ledgerId())
                .param("recordId", recordId)
                .query(FamilyRecordService::recordRow)
                .optional()
                .orElseThrow(() -> recordNotFound(recordId));
        return views(family, List.of(row)).getFirst();
    }

    /**
     * Changes the record's family fields, journals what changed, and posts the record again.
     *
     * @param family the family ledger, as its author or one of its owners
     * @param expectedVersion the version the caller read
     * @throws NotFoundException if the family ledger has no such record, or it is deleted
     * @throws OptimisticLockingFailureException if the record is no longer at {@code expectedVersion}
     * @throws ConflictException if the member may not change it, or it is frozen
     * @throws RuleViolationException listing every rule the new fields break
     */
    @Transactional
    public FamilyRecordView update(LedgerScope family, long recordId, int expectedVersion,
            FamilyRecordChanges changes) {
        Ledger ledger = lockLedger(family);
        RecordRow record = lockRecord(family, recordId, expectedVersion);
        Map<Long, Member> members = members(family);
        if (record.authorId() != family.memberId() && family.role() != MemberRole.OWNER) {
            throw new ConflictException("Only the record's author or an owner of the family budget can change it");
        }
        requireNotFrozen(family, record, members);
        int scale = ShareSplit.minorUnit(ledger.baseCurrency());

        List<Violation> violations = new ArrayList<>();
        long categoryId = record.categoryId();
        if (changes.categoryId() != null && changes.categoryId() != record.categoryId()) {
            checkCategory(family, changes.categoryId(), violations);
            categoryId = changes.categoryId();
        }
        String comment = changes.changesComment() ? changes.comment() : record.comment();
        Map<Long, ShareRow> oldShares = shares(family, List.of(recordId)).getOrDefault(recordId, Map.of());
        List<Share> newShares = null;
        String method = record.splitMethod();
        if (changes.split() != null) {
            newShares = split(changes.split(), record.amount(), scale, record.date(), record.payerId(), ledger,
                    members, violations);
            method = method(changes.split(), ledger);
        }
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }

        List<Map<String, Object>> journal = new ArrayList<>();
        if (categoryId != record.categoryId()) {
            journal.add(change("category", null, record.categoryId(), categoryId));
        }
        if (!method.equals(record.splitMethod())) {
            journal.add(change("splitMethod", null, record.splitMethod(), method));
        }
        Map<Long, Share> wanted = new LinkedHashMap<>();
        boolean rewritten = false;
        if (newShares != null) {
            newShares.forEach(share -> wanted.put(share.memberId(), share));
            Set<Long> everyone = new TreeSet<>(oldShares.keySet());
            everyone.addAll(wanted.keySet());
            for (long memberId : everyone) {
                ShareRow old = oldShares.get(memberId);
                Share share = wanted.get(memberId);
                if (old == null || share == null || old.amount().compareTo(share.amount()) != 0
                        || !Objects.equals(old.basisPoints(), share.basisPoints())) {
                    if (old == null || share == null || old.amount().compareTo(share.amount()) != 0) {
                        journal.add(change("share", memberId, old == null ? null : text(old.amount(), scale),
                                share == null ? null : text(share.amount(), scale)));
                    }
                    jdbc.sql("DELETE FROM family_share WHERE record_id = :recordId AND member_id = :memberId "
                            + "AND ledger_id = :ledgerId")
                            .param("recordId", recordId).param("memberId", memberId)
                            .param("ledgerId", family.ledgerId())
                            .update();
                    if (share != null) {
                        insertShare(family, recordId, share);
                    }
                    rewritten = true;
                }
            }
        }
        if (!Objects.equals(comment, record.comment())) {
            journal.add(change("comment", null, record.comment(), comment));
        }
        if (journal.isEmpty() && !rewritten) {
            return get(family, recordId);
        }
        jdbc.sql("""
                UPDATE family_record
                SET category_id = :categoryId, comment = :comment, split_method = :method,
                    updated_by_member_id = :memberId, updated_at = now(), version = version + 1
                WHERE id = :recordId AND ledger_id = :ledgerId""")
                .param("categoryId", categoryId).param("comment", comment).param("method", method)
                .param("memberId", family.memberId()).param("recordId", recordId)
                .param("ledgerId", family.ledgerId())
                .update();
        if (!journal.isEmpty()) {
            journal(family, recordId, "UPDATE", journal);
        }
        posting.post(family, recordId, new FamilyPostingService.Unchanged());
        return get(family, recordId);
    }

    /**
     * Deletes the record: it stays for the journal, marked deleted, and all its posted entries go, the payment
     * included (D-14).
     *
     * @param family the family ledger, as the payer if they have an account, else as the author or an owner
     * @param expectedVersion the version the caller read
     * @throws NotFoundException if the family ledger has no such record, or it is deleted already
     * @throws OptimisticLockingFailureException if the record is no longer at {@code expectedVersion}
     * @throws ConflictException if the member may not delete it, or it is frozen
     */
    @Transactional
    public void delete(LedgerScope family, long recordId, int expectedVersion) {
        lockLedger(family);
        RecordRow record = lockRecord(family, recordId, expectedVersion);
        Map<Long, Member> members = members(family);
        Member payer = members.get(record.payerId());
        if (payer.hasAccount() ? record.payerId() != family.memberId()
                : record.authorId() != family.memberId() && family.role() != MemberRole.OWNER) {
            throw new ConflictException(payer.hasAccount()
                    ? "Only %s, who paid it, can delete this record".formatted(payer.displayName())
                    : "Only the record's author or an owner of the family budget can delete it");
        }
        requireNotFrozen(family, record, members);
        jdbc.sql("""
                UPDATE family_record
                SET deleted_at = now(), deleted_by_member_id = :memberId, updated_by_member_id = :memberId,
                    updated_at = now(), version = version + 1
                WHERE id = :recordId AND ledger_id = :ledgerId""")
                .param("memberId", family.memberId()).param("recordId", recordId).param("ledgerId", family.ledgerId())
                .update();
        journal(family, recordId, "DELETE", List.of());
        posting.post(family, recordId, new FamilyPostingService.Unchanged());
    }

    /**
     * Every member's balance, B(m) = their expense shares − the expenses they paid, over the records that aren't
     * deleted (ADR 0003, topic D; income and settlements come with F4c). The balances sum to zero, since every
     * record's shares add up to its amount.
     */
    @Transactional(readOnly = true)
    public FamilyBalances balances(LedgerScope family) {
        String currency = jdbc.sql("SELECT base_currency FROM ledger WHERE id = :ledgerId")
                .param("ledgerId", family.ledgerId()).query(String.class).single();
        int scale = ShareSplit.minorUnit(currency);
        List<FamilyBalances.MemberBalance> balances = jdbc.sql("""
                SELECT m.id, m.display_name, m.status, m.user_sub IS NOT NULL AS has_account,
                       coalesce((SELECT sum(CASE r.type WHEN 'EXPENSE' THEN s.amount ELSE -s.amount END)
                                 FROM family_share s JOIN family_record r ON r.id = s.record_id
                                 WHERE s.member_id = m.id AND r.ledger_id = :ledgerId AND r.deleted_at IS NULL), 0)
                       - coalesce((SELECT sum(CASE r.type WHEN 'INCOME' THEN -r.base_amount ELSE r.base_amount END)
                                   FROM family_record r
                                   WHERE r.payer_member_id = m.id AND r.ledger_id = :ledgerId
                                     AND r.deleted_at IS NULL), 0)
                       + coalesce((SELECT sum(r.base_amount) FROM family_record r
                                   WHERE r.payee_member_id = m.id AND r.ledger_id = :ledgerId
                                     AND r.deleted_at IS NULL), 0) AS balance
                FROM ledger_member m
                WHERE m.ledger_id = :ledgerId
                ORDER BY m.join_date, m.id""")
                .param("ledgerId", family.ledgerId())
                .query((row, n) -> new FamilyBalances.MemberBalance(row.getLong("id"), row.getString("display_name"),
                        MemberStatus.valueOf(row.getString("status")), row.getBoolean("has_account"),
                        row.getBigDecimal("balance").setScale(scale, RoundingMode.UNNECESSARY),
                        row.getLong("id") == family.memberId()))
                .list();
        return new FamilyBalances(currency, balances);
    }

    /**
     * The change journal, newest first, with members and categories named as they are now (D-16, D-20).
     *
     * @param recordId only this record's changes; null for all, the system changes included
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FamilyJournalPage journal(LedgerScope family, Long recordId, int page, int size) {
        String where = "c.ledger_id = :ledgerId" + (recordId == null ? "" : " AND c.record_id = :recordId");
        var count = jdbc.sql("SELECT count(*) FROM family_record_change c WHERE " + where)
                .param("ledgerId", family.ledgerId());
        var rows = jdbc.sql("""
                SELECT c.id, c.changed_at, c.action, c.record_id, c.changed_by_member_id, c.about_member_id,
                       c.changes::text AS changes, r.record_date, r.category_id, r.base_amount,
                       r.deleted_at IS NOT NULL AS deleted
                FROM family_record_change c
                LEFT JOIN family_record r ON r.id = c.record_id AND r.ledger_id = c.ledger_id
                WHERE %s
                ORDER BY c.id DESC LIMIT :limit OFFSET :offset""".formatted(where))
                .param("ledgerId", family.ledgerId()).param("limit", size).param("offset", (long) page * size);
        if (recordId != null) {
            count = count.param("recordId", recordId);
            rows = rows.param("recordId", recordId);
        }
        long total = count.query(Long.class).single();
        Map<Long, Member> members = members(family);
        Map<Long, CategoryRef> categories = categories(family);
        int scale = ShareSplit.minorUnit(jdbc.sql("SELECT base_currency FROM ledger WHERE id = :ledgerId")
                .param("ledgerId", family.ledgerId()).query(String.class).single());
        List<FamilyChangeView> content = rows.query((row, n) -> new FamilyChangeView(row.getLong("id"),
                        row.getTimestamp("changed_at").toInstant(), row.getString("action"),
                        row.getObject("record_id", Long.class),
                        ref(members, row.getObject("changed_by_member_id", Long.class)),
                        ref(members, row.getObject("about_member_id", Long.class)),
                        changes(row.getString("changes"), members, categories),
                        row.getObject("record_id") == null ? null : new FamilyChangeView.RecordSummary(
                                row.getObject("record_date", LocalDate.class),
                                categoryName(categories, row.getObject("category_id", Long.class)),
                                row.getBigDecimal("base_amount").setScale(scale, RoundingMode.UNNECESSARY),
                                row.getBoolean("deleted"))))
                .list();
        return new FamilyJournalPage(content, page, size, total, Math.toIntExact((total + size - 1) / size));
    }

    // --- The rules ---

    private void requireStarted(Ledger ledger, LocalDate date) {
        if (date.isBefore(ledger.startDate())) {
            throw new ConflictException(("The family budget starts on %s, and a record can't be dated before its "
                    + "start date").formatted(ledger.startDate()));
        }
    }

    private void checkCategory(LedgerScope family, long categoryId, List<Violation> violations) {
        record Category(String code, String type, boolean archived) {
        }
        jdbc.sql("SELECT code, type, archived_at IS NOT NULL AS archived FROM category "
                + "WHERE id = :categoryId AND ledger_id = :ledgerId")
                .param("categoryId", categoryId).param("ledgerId", family.ledgerId())
                .query((row, n) -> new Category(row.getString("code"), row.getString("type"),
                        row.getBoolean("archived")))
                .optional()
                .ifPresentOrElse(category -> {
                    if (category.archived()) {
                        violations.add(new Violation(CATEGORY, null,
                                "the category %s is archived".formatted(category.code())));
                    } else if (!category.type().equals("EXPENSE")) {
                        violations.add(new Violation(CATEGORY, null,
                                "the category %s is %s, and an expense needs an EXPENSE category"
                                        .formatted(category.code(), category.type())));
                    }
                }, () -> violations.add(new Violation(CATEGORY, null,
                        "category %d is not a category of the family budget".formatted(categoryId))));
    }

    private static void checkAmount(BigDecimal amount, String currency, int scale, List<Violation> violations) {
        if (amount.signum() <= 0) {
            violations.add(new Violation(AMOUNT, null, "the amount must be above 0"));
        } else if (amount.stripTrailingZeros().scale() > scale) {
            violations.add(new Violation(AMOUNT, null, "the amount %s has more decimals than %s has (%d)"
                    .formatted(amount.toPlainString(), currency, scale)));
        } else if (amount.precision() - amount.scale() > 15) {
            violations.add(new Violation(AMOUNT, null, "the amount %s is too large".formatted(amount.toPlainString())));
        }
    }

    /**
     * The payer and how they paid (D-14): a member with an account names only themselves, and says which account of
     * theirs they paid with, or that they specify it later; a member without an account says nothing about it.
     */
    private Payment payment(LedgerScope family, LedgerScope personal, NewFamilyRecord request, Member payer,
            List<Violation> violations) {
        long payerId = request.payerMemberId();
        if (payer == null || payer.status() != MemberStatus.ACTIVE) {
            violations.add(new Violation(PAYER, payerId,
                    "Member %d is not an active member of the family budget".formatted(payerId)));
            return new FamilyPostingService.Unchanged();
        }
        boolean named = request.paymentAccountId() != null || request.paymentLater();
        if (!payer.hasAccount()) {
            if (named) {
                violations.add(new Violation(PAYMENT, payerId, ("%s has no account, so there is no account of "
                        + "theirs to pay with").formatted(payer.displayName())));
            }
            return new FamilyPostingService.Unchanged();
        }
        if (payerId != family.memberId()) {
            violations.add(new Violation(PAYER, payerId, ("%s has an account: only they can record what they paid; "
                    + "name yourself, or a member without an account").formatted(payer.displayName())));
            return new FamilyPostingService.Unchanged();
        }
        if (payer.joinDate().isAfter(request.date())) {
            violations.add(joinedAfter(payer, request.date()));
        }
        if (request.paymentAccountId() != null && request.paymentLater()) {
            violations.add(new Violation(PAYMENT, payerId,
                    "name the account you paid with, or specify it later, not both"));
            return new FamilyPostingService.Unchanged();
        }
        if (request.paymentLater()) {
            return new FamilyPostingService.Later();
        }
        if (request.paymentAccountId() == null) {
            violations.add(new Violation(PAYMENT, payerId,
                    "name the account you paid with, or specify it later"));
            return new FamilyPostingService.Unchanged();
        }
        long accountId = request.paymentAccountId();
        record Account(String code, String type, boolean requiresCounterparty, boolean system, boolean debt) {
        }
        var account = jdbc.sql("""
                SELECT code, type, requires_counterparty, is_system, family_ledger_id IS NOT NULL AS debt
                FROM account WHERE id = :accountId AND ledger_id = :ledgerId FOR SHARE""")
                .param("accountId", accountId).param("ledgerId", personal.ledgerId())
                .query((row, n) -> new Account(row.getString("code"), row.getString("type"),
                        row.getBoolean("requires_counterparty"), row.getBoolean("is_system"), row.getBoolean("debt")))
                .optional();
        if (account.isEmpty()) {
            // The answer for another user's account is the one for a missing account (rule 11).
            violations.add(new Violation(PAYMENT, payerId, "account %d does not exist".formatted(accountId)));
        } else if (!List.of("ASSET", "LIABILITY").contains(account.get().type()) || account.get().debt()
                || account.get().requiresCounterparty() || account.get().system()) {
            violations.add(new Violation(PAYMENT, payerId, ("the account %s can't pay a family record: pay with an "
                    + "account of your own money or credit, or specify it later").formatted(account.get().code())));
        }
        return new FamilyPostingService.OwnAccount(accountId);
    }

    /**
     * The shares of the amount (D-12). Shares go to ACTIVE members only, and to a member with an account only for a
     * record on or after their join date, which is when records are posted to them (D-7, D-18).
     */
    private List<Share> split(RecordSplit split, BigDecimal amount, int scale, LocalDate date, long payerId,
            Ledger ledger, Map<Long, Member> members, List<Violation> violations) {
        RecordSplit wanted = split == null ? RecordSplit.rule() : split;
        List<Member> byJoinDate = members.values().stream()
                .sorted(Comparator.comparing(Member::joinDate).thenComparing(Member::id)).toList();
        int before = violations.size();
        switch (wanted.method()) {
            case RULE -> {
                if (ledger.splitRule() == SplitRule.EQUAL) {
                    List<Long> participants = byJoinDate.stream()
                            .filter(m -> m.status() == MemberStatus.ACTIVE && joinedBy(m, date)).map(Member::id)
                            .toList();
                    if (participants.isEmpty()) {
                        violations.add(new Violation(NO_MEMBERS, null, "no active member shares a record of %s"
                                .formatted(date)));
                        return List.of();
                    }
                    return ShareSplit.equal(amount, scale, participants, payerId);
                }
                List<Weight> weights = new ArrayList<>();
                for (Member member : byJoinDate) {
                    if (member.status() == MemberStatus.ACTIVE && member.share() != null) {
                        if (member.share() > 0 && !joinedBy(member, date)) {
                            violations.add(joinedAfter(member, date));
                        }
                        weights.add(new Weight(member.id(), member.share(), member.share()));
                    }
                }
                return violations.size() > before ? List.of() : ShareSplit.percent(amount, scale, weights, payerId);
            }
            case PERCENT -> {
                Map<Long, ShareInput> inputs = inputs(wanted, members, date, violations, true);
                long total = 0;
                boolean valid = true;
                for (ShareInput input : inputs.values()) {
                    if (input.basisPoints() == null || input.basisPoints() < 0 || input.basisPoints() > 10_000) {
                        violations.add(new Violation(SHARE, input.memberId(), ("%s needs a share from 0 to 10000 "
                                + "basis points").formatted(name(members, input.memberId()))));
                        valid = false;
                    } else {
                        total += input.basisPoints();
                    }
                }
                if (valid && !inputs.isEmpty() && total != BasisPoints.WHOLE) {
                    violations.add(FamilyLedgerService.sumNotWhole(total));
                }
                if (violations.size() > before) {
                    return List.of();
                }
                List<Weight> weights = byJoinDate.stream().filter(m -> inputs.containsKey(m.id()))
                        .map(m -> new Weight(m.id(), inputs.get(m.id()).basisPoints(), inputs.get(m.id()).basisPoints()))
                        .toList();
                return ShareSplit.percent(amount, scale, weights, payerId);
            }
            case AMOUNT -> {
                Map<Long, ShareInput> inputs = inputs(wanted, members, date, violations, false);
                boolean valid = true;
                for (ShareInput input : inputs.values()) {
                    BigDecimal share = input.amount();
                    if (share == null || share.signum() < 0 || share.stripTrailingZeros().scale() > scale) {
                        violations.add(new Violation(SHARE, input.memberId(), ("%s needs an amount of 0 or more, with "
                                + "at most %d decimals").formatted(name(members, input.memberId()), scale)));
                        valid = false;
                    }
                }
                List<Share> shares = List.of();
                if (valid && !inputs.isEmpty()) {
                    try {
                        shares = ShareSplit.amounts(amount, scale, inputs.values().stream()
                                .map(input -> new Share(input.memberId(), input.amount(), null)).toList());
                    } catch (IllegalArgumentException e) {
                        violations.add(new Violation(AMOUNTS_DONT_ADD_UP, null, e.getMessage()));
                    }
                }
                if (violations.size() > before) {
                    return List.of();
                }
                Map<Long, Share> byMember = new HashMap<>();
                shares.forEach(share -> byMember.put(share.memberId(), share));
                return byJoinDate.stream().filter(m -> byMember.containsKey(m.id())).map(m -> byMember.get(m.id()))
                        .toList();
            }
            case ONE_MEMBER -> {
                Long memberId = wanted.memberId();
                Member member = memberId == null ? null : members.get(memberId);
                if (member == null || member.status() != MemberStatus.ACTIVE) {
                    violations.add(memberId == null
                            ? new Violation(SHARE, null, "name the member the whole amount is on")
                            : FamilyLedgerService.notActive(memberId));
                    return List.of();
                }
                if (!joinedBy(member, date)) {
                    violations.add(joinedAfter(member, date));
                    return List.of();
                }
                return ShareSplit.oneMember(amount, scale, memberId);
            }
            default -> throw new IllegalArgumentException("Unknown split " + wanted.method());
        }
    }

    /** The shares as entered, by member, each of an ACTIVE member who shares records of the date, once. */
    private static Map<Long, ShareInput> inputs(RecordSplit split, Map<Long, Member> members, LocalDate date,
            List<Violation> violations, boolean percent) {
        Map<Long, ShareInput> inputs = new LinkedHashMap<>();
        Set<Long> reported = new HashSet<>();
        for (ShareInput input : split.shares()) {
            Member member = members.get(input.memberId());
            if (inputs.putIfAbsent(input.memberId(), input) != null) {
                // The first one counts; the others are reported once.
                if (reported.add(input.memberId())) {
                    violations.add(FamilyLedgerService.duplicate(input.memberId()));
                }
            } else if (member == null || member.status() != MemberStatus.ACTIVE) {
                violations.add(FamilyLedgerService.notActive(input.memberId()));
            } else if (!joinedBy(member, date) && (percent ? input.basisPoints() != null && input.basisPoints() > 0
                    : input.amount() != null && input.amount().signum() > 0)) {
                violations.add(joinedAfter(member, date));
            }
        }
        if (inputs.isEmpty()) {
            violations.add(new Violation(SHARE, null, "the shares are missing"));
        }
        return inputs;
    }

    /** A member without an account shares any record; one with an account those on or after their join date. */
    private static boolean joinedBy(Member member, LocalDate date) {
        return !member.hasAccount() || !member.joinDate().isAfter(date);
    }

    private static Violation joinedAfter(Member member, LocalDate date) {
        return new Violation(JOINED_AFTER, member.id(), "%s joined on %s, after the record's date %s"
                .formatted(member.displayName(), member.joinDate(), date));
    }

    /** The split method a record stores (topic D): the rule's EQUAL or, for its custom shares, PERCENT. */
    private static String method(RecordSplit split, Ledger ledger) {
        RecordSplit.Method method = split == null ? RecordSplit.Method.RULE : split.method();
        return switch (method) {
            case RULE -> ledger.splitRule() == SplitRule.EQUAL ? "EQUAL" : "PERCENT";
            case PERCENT -> "PERCENT";
            case AMOUNT -> "AMOUNT";
            case ONE_MEMBER -> "ONE_MEMBER";
        };
    }

    /** A record whose payer, or a member with a share, has left or deleted their data is frozen (D-19, D-20). */
    private void requireNotFrozen(LedgerScope family, RecordRow record, Map<Long, Member> members) {
        if (frozen(record, shares(family, List.of(record.id())).getOrDefault(record.id(), Map.of()), members)) {
            throw new ConflictException("The record is frozen: a member it involves has left the family budget or "
                    + "deleted their data, so nobody can change it");
        }
    }

    private static boolean frozen(RecordRow record, Map<Long, ShareRow> shares, Map<Long, Member> members) {
        return members.get(record.payerId()).status() != MemberStatus.ACTIVE || shares.values().stream()
                .anyMatch(s -> s.amount().signum() > 0 && members.get(s.memberId()).status() != MemberStatus.ACTIVE);
    }

    // --- Reading and writing ---

    private record Ledger(LocalDate startDate, String baseCurrency, SplitRule splitRule) {
    }

    private record Member(long id, String displayName, MemberStatus status, boolean hasAccount, LocalDate joinDate,
            Integer share) {
    }

    private record RecordRow(long id, String type, LocalDate date, long categoryId, BigDecimal amount, String comment,
            long payerId, String splitMethod, long authorId, Instant createdAt, long updatedById, Instant updatedAt,
            int version) {
    }

    private record ShareRow(long memberId, BigDecimal amount, Integer basisPoints, long updatedById,
            Instant updatedAt) {
    }

    /**
     * The ledger, locked FOR SHARE until the transaction ends, so that its members, split rule and base currency
     * don't change meanwhile: those changes lock it FOR UPDATE (FamilyLedgerService).
     */
    private Ledger lockLedger(LedgerScope family) {
        return jdbc.sql("SELECT start_date, base_currency, split_rule FROM ledger WHERE id = :ledgerId FOR SHARE")
                .param("ledgerId", family.ledgerId())
                .query((row, n) -> new Ledger(row.getObject("start_date", LocalDate.class),
                        row.getString("base_currency"), SplitRule.valueOf(row.getString("split_rule"))))
                .single();
    }

    private RecordRow lockRecord(LedgerScope family, long recordId, int expectedVersion) {
        RecordRow record = jdbc.sql(RECORDS + " AND r.id = :recordId FOR UPDATE")
                .param("ledgerId", family.ledgerId()).param("recordId", recordId)
                .query(FamilyRecordService::recordRow)
                .optional()
                .orElseThrow(() -> recordNotFound(recordId));
        if (record.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(("Record %d has changed since version %d. Reload it and try "
                    + "again.").formatted(recordId, expectedVersion));
        }
        return record;
    }

    private Map<Long, Member> members(LedgerScope family) {
        Map<Long, Member> members = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, display_name, status, user_sub IS NOT NULL AS has_account, join_date, share_bp
                FROM ledger_member WHERE ledger_id = :ledgerId ORDER BY join_date, id""")
                .param("ledgerId", family.ledgerId())
                .query(row -> {
                    members.put(row.getLong("id"), new Member(row.getLong("id"), row.getString("display_name"),
                            MemberStatus.valueOf(row.getString("status")), row.getBoolean("has_account"),
                            row.getObject("join_date", LocalDate.class), row.getObject("share_bp", Integer.class)));
                });
        return members;
    }

    private Map<Long, CategoryRef> categories(LedgerScope family) {
        Map<Long, CategoryRef> categories = new HashMap<>();
        jdbc.sql("SELECT id, code, name, archived_at IS NOT NULL AS archived FROM category WHERE ledger_id = :ledgerId")
                .param("ledgerId", family.ledgerId())
                .query(row -> {
                    categories.put(row.getLong("id"), new CategoryRef(row.getLong("id"), row.getString("code"),
                            row.getString("name"), row.getBoolean("archived")));
                });
        return categories;
    }

    /** The records' shares, by record and member. */
    private Map<Long, Map<Long, ShareRow>> shares(LedgerScope family, List<Long> recordIds) {
        Map<Long, Map<Long, ShareRow>> shares = new HashMap<>();
        if (recordIds.isEmpty()) {
            return shares;
        }
        jdbc.sql("""
                SELECT record_id, member_id, amount, share_bp, updated_by_member_id, updated_at FROM family_share
                WHERE ledger_id = :ledgerId AND record_id IN (:recordIds)""")
                .param("ledgerId", family.ledgerId()).param("recordIds", recordIds)
                .query(row -> {
                    shares.computeIfAbsent(row.getLong("record_id"), id -> new LinkedHashMap<>())
                            .put(row.getLong("member_id"), new ShareRow(row.getLong("member_id"),
                                    row.getBigDecimal("amount"), row.getObject("share_bp", Integer.class),
                                    row.getLong("updated_by_member_id"), row.getTimestamp("updated_at").toInstant()));
                });
        return shares;
    }

    private void insertShare(LedgerScope family, long recordId, Share share) {
        jdbc.sql("""
                INSERT INTO family_share (ledger_id, record_id, member_id, amount, share_bp, updated_by_member_id)
                VALUES (:ledgerId, :recordId, :memberId, :amount, :basisPoints, :by)""")
                .param("ledgerId", family.ledgerId()).param("recordId", recordId).param("memberId", share.memberId())
                .param("amount", share.amount()).param("basisPoints", share.basisPoints())
                .param("by", family.memberId())
                .update();
    }

    private List<FamilyRecordView> views(LedgerScope family, List<RecordRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String currency = jdbc.sql("SELECT base_currency FROM ledger WHERE id = :ledgerId")
                .param("ledgerId", family.ledgerId()).query(String.class).single();
        int scale = ShareSplit.minorUnit(currency);
        Map<Long, Member> members = members(family);
        Map<Long, CategoryRef> categories = categories(family);
        Map<Long, Map<Long, ShareRow>> shares = shares(family, rows.stream().map(RecordRow::id).toList());
        List<Long> joinOrder = new ArrayList<>(members.keySet());
        return rows.stream().map(row -> {
            Map<Long, ShareRow> recordShares = shares.getOrDefault(row.id(), Map.of());
            boolean frozen = frozen(row, recordShares, members);
            Member payer = members.get(row.payerId());
            boolean mayEdit = row.authorId() == family.memberId() || family.role() == MemberRole.OWNER;
            boolean mayDelete = payer.hasAccount() ? row.payerId() == family.memberId() : mayEdit;
            return new FamilyRecordView(row.id(), row.type(), row.date(), categories.get(row.categoryId()),
                    row.amount().setScale(scale, RoundingMode.UNNECESSARY), currency, row.comment(),
                    ref(members, row.payerId()), row.splitMethod(), recordShares.values().stream()
                            .sorted(Comparator.comparing(s -> joinOrder.indexOf(s.memberId())))
                            .map(s -> new ShareView(ref(members, s.memberId()),
                                    s.amount().setScale(scale, RoundingMode.UNNECESSARY), s.basisPoints(),
                                    ref(members, s.updatedById()), s.updatedAt()))
                            .toList(),
                    ref(members, row.authorId()), row.createdAt(), ref(members, row.updatedById()), row.updatedAt(),
                    row.version(), frozen, mayEdit && !frozen, mayDelete && !frozen);
        }).toList();
    }

    private void journal(LedgerScope family, long recordId, String action, List<Map<String, Object>> changes) {
        try {
            jdbc.sql("""
                    INSERT INTO family_record_change (ledger_id, record_id, changed_by_member_id, action, changes)
                    VALUES (:ledgerId, :recordId, :memberId, :action, CAST(:changes AS jsonb))""")
                    .param("ledgerId", family.ledgerId()).param("recordId", recordId)
                    .param("memberId", family.memberId()).param("action", action)
                    .param("changes", json.writeValueAsString(changes))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the journal", e);
        }
    }

    /** A change as the journal stores it: members and categories by id (topic H). */
    private static Map<String, Object> change(String field, Long memberId, Object old, Object value) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        if (memberId != null) {
            change.put("member", memberId);
        }
        change.put("old", old);
        change.put("new", value);
        return change;
    }

    /** The stored changes, with members and categories named as they are now. */
    private List<Change> changes(String stored, Map<Long, Member> members, Map<Long, CategoryRef> categories) {
        try {
            List<Change> changes = new ArrayList<>();
            for (JsonNode change : json.readTree(stored)) {
                String field = change.path("field").asText();
                JsonNode member = change.get("member");
                changes.add(new Change(field, member == null || member.isNull() ? null : ref(members, member.asLong()),
                        value(field, change.get("old"), members, categories),
                        value(field, change.get("new"), members, categories)));
            }
            return changes;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read the journal", e);
        }
    }

    private static String value(String field, JsonNode value, Map<Long, Member> members,
            Map<Long, CategoryRef> categories) {
        if (value == null || value.isNull()) {
            return null;
        }
        return switch (field) {
            case "category" -> {
                CategoryRef category = categories.get(value.asLong());
                yield category == null ? null : category.name();
            }
            case "payer" -> ref(members, value.asLong()).displayName();
            default -> value.asText();
        };
    }

    /** A category's name; null for a record without one, such as a settlement (F4c). */
    private static String categoryName(Map<Long, CategoryRef> categories, Long categoryId) {
        CategoryRef category = categoryId == null ? null : categories.get(categoryId);
        return category == null ? null : category.name();
    }

    private static MemberRef ref(Map<Long, Member> members, Long memberId) {
        if (memberId == null) {
            return null;
        }
        Member member = members.get(memberId);
        return new MemberRef(memberId, member == null ? "Former member" : member.displayName());
    }

    private static String name(Map<Long, Member> members, long memberId) {
        Member member = members.get(memberId);
        return member == null ? "Member " + memberId : member.displayName();
    }

    private static String text(BigDecimal amount, int scale) {
        return amount.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static NotFoundException recordNotFound(long recordId) {
        return new NotFoundException("Record " + recordId + " not found");
    }

    private static RecordRow recordRow(ResultSet row, int n) throws SQLException {
        return new RecordRow(row.getLong("id"), row.getString("type"), row.getObject("record_date", LocalDate.class),
                row.getLong("category_id"), row.getBigDecimal("base_amount"), row.getString("comment"),
                row.getLong("payer_member_id"), row.getString("split_method"), row.getLong("author_member_id"),
                row.getTimestamp("created_at").toInstant(), row.getLong("updated_by_member_id"),
                row.getTimestamp("updated_at").toInstant(), row.getInt("version"));
    }
}
