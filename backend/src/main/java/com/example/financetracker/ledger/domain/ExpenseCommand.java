package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Money spent from an account under an EXPENSE category (rule 5): the account −amount, UNALLOCATED +amount with the
 * category. A negative amount is a refund, filed under the same category.
 */
public record ExpenseCommand(LocalDate entryDate, Long payeeId, String memo, Long accountId, String currency,
        BigDecimal amount, Long categoryId) implements EntryCommand {

    public ExpenseCommand {
        Problems.ofEntry(entryDate)
                .present(accountId, "account")
                .present(currency, "currency")
                .nonZero(amount, "amount")
                .present(categoryId, "category")
                .throwIfAny();
    }

    @Override
    public EntryKind kind() {
        return EntryKind.EXPENSE;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        return List.of(
                PostingLine.of(accountId, currency, amount.negate()),
                new PostingLine(context.account(UNALLOCATED), currency, amount, categoryId, null));
    }
}
