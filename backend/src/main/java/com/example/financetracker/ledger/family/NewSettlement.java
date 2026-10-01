package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A settlement as its recorder enters it (D2, D-24): one member pays another. Its base amount, in the family ledger's
 * base currency, is what settles the balances (D-13, F4e); its original amount is in the recorder's account's currency.
 * A member with an account records one in which they pay or receive, with their own side's account; an owner also
 * records one between two members without an account.
 *
 * @param payerMemberId who paid
 * @param payeeMemberId who received
 * @param paymentAccountId for a recorder who pays or receives, the account of their personal ledger it went from or
 *        into; or null
 * @param paymentLater for a recorder who pays or receives, "Specify later": their side goes to "Payments without a
 *        specified account"
 * @param amount the original amount, in {@code currency}
 * @param currency the original currency (F4e): the recorder's account's if it has one; else as chosen, or null for the
 *        base currency
 * @param baseAmount the amount in the base currency, as entered; null for the rate's (D-13)
 */
public record NewSettlement(LocalDate date, BigDecimal amount, long payerMemberId, long payeeMemberId, String comment,
        Long paymentAccountId, boolean paymentLater, String currency, BigDecimal baseAmount) {
}
