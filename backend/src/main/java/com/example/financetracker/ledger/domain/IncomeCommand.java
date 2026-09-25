package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Money received into an account under an INCOME category (rule 5): the account +amount, UNALLOCATED −amount with the
 * category. A negative amount reverses an income.
 */
public record IncomeCommand(LocalDate entryDate, Long payeeId, String memo, Long accountId, String currency,
        BigDecimal amount, Long categoryId) implements EntryCommand {

    public IncomeCommand {
        Problems.ofEntry(entryDate)
                .present(accountId, "account")
                .present(currency, "currency")
                .nonZero(amount, "amount")
                .present(categoryId, "category")
                .throwIfAny();
    }

    @Override
    public EntryKind kind() {
        return EntryKind.INCOME;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        return List.of(
                PostingLine.of(accountId, currency, amount),
                new PostingLine(context.account(UNALLOCATED), currency, amount.negate(), categoryId, null));
    }
}
