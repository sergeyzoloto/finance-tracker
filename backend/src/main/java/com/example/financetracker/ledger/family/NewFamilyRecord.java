package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A family expense or income as its author enters it (C1, C5; D-6, D-13, D-14): its original amount, and its amount in
 * the family ledger's base currency (F4e).
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
 * @param amount the original amount, in {@code currency}
 * @param currency the original currency (F4e): the paying account's if it has one; else as chosen, or null for the
 *        base currency
 * @param baseAmount the amount in the base currency, as entered (F4e); null for the rate's (D-13)
 */
public record NewFamilyRecord(String type, LocalDate date, long categoryId, BigDecimal amount, String comment,
        long payerMemberId, Long paymentAccountId, boolean paymentLater, RecordSplit split, String privateNote,
        String currency, BigDecimal baseAmount) {

    public NewFamilyRecord {
        if (!type.equals("EXPENSE") && !type.equals("INCOME")) {
            throw new IllegalArgumentException("A family record with shares is an EXPENSE or an INCOME, not " + type);
        }
    }
}
