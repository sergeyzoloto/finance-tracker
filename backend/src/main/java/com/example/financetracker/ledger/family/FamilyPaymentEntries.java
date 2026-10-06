package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.EntryNotFoundException;
import com.example.financetracker.ledger.FamilyPayments;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payer's own payment for a family expense, as an entry of their personal ledger (F4c; D-14; ADR 0003, topic E):
 * a change of its date, amount, account or note is the payer's change of the expense, through
 * {@link FamilyRecordService#update}, and deleting it deletes the expense; the receiver's receipt of a family income
 * the same way (F4d). A member's own side of a settlement (F4d)
 * changes the same way: its account by either side, its date and amount by the side who recorded it, who also deletes
 * it through it (D-24). The entry is found in the caller's own personal ledger only, so nobody but its member reaches
 * it: anyone else's entry is missing (rule 11).
 * <p>
 * Only while the feature switch is on (D-25, {@link FamilySwitch}).
 */
@Service
@ConditionalOnProperty(name = FamilySwitch.PROPERTY, havingValue = "true")
public class FamilyPaymentEntries implements FamilyPayments {

    /**
     * A change of the payment: fields left null stay as they are; the note changes, also to none, when
     * {@code changesNote} is set.
     *
     * @param amount the record's amount, in its currency (D-45)
     * @param accountId the account of the personal ledger it is paid from
     * @param later "Specify later": "Payments without a specified account"
     * @param note the payer's private note, on the entry only
     * @param currency the record's new currency, which needs its amount (D-45)
     * @param accountAmount what went from or into the account, in the paying currency, when that isn't the record's
     *        (D-87, D-89)
     * @param accountCurrency the paying currency (D-89, F8b); null keeps the side's, or for a newly named account takes
     *        its default currency, else the record's
     */
    public record PaymentEdit(LocalDate date, BigDecimal amount, Long accountId, boolean later, boolean changesNote,
            String note, String currency, BigDecimal accountAmount, String accountCurrency) {

        public PaymentEdit(LocalDate date, BigDecimal amount, Long accountId, boolean later, boolean changesNote,
                String note) {
            this(date, amount, accountId, later, changesNote, note, null, null, null);
        }
    }

    /** The entry's link to the expense it pays. */
    private record Payment(int version, long familyLedgerId, long recordId, long memberId) {
    }

    private final JdbcClient jdbc;
    private final LedgerAccess access;
    private final FamilyRecordService records;

    FamilyPaymentEntries(JdbcClient jdbc, LedgerAccess access, FamilyRecordService records) {
        this.jdbc = jdbc;
        this.access = access;
        this.records = records;
    }

    /**
     * Changes the expense the entry pays, as its payer, or the settlement it is a side of, as that side (F4d).
     *
     * @param expectedVersion the entry's version the caller read
     * @throws EntryNotFoundException if the personal ledger has no such entry
     * @throws OptimisticLockingFailureException if the entry is no longer at {@code expectedVersion}
     * @throws ConflictException if the entry isn't a payment for a family expense, or the expense is frozen
     * @throws RuleViolationException listing every rule the change breaks, such as a new amount of an expense split by
     *         amounts ({@code AMOUNTS_NEEDED})
     */
    @Transactional
    public FamilyRecordView update(LedgerScope personal, long entryId, int expectedVersion, PaymentEdit edit) {
        Payment payment = payment(personal, entryId);
        if (payment.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(("Journal entry %d has changed since version %d. Reload it and "
                    + "try again.").formatted(entryId, expectedVersion));
        }
        return records.update(familyOf(personal, payment), personal, payment.recordId(), null,
                new FamilyRecordChanges(null, false, null, null, edit.date(), edit.amount(), null, edit.accountId(),
                        edit.later(), edit.changesNote(), edit.note(), edit.currency(),
                        edit.accountAmount(), edit.accountCurrency()));
    }

    /** {@inheritDoc} The caller has checked the entry's version. */
    @Override
    @Transactional
    public void deleteRecordOf(LedgerScope personal, long entryId) {
        Payment payment = payment(personal, entryId);
        records.delete(familyOf(personal, payment), payment.recordId(), null);
    }

    private Payment payment(LedgerScope personal, long entryId) {
        if (personal.type() != LedgerType.PERSONAL) {
            throw new IllegalArgumentException("A payment entry is in its payer's personal ledger");
        }
        record Found(int version, String link, Long familyLedgerId, Long recordId, Long memberId) {
        }
        Found found = jdbc.sql("""
                SELECT e.version, l.link_type, l.family_ledger_id, l.record_id, l.member_id
                FROM journal_entry e
                LEFT JOIN family_entry_link l ON l.entry_id = e.id AND l.detached_at IS NULL
                WHERE e.id = :entryId AND e.ledger_id = :ledgerId""")
                .param("entryId", entryId).param("ledgerId", personal.ledgerId())
                .query((row, n) -> new Found(row.getInt("version"), row.getString("link_type"),
                        row.getObject("family_ledger_id", Long.class), row.getObject("record_id", Long.class),
                        row.getObject("member_id", Long.class)))
                .optional()
                .orElseThrow(() -> new EntryNotFoundException(entryId));
        if (!"PAYMENT".equals(found.link()) && !"SETTLEMENT".equals(found.link())) {
            throw new ConflictException(("Entry %d is not your payment for a family expense, what you received for a "
                    + "family income or your side of a settlement, so it doesn't change as one").formatted(entryId));
        }
        return new Payment(found.version(), found.familyLedgerId(), found.recordId(), found.memberId());
    }

    /** The family ledger, as the payer: the member whose payment it is, which is only ever the caller. */
    private LedgerScope familyOf(LedgerScope personal, Payment payment) {
        LedgerScope family;
        try {
            family = access.member(personal.userId(), payment.familyLedgerId());
        } catch (NotFoundException e) {
            throw new IllegalStateException("A payment's link outlived its payer's membership", e);
        }
        if (family.memberId() != payment.memberId()) {
            throw new IllegalStateException("Payment of member %d in the ledger of member %d"
                    .formatted(payment.memberId(), family.memberId()));
        }
        return family;
    }
}
