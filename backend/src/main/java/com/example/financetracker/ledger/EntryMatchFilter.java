package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.example.financetracker.ledger.domain.CategoryType;

/**
 * What {@link EntryMatchService#matches} looks for (UX-8, D-453 to D-459, D-463): the user's earlier expenses or
 * incomes like the one being typed. Every criterion that is set must match. A text of fewer than 2 characters, trimmed,
 * is no criterion (D-458).
 *
 * @param type EXPENSE or INCOME
 * @param payeeWords the payee's words, each to appear in the entry's payee's name, in any case; empty for none
 * @param memoWords the memo's words, each to appear in the entry's memo; empty for none
 * @param amount the amount without its sign, as the client wrote it ("12.5"), or null for none (D-459)
 * @param currency the form's currency, in which an amount within 1 matches too; null for the characters alone
 */
public record EntryMatchFilter(CategoryType type, List<String> payeeWords, List<String> memoWords, Long accountId,
        Long categoryId, String amount, String currency) {

    /** A plain decimal at or above 0: digits, and up to 4 decimals after a dot (rule 3's NUMERIC(19,4)). */
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,15}(\\.\\d{1,4})?");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public EntryMatchFilter {
        if (type != CategoryType.EXPENSE && type != CategoryType.INCOME) {
            throw new IllegalArgumentException("'type' must be EXPENSE or INCOME");
        }
        payeeWords = List.copyOf(payeeWords);
        memoWords = List.copyOf(memoWords);
        if (amount != null && !AMOUNT.matcher(amount).matches()) {
            throw new IllegalArgumentException("'amount' must be a plain decimal at or above 0, such as 12.50");
        }
        if (currency != null && !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("'currency' must be a three-letter currency code, such as EUR");
        }
        if (payeeWords.isEmpty() && memoWords.isEmpty() && accountId == null && categoryId == null && amount == null) {
            throw new IllegalArgumentException("Give at least one of 'payee', 'memo', 'accountId', 'categoryId' and "
                    + "'amount'; a text counts from 2 characters");
        }
    }

    /** The filter of the request's parameters: the texts split into words, a text under 2 characters left out. */
    public static EntryMatchFilter of(CategoryType type, String payee, String memo, Long accountId, Long categoryId,
            String amount, String currency) {
        return new EntryMatchFilter(type, words(payee), words(memo), accountId, categoryId,
                amount == null || amount.isBlank() ? null : amount.strip(),
                currency == null || currency.isBlank() ? null : currency.strip());
    }

    /** The amount as a number, for the match within 1; null without one. */
    BigDecimal amountValue() {
        return amount == null ? null : new BigDecimal(amount);
    }

    private static List<String> words(String text) {
        if (text == null || text.strip().length() < 2) {
            return List.of();
        }
        return Arrays.stream(SPACES.split(text.strip())).filter(w -> !w.isEmpty()).toList();
    }
}
