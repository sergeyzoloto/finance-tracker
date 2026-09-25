package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.AccountRole.LOANS;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Money lent to a counterparty: the paying account −amount, LOANS_ASSET +amount with the borrower as counterparty
 * (rule 8).
 */
public record LoanGivenCommand(LocalDate entryDate, Long payeeId, String memo, Long fromAccountId, Long counterpartyId,
        String currency, BigDecimal amount) implements EntryCommand {

    public LoanGivenCommand {
        Problems.ofEntry(entryDate)
                .present(fromAccountId, "account")
                .present(counterpartyId, "borrower")
                .present(currency, "currency")
                .positive(amount, "amount")
                .throwIfAny();
    }

    @Override
    public EntryKind kind() {
        return EntryKind.LOAN_GIVEN;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        return List.of(
                PostingLine.of(fromAccountId, currency, amount.negate()),
                new PostingLine(context.account(LOANS), currency, amount, null, counterpartyId));
    }
}
