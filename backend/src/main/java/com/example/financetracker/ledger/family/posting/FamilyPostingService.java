package com.example.financetracker.ledger.family.posting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 * Posts a family record into its members' personal ledgers (D-7, D-8, D-10, D-24; ADR 0003, topic E), in the
 * transaction that created, changed or deleted the record, through {@link CrossLedgerWriter}. For each ACTIVE member
 * with an account who joined on or before the record's date:
 * <ul>
 * <li>their share of an expense, unless it is 0: UNALLOCATED +share with the family category, and their debt account
 * −share ({@code FAMILY_SHARE}); of an income (F4d), the other way round: UNALLOCATED −share with the family's INCOME
 * category, and their debt account +share;
 * <li>for the payer, the payment: their own account −amount, or "Payments without a specified account" −amount when
 * they chose to specify it later, and their debt account +amount ({@code FAMILY_PAYMENT}); for the receiver of an
 * income, the receipt, the other way round: the account +amount, the debt account −amount;
 * <li>for each side of a settlement (F4d), its part ({@code FAMILY_SETTLEMENT}): the payer's account −amount and debt
 * account +amount, the receiver's account +amount and debt account −amount. The side who records it names their
 * account, or "Specify later"; the other side's part goes to their "Payments without a specified account" (D-24), and
 * they move it to an account themselves.
 * </ul>
 * Every record is in its own currency (D-45, ADR 0004): its shares and every line on a debt account are in it, so a
 * member's debt account holds as many currencies as the family's records, and D-10 holds in each. A side on an account
 * in another currency (D-87) goes through the member's FX_EXCHANGE as a personal currency exchange does (rule 9): the
 * account in its currency, FX_EXCHANGE the other way in that currency and again in the record's, and the debt account
 * in the record's; FX_EXCHANGE keeps the difference, and no rate is used. The payer's payment, an income's receipt and
 * the side of a settlement's recorder are as the record's paying side holds them ({@code original_amount} in
 * {@code original_currency}); the other side of a settlement is in the record's currency on their placeholder, or in
 * their own account's currency with the amount they name for it, which only their entry holds.
 * <p>
 * A member who joined after the family ledger's start date by taking a seat (F5, D-18) has a family balance from the
 * records before their join date, which they shared as a member without an account: it arrives as one opening balance
 * ({@code FAMILY_OPENING}), dated on their join date, their debt account −balance and OPENING_BALANCE +balance, kept in
 * step whenever a record changes.
 * <p>
 * So a member's debt account always shows their family balance (D-10). Re-posting is idempotent, keyed by record,
 * member and link type: an entry as wanted stays, a different one is replaced (same entry, new version), a missing one
 * is written and one no longer wanted is deleted. A payment or a side follows the record's date and amount (F4c): it is
 * re-posted with the account its member chose, or kept as it is. Only its own member changes a side on an account of
 * theirs (D-8, D-28): another member's change never moves, rewrites or deletes it, and the record service refuses the
 * changes that would need to (a settlement's date, amount and deletion once its other side has placed its part).
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
     * @param amount for the other side of a settlement (F4e): what went from or into the account, in its currency;
     *        null for the record's original amount, which the payer's, the receiver's and the recorder's sides use
     * @param currency the currency of {@code amount}; null with it
     * @param counterpartyId the counterparty of the account's line, when the account requires one (D-80); null
     *        otherwise
     * @param payeeId the payer's own payee on their payment entry (D-81); null for none
     */
    public record OwnAccount(long accountId, String note, BigDecimal amount, String currency, Long counterpartyId,
            Long payeeId) implements Payment {

        public OwnAccount(long accountId, String note) {
            this(accountId, note, null, null, null, null);
        }

        public OwnAccount(long accountId, String note, BigDecimal amount, String currency) {
            this(accountId, note, amount, currency, null, null);
        }
    }

    /**
     * "Specify later": to "Payments without a specified account" (D-14), with the payer's private note or none, and
     * their payee (D-81) or none.
     */
    public record Later(String note, Long payeeId) implements Payment {

        public Later(String note) {
            this(note, null);
        }
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
     * @param amount what went from or into the account or the placeholder, in {@code currency}, above 0 (F4e)
     * @param currency the currency of the side's account line
     * @param counterpartyId the counterparty of the account's line, when it requires one (D-80)
     * @param payeeId their payee on the entry (D-81)
     */
    public record OwnPayment(long entryId, int entryVersion, Long accountId, String accountName, boolean later,
            String note, BigDecimal amount, String currency, Long counterpartyId, Long payeeId) {
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
        postRecord(family, recordId, payment);
        openings(family);
    }

    /**
     * Posts the family ledger to the member the scope stands for, who has just joined (F5; D-18, ADR 0003 topic E):
     * their debt account; every record dated on or after their join date in which they have a share, or which they paid,
     * received or settled while they had no account, their side of it on their "Payments without a specified account"
     * (D-24); and their balance before their join date as an opening balance. A new member, who joins today, has
     * none of those yet, only the debt account. Records are never split again (D-18).
     *
     * @param family the family ledger, as the member who joined
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void join(LedgerScope family) {
        long memberId = family.memberId();
        writer.debtAccount(family, memberId);
        record Involved(long id, boolean pays) {
        }
        List<Involved> records = jdbc.sql("""
                SELECT r.id, r.type <> 'SETTLEMENT' AND r.payer_member_id = :memberId AS pays
                FROM family_record r
                JOIN ledger_member m ON m.id = :memberId AND m.ledger_id = r.ledger_id
                WHERE r.ledger_id = :familyId AND r.deleted_at IS NULL AND r.record_date >= m.join_date
                  AND (r.payer_member_id = :memberId OR r.payee_member_id = :memberId
                       OR EXISTS (SELECT FROM family_share s
                                  WHERE s.record_id = r.id AND s.member_id = :memberId AND s.amount <> 0))
                ORDER BY r.record_date, r.id""")
                .param("memberId", memberId).param("familyId", family.ledgerId())
                .query((row, n) -> new Involved(row.getLong("id"), row.getBoolean("pays")))
                .list();
        // What they paid or received themselves goes to their placeholder, for them to put on an account (D-18), unless
        // it is their own payment of before they left, attached again (F6a, D-26), which stays as they left it.
        Set<Long> attached = new HashSet<>(jdbc.sql("""
                SELECT record_id FROM family_entry_link
                WHERE family_ledger_id = :familyId AND member_id = :memberId AND link_type = 'PAYMENT'
                  AND detached_at IS NULL""")
                .param("familyId", family.ledgerId()).param("memberId", memberId)
                .query(Long.class).list());
        records.forEach(record -> postRecord(family, record.id(),
                record.pays() && !attached.contains(record.id()) ? new Later(null) : new Unchanged()));
        openings(family);
    }

    /**
     * Detaches the member, who leaves or whom an owner removes (D-19, D-33; ADR 0003, topic E), while they are still
     * ACTIVE: their links detached, the family categories their personal ledger refers to copied into it as personal
     * ones, and their debt account an ordinary liability. Nothing is posted; everything posted stays as theirs.
     *
     * @param family the family ledger, as the member who acts: the one who leaves, or the owner who removes them
     * @return the personal categories the references moved to, by the family category's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Long> detach(LedgerScope family, long memberId) {
        return writer.detach(family, memberId);
    }

    private void postRecord(LedgerScope family, long recordId, Payment payment) {
        // The record's amount in its own currency (D-45), and its paying side (D-87).
        record Record(String type, LocalDate date, boolean deleted, long payerId, Long payeeId, long authorId,
                Long categoryId, BigDecimal amount, String currency, BigDecimal originalAmount,
                String originalCurrency) {
        }
        Record record = jdbc.sql("""
                SELECT r.type, r.record_date, r.deleted_at IS NOT NULL AS deleted, r.payer_member_id,
                       r.payee_member_id, r.author_member_id, r.category_id, r.base_amount, r.currency,
                       r.original_amount, r.original_currency
                FROM family_record r
                WHERE r.id = :recordId AND r.ledger_id = :familyId
                FOR UPDATE OF r""")
                .param("recordId", recordId).param("familyId", family.ledgerId())
                .query((row, n) -> new Record(row.getString("type"), row.getObject("record_date", LocalDate.class),
                        row.getBoolean("deleted"), row.getLong("payer_member_id"),
                        row.getObject("payee_member_id", Long.class), row.getLong("author_member_id"),
                        row.getObject("category_id", Long.class), row.getBigDecimal("base_amount"),
                        row.getString("currency"), row.getBigDecimal("original_amount"),
                        row.getString("original_currency")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("No record " + recordId + " in " + family));

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
                        record.categoryId(), record.type().equals("INCOME") ? amount.negate() : amount,
                        record.currency()));
            }
        });
        Map<String, Link> existing = new HashMap<>();
        writer.links(family, recordId).forEach(link -> existing.put(key(link.memberId(), link.type()), link));

        // The payer's payment, or the settlement's two sides, while their members are posted. The payer's, the
        // receiver's and the recorder's side is the record's paying side; the other side of a settlement is in the
        // record's currency, unless they put it on an account of theirs in another one.
        boolean settlement = record.type().equals("SETTLEMENT");
        List<Side> sides = settlement
                ? List.of(new Side(record.payerId(), LinkType.SETTLEMENT, true, record.authorId() == record.payerId()),
                        new Side(record.payeeId(), LinkType.SETTLEMENT, false,
                                record.payeeId() == record.authorId()))
                : List.of(new Side(record.payerId(), LinkType.PAYMENT, !record.type().equals("INCOME"), true));
        Amounts amounts = new Amounts(record.originalAmount(), record.originalCurrency(), record.amount(),
                record.currency());
        for (Side side : sides) {
            if (posted.contains(side.memberId())) {
                wanted.put(key(side.memberId(), side.link()), side(family, side, existing.get(key(side.memberId(),
                        side.link())), payment, recordId, record.date(), amounts));
            }
        }

        for (var entry : existing.entrySet()) {
            Link link = entry.getValue();
            PostedEntry want = wanted.remove(entry.getKey());
            if (!link.systemOwned() && link.memberId() != family.memberId()
                    && (want == null || !writer.holds(family, link, want))) {
                throw wouldChange(family, recordId, link.memberId());
            }
            if (want == null) {
                writer.delete(family, link);
            } else if (!writer.holds(family, link, want)) {
                writer.replace(family, link, want);
            }
        }
        wanted.values().forEach(entry -> writer.write(family, entry));
    }

    /**
     * The opening balances (F5; D-18, ADR 0003 topic E) of the members with an account who joined after the start
     * date, in each currency (D-45, ADR 0004): each one's family balance in it from the records dated before their join
     * date, less what their debt account shows in it already on that date from entries that aren't posted for this
     * membership (a returning member's entries of before they left, and whatever they posted to the account while it
     * was theirs; F6a, D-26), as one entry on their join date with a pair of lines per currency whose amount isn't 0,
     * their debt account −amount and OPENING_BALANCE +amount, by currency; none when every amount is 0. For a member
     * who took a seat that is the whole balance before their join date, an opening balance ({@code FAMILY_OPENING});
     * for one who returned, the correction ({@code FAMILY_CORRECTION}). Like a record's entries, an equal one stays, a
     * different one is replaced and one no longer wanted goes, so a change of a record before a member's join date
     * reaches it (D-10, D-31).
     */
    private void openings(LedgerScope family) {
        record Joined(long memberId, LocalDate joinDate, boolean returned) {
        }
        List<Joined> joined = jdbc.sql("""
                SELECT m.id, m.join_date,
                       EXISTS (SELECT FROM family_entry_link d
                               WHERE d.family_ledger_id = l.id AND d.member_id = m.id AND d.detached_at IS NOT NULL)
                           AS returned
                FROM ledger_member m JOIN ledger l ON l.id = m.ledger_id
                WHERE m.ledger_id = :familyId AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL
                  AND m.join_date > l.start_date
                ORDER BY m.id""")
                .param("familyId", family.ledgerId())
                .query((row, n) -> new Joined(row.getLong("id"), row.getObject("join_date", LocalDate.class),
                        row.getBoolean("returned")))
                .list();
        if (joined.isEmpty()) {
            return;
        }
        List<String> recordCurrencies = jdbc.sql("""
                SELECT DISTINCT currency FROM family_record WHERE ledger_id = :familyId AND deleted_at IS NULL""")
                .param("familyId", family.ledgerId()).query(String.class).list();
        for (Joined member : joined) {
            LinkType type = member.returned() ? LinkType.CORRECTION : LinkType.OPENING_BALANCE;
            Link existing = writer.openingLink(family, member.memberId(), type);
            long debt = writer.debtAccount(family, member.memberId());
            // Every currency of a record, and of the debt account, whose earlier lines a correction may have to meet.
            Set<String> currencies = new TreeSet<>(recordCurrencies);
            currencies.addAll(jdbc.sql("SELECT DISTINCT currency FROM posting WHERE account_id = :debt")
                    .param("debt", debt).query(String.class).list());
            List<Line> lines = new ArrayList<>();
            for (String currency : currencies) {
                BigDecimal amount = before(family, member.memberId(), member.joinDate(), currency)
                        .subtract(shown(family, member.memberId(), debt, member.joinDate(), currency));
                if (amount.signum() != 0) {
                    lines.add(new Line(debt, currency, amount.negate(), null));
                    lines.add(new Line(writer.openingBalance(family, member.memberId()), currency, amount, null));
                }
            }
            if (lines.isEmpty()) {
                if (existing != null) {
                    writer.delete(family, existing);
                }
                continue;
            }
            PostedEntry wanted = new PostedEntry(member.memberId(), null, type,
                    member.returned() ? EntryKind.FAMILY_CORRECTION : EntryKind.FAMILY_OPENING, true,
                    member.joinDate(), lines, null);
            if (existing == null) {
                writer.write(family, wanted);
            } else if (!writer.holds(family, existing, wanted)) {
                writer.replace(family, existing, wanted);
            }
        }
    }

    /**
     * A member's family balance in the currency from the records in it dated before the day (ADR 0003, topic D): their
     * expense shares − the expenses they paid + the incomes they received − their income shares − the settlements they
     * paid + the settlements they received.
     */
    private BigDecimal before(LedgerScope family, long memberId, LocalDate day, String currency) {
        return jdbc.sql("""
                SELECT coalesce((SELECT sum(CASE r.type WHEN 'EXPENSE' THEN s.amount ELSE -s.amount END)
                                 FROM family_share s JOIN family_record r ON r.id = s.record_id
                                 WHERE s.member_id = :memberId AND r.ledger_id = :familyId AND r.deleted_at IS NULL
                                   AND r.record_date < :day AND r.currency = :currency), 0)
                       - coalesce((SELECT sum(CASE r.type WHEN 'INCOME' THEN -r.base_amount ELSE r.base_amount END)
                                   FROM family_record r
                                   WHERE r.payer_member_id = :memberId AND r.ledger_id = :familyId
                                     AND r.deleted_at IS NULL AND r.record_date < :day AND r.currency = :currency), 0)
                       + coalesce((SELECT sum(r.base_amount) FROM family_record r
                                   WHERE r.payee_member_id = :memberId AND r.ledger_id = :familyId
                                     AND r.deleted_at IS NULL AND r.record_date < :day AND r.currency = :currency), 0)""")
                .param("memberId", memberId).param("familyId", family.ledgerId()).param("day", day)
                .param("currency", currency)
                .query(BigDecimal.class).single();
    }

    /**
     * What the member's debt account shows on the day, in the currency, from entries that aren't posted for their
     * membership now: 0 for a member who took a seat, whose account has only those; for a member who returned, their
     * entries of before they left and whatever they posted to the account while it was theirs (D-26).
     */
    private BigDecimal shown(LedgerScope family, long memberId, long debt, LocalDate day, String currency) {
        return jdbc.sql("""
                SELECT coalesce(-sum(p.amount), 0)
                FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE p.account_id = :debt AND p.currency = :currency AND e.entry_date <= :day
                  AND NOT EXISTS (SELECT FROM family_entry_link l
                                  WHERE l.entry_id = e.id AND l.family_ledger_id = :familyId AND l.member_id = :memberId
                                    AND l.detached_at IS NULL)""")
                .param("debt", debt).param("currency", currency).param("day", day)
                .param("familyId", family.ledgerId()).param("memberId", memberId)
                .query(BigDecimal.class).single();
    }

    /**
     * Posts the family ledger to the member the scope stands for, who has just come back (F6a, D-26): their debt
     * account named after the family ledger again ({@link CrossLedgerWriter#relinkDebt}), their entries of before they
     * left for records dated from their new join date attached again ({@link CrossLedgerWriter#reattach}), then as
     * {@link #join}: every record from their new join date that involves them, and one correction dated on it that makes
     * the debt account show their family balance.
     *
     * @param family the family ledger, as the member who returned
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejoin(LedgerScope family) {
        writer.relinkDebt(family, family.memberId());
        writer.reattach(family, family.memberId());
        join(family);
    }

    /**
     * What the debt account that a member's detach left in their personal ledger shows on the day, in each currency of
     * its lines (D-45): for the correction a returning member's invite shows before they accept (D-26). Empty if they
     * have none.
     *
     * @param personal the personal ledger of the member who left
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> formerDebtBalances(LedgerScope personal, long familyLedgerId, long memberId,
            LocalDate day) {
        Map<String, BigDecimal> balances = new TreeMap<>();
        Long former = writer.formerDebt(personal, personal.ledgerId(), familyLedgerId, memberId);
        if (former == null) {
            return balances;
        }
        // Not the entries that come back attached to their records, which the return posts again (reattach).
        jdbc.sql("""
                SELECT p.currency, coalesce(-sum(p.amount), 0) AS balance
                FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE p.account_id = :debt AND e.ledger_id = :ledgerId AND e.entry_date <= :day
                  AND NOT EXISTS (SELECT FROM family_entry_link l JOIN family_record r ON r.id = l.record_id
                                  WHERE l.entry_id = e.id AND l.family_ledger_id = :familyId AND l.member_id = :memberId
                                    AND r.deleted_at IS NULL AND r.record_date >= :day
                                    AND e.kind LIKE 'FAMILY\\_%')
                GROUP BY p.currency""")
                .param("debt", former).param("ledgerId", personal.ledgerId())
                .param("day", day).param("familyId", familyLedgerId).param("memberId", memberId)
                .query(row -> {
                    balances.put(row.getString("currency"), row.getBigDecimal("balance"));
                });
        return balances;
    }

    /**
     * An entry of the returning member's own on the debt account their detach left them, dated after the day they
     * return (D-37).
     *
     * @param amount what it adds to the debt that account shows, in {@code currency}: positive when it adds to what they
     *        owe
     * @param memo the entry's memo, their own; null for none
     */
    public record EntryAfterReturn(long entryId, LocalDate date, BigDecimal amount, String currency, String memo) {
    }

    /**
     * The entries on the debt account that a member's detach left in their personal ledger, dated after the day they
     * would return, that belong to no record the return posts again (D-37): entries they made on it themselves while
     * away, or ones of the family kinds they changed. The correction counts what the account shows up to the join date
     * (D-26), so these would make it differ from their family balance from their date on (D-10); and once the account
     * is the debt account again, they would change only with the family budget (F6a). A return waits until they are
     * moved or deleted. Empty if there is no such account.
     *
     * @param personal the personal ledger of the member who left
     */
    @Transactional(readOnly = true)
    public List<EntryAfterReturn> entriesAfterReturn(LedgerScope personal, long familyLedgerId, long memberId,
            LocalDate day) {
        Long former = writer.formerDebt(personal, personal.ledgerId(), familyLedgerId, memberId);
        if (former == null) {
            return List.of();
        }
        // Not the entries that come back attached to their records, which the return posts again (reattach).
        return jdbc.sql("""
                SELECT e.id, e.entry_date, -sum(p.amount) AS amount, p.currency, e.memo
                FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE p.account_id = :debt AND e.ledger_id = :ledgerId AND e.entry_date > :day
                  AND NOT EXISTS (SELECT FROM family_entry_link l JOIN family_record r ON r.id = l.record_id
                                  WHERE l.entry_id = e.id AND l.family_ledger_id = :familyId AND l.member_id = :memberId
                                    AND r.deleted_at IS NULL AND r.record_date >= :day
                                    AND e.kind LIKE 'FAMILY\\_%')
                GROUP BY e.id, e.entry_date, p.currency, e.memo
                ORDER BY e.entry_date, e.id""")
                .param("debt", former).param("ledgerId", personal.ledgerId()).param("day", day)
                .param("familyId", familyLedgerId).param("memberId", memberId)
                .query((row, n) -> new EntryAfterReturn(row.getLong("id"), row.getObject("entry_date", LocalDate.class),
                        row.getBigDecimal("amount"), row.getString("currency"), row.getString("memo")))
                .list();
    }

    /**
     * A member's side of a record: the payer's payment of an expense, the receiver's receipt of an income, or a side of
     * a settlement.
     *
     * @param out whether money went out of the member's hands: they paid
     * @param original whether it is the record's paying side: the payer's, the receiver's and a settlement's
     *        recorder's; the other side of a settlement is in the record's amount, or in their own amount
     */
    private record Side(long memberId, LinkType link, boolean out, boolean original) {
    }

    /**
     * A record's paying side (D-87: what went from or into the paying account, in its currency), and the record's own
     * amount in its own currency (D-45), which the debt accounts take.
     */
    private record Amounts(BigDecimal original, String originalCurrency, BigDecimal amount, String currency) {
    }

    /**
     * The side as it is wanted now: as the member who acts names it for their own side, else as it is, with the
     * record's date and amount. A new side of a settlement's other member goes to their "Payments without a specified
     * account" (D-24). A side on its member's own account that someone else's change would move is refused in
     * {@link #post}: only its member changes it (D-8, D-28).
     */
    private PostedEntry side(LedgerScope family, Side side, Link existing, Payment payment, long recordId,
            LocalDate date, Amounts amounts) {
        boolean acting = side.memberId() == family.memberId();
        // On the placeholder: the record's paying side, or for the other side of a settlement the record's amount.
        BigDecimal placed = side.original() ? amounts.original() : amounts.amount();
        String placedIn = side.original() ? amounts.originalCurrency() : amounts.currency();
        if (acting && payment instanceof OwnAccount own) {
            return own.amount() == null || side.original()
                    ? ownSide(family, side, recordId, date, own.accountId(), placed, placedIn, amounts, own.note(),
                            own.counterpartyId(), own.payeeId())
                    : ownSide(family, side, recordId, date, own.accountId(), own.amount(), own.currency(), amounts,
                            own.note(), own.counterpartyId(), own.payeeId());
        }
        if (acting && payment instanceof Later later) {
            return placeholderSide(family, side, recordId, date, placed, placedIn, amounts, later.note(),
                    later.payeeId());
        }
        if (existing == null) {
            if (side.link() == LinkType.PAYMENT) {
                // A member with an account who pays names how (D-14); the record service asks for it.
                throw new ConflictException("The payer's payment of record %d is missing".formatted(recordId));
            }
            return placeholderSide(family, side, recordId, date, placed, placedIn, amounts, null, null);
        }
        CrossLedgerWriter.PaymentSide current = writer.paymentSide(family, existing);
        if (current.later()) {
            return placeholderSide(family, side, recordId, date, placed, placedIn, amounts, current.memo(),
                    current.payeeId());
        }
        // On the member's own account: re-posted with the record's amounts by the payer or the recorder who acts; else
        // as it is, with the amount its member named, which post() keeps only if nothing else changed (D-8, D-28).
        if (acting && side.original()) {
            return ownSide(family, side, recordId, date, current.accountId(), placed, placedIn, amounts,
                    current.memo(), current.counterpartyId(), current.payeeId());
        }
        // What the line holds comes back above 0; the side's amount has the record's sign, negative for a refund (D-79).
        BigDecimal kept = amounts.amount().signum() < 0 ? current.amount().negate() : current.amount();
        if (current.currency().equals(amounts.currency()) && kept.compareTo(amounts.amount()) != 0) {
            throw wouldChange(family, recordId, side.memberId());
        }
        return ownSide(family, side, recordId, date, current.accountId(), kept, current.currency(), amounts,
                current.memo(), current.counterpartyId(), current.payeeId());
    }

    /** A side on its member's own account that someone else's change would rewrite or delete: a bug (D-8, D-28). */
    private static IllegalStateException wouldChange(LedgerScope family, long recordId, long memberId) {
        return new IllegalStateException(("Record %d: member %d's side on an account of theirs would change through "
                + "member %d's change; only they change it (D-8, D-28)").formatted(recordId, memberId,
                family.memberId()));
    }

    /**
     * The caller's own payments for these records, by record: only those of records they paid with an account, and
     * their own sides of settlements (F4d), from their own personal ledger (D-16).
     */
    @Transactional(readOnly = true)
    public Map<Long, OwnPayment> ownPayments(LedgerScope family, List<Long> recordIds) {
        Map<Long, OwnPayment> payments = new HashMap<>();
        if (recordIds.isEmpty()) {
            return payments;
        }
        jdbc.sql("""
                SELECT l.record_id, e.id AS entry_id, e.version, e.memo, a.id AS account_id, a.name AS account_name,
                       a.code = :placeholder AND a.is_system AS later, abs(p.amount) AS amount, p.currency,
                       p.counterparty_id, e.payee_id
                FROM family_entry_link l
                JOIN journal_entry e ON e.id = l.entry_id
                JOIN ledger_member own ON own.ledger_id = e.ledger_id AND own.ledger_type = 'PERSONAL'
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id AND a.ledger_id = e.ledger_id
                WHERE l.family_ledger_id = :familyId AND l.member_id = :memberId AND l.link_type IN ('PAYMENT', 'SETTLEMENT')
                  AND l.detached_at IS NULL AND l.record_id IN (:recordIds) AND own.user_sub = :sub
                  AND a.family_ledger_id IS NULL AND NOT (a.code = :fx AND a.is_system)""")
                .param("placeholder", CrossLedgerWriter.PLACEHOLDER_CODE).param("fx", CrossLedgerWriter.FX_CODE)
                .param("familyId", family.ledgerId())
                .param("memberId", family.memberId()).param("recordIds", recordIds).param("sub", family.userId())
                .query(row -> {
                    boolean later = row.getBoolean("later");
                    payments.put(row.getLong("record_id"), new OwnPayment(row.getLong("entry_id"), row.getInt("version"),
                            later ? null : row.getLong("account_id"), later ? null : row.getString("account_name"),
                            later, row.getString("memo"), row.getBigDecimal("amount"), row.getString("currency"),
                            row.getObject("counterparty_id", Long.class), row.getObject("payee_id", Long.class)));
                });
        return payments;
    }

    /**
     * A member's share: UNALLOCATED +share with the family category, the debt account −share; an income's share comes
     * negative, so income on UNALLOCATED and the debt account +share.
     */
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

    /**
     * A side with the member's own account: the account −amount and the debt account +the record's amount for the side
     * that paid, the other way round for the side that received; through FX_EXCHANGE if the account's amount isn't in
     * the record's currency (D-87).
     */
    private PostedEntry ownSide(LedgerScope family, Side side, long recordId, LocalDate date, long accountId,
            BigDecimal amount, String currency, Amounts amounts, String note, Long counterpartyId, Long payeeId) {
        return new PostedEntry(side.memberId(), recordId, side.link(), kind(side.link()), false, date,
                lines(family, side, accountId, counterpartyId, amount, currency, amounts), note, payeeId);
    }

    /** "Specify later", or the other side of a settlement: "Payments without a specified account" in its place. */
    private PostedEntry placeholderSide(LedgerScope family, Side side, long recordId, LocalDate date,
            BigDecimal amount, String currency, Amounts amounts, String note, Long payeeId) {
        long placeholder = writer.placeholder(family, side.memberId());
        return new PostedEntry(side.memberId(), recordId, side.link(), kind(side.link()), true, date,
                lines(family, side, placeholder, null, amount, currency, amounts), note, payeeId);
    }

    /**
     * A side's lines, the account's first: in the record's currency the account and the debt account; in another
     * currency the account and FX_EXCHANGE in it, then FX_EXCHANGE and the debt account in the record's currency (rule
     * 9, D-87). FX_EXCHANGE keeps the difference between the two amounts, which no rate decides.
     */
    private List<Line> lines(LedgerScope family, Side side, long accountId, Long counterpartyId, BigDecimal amount,
            String currency, Amounts amounts) {
        long debt = writer.debtAccount(family, side.memberId());
        BigDecimal paid = side.out() ? amount.negate() : amount;
        BigDecimal owed = side.out() ? amounts.amount().negate() : amounts.amount();
        if (currency.equals(amounts.currency())) {
            if (amount.compareTo(amounts.amount()) != 0) {
                throw new IllegalStateException("A side in the record's currency %s is its amount %s, not %s"
                        .formatted(currency, amounts.amount(), amount));
            }
            return List.of(new Line(accountId, currency, paid, null, counterpartyId),
                    new Line(debt, currency, paid.negate(), null));
        }
        long fx = writer.fxExchange(family, side.memberId());
        return List.of(new Line(accountId, currency, paid, null, counterpartyId),
                new Line(fx, currency, paid.negate(), null),
                new Line(fx, amounts.currency(), owed, null),
                new Line(debt, amounts.currency(), owed.negate(), null));
    }

    private static EntryKind kind(LinkType link) {
        return link == LinkType.SETTLEMENT ? EntryKind.FAMILY_SETTLEMENT : EntryKind.FAMILY_PAYMENT;
    }

    private static String key(long memberId, LinkType type) {
        return memberId + " " + type;
    }
}
