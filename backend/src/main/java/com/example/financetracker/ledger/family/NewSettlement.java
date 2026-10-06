package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A settlement as its recorder enters it (D2, D-24): one member pays another, in one currency, which settles the
 * balances in that currency only (D-46). A member with an account records one in which they pay or receive, with their
 * own side's account; an owner also records one between two members without an account.
 *
 * @param payerMemberId who paid
 * @param payeeMemberId who received
 * @param paymentAccountId for a recorder who pays or receives, the account of their personal ledger it went from or
 *        into; or null
 * @param paymentLater for a recorder who pays or receives, "Specify later": their side goes to "Payments without a
 *        specified account"
 * @param amount the settlement's amount, in {@code currency}
 * @param currency the settlement's currency (D-46); null for the family ledger's main currency
 * @param accountAmount what went from or into the recorder's account, in the paying currency, when that isn't the
 *        settlement's (D-87, D-89); null otherwise
 * @param accountCurrency the recorder's paying currency (D-89, F8b); null for the account's default currency, else the
 *        settlement's
 */
public record NewSettlement(LocalDate date, BigDecimal amount, long payerMemberId, long payeeMemberId, String comment,
        Long paymentAccountId, boolean paymentLater, String currency, BigDecimal accountAmount,
        String accountCurrency) {
}
