package com.example.financetracker.ledger.family.posting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.family.posting.CrossLedgerWriter.Link;
import com.example.financetracker.ledger.family.posting.CrossLedgerWriter.MemberLedger;
import com.example.financetracker.ledger.family.posting.PostedEntry.Line;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts a family record into its members' personal ledgers (D-7, D-8, D-10; ADR 0003, topic E), in the transaction
 * that created, changed or deleted the record, through {@link CrossLedgerWriter}. For each ACTIVE member with an account
 * who joined on or before the record's date:
 * <ul>
 * <li>their share of an expense, unless it is 0: UNALLOCATED +share with the family category, and their debt account
 * −share ({@code FAMILY_SHARE});
 * <li>for the payer, the payment: their own account −amount, or "Payments without a specified account" −amount when
 * they chose to specify it later, and their debt account +amount ({@code FAMILY_PAYMENT}).
 * </ul>
 * So a member's debt account always shows their family balance (D-10). Re-posting is idempotent, keyed by record,
 * member and link type: an entry as wanted stays, a different one is replaced (same entry, new version), a missing one
 * is written and one no longer wanted is deleted. The payment follows the record's date, amount and payer (F4c): it is
 * re-posted with the account the payer chose, or kept as it is, and it goes when the payer changes (D-14).
 */
@Service
public class FamilyPostingService {

    /**
     * How the payer paid, as the payer with an account says it when they create the record or change its payment;
     * only the payer themselves says it (D-14).
     */
    public sealed interface Payment {
    }

    /**
     * With the payer's own account.
     *
     * @param note the payer's private note, on their payment entry only; null for none
     */
    public record OwnAccount(long accountId, String note) implements Payment {
    }

    /** "Specify later": to "Payments without a specified account" (D-14), with the payer's private note or none. */
    public record Later(String note) implements Payment {
    }

    /**
     * The payment as it is: the payer has none (no account), or keeps theirs, re-posted with the record's date and
     * amount.
     */
    public record Unchanged() implements Payment {
    }

    /**
     * The caller's own payment for a record they paid, as only they see it (D-16): their entry, and the account they
     * paid with or "Specify later".
     *
     * @param accountId null for "Specify later"
     * @param accountName null for "Specify later"
     * @param note their private note, on the entry only
     */
    public record OwnPayment(long entryId, int entryVersion, Long accountId, String accountName, boolean later,
            String note) {
    }

    private final JdbcClient jdbc;
    private final CrossLedgerWriter writer;

    FamilyPostingService(JdbcClient jdbc, CrossLedgerWriter writer) {
        this.jdbc = jdbc;
        this.writer = writer;
    }

    /**
     * Brings the record's posted entries in line with the record: its shares, its payment, or none of either once it
     * is deleted.
     *
     * @param family the family ledger, as the member who created, changed or deleted the record; a payment with an
     *        own account is only ever that member's (D-14)
     * @param payment the payment of a new record; {@link Unchanged} otherwise
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(LedgerScope family, long recordId, Payment payment) {
        record Record(LocalDate date, boolean deleted, long payerId, long categoryId, BigDecimal amount,
                String currency) {
        }
        Record record = jdbc.sql("""
                SELECT r.record_date, r.deleted_at IS NOT NULL AS deleted, r.payer_member_id, r.category_id,
                       r.base_amount, l.base_currency
                FROM family_record r JOIN ledger l ON l.id = r.ledger_id
                WHERE r.id = :recordId AND r.ledger_id = :familyId AND r.type = 'EXPENSE'
                FOR UPDATE OF r""")
                .param("recordId", recordId).param("familyId", family.ledgerId())
                .query((row, n) -> new Record(row.getObject("record_date", LocalDate.class),
                        row.getBoolean("deleted"), row.getLong("payer_member_id"), row.getLong("category_id"),
                        row.getBigDecimal("base_amount"), row.getString("base_currency")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("No expense " + recordId + " in " + family));

        // The members it posts to: ACTIVE, with an account, joined on or before the record's date (D-7).
        Set<Long> posted = new HashSet<>(record.deleted() ? List.of() : jdbc.sql("""
                SELECT id FROM ledger_member
                WHERE ledger_id = :familyId AND status = 'ACTIVE' AND user_sub IS NOT NULL AND join_date <= :date""")
                .param("familyId", family.ledgerId()).param("date", record.date())
                .query(Long.class).list());
        Map<Long, BigDecimal> shares = new LinkedHashMap<>();
        jdbc.sql("SELECT member_id, amount FROM family_share WHERE record_id = :recordId AND ledger_id = :familyId "
                + "ORDER BY member_id")
                .param("recordId", recordId).param("familyId", family.ledgerId())
                .query(row -> {
                    shares.put(row.getLong("member_id"), row.getBigDecimal("amount"));
                });

        Map<String, PostedEntry> wanted = new LinkedHashMap<>();
        shares.forEach((memberId, amount) -> {
            if (posted.contains(memberId) && amount.signum() != 0) {
                wanted.put(key(memberId, LinkType.SHARE), share(family, memberId, recordId, record.date(),
                        record.categoryId(), amount, record.currency()));
            }
        });
        Map<String, Link> existing = new HashMap<>();
        writer.links(family, recordId).forEach(link -> existing.put(key(link.memberId(), link.type()), link));

        // The payer's payment, while they are posted: as they chose now, or as it is, with the record's date and amount.
        boolean payerPosted = posted.contains(record.payerId());
        Link payerLink = existing.get(key(record.payerId(), LinkType.PAYMENT));
        if (payerPosted) {
            PostedEntry payerPayment = switch (payment) {
                case OwnAccount own -> ownPayment(family, record.payerId(), recordId, record.date(), own.accountId(),
                        record.amount(), record.currency(), own.note());
                case Later later -> placeholderPayment(family, record.payerId(), recordId, record.date(),
                        record.amount(), record.currency(), later.note());
                case Unchanged unchanged -> {
                    if (payerLink == null) {
                        // A member with an account who pays names how (D-14); the record service asks for it.
                        throw new ConflictException("The payer's payment of record %d is missing".formatted(recordId));
                    }
                    yield asItIs(family, payerLink, recordId, record.date(), record.amount(), record.currency());
                }
            };
            wanted.put(key(record.payerId(), LinkType.PAYMENT), payerPayment);
        }

        for (var entry : existing.entrySet()) {
            Link link = entry.getValue();
            PostedEntry want = wanted.remove(entry.getKey());
            if (want == null) {
                writer.delete(family, link);
            } else if (!writer.holds(family, link, want)) {
                writer.replace(family, link, want);
            }
        }
        wanted.values().forEach(entry -> writer.write(family, entry));
    }

    /**
     * The caller's own payments for these records, by record: only those of records they paid with an account, from
     * their own personal ledger (D-16).
     */
    @Transactional(readOnly = true)
    public Map<Long, OwnPayment> ownPayments(LedgerScope family, List<Long> recordIds) {
        Map<Long, OwnPayment> payments = new HashMap<>();
        if (recordIds.isEmpty()) {
            return payments;
        }
        jdbc.sql("""
                SELECT l.record_id, e.id AS entry_id, e.version, e.memo, a.id AS account_id, a.name AS account_name,
                       a.code = :placeholder AND a.is_system AS later
                FROM family_entry_link l
                JOIN journal_entry e ON e.id = l.entry_id
                JOIN ledger_member own ON own.ledger_id = e.ledger_id AND own.ledger_type = 'PERSONAL'
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id AND a.ledger_id = e.ledger_id
                WHERE l.family_ledger_id = :familyId AND l.member_id = :memberId AND l.link_type = 'PAYMENT'
                  AND l.detached_at IS NULL AND l.record_id IN (:recordIds) AND own.user_sub = :sub
                  AND a.family_ledger_id IS NULL""")
                .param("placeholder", CrossLedgerWriter.PLACEHOLDER_CODE).param("familyId", family.ledgerId())
                .param("memberId", family.memberId()).param("recordIds", recordIds).param("sub", family.userId())
                .query(row -> {
                    boolean later = row.getBoolean("later");
                    payments.put(row.getLong("record_id"), new OwnPayment(row.getLong("entry_id"), row.getInt("version"),
                            later ? null : row.getLong("account_id"), later ? null : row.getString("account_name"),
                            later, row.getString("memo")));
                });
        return payments;
    }

    /** A member's share of an expense: UNALLOCATED +share with the family category, the debt account −share. */
    private PostedEntry share(LedgerScope family, long memberId, long recordId, LocalDate date, long categoryId,
            BigDecimal amount, String currency) {
        long debt = writer.debtAccount(family, memberId);
        MemberLedger member = writer.memberLedger(family, memberId);
        if (member.unallocated() == null) {
            throw new ConflictException("A member's personal ledger has no UNALLOCATED account for their share");
        }
        return new PostedEntry(memberId, recordId, LinkType.SHARE, EntryKind.FAMILY_SHARE, true, date, List.of(
                new Line(member.unallocated(), currency, amount, categoryId),
                new Line(debt, currency, amount.negate(), null)), null);
    }

    /** The payer's payment with their own account: the account −amount, the debt account +amount. */
    private PostedEntry ownPayment(LedgerScope family, long memberId, long recordId, LocalDate date, long accountId,
            BigDecimal amount, String currency, String note) {
        long debt = writer.debtAccount(family, memberId);
        return new PostedEntry(memberId, recordId, LinkType.PAYMENT, EntryKind.FAMILY_PAYMENT, false, date, List.of(
                new Line(accountId, currency, amount.negate(), null), new Line(debt, currency, amount, null)), note);
    }

    /** "Specify later": "Payments without a specified account" −amount, the debt account +amount. */
    private PostedEntry placeholderPayment(LedgerScope family, long memberId, long recordId, LocalDate date,
            BigDecimal amount, String currency, String note) {
        long debt = writer.debtAccount(family, memberId);
        long placeholder = writer.placeholder(family, memberId);
        return new PostedEntry(memberId, recordId, LinkType.PAYMENT, EntryKind.FAMILY_PAYMENT, true, date, List.of(
                new Line(placeholder, currency, amount.negate(), null), new Line(debt, currency, amount, null)), note);
    }

    /**
     * The payment as the payer left it, with the record's date and amount: the same account, or the placeholder, and
     * the same note. Only the payer changes the date or the amount (D-14), so when this differs from the entry, the
     * member who acts is the payer.
     */
    private PostedEntry asItIs(LedgerScope family, Link link, long recordId, LocalDate date, BigDecimal amount,
            String currency) {
        CrossLedgerWriter.PaymentSide side = writer.paymentSide(family, link);
        return side.later()
                ? placeholderPayment(family, link.memberId(), recordId, date, amount, currency, side.memo())
                : ownPayment(family, link.memberId(), recordId, date, side.accountId(), amount, currency, side.memo());
    }

    private static String key(long memberId, LinkType type) {
        return memberId + " " + type;
    }
}
