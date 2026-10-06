package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A family expense or income as its author enters it (C1, C5; D-6, D-14, D-45, D-87): its amount in its own currency,
 * and what went from or into the payer's account when that is in another currency.
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
 * @param amount the record's amount, in {@code currency}, which its shares split (D-45)
 * @param currency the record's currency (D-45); null for the family ledger's main currency
 * @param accountAmount what went from or into the payer's account, in the paying currency, when that isn't the
 *        record's (D-87, D-89); null otherwise
 * @param accountCurrency the paying currency, what the payer's account paid or received in (D-89, F8b); null for the
 *        account's default currency, else the record's
 */
public record NewFamilyRecord(String type, LocalDate date, long categoryId, BigDecimal amount, String comment,
        long payerMemberId, Long paymentAccountId, boolean paymentLater, RecordSplit split, String privateNote,
        String currency, BigDecimal accountAmount, String accountCurrency) {

    public NewFamilyRecord {
        if (!type.equals("EXPENSE") && !type.equals("INCOME")) {
            throw new IllegalArgumentException("A family record with shares is an EXPENSE or an INCOME, not " + type);
        }
    }
}
