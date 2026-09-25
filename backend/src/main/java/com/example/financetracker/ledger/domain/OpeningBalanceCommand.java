package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.AccountRole.OPENING_BALANCE;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An account's balance when its history in the ledger starts, posted against OPENING_BALANCE (rule 10): the account
 * +amount, OPENING_BALANCE −amount.
 *
 * @param amount the posting to the account, signed as every posting is (rule 2): positive for an asset, negative
 *        for money owed on a LIABILITY account
 * @param counterpartyId for an account that requires one (rule 8), whose balance is kept per counterparty
 */
public record OpeningBalanceCommand(LocalDate entryDate, String memo, Long accountId, String currency,
        BigDecimal amount, Long counterpartyId) implements EntryCommand {

    public OpeningBalanceCommand {
        Problems.ofEntry(entryDate)
                .present(accountId, "account")
                .present(currency, "currency")
                .nonZero(amount, "amount")
                .throwIfAny();
    }

    @Override
    public EntryKind kind() {
        return EntryKind.OPENING_BALANCE;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        return List.of(
                new PostingLine(accountId, currency, amount, null, counterpartyId),
                PostingLine.of(context.account(OPENING_BALANCE), currency, amount.negate()));
    }
}
