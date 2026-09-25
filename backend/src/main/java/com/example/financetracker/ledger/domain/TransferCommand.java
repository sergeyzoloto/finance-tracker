package com.example.financetracker.ledger.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Money moved between two accounts, without a category (rule 6): the source −amount, the target +amount. The optional
 * counterparty goes on both postings, so that either account may be one that requires it (rule 8), such as
 * CREDITOR_DEBT.
 */
public record TransferCommand(LocalDate entryDate, Long payeeId, String memo, Long fromAccountId, Long toAccountId,
        String currency, BigDecimal amount, Long counterpartyId) implements EntryCommand {

    public TransferCommand {
        Problems.ofEntry(entryDate)
                .present(fromAccountId, "source account")
                .present(toAccountId, "target account")
                .check(fromAccountId == null || !fromAccountId.equals(toAccountId), "the two accounts must differ")
                .present(currency, "currency")
                .positive(amount, "amount")
                .throwIfAny();
    }

    @Override
    public EntryKind kind() {
        return EntryKind.TRANSFER;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        return List.of(
                new PostingLine(fromAccountId, currency, amount.negate(), null, counterpartyId),
                new PostingLine(toAccountId, currency, amount, null, counterpartyId));
    }
}
