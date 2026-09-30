package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A family expense as its author enters it (C1; D-6, D-14), in the family ledger's base currency (F4a).
 *
 * @param payerMemberId who paid: the author themselves if they have an account, or a member without one (D-14)
 * @param paymentAccountId for a payer with an account, the account of their personal ledger they paid with; or null
 * @param paymentLater for a payer with an account, "Specify later": the payment goes to "Payments without a specified
 *        account" (D-14)
 */
public record NewFamilyRecord(LocalDate date, long categoryId, BigDecimal amount, String comment, long payerMemberId,
        Long paymentAccountId, boolean paymentLater, RecordSplit split) {
}
