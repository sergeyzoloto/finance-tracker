package com.example.financetracker.ledger.report;

import java.math.BigDecimal;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * An account's displayed balance in one currency (rule 4): the sum of its postings for an ASSET account, minus that
 * sum for a LIABILITY or EQUITY account. The amount is {@link Money#normalize}d, as in an entry.
 *
 * @param familyLedgerId for a member's debt account to a family budget ({@code account.family_ledger_id}) of one of
 *        their ACTIVE memberships, that budget (D-299, UX-1); left out for every other account and with the family
 *        switch off
 * @param familyLedgerName its name, left out likewise
 * @param archived whether the account is archived: an archived account is listed only in a currency it holds money in
 *        (UX-2, D-355)
 */
public record AccountBalance(long accountId, String accountCode, String accountName, AccountType accountType,
        String currency, BigDecimal balance, @JsonInclude(Include.NON_NULL) Long familyLedgerId,
        @JsonInclude(Include.NON_NULL) String familyLedgerName, boolean archived) {

    public AccountBalance {
        balance = Money.normalize(balance);
    }

    /** The balance of an account that is no debt to a family budget. */
    public AccountBalance(long accountId, String accountCode, String accountName, AccountType accountType,
            String currency, BigDecimal balance) {
        this(accountId, accountCode, accountName, accountType, currency, balance, null, null, false);
    }
}
