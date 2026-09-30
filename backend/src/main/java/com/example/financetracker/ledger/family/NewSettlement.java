package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A settlement as its recorder enters it (D2, D-24): one member pays another, in the family ledger's base currency.
 * A member with an account records one in which they pay or receive, with their own side's account; an owner also
 * records one between two members without an account.
 *
 * @param payerMemberId who paid
 * @param payeeMemberId who received
 * @param paymentAccountId for a recorder who pays or receives, the account of their personal ledger it went from or
 *        into; or null
 * @param paymentLater for a recorder who pays or receives, "Specify later": their side goes to "Payments without a
 *        specified account"
 */
public record NewSettlement(LocalDate date, BigDecimal amount, long payerMemberId, long payeeMemberId, String comment,
        Long paymentAccountId, boolean paymentLater) {
}
