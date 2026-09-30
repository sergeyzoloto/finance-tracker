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
 */
public record FamilyRecordChanges(Long categoryId, boolean changesComment, String comment, RecordSplit split,
        LocalDate date, BigDecimal amount, Long payerMemberId, Long paymentAccountId, boolean paymentLater,
        boolean changesNote, String note) {

    /** A change of the family fields only. */
    public static FamilyRecordChanges family(Long categoryId, boolean changesComment, String comment,
            RecordSplit split) {
        return new FamilyRecordChanges(categoryId, changesComment, comment, split, null, null, null, null, false, false,
                null);
    }

    /** Whether it names a payment field: the date, the amount, the payer, the account, or the note. */
    boolean changesPayment() {
        return date != null || amount != null || payerMemberId != null || namesAccount() || changesNote;
    }

    /** Whether it says how the caller paid: an account, or "Specify later". */
    boolean namesAccount() {
        return paymentAccountId != null || paymentLater;
    }
}
