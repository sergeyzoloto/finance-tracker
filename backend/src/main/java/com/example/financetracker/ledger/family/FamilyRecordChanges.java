package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A change of a family record (D-14): its family fields (the category, the comment, the split) and its payment fields
 * (the date, the amount, the payer, and for the payer with an account the account paid with, or "Specify later", and
 * their private note on their payment). Null leaves a field as it is; the comment and the note change, also to none,
 * when their flag is set.
 *
 * @param paymentAccountId the account of the caller's personal ledger they paid with, when they are the payer
 * @param paymentLater "Specify later", when the caller is the payer
 * @param note the payer's private note, which only their payment entry holds (F4c)
 * @param amount the record's amount, in its currency or in {@code currency}
 * @param currency the record's new currency (D-45), which needs its amount
 * @param accountAmount what went from or into the caller's own account, in its currency, when it isn't the record's
 *        (D-87): for the payer, the receiver or a settlement's recorder, and for the other side of a settlement, whose
 *        own entry alone holds it (F4e)
 */
public record FamilyRecordChanges(Long categoryId, boolean changesComment, String comment, RecordSplit split,
        LocalDate date, BigDecimal amount, Long payerMemberId, Long paymentAccountId, boolean paymentLater,
        boolean changesNote, String note, String currency, BigDecimal accountAmount) {

    /** A change of the family fields only. */
    public static FamilyRecordChanges family(Long categoryId, boolean changesComment, String comment,
            RecordSplit split) {
        return new FamilyRecordChanges(categoryId, changesComment, comment, split, null, null, null, null, false, false,
                null, null, null);
    }

    /**
     * Whether it names a payment field: the date, the amount, its currency, the payer, the account (with its amount),
     * or the note.
     */
    boolean changesPayment() {
        return date != null || amount != null || currency != null || payerMemberId != null || namesAccount()
                || changesNote || accountAmount != null;
    }

    /** Whether it names the amount or its currency. */
    boolean changesAmount() {
        return amount != null || currency != null;
    }

    /** Whether it says how the caller paid: an account, or "Specify later". */
    boolean namesAccount() {
        return paymentAccountId != null || paymentLater;
    }
}
