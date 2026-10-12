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
 * @param paymentCounterpartyId for a recorder whose side is on an account that requires a counterparty (D-148), such
 *        as a loan given or a creditor debt: the counterparty of their personal ledger the account's line names;
 *        required then, and null otherwise
 * @param throughExchange the recorder's side goes through their FX_EXCHANGE, which {@code paymentAccountId} then
 *        names: one of the two settlements that convert a family debt from one currency into another (D-71). Only the
 *        import sets it; no endpoint does
 */
public record NewSettlement(LocalDate date, BigDecimal amount, long payerMemberId, long payeeMemberId, String comment,
        Long paymentAccountId, boolean paymentLater, String currency, BigDecimal accountAmount,
        String accountCurrency, Long paymentCounterpartyId, boolean throughExchange) {

    /** A settlement whose recorder's side, if any, is on an account that requires no counterparty. */
    public NewSettlement(LocalDate date, BigDecimal amount, long payerMemberId, long payeeMemberId, String comment,
            Long paymentAccountId, boolean paymentLater, String currency, BigDecimal accountAmount,
            String accountCurrency) {
        this(date, amount, payerMemberId, payeeMemberId, comment, paymentAccountId, paymentLater, currency,
                accountAmount, accountCurrency, null, false);
    }
}
