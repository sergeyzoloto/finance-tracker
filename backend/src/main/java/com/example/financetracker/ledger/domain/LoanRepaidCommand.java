package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.AccountRole.LOANS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A borrower paying back, in full or in part: LOANS_ASSET −amount with the borrower as counterparty (rule 8), the
 * receiving account +amount.
 */
public record LoanRepaidCommand(LocalDate entryDate, Long payeeId, String memo, Long toAccountId, Long counterpartyId,
        String currency, BigDecimal amount) implements EntryCommand {

    public LoanRepaidCommand {
        Problems.ofEntry(entryDate)
                .present(toAccountId, "account")
                .present(counterpartyId, "borrower")
                .present(currency, "currency")
                .positive(amount, "amount")
                .throwIfAny();
    }

    @Override
    public EntryKind kind() {
        return EntryKind.LOAN_REPAID;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        return List.of(
                new PostingLine(context.account(LOANS), currency, amount.negate(), null, counterpartyId),
                PostingLine.of(toAccountId, currency, amount));
    }
}
