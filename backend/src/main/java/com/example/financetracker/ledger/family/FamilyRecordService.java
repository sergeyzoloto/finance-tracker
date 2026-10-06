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
import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.family.FamilyChangeView.Change;
import com.example.financetracker.ledger.family.FamilyRecordView.CategoryRef;
import com.example.financetracker.ledger.family.FamilyRecordView.MemberRef;
import com.example.financetracker.ledger.family.FamilyRecordView.ShareView;
import com.example.financetracker.ledger.family.RecordSplit.ShareInput;
import com.example.financetracker.ledger.family.ShareSplit.Share;
import com.example.financetracker.ledger.family.ShareSplit.Weight;
import com.example.financetracker.ledger.family.posting.FamilyPostingService;
import com.example.financetracker.ledger.family.posting.FamilyPostingService.OwnPayment;
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
 * A family ledger's records (C1, C3, C5, D2; D-6, D-12, D-14, D-16; ADR 0003, topics D and H): family expenses (F4a),
 * incomes and settlements (F4d), their shares, the members' balances and the change journal. Each record is in its own
 * currency, the family's main currency unless chosen, and its shares are split in it (D-45, ADR 0004); no exchange rate
 * is ever used (D-87): a side on an account in another currency names what went from or into it, and the posting
 * service routes it through the member's FX_EXCHANGE. Every method takes the family ledger's
 * {@link LedgerScope} of the member who acts, from {@code LedgerAccess.member}; the payment's account comes from their
 * personal ledger's. Each change is journaled and posted into the members' personal ledgers by
 * {@link FamilyPostingService} in the same transaction.
 * <p>
 * The rules: a record is dated on or after the ledger's start date (D-27, 409); its category is one of the family's,
 * not archived, of its type; its amount is above 0 in the currency's minor unit; a member with an account names only
 * themselves as payer, and says how they paid; shares go only to ACTIVE members, and a member with an account only
 * from their join date on, and they sum to 10000 basis points or to the amount (422, each violation with its code and
 * member). The author and the owners change the family fields; the payer with an account changes the payment fields
 * (the date, the amount, the payer, the account) and deletes, or for a payer without one the author or an owner (F4c);
 * and nobody changes a record that involves a member who left or deleted their data (D-19, frozen: 409). The
 * user-facing messages call a record an expense, an income or a settlement, as the screens do. An income (C5, F4d)
 * mirrors an expense: its payer is the member who received it, its category an INCOME one, and D-12's tie goes to the
 * receiver.
 * <p>
 * Settlements (F4d; D2, D-24): one member pays another, with no category and no shares. A member with an account
 * records one they pay or receive, with their own side's account or "Specify later"; an owner also records one between
 * two members without an account. The side who recorded it changes its date, amount and comment and deletes it (for
 * one between members without an account, its author or an owner), and each side with an account puts its own side on
 * an account of theirs.
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
    /** A new amount of an expense split by amounts needs the new amounts with it (F4c). */
    public static final String AMOUNTS_NEEDED = "AMOUNTS_NEEDED";
    /** The receiver of a settlement (F4d). */
    public static final String PAYEE = "PAYEE";
    /**
     * What went from or into a member's account in another currency than the record's (D-87): the payer's, the
     * receiver's, or a side's of a settlement (F4e).
     */
    public static final String ACCOUNT_AMOUNT = "ACCOUNT_AMOUNT";

    private static final String EXPENSE = "EXPENSE";
    private static final String INCOME = "INCOME";
    private static final String SETTLEMENT = "SETTLEMENT";

    private static final String RECORDS = """
            SELECT r.id, r.type, r.record_date, r.category_id, r.base_amount, r.currency, r.comment, r.payer_member_id,
                   r.payee_member_id, r.split_method, r.author_member_id, r.created_at, r.updated_by_member_id,
                   r.updated_at, r.version, r.original_amount, r.original_currency, r.base_rate, r.base_rate_source,
                   r.base_rate_date
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
     * Records a family expense or income (C5, F4d), and posts it into the members' personal ledgers. An income mirrors
     * an expense: its payer is the member who received it, its category an INCOME one, and each share is posted as
     * income, with the receiver's receipt on the account they name.
     *
     * @param family the family ledger, as the member who enters it: its author
     * @param personal the author's personal ledger, which the payment's account is in
     * @throws ConflictException if it is dated before the ledger's start date (D-27)
     * @throws RuleViolationException listing every other rule it breaks
     */
    @Transactional
    public FamilyRecordView create(LedgerScope family, LedgerScope personal, NewFamilyRecord request) {
        requireOwnPersonal(family, personal);
        Ledger ledger = lockLedger(family);
        String type = request.type();
        requireStarted(ledger, request.date(), type);
        // The record's own currency, the main currency unless chosen (D-45): its amount and its shares are in it.
        String currency = request.currency() == null ? ledger.baseCurrency() : request.currency();
        int scale = ShareSplit.minorUnit(currency);
        Map<Long, Member> members = members(family);
        List<Violation> violations = new ArrayList<>();
        checkCategory(family, request.categoryId(), type, violations);
        checkAmount(request.amount(), currency, scale, violations);
        Member payer = members.get(request.payerMemberId());
        Payment payment = payment(family, personal, request, payer, violations);
        BigDecimal amount = null;
        Side side = null;
        List<Share> shares = List.of();
        if (amountValid(violations)) {
            amount = inMinorUnits(request.amount(), currency);
            // The paying side once the payer and their account are valid, so that its rules don't add to theirs.
            side = violations.isEmpty() ? payingSide(personal, payment, amount, currency, request.accountAmount(), null,
                    true, request.payerMemberId(), violations, type, way(type, true)) : null;
            shares = split(request.split(), amount, scale, request.date(), request.payerMemberId(), ledger, members,
                    Set.of(), violations, type);
        }
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }

        String method = method(request.split(), ledger);
        long recordId = jdbc.sql("""
                INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id,
                    original_amount, original_currency, base_amount, currency, base_rate, base_rate_source,
                    base_rate_date, split_method, comment, author_member_id, updated_by_member_id)
                VALUES (:ledgerId, :type, :date, :categoryId, :payerId, :original, :originalCurrency, :amount,
                        :currency, :rate, :source, :rateDate, :method, :comment, :memberId, :memberId)
                RETURNING id""")
                .param("ledgerId", family.ledgerId()).param("type", type).param("date", request.date())
                .param("categoryId", request.categoryId()).param("payerId", request.payerMemberId())
                .param("original", side.amount()).param("originalCurrency", side.currency())
                .param("amount", amount).param("currency", currency).param("rate", side.rate())
                .param("source", side.source()).param("rateDate", side.rateDate()).param("method", method)
                .param("comment", request.comment()).param("memberId", family.memberId())
                .query(Long.class).single();
        for (Share share : shares) {
            insertShare(family, recordId, share);
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(change("date", null, null, request.date().toString()));
        changes.add(change("category", null, null, request.categoryId()));
        changes.add(change("amount", null, null, text(amount, scale)));
        if (!currency.equals(ledger.baseCurrency())) {
            changes.add(change("currency", null, null, currency));
        }
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
                .orElseThrow(() -> recordNotFound(family, recordId));
        return views(family, List.of(row)).getFirst();
    }

    /**
     * Changes the record's family fields or its payment fields (D-14), journals what changed, and posts the record
     * again, the payer's payment included, in one transaction. A new date, amount or payer splits the amount again by
     * the record's stored split, unless the change brings a split of its own ({@link #resplit}); only the private side
     * of the payment, the account and the payer's note, changes no field of the record and nothing in its journal.
     *
     * @param family the family ledger, as its author or one of its owners for the family fields, and for the payment
     *        fields as the payer if they have an account, else as the author or an owner
     * @param personal the caller's personal ledger, which the account they paid with is in
     * @param expectedVersion the version the caller read; null for a change of the payer's own payment entry, whose
     *        own version the caller read (FamilyPaymentEntries)
     * @throws NotFoundException if the family ledger has no such record, or it is deleted
     * @throws OptimisticLockingFailureException if the record is no longer at {@code expectedVersion}
     * @throws ConflictException if the member may not change it, it is frozen, or the date is before the start date
     * @throws RuleViolationException listing every rule the new fields break
     */
    @Transactional
    public FamilyRecordView update(LedgerScope family, LedgerScope personal, long recordId, Integer expectedVersion,
            FamilyRecordChanges changes) {
        requireOwnPersonal(family, personal);
        Ledger ledger = lockLedger(family);
        RecordRow record = lockRecord(family, recordId, expectedVersion);
        if (record.type().equals(SETTLEMENT)) {
            return updateSettlement(family, personal, ledger, record, changes);
        }
        Map<Long, Member> members = members(family);
        Member payer = members.get(record.payerId());
        boolean mayEditFamily = record.authorId() == family.memberId() || family.role() == MemberRole.OWNER;
        boolean mayEditPayment = payer.hasAccount() ? record.payerId() == family.memberId() : mayEditFamily;
        LocalDate date = changes.date() == null ? record.date() : changes.date();
        long payerId = changes.payerMemberId() == null ? record.payerId() : changes.payerMemberId();
        // The record's currency, or a new one with its amount (D-45). A split that comes with a change of the amount
        // belongs to the payment's change: under AMOUNT it has to come (D-14).
        String currency = changes.currency() == null ? record.currency() : changes.currency();
        boolean movesAmount = changes.amount() != null && changes.amount().compareTo(record.amount()) != 0
                || !currency.equals(record.currency());
        boolean familyFields = changes.categoryId() != null || changes.changesComment()
                || changes.split() != null && !(mayEditPayment && movesAmount);
        String type = record.type();
        if (familyFields && !mayEditFamily) {
            throw new ConflictException(("Only the %s's author or an owner of the family budget can change its "
                    + "category, split or comment").formatted(noun(type)));
        }
        if ((changes.changesPayment() || !mayEditFamily) && !mayEditPayment) {
            throw new ConflictException(payer.hasAccount()
                    ? (type.equals(INCOME)
                            ? "Only %s, who received it, can change the income's date, amount, receiver or receiving "
                                    + "account"
                            : "Only %s, who paid it, can change the expense's date, amount, payer or paying account")
                            .formatted(payer.displayName())
                    : "Only the %s's author or an owner of the family budget can change it".formatted(noun(type)));
        }
        requireNotFrozen(family, record, members);
        if (!date.equals(record.date())) {
            requireStarted(ledger, date, record.type());
        }
        int scale = ShareSplit.minorUnit(currency);
        int oldScale = ShareSplit.minorUnit(record.currency());

        List<Violation> violations = new ArrayList<>();
        long categoryId = record.categoryId();
        if (changes.categoryId() != null && changes.categoryId() != record.categoryId()) {
            checkCategory(family, changes.categoryId(), type, violations);
            categoryId = changes.categoryId();
        }
        Map<Long, ShareRow> oldShares = shares(family, List.of(recordId)).getOrDefault(recordId, Map.of());
        Set<Long> guests = guests(record, oldShares, members);
        Payment payment = changedPayment(family, personal, record, changes, date, payerId, members, guests,
                violations);
        // The record's amount in its currency, or in its new one, which needs its amount (D-45).
        BigDecimal amount = record.amount();
        if (!currency.equals(record.currency()) && changes.amount() == null) {
            violations.add(new Violation(AMOUNT, null, "the %s is in %s now, not %s: give its amount in %s"
                    .formatted(noun(type), currency, record.currency(), currency)));
        } else if (changes.amount() != null) {
            checkAmount(changes.amount(), currency, scale, violations);
            if (amountValid(violations)) {
                amount = inMinorUnits(changes.amount(), currency);
            }
        }
        boolean amountChanged = amount.compareTo(record.amount()) != 0 || !currency.equals(record.currency());
        Side side = sideOf(record);
        if (violations.isEmpty()) {
            side = changedSide(family, personal, record, changes, payment, payerId, date, amount, currency,
                    amountChanged, members, violations);
        }
        String comment = changes.changesComment() ? changes.comment() : record.comment();
        List<Share> newShares = null;
        String method = record.splitMethod();
        boolean amountValid = violations.stream().noneMatch(v -> v.code().equals(AMOUNT));
        if (changes.split() != null) {
            newShares = amountValid ? split(changes.split(), amount, scale, date, payerId, ledger, members, guests,
                    violations, type) : List.of();
            method = method(changes.split(), ledger);
        } else if (amountChanged || !date.equals(record.date()) || payerId != record.payerId()) {
            newShares = amountValid ? resplit(record, oldShares, amount, amountChanged, scale, date, payerId, members,
                    guests, violations) : List.of();
        }
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }

        List<Map<String, Object>> journal = new ArrayList<>();
        if (!date.equals(record.date())) {
            journal.add(change("date", null, record.date().toString(), date.toString()));
        }
        if (categoryId != record.categoryId()) {
            journal.add(change("category", null, record.categoryId(), categoryId));
        }
        journalAmount(journal, record, amount, currency);
        if (payerId != record.payerId()) {
            journal.add(change("payer", null, record.payerId(), payerId));
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
                        journal.add(change("share", memberId, old == null ? null : text(old.amount(), oldScale),
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
        boolean recordChanged = !journal.isEmpty() || rewritten;
        boolean sideChanged = !sameSide(side, sideOf(record));
        if (recordChanged) {
            jdbc.sql("""
                    UPDATE family_record
                    SET record_date = :date, category_id = :categoryId, payer_member_id = :payerId,
                        original_amount = :original, original_currency = :originalCurrency, base_amount = :amount,
                        currency = :currency, base_rate = :rate, base_rate_source = :source, base_rate_date = :rateDate,
                        comment = :comment, split_method = :method, updated_by_member_id = :memberId,
                        updated_at = now(), version = version + 1
                    WHERE id = :recordId AND ledger_id = :ledgerId""")
                    .param("date", date).param("categoryId", categoryId).param("payerId", payerId)
                    .param("original", side.amount()).param("originalCurrency", side.currency())
                    .param("amount", amount).param("currency", currency).param("rate", side.rate())
                    .param("source", side.source()).param("rateDate", side.rateDate()).param("comment", comment)
                    .param("method", method).param("memberId", family.memberId()).param("recordId", recordId)
                    .param("ledgerId", family.ledgerId())
                    .update();
            if (!journal.isEmpty()) {
                journal(family, recordId, "UPDATE", journal);
            }
        } else if (sideChanged) {
            updateSide(family, recordId, side);
        }
        if (recordChanged || sideChanged || !(payment instanceof FamilyPostingService.Unchanged)) {
            posting.post(family, recordId, payment);
        }
        return get(family, recordId);
    }

    /**
     * Deletes the record: it stays for the journal, marked deleted, and all its posted entries go, the payment
     * included (D-14), or both sides of a settlement.
     *
     * @param family the family ledger, as the payer if they have an account, else as the author or an owner; for a
     *        settlement, as the side who recorded it, or between two members without an account as its author or an
     *        owner
     * @param expectedVersion the version the caller read; null for the deletion of the payer's own payment entry,
     *        whose own version the caller read (FamilyPaymentEntries)
     * @throws NotFoundException if the family ledger has no such record, or it is deleted already
     * @throws OptimisticLockingFailureException if the record is no longer at {@code expectedVersion}
     * @throws ConflictException if the member may not delete it, or it is frozen
     */
    @Transactional
    public void delete(LedgerScope family, long recordId, Integer expectedVersion) {
        lockLedger(family);
        RecordRow record = lockRecord(family, recordId, expectedVersion);
        Map<Long, Member> members = members(family);
        Member payer = members.get(record.payerId());
        if (record.type().equals(SETTLEMENT)) {
            if (!maySettle(family, record)) {
                throw new ConflictException(notRecorder(record, members, "delete this settlement"));
            }
        } else if (payer.hasAccount() ? record.payerId() != family.memberId()
                : record.authorId() != family.memberId() && family.role() != MemberRole.OWNER) {
            throw new ConflictException(payer.hasAccount()
                    ? "Only %s, who %s it, can delete this %s".formatted(payer.displayName(), paid(record.type()),
                            noun(record.type()))
                    : "Only the %s's author or an owner of the family budget can delete it".formatted(
                            noun(record.type())));
        }
        requireNotFrozen(family, record, members);
        if (record.type().equals(SETTLEMENT)) {
            requireNotPlaced(family, record, members, "it can't be deleted");
        }
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
     * Every member's balance in each currency (D-45, ADR 0004), B(m) = their expense shares − the expenses they paid +
     * the incomes they received − their income shares − the settlements they paid + the settlements they received, over
     * the records in that currency that aren't deleted (ADR 0003, topic D). In each currency the balances sum to zero,
     * since every record's shares add up to its amount and a settlement moves as much to one as from the other.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FamilyBalances balances(LedgerScope family) {
        String main = jdbc.sql("SELECT base_currency FROM ledger WHERE id = :ledgerId")
                .param("ledgerId", family.ledgerId()).query(String.class).single();
        List<String> currencies = new ArrayList<>(List.of(main));
        currencies.addAll(jdbc.sql("""
                SELECT DISTINCT currency FROM family_record
                WHERE ledger_id = :ledgerId AND deleted_at IS NULL AND currency <> :main
                ORDER BY currency""")
                .param("ledgerId", family.ledgerId()).param("main", main).query(String.class).list());
        List<FamilyBalances.CurrencyBalances> byCurrency = new ArrayList<>();
        for (String currency : currencies) {
            byCurrency.add(new FamilyBalances.CurrencyBalances(currency, balancesIn(family, currency)));
        }
        return new FamilyBalances(main, byCurrency.getFirst().members(), byCurrency);
    }

    /** Every member's balance in the currency, by join order. */
    private List<FamilyBalances.MemberBalance> balancesIn(LedgerScope family, String currency) {
        int scale = ShareSplit.minorUnit(currency);
        return jdbc.sql("""
                SELECT m.id, m.display_name, m.status, m.user_sub IS NOT NULL AS has_account,
                       coalesce((SELECT sum(CASE r.type WHEN 'EXPENSE' THEN s.amount ELSE -s.amount END)
                                 FROM family_share s JOIN family_record r ON r.id = s.record_id
                                 WHERE s.member_id = m.id AND r.ledger_id = :ledgerId AND r.deleted_at IS NULL
                                   AND r.currency = :currency), 0)
                       - coalesce((SELECT sum(CASE r.type WHEN 'INCOME' THEN -r.base_amount ELSE r.base_amount END)
                                   FROM family_record r
                                   WHERE r.payer_member_id = m.id AND r.ledger_id = :ledgerId
                                     AND r.deleted_at IS NULL AND r.currency = :currency), 0)
                       + coalesce((SELECT sum(r.base_amount) FROM family_record r
                                   WHERE r.payee_member_id = m.id AND r.ledger_id = :ledgerId
                                     AND r.deleted_at IS NULL AND r.currency = :currency), 0) AS balance
                FROM ledger_member m
                WHERE m.ledger_id = :ledgerId
                ORDER BY m.join_date, m.id""")
                .param("ledgerId", family.ledgerId()).param("currency", currency)
                .query((row, n) -> new FamilyBalances.MemberBalance(row.getLong("id"), row.getString("display_name"),
                        MemberStatus.valueOf(row.getString("status")), row.getBoolean("has_account"),
                        row.getBigDecimal("balance").setScale(scale, RoundingMode.UNNECESSARY),
                        row.getLong("id") == family.memberId()))
                .list();
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
                       c.changes::text AS changes, r.type, r.record_date, r.category_id, r.base_amount, r.currency,
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
        List<FamilyChangeView> content = rows.query((row, n) -> new FamilyChangeView(row.getLong("id"),
                        row.getTimestamp("changed_at").toInstant(), row.getString("action"),
                        row.getObject("record_id", Long.class),
                        ref(members, row.getObject("changed_by_member_id", Long.class)),
                        ref(members, row.getObject("about_member_id", Long.class)),
                        changes(row.getString("changes"), members, categories),
                        row.getObject("record_id") == null ? null : new FamilyChangeView.RecordSummary(
                                row.getObject("record_date", LocalDate.class),
                                categoryName(categories, row.getObject("category_id", Long.class)),
                                row.getBigDecimal("base_amount").setScale(ShareSplit.minorUnit(row.getString("currency")),
                                        RoundingMode.UNNECESSARY),
                                row.getBoolean("deleted"), row.getString("type"))))
                .list();
        return new FamilyJournalPage(content, page, size, total, Math.toIntExact((total + size - 1) / size));
    }

    // --- Settlements (F4d; D2, D-24) ---

    /**
     * Records a settlement between two members, and posts each side with an account into their personal ledger: the
     * recorder's with the account they name or "Specify later", the other's to their "Payments without a specified
     * account" (D-24).
     *
     * @param family the family ledger, as the member who records it: its payer or receiver with an account, or an
     *        owner for a settlement between two members without an account
     * @param personal the recorder's personal ledger, which their side's account is in
     * @throws ConflictException if it is dated before the ledger's start date (D-27), or a member who isn't an owner
     *         records one between two members without an account (D-15)
     * @throws RuleViolationException listing every other rule it breaks
     */
    @Transactional
    public FamilyRecordView settle(LedgerScope family, LedgerScope personal, NewSettlement request) {
        requireOwnPersonal(family, personal);
        Ledger ledger = lockLedger(family);
        requireStarted(ledger, request.date(), SETTLEMENT);
        // In one currency, the main currency unless chosen, whose balances it settles (D-46).
        String currency = request.currency() == null ? ledger.baseCurrency() : request.currency();
        int scale = ShareSplit.minorUnit(currency);
        Map<Long, Member> members = members(family);
        List<Violation> violations = new ArrayList<>();
        checkAmount(request.amount(), currency, scale, violations);
        Member payer = active(members, request.payerMemberId(), PAYER, violations);
        Member payee = active(members, request.payeeMemberId(), PAYEE, violations);
        boolean namesAccount = request.paymentAccountId() != null || request.paymentLater();
        Payment payment = new FamilyPostingService.Unchanged();
        if (payer != null && payee != null && payer.id() == payee.id()) {
            violations.add(new Violation(PAYEE, payee.id(), "%s can't settle with themselves; name who received it"
                    .formatted(payee.displayName())));
        } else if (payer != null && payee != null) {
            Member recorder = members.get(family.memberId());
            if (recorder.id() == payer.id() || recorder.id() == payee.id()) {
                for (Member side : List.of(payer, payee)) {
                    if (!joinedBy(side, request.date())) {
                        violations.add(joinedAfter(side, request.date(), SETTLEMENT));
                    }
                }
                if (joinedBy(recorder, request.date()) && recorder.joinDate().isAfter(request.date())) {
                    // A claimed seat's settlement before the claim's date (D-35): in their opening balance (D-32).
                    if (namesAccount) {
                        violations.add(beforeJoining(recorder, SETTLEMENT));
                    }
                } else if (joinedBy(recorder, request.date())) {
                    payment = paidWith(personal, recorder.id(), request.paymentAccountId(), request.paymentLater(),
                            null, violations, SETTLEMENT);
                }
            } else if (payer.hasAccount() || payee.hasAccount()) {
                Member side = payer.hasAccount() ? payer : payee;
                violations.add(new Violation(side == payer ? PAYER : PAYEE, side.id(), ("%s has an account: only they "
                        + "record a settlement they pay or receive").formatted(side.displayName())));
            } else {
                if (family.role() != MemberRole.OWNER) {
                    throw new ConflictException("Only an owner of the family budget records a settlement between two "
                            + "members without an account");
                }
                if (namesAccount) {
                    violations.add(new Violation(PAYMENT, family.memberId(), "you neither pay nor receive this "
                            + "settlement, so no account of yours is in it"));
                }
            }
        }
        BigDecimal amount = null;
        Side side = null;
        if (violations.isEmpty()) {
            // The recorder's own side: on their account, in its currency with what went from or into it (D-87).
            amount = inMinorUnits(request.amount(), currency);
            side = payingSide(personal, payment, amount, currency, request.accountAmount(), null, true,
                    family.memberId(), violations, SETTLEMENT, way(SETTLEMENT, request.payerMemberId() == family.memberId()));
        }
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }

        long recordId = jdbc.sql("""
                INSERT INTO family_record (ledger_id, type, record_date, payer_member_id, payee_member_id,
                    original_amount, original_currency, base_amount, currency, base_rate, base_rate_source,
                    base_rate_date, comment, author_member_id, updated_by_member_id)
                VALUES (:ledgerId, 'SETTLEMENT', :date, :payerId, :payeeId, :original, :originalCurrency, :amount,
                        :currency, :rate, :source, :rateDate, :comment, :memberId, :memberId)
                RETURNING id""")
                .param("ledgerId", family.ledgerId()).param("date", request.date())
                .param("payerId", request.payerMemberId()).param("payeeId", request.payeeMemberId())
                .param("original", side.amount()).param("originalCurrency", side.currency())
                .param("amount", amount).param("currency", currency).param("rate", side.rate())
                .param("source", side.source()).param("rateDate", side.rateDate()).param("comment", request.comment())
                .param("memberId", family.memberId())
                .query(Long.class).single();
        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(change("date", null, null, request.date().toString()));
        changes.add(change("amount", null, null, text(amount, scale)));
        if (!currency.equals(ledger.baseCurrency())) {
            changes.add(change("currency", null, null, currency));
        }
        changes.add(change("payer", null, null, request.payerMemberId()));
        changes.add(change("payee", null, null, request.payeeMemberId()));
        if (request.comment() != null) {
            changes.add(change("comment", null, null, request.comment()));
        }
        journal(family, recordId, "CREATE", changes);
        posting.post(family, recordId, payment);
        return get(family, recordId);
    }

    /**
     * A settlement's change: its date, amount and comment by who may change it ({@link #maySettle}), and a side's
     * account by that side. Its members and its type don't change; a settlement has no category, split or note. The
     * account isn't journaled, and alone it changes neither the version nor the editor (D-16).
     */
    private FamilyRecordView updateSettlement(LedgerScope family, LedgerScope personal, Ledger ledger,
            RecordRow record, FamilyRecordChanges changes) {
        Map<Long, Member> members = members(family);
        boolean changesRecord = changes.date() != null || changes.changesAmount() || changes.changesComment();
        boolean ownSide = record.payerId() == family.memberId() || record.payeeId() == family.memberId();
        // The recorder's own side is in original_* (D-87); the other side's in the settlement's currency, or in
        // their account's with the amount they name (F4e), which only their entry holds.
        boolean recordersSide = ownSide && record.authorId() == family.memberId();
        if ((changesRecord || !ownSide) && !maySettle(family, record)) {
            throw new ConflictException(notRecorder(record, members, "change the settlement's date, amount or comment"));
        }
        requireNotFrozen(family, record, members);
        LocalDate date = changes.date() == null ? record.date() : changes.date();
        String currency = changes.currency() == null ? record.currency() : changes.currency();
        if (!date.equals(record.date()) || changes.amount() != null
                && changes.amount().compareTo(record.amount()) != 0 || !currency.equals(record.currency())) {
            requireNotPlaced(family, record, members, "its date and amount can't change");
        }
        if (!date.equals(record.date())) {
            requireStarted(ledger, date, SETTLEMENT);
        }
        int scale = ShareSplit.minorUnit(currency);

        List<Violation> violations = new ArrayList<>();
        if (changes.categoryId() != null) {
            violations.add(new Violation(CATEGORY, null, "a settlement has no category"));
        }
        if (changes.split() != null) {
            violations.add(new Violation(SHARE, null, "a settlement has no shares"));
        }
        if (changes.payerMemberId() != null && changes.payerMemberId() != record.payerId()) {
            violations.add(new Violation(PAYER, changes.payerMemberId(), "who paid and who received a settlement "
                    + "don't change: delete it and record it again"));
        }
        if (changes.changesNote()) {
            violations.add(new Violation(PAYMENT, family.memberId(), "a settlement takes no private note"));
        }
        Set<Long> guests = guests(record, Map.of(), members);
        for (long sideId : List.of(record.payerId(), record.payeeId())) {
            Member side = members.get(sideId);
            if (!date.equals(record.date()) && !sharesOn(side, date, guests)) {
                violations.add(joinedAfter(side, date, SETTLEMENT));
            }
        }
        Payment payment = new FamilyPostingService.Unchanged();
        if (changes.namesAccount() && ownSide && members.get(family.memberId()).joinDate().isAfter(date)) {
            violations.add(beforeJoining(members.get(family.memberId()), SETTLEMENT));
        } else if (changes.namesAccount() && ownSide) {
            payment = paidWith(personal, family.memberId(), changes.paymentAccountId(), changes.paymentLater(), null,
                    violations, SETTLEMENT);
        } else if (changes.namesAccount()) {
            violations.add(new Violation(PAYMENT, family.memberId(), "you neither pay nor receive this settlement, so "
                    + "no account of yours is in it"));
        }
        // Its amount in its currency, or in a new one, which needs its amount (D-46).
        BigDecimal amount = record.amount();
        if (!currency.equals(record.currency()) && changes.amount() == null) {
            violations.add(new Violation(AMOUNT, null, "the settlement is in %s now, not %s: give its amount in %s"
                    .formatted(currency, record.currency(), currency)));
        } else if (changes.amount() != null) {
            checkAmount(changes.amount(), currency, scale, violations);
            if (amountValid(violations)) {
                amount = inMinorUnits(changes.amount(), currency);
            }
        }
        boolean amountChanged = amount.compareTo(record.amount()) != 0 || !currency.equals(record.currency());
        boolean bySide = record.authorId() == record.payerId() || Objects.equals(record.authorId(), record.payeeId());
        Side side = sideOf(record);
        if (violations.isEmpty()) {
            String way = way(SETTLEMENT, record.authorId() == record.payerId());
            if (!bySide) {
                // Recorded by an owner between two members without an account: nobody's account.
                side = Side.of(amount, currency);
            } else if (recordersSide && !(payment instanceof FamilyPostingService.Unchanged)) {
                side = payingSide(personal, payment, amount, currency, changes.accountAmount(), sideOf(record),
                        amountChanged, family.memberId(), violations, SETTLEMENT, way);
            } else if (recordersSide) {
                side = keptSide(family, personal, record, amount, currency, amountChanged, changes.accountAmount(),
                        violations, way);
            }
        }
        if (payment instanceof FamilyPostingService.OwnAccount own && ownSide && !recordersSide) {
            payment = otherSidesAccount(personal, own, changes.accountAmount(), currency, amount, record,
                    family.memberId(), violations);
        } else if (!recordersSide && changes.accountAmount() != null) {
            violations.add(new Violation(ACCOUNT_AMOUNT, family.memberId(), ("name the amount only with an account of "
                    + "yours in another currency than %s").formatted(currency)));
        }
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }

        List<Map<String, Object>> journal = new ArrayList<>();
        if (!date.equals(record.date())) {
            journal.add(change("date", null, record.date().toString(), date.toString()));
        }
        journalAmount(journal, record, amount, currency);
        String comment = changes.changesComment() ? changes.comment() : record.comment();
        if (!Objects.equals(comment, record.comment())) {
            journal.add(change("comment", null, record.comment(), comment));
        }
        boolean sideChanged = !sameSide(side, sideOf(record));
        if (!journal.isEmpty()) {
            jdbc.sql("""
                    UPDATE family_record
                    SET record_date = :date, original_amount = :original, original_currency = :originalCurrency,
                        base_amount = :amount, currency = :currency, base_rate = :rate, base_rate_source = :source,
                        base_rate_date = :rateDate, comment = :comment, updated_by_member_id = :memberId,
                        updated_at = now(), version = version + 1
                    WHERE id = :recordId AND ledger_id = :ledgerId""")
                    .param("date", date).param("original", side.amount()).param("originalCurrency", side.currency())
                    .param("amount", amount).param("currency", currency).param("rate", side.rate())
                    .param("source", side.source()).param("rateDate", side.rateDate()).param("comment", comment)
                    .param("memberId", family.memberId()).param("recordId", record.id())
                    .param("ledgerId", family.ledgerId())
                    .update();
            journal(family, record.id(), "UPDATE", journal);
        } else if (sideChanged) {
            updateSide(family, record.id(), side);
        }
        if (!journal.isEmpty() || sideChanged || !(payment instanceof FamilyPostingService.Unchanged)) {
            posting.post(family, record.id(), payment);
        }
        return get(family, record.id());
    }

    /**
     * Who changes and deletes a settlement: the side who recorded it; for one between two members without an account,
     * which an owner recorded, its author or an owner.
     */
    private static boolean maySettle(LedgerScope family, RecordRow settlement) {
        boolean bySide = settlement.authorId() == settlement.payerId()
                || Objects.equals(settlement.authorId(), settlement.payeeId());
        return settlement.authorId() == family.memberId() || !bySide && family.role() == MemberRole.OWNER;
    }

    /**
     * The settlement lock (D-28): once the other side, a member with an account, has put its part on an account of
     * theirs, only they could move it, so the settlement's date and amount don't change and it isn't deleted. They
     * move it back to "Specify later" to allow it. Nobody's action changes another member's accounts (D-8).
     *
     * @throws ConflictException naming the other side, if their part is on an account of theirs
     */
    private void requireNotPlaced(LedgerScope family, RecordRow settlement, Map<Long, Member> members, String what) {
        Long placedBy = placedSides(family, List.of(settlement.id())).get(settlement.id());
        if (placedBy != null) {
            String name = name(members, placedBy);
            throw new ConflictException(("%s has put their side of this settlement on an account of theirs, so %s; %s "
                    + "can move it back to \"Specify later\" to allow it").formatted(name, what, name));
        }
    }

    /**
     * Of these settlements, those whose other side, the side that didn't record it, has put its part on an account of
     * theirs: by settlement, that side. Their link is then their own, not the family budget's (D-24, D-28).
     */
    private Map<Long, Long> placedSides(LedgerScope family, List<Long> recordIds) {
        Map<Long, Long> placed = new HashMap<>();
        if (recordIds.isEmpty()) {
            return placed;
        }
        jdbc.sql("""
                SELECT l.record_id, l.member_id
                FROM family_entry_link l JOIN family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                WHERE l.family_ledger_id = :ledgerId AND l.record_id IN (:recordIds) AND r.type = 'SETTLEMENT'
                  AND l.link_type = 'SETTLEMENT' AND l.detached_at IS NULL AND NOT l.system_owned
                  AND l.member_id <> r.author_member_id""")
                .param("ledgerId", family.ledgerId()).param("recordIds", recordIds)
                .query(row -> {
                    placed.put(row.getLong("record_id"), row.getLong("member_id"));
                });
        return placed;
    }

    private static String notRecorder(RecordRow settlement, Map<Long, Member> members, String what) {
        boolean bySide = settlement.authorId() == settlement.payerId()
                || Objects.equals(settlement.authorId(), settlement.payeeId());
        return bySide ? "Only %s, who recorded it, can %s".formatted(name(members, settlement.authorId()), what)
                : "Only the settlement's author or an owner of the family budget can %s".formatted(what);
    }

    /** The member, if they are an ACTIVE member of the family ledger; else a violation with the code. */
    private static Member active(Map<Long, Member> members, long memberId, String code, List<Violation> violations) {
        Member member = members.get(memberId);
        if (member == null || member.status() != MemberStatus.ACTIVE) {
            violations.add(new Violation(code, memberId,
                    "Member %d is not an active member of the family budget".formatted(memberId)));
            return null;
        }
        return member;
    }

    // --- The rules ---

    private static void requireOwnPersonal(LedgerScope family, LedgerScope personal) {
        if (personal.type() != LedgerType.PERSONAL || !personal.userId().equals(family.userId())) {
            throw new IllegalArgumentException("The payment's account is in the caller's own personal ledger");
        }
    }

    private void requireStarted(Ledger ledger, LocalDate date, String type) {
        if (date.isBefore(ledger.startDate())) {
            throw new ConflictException(("The family budget starts on %s, and %s can't be dated before its start date")
                    .formatted(ledger.startDate(), article(type)));
        }
    }

    /** What the screens call a record of the type: "expense", "income" or "settlement". */
    private static String noun(String type) {
        return switch (type) {
            case INCOME -> "income";
            case SETTLEMENT -> "settlement";
            default -> "expense";
        };
    }

    private static String article(String type) {
        return (type.equals(SETTLEMENT) ? "a " : "an ") + noun(type);
    }

    /** What the payer of a record of the type did with its amount: "paid" an expense, "received" an income. */
    private static String paid(String type) {
        return type.equals(INCOME) ? "received" : "paid";
    }

    private static String capitalized(String type) {
        String noun = noun(type);
        return Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
    }

    private void checkCategory(LedgerScope family, long categoryId, String type, List<Violation> violations) {
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
                    } else if (!category.type().equals(type)) {
                        violations.add(new Violation(CATEGORY, null,
                                "the category %s is %s, and %s needs an %s category"
                                        .formatted(category.code(), category.type(), article(type), type)));
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
     * theirs they paid with, or that they specify it later; a member without an account says nothing about it. Only the
     * payer's own payment takes their private note (C2).
     */
    private Payment payment(LedgerScope family, LedgerScope personal, NewFamilyRecord request, Member payer,
            List<Violation> violations) {
        long payerId = request.payerMemberId();
        String type = request.type();
        if (payer == null || payer.status() != MemberStatus.ACTIVE) {
            violations.add(new Violation(PAYER, payerId,
                    "Member %d is not an active member of the family budget".formatted(payerId)));
            return new FamilyPostingService.Unchanged();
        }
        boolean named = request.paymentAccountId() != null || request.paymentLater();
        if (!payer.hasAccount()) {
            if (named) {
                violations.add(noAccount(payer));
            }
            if (request.privateNote() != null) {
                violations.add(notYourPayment(payerId, type));
            }
            return new FamilyPostingService.Unchanged();
        }
        if (payerId != family.memberId()) {
            violations.add(notYou(payer, type));
            return new FamilyPostingService.Unchanged();
        }
        if (!joinedBy(payer, request.date())) {
            violations.add(joinedAfter(payer, request.date(), type));
        } else if (payer.joinDate().isAfter(request.date())) {
            // A claimed seat's record before the claim's date (D-35): in their opening balance, with no payment entry
            // and no account of theirs (D-32).
            if (named || request.privateNote() != null) {
                violations.add(beforeJoining(payer, type));
            }
            return new FamilyPostingService.Unchanged();
        }
        return paidWith(personal, payerId, request.paymentAccountId(), request.paymentLater(), request.privateNote(),
                violations, type);
    }

    /**
     * How the payment changes (D-14). To a member without an account: no payment of anyone's, and the old payer's goes.
     * To the caller: they say how they paid, as for a new record. The payer with an account who stays: the account or
     * "Specify later" they name now, else theirs as it is, with their note or the one they send. Only the caller can
     * become a payer with an account.
     */
    private Payment changedPayment(LedgerScope family, LedgerScope personal, RecordRow record,
            FamilyRecordChanges changes, LocalDate date, long payerId, Map<Long, Member> members, Set<Long> guests,
            List<Violation> violations) {
        Member payer = members.get(payerId);
        String type = record.type();
        if (payerId != record.payerId()) {
            if (payer == null || payer.status() != MemberStatus.ACTIVE) {
                violations.add(new Violation(PAYER, payerId,
                        "Member %d is not an active member of the family budget".formatted(payerId)));
                return new FamilyPostingService.Unchanged();
            }
            if (!payer.hasAccount()) {
                if (changes.namesAccount()) {
                    violations.add(noAccount(payer));
                }
                if (changes.changesNote()) {
                    violations.add(notYourPayment(payerId, type));
                }
                return new FamilyPostingService.Unchanged();
            }
            if (payerId != family.memberId()) {
                violations.add(notYou(payer, type));
                return new FamilyPostingService.Unchanged();
            }
            if (!joinedBy(payer, date)) {
                violations.add(joinedAfter(payer, date, type));
            } else if (payer.joinDate().isAfter(date)) {
                // A claimed seat paid before the claim's date (D-35): in their opening balance (D-32).
                if (changes.namesAccount() || changes.changesNote()) {
                    violations.add(beforeJoining(payer, type));
                }
                return new FamilyPostingService.Unchanged();
            }
            return paidWith(personal, payerId, changes.paymentAccountId(), changes.paymentLater(), changes.note(),
                    violations, type);
        }
        if (!payer.hasAccount()) {
            if (changes.namesAccount()) {
                violations.add(noAccount(payer));
            }
            if (changes.changesNote()) {
                violations.add(notYourPayment(payerId, type));
            }
            return new FamilyPostingService.Unchanged();
        }
        // The payer with an account stays, and is the caller: only they change the payment fields.
        if (payer.joinDate().isAfter(date)) {
            // Paid before they took their seat (F5, D-18; D-35): in their opening balance, with no payment entry.
            if (!sharesOn(payer, date, guests)) {
                violations.add(joinedAfter(payer, date, type));
            } else if (changes.namesAccount() || changes.changesNote()) {
                violations.add(beforeJoining(payer, type));
            }
            return new FamilyPostingService.Unchanged();
        }
        if (!changes.namesAccount() && !changes.changesNote()) {
            // Moved from before their join date to it or after: posted now, to "Specify later" (D-18).
            return guests.contains(payerId) && record.date().isBefore(payer.joinDate())
                    ? new FamilyPostingService.Later(null) : new FamilyPostingService.Unchanged();
        }
        OwnPayment current = posting.ownPayments(family, List.of(record.id())).get(record.id());
        String note = changes.changesNote() ? changes.note() : current == null ? null : current.note();
        if (changes.namesAccount()) {
            return paidWith(personal, payerId, changes.paymentAccountId(), changes.paymentLater(), note, violations,
                    type);
        }
        return current == null || current.later() ? new FamilyPostingService.Later(note)
                : new FamilyPostingService.OwnAccount(current.accountId(), note);
    }

    /**
     * The account of the payer's own personal ledger they paid with, or "Specify later", as they name it; for an
     * income, the account the receiver received it into, and for a settlement, the member's own side's (F4d).
     */
    private Payment paidWith(LedgerScope personal, long payerId, Long accountId, boolean later, String note,
            List<Violation> violations, String type) {
        String which = switch (type) {
            case SETTLEMENT -> "name the account your side of it went from or into";
            case INCOME -> "name the account you received it into";
            default -> "name the account you paid with";
        };
        if (accountId != null && later) {
            violations.add(new Violation(PAYMENT, payerId, which + ", or specify it later, not both"));
            return new FamilyPostingService.Unchanged();
        }
        if (later) {
            return new FamilyPostingService.Later(note);
        }
        if (accountId == null) {
            violations.add(new Violation(PAYMENT, payerId, which + ", or specify it later"));
            return new FamilyPostingService.Unchanged();
        }
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
            violations.add(new Violation(PAYMENT, payerId, (switch (type) {
                case SETTLEMENT -> "the account %s can't take a settlement: use an account of your own money or "
                        + "credit, or specify it later";
                case INCOME -> "the account %s can't receive a family income: use an account of your own money or "
                        + "credit, or specify it later";
                default -> "the account %s can't pay a family expense: pay with an account of your own money or "
                        + "credit, or specify it later";
            }).formatted(account.get().code())));
        }
        return new FamilyPostingService.OwnAccount(accountId, note);
    }

    // --- Currencies (D-45, D-87; ADR 0004) ---

    /**
     * The paying side (D-87): what went from or into the account of the payer, the receiver or a settlement's recorder,
     * in that account's currency, as {@code original_amount} and {@code original_currency} hold it. It is the record's
     * amount in the record's currency, unless it is on an account in another currency, with the amount entered
     * ({@code ENTERED}); a record converted at a rate before V11 keeps its rate and source (D-13).
     */
    private record Side(BigDecimal amount, String currency, BigDecimal rate, String source, LocalDate rateDate) {

        static Side of(BigDecimal amount, String currency) {
            return new Side(amount, currency, null, null, null);
        }

        static Side entered(BigDecimal amount, String currency) {
            return new Side(amount, currency, null, "ENTERED", null);
        }
    }

    private static Side sideOf(RecordRow record) {
        return new Side(record.originalAmount(), record.originalCurrency(), record.rate(), record.rateSource(),
                record.rateDate());
    }

    private static boolean sameSide(Side one, Side other) {
        return one.amount().compareTo(other.amount()) == 0 && one.currency().equals(other.currency())
                && Objects.equals(one.source(), other.source()) && Objects.equals(one.rateDate(), other.rateDate())
                && (one.rate() == null ? other.rate() == null
                        : other.rate() != null && one.rate().compareTo(other.rate()) == 0);
    }

    /** The paying side alone: it changes neither the record's version nor its journal (D-16). */
    private void updateSide(LedgerScope family, long recordId, Side side) {
        jdbc.sql("""
                UPDATE family_record
                SET original_amount = :original, original_currency = :originalCurrency, base_rate = :rate,
                    base_rate_source = :source, base_rate_date = :rateDate
                WHERE id = :recordId AND ledger_id = :ledgerId""")
                .param("original", side.amount()).param("originalCurrency", side.currency())
                .param("rate", side.rate()).param("source", side.source()).param("rateDate", side.rateDate())
                .param("recordId", recordId).param("ledgerId", family.ledgerId())
                .update();
    }

    /** Which way a side's money went: "from" the account that paid, "into" the one that received. */
    private static String way(String type, boolean pays) {
        return switch (type) {
            case INCOME -> "into";
            case SETTLEMENT -> pays ? "from" : "into";
            default -> "from";
        };
    }

    /** The account's own currency, null if it holds any (an account's default currency is optional). */
    private String accountCurrency(LedgerScope personal, long accountId) {
        return jdbc.sql("SELECT default_currency FROM account WHERE id = :accountId AND ledger_id = :ledgerId")
                .param("accountId", accountId).param("ledgerId", personal.ledgerId())
                .query(String.class).optional().orElse(null);
    }

    /**
     * The paying side of the member who names how they paid or received (D-87): on an account in the record's
     * currency, or one without a currency, the record's amount; on an account in another currency, what went from or
     * into it, which they name ({@code accountAmount}), or as it was if neither the amount nor that currency changed;
     * on "Specify later", or for a member without an account, the record's amount. No rate is ever used.
     *
     * @param current the side as the record has it; null for a new record
     * @param amountMoves whether the record's amount or currency changes, which the side's own amount must follow
     */
    private Side payingSide(LedgerScope personal, Payment payment, BigDecimal amount, String currency,
            BigDecimal accountAmount, Side current, boolean amountMoves, long memberId, List<Violation> violations,
            String type, String way) {
        if (payment instanceof FamilyPostingService.OwnAccount own) {
            String accountCurrency = accountCurrency(personal, own.accountId());
            if (accountCurrency == null || accountCurrency.equals(currency)) {
                if (accountAmount != null && accountAmount.compareTo(amount) != 0) {
                    violations.add(new Violation(ACCOUNT_AMOUNT, memberId, ("the account is in %s, the %s's currency, "
                            + "so what went %s it is the %s's amount").formatted(currency, noun(type), way,
                            noun(type))));
                }
                return Side.of(amount, currency);
            }
            if (accountAmount == null) {
                if (current != null && !amountMoves && current.currency().equals(accountCurrency)) {
                    return current;
                }
                violations.add(new Violation(ACCOUNT_AMOUNT, memberId, ("the account is in %s and the %s in %s: name "
                        + "the amount that went %s it").formatted(accountCurrency, noun(type), currency, way)));
                return Side.of(amount, currency);
            }
            int before = violations.size();
            checkAmount(accountAmount, accountCurrency, ShareSplit.minorUnit(accountCurrency), violations);
            violations.replaceAll(v -> violations.indexOf(v) >= before && v.code().equals(AMOUNT)
                    ? new Violation(ACCOUNT_AMOUNT, memberId, v.message()) : v);
            return violations.size() > before ? Side.of(amount, currency)
                    : Side.entered(inMinorUnits(accountAmount, accountCurrency), accountCurrency);
        }
        if (accountAmount != null) {
            violations.add(new Violation(ACCOUNT_AMOUNT, memberId, ("name the amount only with an account of yours in "
                    + "another currency than the %s's, %s").formatted(noun(type), currency)));
        }
        return Side.of(amount, currency);
    }

    /**
     * The paying side after a change of an expense or an income that names no account: the payer's own side follows
     * the record's amount, on the account it is on (asking for what went from or into it in another currency); a payer
     * without an account, or one whose part is in their opening balance, has the record's amount; another member's
     * side doesn't change.
     */
    private Side changedSide(LedgerScope family, LedgerScope personal, RecordRow record, FamilyRecordChanges changes,
            Payment payment, long payerId, LocalDate date, BigDecimal amount, String currency, boolean amountChanged,
            Map<Long, Member> members, List<Violation> violations) {
        String way = way(record.type(), true);
        if (!(payment instanceof FamilyPostingService.Unchanged)) {
            return payingSide(personal, payment, amount, currency, changes.accountAmount(), sideOf(record),
                    amountChanged || payerId != record.payerId(), payerId, violations, record.type(), way);
        }
        Member payer = members.get(payerId);
        boolean posted = payer != null && payer.hasAccount() && !payer.joinDate().isAfter(date);
        if (payerId == record.payerId() && posted && payerId == family.memberId()) {
            return keptSide(family, personal, record, amount, currency, amountChanged, changes.accountAmount(),
                    violations, way);
        }
        if (payerId != record.payerId() || !posted) {
            return payingSide(personal, payment, amount, currency, changes.accountAmount(), null, true, payerId,
                    violations, record.type(), way);
        }
        return sideOf(record);
    }

    /**
     * The acting member's own side as it is, on its account or "Specify later", after a change of the record's amount
     * or of what went from or into the account (D-87).
     */
    private Side keptSide(LedgerScope family, LedgerScope personal, RecordRow record, BigDecimal amount,
            String currency, boolean amountChanged, BigDecimal accountAmount, List<Violation> violations, String way) {
        if (!amountChanged && accountAmount == null) {
            return sideOf(record);
        }
        OwnPayment current = posting.ownPayments(family, List.of(record.id())).get(record.id());
        Payment kept = current == null || current.later() ? new FamilyPostingService.Later(null)
                : new FamilyPostingService.OwnAccount(current.accountId(), null);
        return payingSide(personal, kept, amount, currency, accountAmount, sideOf(record), amountChanged,
                family.memberId(), violations, record.type(), way);
    }

    /**
     * The account the other side of a settlement puts their part on (F4e): in the settlement's currency their part is
     * its amount; in another currency, the amount they name for it, which only their own entry holds (D-87).
     */
    private Payment otherSidesAccount(LedgerScope personal, FamilyPostingService.OwnAccount own,
            BigDecimal accountAmount, String currency, BigDecimal amount, RecordRow record, long memberId,
            List<Violation> violations) {
        String accountCurrency = accountCurrency(personal, own.accountId());
        String in = accountCurrency == null ? currency : accountCurrency;
        if (in.equals(currency)) {
            if (accountAmount != null && accountAmount.compareTo(amount) != 0) {
                violations.add(new Violation(ACCOUNT_AMOUNT, memberId, ("the account is in %s, the settlement's "
                        + "currency, so your side is the settlement's amount").formatted(currency)));
            }
            return new FamilyPostingService.OwnAccount(own.accountId(), null, amount, currency);
        }
        String way = record.payerId() == memberId ? "from" : "into";
        if (accountAmount == null) {
            violations.add(new Violation(ACCOUNT_AMOUNT, memberId, "the account is in %s: name the amount that went %s it"
                    .formatted(in, way)));
            return own;
        }
        int before = violations.size();
        checkAmount(accountAmount, in, ShareSplit.minorUnit(in), violations);
        violations.replaceAll(v -> violations.indexOf(v) >= before && v.code().equals(AMOUNT)
                ? new Violation(ACCOUNT_AMOUNT, memberId, v.message()) : v);
        return violations.size() > before ? own
                : new FamilyPostingService.OwnAccount(own.accountId(), null, inMinorUnits(accountAmount, in), in);
    }

    /**
     * The journal's change of the record's amount and of its currency (topic H, D-45). What went from or into a
     * member's account is the private side of their payment, never journaled (D-16).
     */
    private static void journalAmount(List<Map<String, Object>> journal, RecordRow record, BigDecimal amount,
            String currency) {
        if (amount.compareTo(record.amount()) != 0) {
            journal.add(change("amount", null, text(record.amount(), ShareSplit.minorUnit(record.currency())),
                    text(amount, ShareSplit.minorUnit(currency))));
        }
        if (!currency.equals(record.currency())) {
            journal.add(change("currency", null, record.currency(), currency));
        }
    }

    private static BigDecimal inMinorUnits(BigDecimal amount, String currency) {
        return amount.setScale(ShareSplit.minorUnit(currency), RoundingMode.UNNECESSARY);
    }

    /** Whether no amount was refused, so that shares can follow from it. */
    private static boolean amountValid(List<Violation> violations) {
        return violations.stream().noneMatch(v -> v.code().equals(AMOUNT));
    }

    /**
     * What F4e's forms ask before saving (D-13), rate-free since F8a (D-87): the amount itself in the family's main
     * currency; in another currency no base amount and no rate, since a record keeps its own currency (D-45).
     *
     * @throws RuleViolationException if the amount can't be one
     * @deprecated until F8b's forms stop asking
     */
    @Deprecated
    @Transactional(readOnly = true)
    public FamilyConversion conversion(LedgerScope family, BigDecimal amount, String currency, LocalDate date) {
        String main = jdbc.sql("SELECT base_currency FROM ledger WHERE id = :ledgerId")
                .param("ledgerId", family.ledgerId()).query(String.class).single();
        List<Violation> violations = new ArrayList<>();
        checkAmount(amount, currency, ShareSplit.minorUnit(currency), violations);
        if (!violations.isEmpty()) {
            throw RuleViolationException.of(violations);
        }
        return new FamilyConversion(inMinorUnits(amount, currency), currency,
                currency.equals(main) ? inMinorUnits(amount, main) : null, main, null, null, null);
    }

    private static Violation noAccount(Member payer) {
        return new Violation(PAYMENT, payer.id(), ("%s has no account, so there is no account of theirs to pay with")
                .formatted(payer.displayName()));
    }

    private static Violation notYou(Member payer, String type) {
        return new Violation(PAYER, payer.id(), ("%s has an account: only they can record what they %s; name "
                + "yourself, or a member without an account").formatted(payer.displayName(), paid(type)));
    }

    private static Violation notYourPayment(long payerId, String type) {
        return new Violation(PAYMENT, payerId, type.equals(INCOME)
                ? "a private note goes only on your own receipt, and you didn't receive this"
                : "a private note goes only on your own payment, and you didn't pay this");
    }

    /**
     * The shares after a new amount, date or payer, by the record's stored split (D-12, D-14): equal shares among its
     * members as of the new date, the same percentages, the same member, or the same amounts. Among the members of
     * equal shares, a member with an account drops out when the date moves before their join date, and comes in when it
     * moves from before their join date to it or after, so that their share entry appears or goes (D-7); not one who
     * took a seat, who takes part either way, their part moving between the opening balance and an entry (D-35); a member added
     * since doesn't come in (D-18: records are never split again for a new member). A new amount of a split by amounts
     * needs the new amounts with it.
     */
    private List<Share> resplit(RecordRow record, Map<Long, ShareRow> old, BigDecimal amount, boolean amountChanged,
            int scale, LocalDate date, long payerId, Map<Long, Member> members, Set<Long> guests,
            List<Violation> violations) {
        List<Member> byJoinDate = members.values().stream()
                .sorted(Comparator.comparing(Member::joinDate).thenComparing(Member::id)).toList();
        int before = violations.size();
        String type = record.type();
        switch (record.splitMethod()) {
            case "EQUAL" -> {
                List<Long> participants = byJoinDate.stream()
                        .filter(m -> m.status() == MemberStatus.ACTIVE && sharesOn(m, date, guests)
                                && (old.containsKey(m.id()) || m.hasAccount() && !m.claimedSeat()
                                        && m.joinDate().isAfter(record.date())))
                        .map(Member::id).toList();
                if (participants.isEmpty()) {
                    violations.add(new Violation(NO_MEMBERS, null, "no active member shares %s of %s"
                            .formatted(article(type), date)));
                    return List.of();
                }
                return ShareSplit.equal(amount, scale, participants, payerId);
            }
            case "PERCENT" -> {
                List<Weight> weights = new ArrayList<>();
                for (Member member : byJoinDate) {
                    ShareRow share = old.get(member.id());
                    if (share == null) {
                        continue;
                    }
                    int basisPoints = share.basisPoints() == null ? 0 : share.basisPoints();
                    if (basisPoints > 0 && !sharesOn(member, date, guests)) {
                        violations.add(joinedAfter(member, date, type));
                    }
                    weights.add(new Weight(member.id(), basisPoints, basisPoints));
                }
                return violations.size() > before ? List.of() : ShareSplit.percent(amount, scale, weights, payerId);
            }
            case "ONE_MEMBER" -> {
                ShareRow on = old.values().stream().max(Comparator.comparing(ShareRow::amount)).orElseThrow();
                Member member = members.get(on.memberId());
                if (!sharesOn(member, date, guests)) {
                    violations.add(joinedAfter(member, date, type));
                    return List.of();
                }
                return ShareSplit.oneMember(amount, scale, member.id());
            }
            case "AMOUNT" -> {
                if (amountChanged) {
                    violations.add(new Violation(AMOUNTS_NEEDED, null, ("the %s is split by amounts: send the new "
                            + "amounts with the new amount").formatted(noun(type))));
                    return List.of();
                }
                List<Share> shares = new ArrayList<>();
                for (Member member : byJoinDate) {
                    ShareRow share = old.get(member.id());
                    if (share == null) {
                        continue;
                    }
                    if (share.amount().signum() > 0 && !sharesOn(member, date, guests)) {
                        violations.add(joinedAfter(member, date, type));
                    }
                    shares.add(new Share(member.id(), share.amount(), null));
                }
                return violations.size() > before ? List.of() : shares;
            }
            default -> throw new IllegalStateException("Unknown split method " + record.splitMethod());
        }
    }

    /**
     * The shares of the amount (D-12). Shares go to ACTIVE members only, and to a member with an account only for a
     * record on or after their join date, which is when records are posted to them (D-7, D-18).
     */
    private List<Share> split(RecordSplit split, BigDecimal amount, int scale, LocalDate date, long payerId,
            Ledger ledger, Map<Long, Member> members, Set<Long> guests, List<Violation> violations, String type) {
        RecordSplit wanted = split == null ? RecordSplit.rule() : split;
        List<Member> byJoinDate = members.values().stream()
                .sorted(Comparator.comparing(Member::joinDate).thenComparing(Member::id)).toList();
        int before = violations.size();
        switch (wanted.method()) {
            case RULE -> {
                if (ledger.splitRule() == SplitRule.EQUAL) {
                    List<Long> participants = byJoinDate.stream()
                            .filter(m -> m.status() == MemberStatus.ACTIVE && sharesOn(m, date, guests))
                            .map(Member::id).toList();
                    if (participants.isEmpty()) {
                        violations.add(new Violation(NO_MEMBERS, null, "no active member shares %s of %s"
                                .formatted(article(type), date)));
                        return List.of();
                    }
                    return ShareSplit.equal(amount, scale, participants, payerId);
                }
                List<Weight> weights = new ArrayList<>();
                for (Member member : byJoinDate) {
                    if (member.status() == MemberStatus.ACTIVE && member.share() != null) {
                        if (member.share() > 0 && !sharesOn(member, date, guests)) {
                            violations.add(joinedAfter(member, date, type));
                        }
                        weights.add(new Weight(member.id(), member.share(), member.share()));
                    }
                }
                return violations.size() > before ? List.of() : ShareSplit.percent(amount, scale, weights, payerId);
            }
            case PERCENT -> {
                Map<Long, ShareInput> inputs = inputs(wanted, members, date, guests, violations, true, type);
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
                Map<Long, ShareInput> inputs = inputs(wanted, members, date, guests, violations, false, type);
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
                if (!sharesOn(member, date, guests)) {
                    violations.add(joinedAfter(member, date, type));
                    return List.of();
                }
                return ShareSplit.oneMember(amount, scale, memberId);
            }
            default -> throw new IllegalArgumentException("Unknown split " + wanted.method());
        }
    }

    /** The shares as entered, by member, each of an ACTIVE member who shares records of the date, once. */
    private static Map<Long, ShareInput> inputs(RecordSplit split, Map<Long, Member> members, LocalDate date,
            Set<Long> guests, List<Violation> violations, boolean percent, String type) {
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
            } else if (!sharesOn(member, date, guests) && (percent ? input.basisPoints() != null && input.basisPoints() > 0
                    : input.amount() != null && input.amount().signum() > 0)) {
                violations.add(joinedAfter(member, date, type));
            }
        }
        if (inputs.isEmpty()) {
            violations.add(new Violation(SHARE, null, "the shares are missing"));
        }
        return inputs;
    }

    /**
     * Whether the member takes part in a record of the date (D-18 as amended, D-35, D-39): a member without an account,
     * and a member who took a seat, in any record from the family ledger's start date (a claim changes nothing about who
     * takes part, only where the claimer's postings go); a new member and one who returned, in those on or after their
     * join date. Records are posted to a member with an account only from their join date (FamilyPostingService), so a
     * claimed seat's part of a record before its claim's date is in their opening balance.
     */
    private static boolean joinedBy(Member member, LocalDate date) {
        return !member.hasAccount() || member.claimedSeat() || !member.joinDate().isAfter(date);
    }

    /**
     * Whether the member may have a share of a record of the date: as {@link #joinedBy}, or as one of the record's
     * {@link #guests}, who keeps their part in it.
     */
    private static boolean sharesOn(Member member, LocalDate date, Set<Long> guests) {
        return joinedBy(member, date) || guests.contains(member.id());
    }

    /**
     * The record's members from before their join date (F5, D-18; D-39): members with an account who joined after the
     * record's date, and who were in it already, as its payer, its receiver or with a share. They stay in it as they
     * were, through any change that keeps it before their join date: their part of it is in their opening balance, or
     * for a member who returned in their correction (FamilyPostingService). A member who took a seat takes part in any
     * record anyway ({@link #joinedBy}, D-35); for them this says only that the record has no payment entry of theirs.
     * Nobody else with an account comes into a record dated before their join date.
     */
    private static Set<Long> guests(RecordRow record, Map<Long, ShareRow> shares, Map<Long, Member> members) {
        Set<Long> involved = new HashSet<>(shares.keySet());
        involved.add(record.payerId());
        if (record.payeeId() != null) {
            involved.add(record.payeeId());
        }
        Set<Long> guests = new HashSet<>();
        for (long memberId : involved) {
            Member member = members.get(memberId);
            if (member != null && member.hasAccount() && member.joinDate().isAfter(record.date())) {
                guests.add(memberId);
            }
        }
        return guests;
    }

    /** A member's own part of a record dated before they took their seat, which no entry of theirs holds (D-18). */
    private static Violation beforeJoining(Member member, String type) {
        return new Violation(PAYMENT, member.id(), ("this %s is dated before %s took their place in the family budget "
                + "on %s, so it is part of their opening balance and has no account of theirs")
                .formatted(noun(type), member.displayName(), member.joinDate()));
    }

    private static Violation joinedAfter(Member member, LocalDate date, String type) {
        return new Violation(JOINED_AFTER, member.id(), "%s joined on %s, after the %s's date %s"
                .formatted(member.displayName(), member.joinDate(), noun(type), date));
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
            throw new ConflictException(("The %s is frozen: a member it involves has left the family budget or "
                    + "deleted their data, so nobody can change it").formatted(noun(record.type())));
        }
    }

    private static boolean frozen(RecordRow record, Map<Long, ShareRow> shares, Map<Long, Member> members) {
        return members.get(record.payerId()).status() != MemberStatus.ACTIVE
                || record.payeeId() != null && members.get(record.payeeId()).status() != MemberStatus.ACTIVE
                || shares.values().stream().anyMatch(s -> s.amount().signum() > 0
                        && members.get(s.memberId()).status() != MemberStatus.ACTIVE);
    }

    // --- Reading and writing ---

    private record Ledger(LocalDate startDate, String baseCurrency, SplitRule splitRule) {
    }

    /** @param claimedSeat whether the member took a seat by a claim and hasn't left and returned since (D-35, V10) */
    private record Member(long id, String displayName, MemberStatus status, boolean hasAccount, LocalDate joinDate,
            Integer share, boolean claimedSeat) {
    }

    /**
     * @param categoryId null for a settlement
     * @param amount the record's amount, in {@code currency} (D-45)
     * @param originalAmount the paying side, in {@code originalCurrency} (D-87)
     * @param payeeId who received a settlement; null otherwise
     * @param splitMethod null for a settlement
     */
    private record RecordRow(long id, String type, LocalDate date, Long categoryId, BigDecimal amount,
            String currency, String comment,
            long payerId, Long payeeId, String splitMethod, long authorId, Instant createdAt, long updatedById,
            Instant updatedAt, int version, BigDecimal originalAmount, String originalCurrency, BigDecimal rate,
            String rateSource, LocalDate rateDate) {
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

    /** The record, locked for the change; with {@code expectedVersion} null, whatever its version. */
    private RecordRow lockRecord(LedgerScope family, long recordId, Integer expectedVersion) {
        RecordRow record = jdbc.sql(RECORDS + " AND r.id = :recordId FOR UPDATE")
                .param("ledgerId", family.ledgerId()).param("recordId", recordId)
                .query(FamilyRecordService::recordRow)
                .optional()
                .orElseThrow(() -> recordNotFound(family, recordId));
        if (expectedVersion != null && record.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(("%s %d has changed since version %d. Reload it and try "
                    + "again.").formatted(capitalized(record.type()), recordId, expectedVersion));
        }
        return record;
    }

    private Map<Long, Member> members(LedgerScope family) {
        Map<Long, Member> members = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, display_name, status, user_sub IS NOT NULL AS has_account, join_date, share_bp, claimed_seat
                FROM ledger_member WHERE ledger_id = :ledgerId ORDER BY join_date, id""")
                .param("ledgerId", family.ledgerId())
                .query(row -> {
                    members.put(row.getLong("id"), new Member(row.getLong("id"), row.getString("display_name"),
                            MemberStatus.valueOf(row.getString("status")), row.getBoolean("has_account"),
                            row.getObject("join_date", LocalDate.class), row.getObject("share_bp", Integer.class),
                            row.getBoolean("claimed_seat")));
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
        Map<Long, Member> members = members(family);
        Map<Long, CategoryRef> categories = categories(family);
        Map<Long, Map<Long, ShareRow>> shares = shares(family, rows.stream().map(RecordRow::id).toList());
        // The caller's own payments and settlement sides, of the records they paid or settled with an account: for
        // their eyes only (D-16).
        Map<Long, OwnPayment> own = posting.ownPayments(family, rows.stream()
                .filter(row -> (row.payerId() == family.memberId() || Objects.equals(row.payeeId(), family.memberId()))
                        && members.get(family.memberId()).hasAccount())
                .map(RecordRow::id).toList());
        // The settlements locked by their other side (D-28), for who would otherwise change them.
        Map<Long, Long> placed = placedSides(family, rows.stream()
                .filter(row -> row.type().equals(SETTLEMENT) && maySettle(family, row)).map(RecordRow::id).toList());
        List<Long> joinOrder = new ArrayList<>(members.keySet());
        return rows.stream().map(row -> {
            // In the record's own currency (D-45).
            int scale = ShareSplit.minorUnit(row.currency());
            Map<Long, ShareRow> recordShares = shares.getOrDefault(row.id(), Map.of());
            boolean frozen = frozen(row, recordShares, members);
            Member payer = members.get(row.payerId());
            boolean mayEdit = row.type().equals(SETTLEMENT) ? maySettle(family, row)
                    : row.authorId() == family.memberId() || family.role() == MemberRole.OWNER;
            boolean mayDelete = row.type().equals(SETTLEMENT) ? mayEdit
                    : payer.hasAccount() ? row.payerId() == family.memberId() : mayEdit;
            OwnPayment payment = own.get(row.id());
            Long lockedBy = placed.get(row.id());
            boolean mayEditPayment = mayDelete && lockedBy == null;
            return new FamilyRecordView(row.id(), row.type(), row.date(),
                    row.categoryId() == null ? null : categories.get(row.categoryId()),
                    row.amount().setScale(scale, RoundingMode.UNNECESSARY), row.currency(), row.comment(),
                    ref(members, row.payerId()), row.splitMethod(), recordShares.values().stream()
                            .sorted(Comparator.comparing(s -> joinOrder.indexOf(s.memberId())))
                            .map(s -> new ShareView(ref(members, s.memberId()),
                                    s.amount().setScale(scale, RoundingMode.UNNECESSARY), s.basisPoints(),
                                    ref(members, s.updatedById()), s.updatedAt()))
                            .toList(),
                    ref(members, row.authorId()), row.createdAt(), ref(members, row.updatedById()), row.updatedAt(),
                    row.version(), frozen, mayEdit && !frozen, mayEditPayment && !frozen, mayEditPayment && !frozen,
                    payment == null ? null : new FamilyRecordView.YourPayment(payment.entryId(), payment.accountId(),
                            payment.accountName(), payment.later(), inMinorUnits(payment.amount(), payment.currency()),
                            payment.currency()),
                    ref(members, row.payeeId()), lockedBy == null ? null : ref(members, lockedBy),
                    inMinorUnits(row.originalAmount(), row.originalCurrency()), row.originalCurrency(),
                    row.rate() == null ? null : Money.normalize(row.rate()), row.rateSource(), row.rateDate());
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
            case "payer", "payee" -> ref(members, value.asLong()).displayName();
            default -> value.asText();
        };
    }

    /** A category's name; null for a record without one, such as a settlement. */
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

    /** The answer for a record that is missing, or deleted: named by its type if it was one of the ledger's. */
    private NotFoundException recordNotFound(LedgerScope family, long recordId) {
        String type = jdbc.sql("SELECT type FROM family_record WHERE id = :recordId AND ledger_id = :ledgerId")
                .param("recordId", recordId).param("ledgerId", family.ledgerId())
                .query(String.class).optional().orElse(EXPENSE);
        return new NotFoundException(capitalized(type) + " " + recordId + " not found");
    }

    private static RecordRow recordRow(ResultSet row, int n) throws SQLException {
        return new RecordRow(row.getLong("id"), row.getString("type"), row.getObject("record_date", LocalDate.class),
                row.getObject("category_id", Long.class), row.getBigDecimal("base_amount"), row.getString("currency"),
                row.getString("comment"),
                row.getLong("payer_member_id"), row.getObject("payee_member_id", Long.class),
                row.getString("split_method"), row.getLong("author_member_id"),
                row.getTimestamp("created_at").toInstant(), row.getLong("updated_by_member_id"),
                row.getTimestamp("updated_at").toInstant(), row.getInt("version"), row.getBigDecimal("original_amount"),
                row.getString("original_currency"), row.getBigDecimal("base_rate"), row.getString("base_rate_source"),
                row.getObject("base_rate_date", LocalDate.class));
    }
}
