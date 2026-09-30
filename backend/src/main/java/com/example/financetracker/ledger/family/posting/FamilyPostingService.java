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
 * is written and one no longer wanted is deleted. The payment keeps its account: its fields can't change before F4c.
 */
@Service
public class FamilyPostingService {

    /** How the payer paid, when the record is created. */
    public sealed interface Payment {
    }

    /** With the payer's own account. */
    public record OwnAccount(long accountId) implements Payment {
    }

    /** "Specify later": to "Payments without a specified account" (D-14). */
    public record Later() implements Payment {
    }

    /** No new payment: the payer has none (no account), or has it already. */
    public record Unchanged() implements Payment {
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

        for (var entry : existing.entrySet()) {
            Link link = entry.getValue();
            if (link.type() == LinkType.PAYMENT) {
                // The payment stays as the payer entered it while the record lives (F4c brings payment edits).
                if (record.deleted() || !posted.contains(link.memberId())) {
                    writer.delete(family, link);
                }
                continue;
            }
            PostedEntry want = wanted.remove(entry.getKey());
            if (want == null) {
                writer.delete(family, link);
            } else if (!writer.holds(family, link, want)) {
                writer.replace(family, link, want);
            }
        }
        wanted.values().forEach(entry -> writer.write(family, entry));

        boolean payerPosted = posted.contains(record.payerId());
        if (payerPosted && !existing.containsKey(key(record.payerId(), LinkType.PAYMENT))) {
            switch (payment) {
                case OwnAccount own -> writer.write(family, ownPayment(family, record.payerId(), recordId,
                        record.date(), own.accountId(), record.amount(), record.currency()));
                case Later later -> writer.write(family, placeholderPayment(family, record.payerId(), recordId,
                        record.date(), record.amount(), record.currency()));
                case Unchanged unchanged -> {
                    // A member with an account who pays names how (D-14); the record service asks for it.
                    throw new ConflictException("The payer's payment of record %d is missing".formatted(recordId));
                }
            }
        }
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
                new Line(debt, currency, amount.negate(), null)));
    }

    /** The payer's payment with their own account: the account −amount, the debt account +amount. */
    private PostedEntry ownPayment(LedgerScope family, long memberId, long recordId, LocalDate date, long accountId,
            BigDecimal amount, String currency) {
        long debt = writer.debtAccount(family, memberId);
        return new PostedEntry(memberId, recordId, LinkType.PAYMENT, EntryKind.FAMILY_PAYMENT, false, date, List.of(
                new Line(accountId, currency, amount.negate(), null), new Line(debt, currency, amount, null)));
    }

    /** "Specify later": "Payments without a specified account" −amount, the debt account +amount. */
    private PostedEntry placeholderPayment(LedgerScope family, long memberId, long recordId, LocalDate date,
            BigDecimal amount, String currency) {
        long debt = writer.debtAccount(family, memberId);
        long placeholder = writer.placeholder(family, memberId);
        return new PostedEntry(memberId, recordId, LinkType.PAYMENT, EntryKind.FAMILY_PAYMENT, true, date, List.of(
                new Line(placeholder, currency, amount.negate(), null), new Line(debt, currency, amount, null)));
    }

    private static String key(long memberId, LinkType type) {
        return memberId + " " + type;
    }
}
