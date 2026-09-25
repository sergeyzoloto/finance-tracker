package com.example.financetracker.ledger.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.example.financetracker.ledger.domain.LedgerReferences.AccountInfo;
import com.example.financetracker.ledger.domain.LedgerReferences.CategoryInfo;

/**
 * Checks an entry against every rule the database enforces on it, so that users read one message naming all problems
 * instead of the first SQL error:
 * <ul>
 * <li>the column constraints of {@code journal_entry} and {@code posting}: a date, a memo of at most 500 characters,
 * a currency code, an amount that isn't zero and fits NUMERIC(19,4);
 * <li>{@code posting_check_references}: accounts, categories, counterparties and the payee belong to the entry's
 * user; only postings to EQUITY accounts have a category (rule 5); an account that requires a counterparty gets one
 * on every posting (rule 8);
 * <li>{@code journal_entry_check_balanced}: at least two postings, which sum to zero in each currency (rule 2).
 * </ul>
 * On top of those: the currency is a known ISO 4217 code (rule 1), and an entry whose kind implies a category type
 * (an expense, an income) uses only categories of that type.
 * <p>
 * Pure: the caller supplies the rows the entry refers to.
 */
public final class LedgerValidator {

    static final int MEMO_MAX_LENGTH = 500;
    /** NUMERIC(19,4). */
    private static final int MAX_SCALE = 4;
    private static final int MAX_INTEGER_DIGITS = 15;
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    /** @throws InvalidEntryException naming every violation, if there is any */
    public void check(EntryDraft entry, LedgerReferences references) {
        List<String> violations = violations(entry, references);
        if (!violations.isEmpty()) {
            throw new InvalidEntryException(violations);
        }
    }

    /** Every rule the entry breaks, in the order of the entry: header, postings, balance per currency. */
    public List<String> violations(EntryDraft entry, LedgerReferences references) {
        List<String> violations = new ArrayList<>();
        if (entry.entryDate() == null) {
            violations.add("the entry date is missing");
        }
        if (entry.memo() != null) {
            int length = entry.memo().codePointCount(0, entry.memo().length());
            if (length > MEMO_MAX_LENGTH) {
                violations.add("the memo has %d characters, more than %d".formatted(length, MEMO_MAX_LENGTH));
            }
        }
        if (entry.payeeId() != null && !references.counterparties().contains(entry.payeeId())) {
            violations.add("payee %d does not exist".formatted(entry.payeeId()));
        }
        if (entry.postings().size() < 2) {
            violations.add("an entry needs at least 2 postings, this one has %d".formatted(entry.postings().size()));
        }

        // Sorted, so that the messages come in the same order every time.
        Map<String, Totals> totals = new TreeMap<>();
        for (int i = 0; i < entry.postings().size(); i++) {
            PostingLine posting = entry.postings().get(i);
            checkPosting(i + 1, posting, entry.kind(), references, violations);
            if (posting.currency() != null && posting.amount() != null) {
                totals.computeIfAbsent(posting.currency(), currency -> new Totals()).add(posting.amount());
            }
        }
        totals.forEach((currency, sums) -> {
            if (sums.debits.compareTo(sums.credits) != 0) {
                violations.add("the postings in %s do not balance: debits %s, credits %s, difference %s".formatted(
                        currency, Money.format(sums.debits), Money.format(sums.credits),
                        Money.format(sums.debits.subtract(sums.credits).abs())));
            }
        });
        return violations;
    }

    private static void checkPosting(int number, PostingLine posting, EntryKind kind, LedgerReferences references,
            List<String> violations) {
        AccountInfo account = posting.accountId() == null ? null : references.accounts().get(posting.accountId());
        String label = account == null ? "posting " + number : "posting %d (%s)".formatted(number, account.code());
        if (posting.accountId() == null) {
            violations.add(label + ": the account is missing");
        } else if (account == null) {
            violations.add(label + ": account %d does not exist".formatted(posting.accountId()));
        }

        if (posting.currency() == null) {
            violations.add(label + ": the currency is missing");
        } else if (!isCurrencyCode(posting.currency())) {
            violations.add(label + ": '%s' is not an ISO 4217 currency code".formatted(posting.currency()));
        }

        BigDecimal amount = posting.amount();
        if (amount == null) {
            violations.add(label + ": the amount is missing");
        } else if (amount.signum() == 0) {
            violations.add(label + ": the amount is zero");
        } else {
            BigDecimal stripped = amount.stripTrailingZeros();
            if (stripped.scale() > MAX_SCALE) {
                violations.add(label + ": the amount %s has more than %d decimal places"
                        .formatted(amount.toPlainString(), MAX_SCALE));
            }
            if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
                violations.add(label + ": the amount %s has more than %d digits before the decimal point"
                        .formatted(amount.toPlainString(), MAX_INTEGER_DIGITS));
            }
        }

        if (posting.categoryId() != null) {
            CategoryInfo category = references.categories().get(posting.categoryId());
            if (category == null) {
                violations.add(label + ": category %d does not exist".formatted(posting.categoryId()));
            } else if (kind != null && kind.categoryType() != null && category.type() != kind.categoryType()) {
                violations.add(label + ": category %s is %s, and an entry of kind %s needs %s categories"
                        .formatted(category.code(), category.type(), kind, kind.categoryType()));
            }
            if (account != null && account.type() != AccountType.EQUITY) {
                violations.add(label + ": the account is %s, and only postings to EQUITY accounts can have a category"
                        .formatted(account.type()));
            }
        }

        if (posting.counterpartyId() != null) {
            if (!references.counterparties().contains(posting.counterpartyId())) {
                violations.add(label + ": counterparty %d does not exist".formatted(posting.counterpartyId()));
            }
        } else if (account != null && account.requiresCounterparty()) {
            violations.add(label + ": the account requires a counterparty");
        }
    }

    private static boolean isCurrencyCode(String code) {
        if (!CURRENCY_CODE.matcher(code).matches()) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final class Totals {

        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;

        void add(BigDecimal amount) {
            if (amount.signum() > 0) {
                debits = debits.add(amount);
            } else {
                credits = credits.subtract(amount);
            }
        }
    }
}
