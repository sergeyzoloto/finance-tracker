package com.example.financetracker.ledger.domain;

import static com.example.financetracker.ledger.domain.AccountRole.SHARED;
import static com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * An expense shared with another budget (rule 7). Of the total T, the other side's part is round(T × r, 2) with
 * HALF_UP, and the user's own part is the rest, so the parts always add up to T exactly: the payment account −T,
 * UNALLOCATED +own with the category, the user's shared account (FAMILY_DEBT unless the settings name another) +other.
 * A negative total is a refund and splits the same way.
 *
 * @param shareRatio the other side's share r, between 0 and 1 exclusive; null for the user's default
 */
public record SharedExpenseCommand(LocalDate entryDate, Long payeeId, String memo, Long accountId, String currency,
        BigDecimal total, Long categoryId, BigDecimal shareRatio) implements EntryCommand {

    public SharedExpenseCommand {
        Problems.ofEntry(entryDate)
                .present(accountId, "account")
                .present(currency, "currency")
                .nonZero(total, "total")
                .present(categoryId, "category")
                .check(shareRatio == null || shareRatio.signum() > 0 && shareRatio.compareTo(BigDecimal.ONE) < 0,
                        "the share ratio must be greater than 0 and less than 1")
                .throwIfAny();
    }

    @Override
    public EntryKind kind() {
        return EntryKind.SHARED_EXPENSE;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        BigDecimal ratio = shareRatio != null ? shareRatio : context.defaultShareRatio();
        BigDecimal other = total.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal own = total.subtract(other);
        return List.of(
                PostingLine.of(accountId, currency, total.negate()),
                new PostingLine(context.account(UNALLOCATED), currency, own, categoryId, null),
                PostingLine.of(context.account(SHARED), currency, other));
    }
}
