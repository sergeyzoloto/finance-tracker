package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.domain.AccountRole.FX_EXCHANGE;
import static com.example.financetracker.ledger.domain.AccountRole.LOANS;
import static com.example.financetracker.ledger.domain.AccountRole.OPENING_BALANCE;
import static com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.family.ImportSync;
import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.Chart.CategoryRef;
import com.example.financetracker.ledger.importer.EntryPlan.Line;
import com.example.financetracker.ledger.importer.EntryPlan.Party;

/**
 * Turns a row of the owner's personal journal into an entry by the domain rules, without a database: a row without the
 * family flag, or one with it and no item (D-72). Its postings come in the order credit side, the exchange through
 * FX_EXCHANGE if the two sides' currencies differ, debit side. Family rows are {@link ImportPlanner}'s.
 * <p>
 * Whether the rows it refers to exist and fit together is left to {@code LedgerValidator}, when the entry is written;
 * this class reports what only the row can tell. Its messages never hold a value of the row.
 */
final class EntryMapper {

    /**
     * D-192: a row without the Family flag whose two sides are the same account in one currency, with the same amount:
     * it moves nothing, in Excel or in the app, and is skipped and named.
     */
    static final String SAME_ACCOUNT_NOOP = "SAME_ACCOUNT_NOOP: Debit and Credit are the same account, in one currency, "
            + "and the row moves nothing: skipped (D-192)";

    /** Exp. Item of a row without a category. */
    static final String NO_CATEGORY = "-";
    /**
     * The category the workbook books exchange gains under, as income on UNALLOCATED. Under rule 9 an exchange's
     * result is the balance of FX_EXCHANGE, so these rows would count it twice.
     */
    static final String FX_GAIN_CATEGORY = "Прибыль от курсовой разницы";
    /** The counterparty for a posting that needs one (rule 8) when the row names none. */
    static final String UNASSIGNED = "Unassigned";
    /** The most decimals a posting holds (NUMERIC(19,4), LedgerValidator). */
    static final int POSTING_SCALE = 4;

    private final Chart chart;

    EntryMapper(Chart chart) {
        this.chart = chart;
    }

    /** What a reference to a row of the Excel file starts with (D-142, D-153). */
    static final String REFERENCE_PREFIX = "xls:";

    /** The personal entry's reference in its ledger (D-142): the row's permanent ID. */
    static String reference(long id) {
        return REFERENCE_PREFIX + id;
    }

    RowResult map(TransactionRow row) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean fxGain = FX_GAIN_CATEGORY.equals(row.item());
        if (fxGain && row.sum().signum() == 0) {
            return new RowResult.Skipped("an FX gain with Sum 0", true, warnings);
        }
        if (row.sum().signum() == 0 && row.debitAmount().signum() == 0 && row.creditAmount().signum() == 0) {
            return new RowResult.Skipped("Sum is 0 (D-72)", false, warnings);
        }

        AccountRef debit = account(row.debit(), TransactionRow.DEBIT, errors);
        AccountRef credit = account(row.credit(), TransactionRow.CREDIT, errors);
        CategoryRef category = null;
        if (!NO_CATEGORY.equals(row.item())) {
            category = chart.categoriesByName().get(row.item());
            if (category == null) {
                errors.add("Exp. Item is not in the categories file");
            }
        }
        if (category != null || NO_CATEGORY.equals(row.item())) {
            checkItemCode(row, category, warnings);
        }
        // The same account on both sides is an exchange within it, from one of its currencies into another (rule 9; an
        // agent's choice, D3b); in one currency it moves nothing. Without the Family flag and with both sides equal it
        // is skipped as a no-op, and named (D-192); otherwise an error (D-156).
        if (debit != null && debit.equals(credit) && row.debitCurrencyOrRow().equals(row.creditCurrencyOrRow())) {
            if (!row.flagged() && row.debitAmount().compareTo(row.creditAmount()) == 0) {
                warnings.add(SAME_ACCOUNT_NOOP);
                return new RowResult.Skipped(SAME_ACCOUNT_NOOP, false, warnings);
            }
            errors.add("Debit and Credit are the same account, in one currency");
        }
        checkLength(row.counteragent(), TransactionRow.COUNTERAGENT, errors);
        checkLength(row.loanCounterparty(), TransactionRow.LOAN_CA, errors);
        if (!errors.isEmpty()) {
            return new RowResult.Failed(errors, warnings);
        }
        if (debit.familyDebt() || credit.familyDebt()) {
            // The planner sends these rows to the family budget; a personal entry never posts to a debt account.
            return new RowResult.Failed(List.of("the row posts to FAMILY_DEBT, which only the family budget does"),
                    warnings);
        }

        // Rule 2: each side in its own currency when the row gives one, and through FX_EXCHANGE if they differ (rule 9).
        // An amount with more decimals than a posting holds is rounded half up to 4 (an agent's choice, D3b).
        List<Drift> drift = new ArrayList<>();
        String debitCurrency = row.debitCurrencyOrRow();
        String creditCurrency = row.creditCurrencyOrRow();
        BigDecimal debitAmount = rounded(row.debitAmount(), debit, debitCurrency, 1, row.date(), drift, warnings);
        BigDecimal creditAmount = rounded(row.creditAmount(), credit, creditCurrency, -1, row.date(), drift, warnings);
        boolean exchange = !debitCurrency.equals(creditCurrency);
        if (!exchange && debitAmount.compareTo(creditAmount) != 0) {
            errors.add("the debit side's and the credit side's amounts differ, in one currency");
        }

        AccountRef unallocated = debit.is(UNALLOCATED) ? debit : credit.is(UNALLOCATED) ? credit : null;
        if (category != null && unallocated == null) {
            errors.add("Exp. Item is a category, and neither Debit nor Credit is UNALLOCATED");
        }

        Party borrower = null;
        if (debit.requiresCounterparty() || credit.requiresCounterparty()) {
            if (row.loanCounterparty() != null) {
                borrower = new Party(row.loanCounterparty(), Counterparty.Kind.PERSON);
            } else {
                borrower = new Party(UNASSIGNED, null);
                warnings.add("LOAN_CA is empty on a row with an account that requires a counterparty: '%s' is used"
                        .formatted(UNASSIGNED));
            }
        } else if (row.loanCounterparty() != null) {
            warnings.add("LOAN_CA is ignored: neither account requires a counterparty");
        }
        if (!errors.isEmpty()) {
            return new RowResult.Failed(errors, warnings);
        }

        List<Line> lines = new ArrayList<>();
        lines.add(line(credit, creditCurrency, creditAmount.negate(), category, borrower));
        if (exchange) {
            long fxExchange = chart.account(FX_EXCHANGE).id();
            lines.add(new Line(fxExchange, creditCurrency, creditAmount, null, null));
            lines.add(new Line(fxExchange, debitCurrency, debitAmount.negate(), null, null));
        }
        lines.add(line(debit, debitCurrency, debitAmount, category, borrower));

        EntryKind kind = kind(debit, credit, category, exchange, debitAmount, creditAmount);
        Party payee = row.counteragent() == null ? null : new Party(row.counteragent(), Counterparty.Kind.MERCHANT);
        String memo = row.comment() != null && !row.comment().equals(row.counteragent()) ? row.comment() : null;
        return new RowResult.Entry(new EntryPlan(reference(row.id()), row.contentHash(), row.date(), kind, payee, memo,
                lines, fxGain, drift), warnings);
    }

    /**
     * The opening balances (D-62): the first row of the daily balance file, one entry on its date, each account's
     * displayed balance turned into a posting by rule 4, and OPENING_BALANCE taking the rest in each currency (rule 10).
     * FAMILY_DEBT's opening is the family budget's, which starts at 0 (D-70): a non-zero one is an error.
     *
     * @param balances by column's account code and currency, as Excel shows them; zeros are left out
     */
    RowResult openingEntry(LocalDate date, Map<DailyFile.Column, BigDecimal> balances) {
        return openingEntry(date, balances, null);
    }

    /**
     * As above, for a member who took his seat (D-180): his FAMILY_DEBT balance isn't an error but his claim's, and
     * goes into {@code familyDebt} by currency, as Excel shows it, for the reconciliation.
     *
     * @param familyDebt where FAMILY_DEBT's balances go; null for the owner's import, where one is an error (D-70)
     */
    RowResult openingEntry(LocalDate date, Map<DailyFile.Column, BigDecimal> balances,
            Map<String, BigDecimal> familyDebt) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        List<String> hashed = new ArrayList<>();
        hashed.add(date.toString());
        balances.forEach((column, cell) -> {
            // Excel's floating-point residues (-1.42109E-14) are 0 at the 4 decimals a posting holds.
            BigDecimal balance = cell.setScale(POSTING_SCALE, RoundingMode.HALF_UP);
            if (balance.signum() == 0) {
                return;
            }
            AccountRef account = chart.accountsByCode().get(column.code());
            if (account == null) {
                errors.add("the daily balance file's first row has a balance on an account that isn't in the accounts "
                        + "file");
                return;
            }
            if (account.familyDebt() && familyDebt != null) {
                familyDebt.merge(column.currency(), cell, BigDecimal::add);
                return;
            }
            if (account.familyDebt()) {
                errors.add("the daily balance file's first row has a FAMILY_DEBT balance, which the family budget "
                        + "starts without (D-70)");
                return;
            }
            Party counterparty = null;
            if (account.requiresCounterparty()) {
                counterparty = new Party(UNASSIGNED, null);
                warnings.add("an opening balance on an account that requires a counterparty: '%s' is used"
                        .formatted(UNASSIGNED));
            }
            BigDecimal amount = account.type() == AccountType.ASSET ? balance : balance.negate();
            lines.add(new Line(account.id(), column.currency(), amount, null, counterparty));
            hashed.add(column.code() + " " + column.currency() + " " + TransactionRow.plain(balance));
        });
        if (!errors.isEmpty()) {
            return new RowResult.Failed(errors, warnings);
        }
        if (lines.isEmpty()) {
            return new RowResult.Skipped("the first row of the daily balance file has no balance", false, warnings);
        }
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        lines.forEach(line -> totals.merge(line.currency(), line.amount(), BigDecimal::add));
        long openingBalance = chart.account(OPENING_BALANCE).id();
        List<Line> all = new ArrayList<>(lines);
        totals.forEach((currency, total) -> {
            // Balances that cancel out, such as the assets against RESERVE, leave nothing to post.
            if (total.signum() != 0) {
                all.add(new Line(openingBalance, currency, total.negate(), null, null));
            }
        });
        return new RowResult.Entry(new EntryPlan("xls-opening:" + date, ImportSync.contentHash(
                hashed.toArray(String[]::new)), date, EntryKind.OPENING_BALANCE, null, null, all, false, List.of()),
                warnings);
    }

    /**
     * The amount as a posting holds it: rounded half up to 4 decimals where the workbook has more, the difference
     * being explained drift of the account's displayed balance (D-68; an agent's choice, D3b).
     *
     * @param posting +1 for the debit side, -1 for the credit side: the sign of the side's posting
     */
    private static BigDecimal rounded(BigDecimal amount, AccountRef account, String currency, int posting,
            LocalDate date, List<Drift> drift, List<String> warnings) {
        if (amount.stripTrailingZeros().scale() <= POSTING_SCALE) {
            return amount;
        }
        BigDecimal rounded = amount.setScale(POSTING_SCALE, RoundingMode.HALF_UP);
        int displayed = account.type() == AccountType.ASSET ? posting : -posting;
        drift.add(new Drift(account.code(), currency, date,
                rounded.subtract(amount).multiply(BigDecimal.valueOf(displayed)), Drift.Reason.FOUR_DECIMALS));
        warnings.add("an amount with more than 4 decimals is rounded half up to 4");
        return rounded;
    }

    /** The category on UNALLOCATED, the counterparty on an account that requires one. */
    private static Line line(AccountRef account, String currency, BigDecimal amount, CategoryRef category,
            Party borrower) {
        return new Line(account.id(), currency, amount,
                category != null && account.is(UNALLOCATED) ? category.id() : null,
                account.requiresCounterparty() ? borrower : null);
    }

    /** A hint for the UI (rule 6): what the entry would be if it had been entered through a form. */
    private static EntryKind kind(AccountRef debit, AccountRef credit, CategoryRef category, boolean exchange,
            BigDecimal debitAmount, BigDecimal creditAmount) {
        if (category != null) {
            return category.type() == CategoryType.INCOME ? EntryKind.INCOME : EntryKind.EXPENSE;
        }
        if (exchange) {
            return EntryKind.CURRENCY_EXCHANGE;
        }
        // "Debit" and "Credit" are only columns: the sign of the posting to LOANS_ASSET tells lending from repayment.
        BigDecimal loanPosting = debit.is(LOANS) ? debitAmount : credit.is(LOANS) ? creditAmount.negate() : null;
        if (loanPosting != null) {
            return loanPosting.signum() > 0 ? EntryKind.LOAN_GIVEN : EntryKind.LOAN_REPAID;
        }
        return EntryKind.TRANSFER;
    }

    /** CODE_ITEM is a formula over Exp. Item; where they disagree, one of them is wrong in the workbook. */
    private static void checkItemCode(TransactionRow row, CategoryRef category, List<String> warnings) {
        String expected = category == null ? null : category.code();
        if (expected == null ? row.itemCode() != null : !expected.equals(row.itemCode())) {
            warnings.add("CODE_ITEM isn't the code of Exp. Item");
        }
    }

    private AccountRef account(String name, String column, List<String> errors) {
        AccountRef account = chart.accountsByName().get(name.toLowerCase(Locale.ROOT));
        if (account == null) {
            errors.add("%s is not in the accounts file".formatted(column));
        }
        return account;
    }

    private static void checkLength(String name, String column, List<String> errors) {
        if (name != null && name.codePointCount(0, name.length()) > Workbook.NAME_MAX_LENGTH) {
            errors.add("%s is longer than %d characters".formatted(column, Workbook.NAME_MAX_LENGTH));
        }
    }
}
