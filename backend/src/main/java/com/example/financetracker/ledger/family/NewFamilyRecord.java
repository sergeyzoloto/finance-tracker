package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A family expense or income as its author enters it (C1, C5; D-6, D-14), in the family ledger's base currency (F4a).
 *
 * @param type EXPENSE or INCOME (F4d)
 * @param payerMemberId who paid an expense or received an income: the author themselves if they have an account, or a
 *        member without one (D-14)
 * @param paymentAccountId for a payer with an account, the account of their personal ledger they paid with, or for an
 *        income received it into; or null
 * @param paymentLater for a payer with an account, "Specify later": the payment goes to "Payments without a specified
 *        account" (D-14)
 * @param privateNote for the author who paid: a note that only their own payment entry holds, never the family's
 *        answers or journal (F4c, C2); or null
 */
public record NewFamilyRecord(String type, LocalDate date, long categoryId, BigDecimal amount, String comment,
        long payerMemberId, Long paymentAccountId, boolean paymentLater, RecordSplit split, String privateNote) {

    public NewFamilyRecord {
        if (!type.equals("EXPENSE") && !type.equals("INCOME")) {
            throw new IllegalArgumentException("A family record with shares is an EXPENSE or an INCOME, not " + type);
        }
    }
}
