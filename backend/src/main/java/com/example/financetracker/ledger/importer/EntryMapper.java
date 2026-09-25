package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.domain.AccountRole.FX_EXCHANGE;
import static com.example.financetracker.ledger.domain.AccountRole.LOANS;
import static com.example.financetracker.ledger.domain.AccountRole.OPENING_BALANCE;
import static com.example.financetracker.ledger.domain.AccountRole.UNALLOCATED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.Chart.CategoryRef;
import com.example.financetracker.ledger.importer.EntryPlan.Line;
import com.example.financetracker.ledger.importer.EntryPlan.Party;
import com.example.financetracker.ledger.importer.Workbook.OpeningBalanceRow;

/**
 * Turns rows of the Excel ledger into entries by the domain rules, without a database. One row of the journal is one
 * entry. Its postings come in the order credit side, the exchange through FX_EXCHANGE if the two sides' currencies
 * differ, debit side, and the family's part last.
 * <p>
 * Whether the rows it refers to exist and fit together is left to {@code LedgerValidator}, when the entry is written;
 * this class reports what only the row can tell.
 */
final class EntryMapper {

    /** Exp. Item of a row without a category. */
    static final String NO_CATEGORY = "-";
    /**
     * The category the workbook books exchange gains under, as income on UNALLOCATED. Under rule 9 an exchange's
     * result is the balance of FX_EXCHANGE, so these rows would count it twice.
     */
    static final String FX_GAIN_CATEGORY = "Прибыль от курсовой разницы";
    /** The counterparty for a posting that needs one (rule 8) when the row names none. */
    static final String UNASSIGNED = "Unassigned";

    private final Chart chart;

    EntryMapper(Chart chart) {
        this.chart = chart;
    }

    RowResult map(TransactionRow row) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean fxGain = FX_GAIN_CATEGORY.equals(row.item());
        if (fxGain && row.sum().signum() == 0) {
            return new RowResult.Skipped("an FX gain with Sum 0", true, warnings);
        }

        AccountRef debit = account(row.debit(), TransactionRow.DEBIT, errors);
        AccountRef credit = account(row.credit(), TransactionRow.CREDIT, errors);
        CategoryRef category = null;
        if (!NO_CATEGORY.equals(row.item())) {
            category = chart.categoriesByName().get(row.item());
            if (category == null) {
                errors.add("Exp. Item '%s' is not in the categories file".formatted(row.item()));
            }
        }
        if (category != null || NO_CATEGORY.equals(row.item())) {
            checkItemCode(row, category, warnings);
        }
        if (debit != null && debit.equals(credit)) {
            errors.add("Debit and Credit are the same account");
        }
        if (row.familyIncome() != null && row.familyIncome().signum() != 0) {
            errors.add("FAMILY_INC is %s: shared income is for manual review".formatted(amount(row.familyIncome())));
        }
        checkLength(row.counteragent(), TransactionRow.COUNTERAGENT, errors);
        checkLength(row.loanCounterparty(), TransactionRow.LOAN_CA, errors);
        if (!errors.isEmpty()) {
            return new RowResult.Failed(errors, warnings);
        }

        // Rule 2: each side in its own currency when the row gives one, and through FX_EXCHANGE if they differ (rule 9).
        BigDecimal debitAmount = row.debitSum() != null ? row.debitSum() : row.sum();
        String debitCurrency = row.debitCurrency() != null ? row.debitCurrency() : row.currency();
        BigDecimal creditAmount = row.creditSum() != null ? row.creditSum() : row.sum();
        String creditCurrency = row.creditCurrency() != null ? row.creditCurrency() : row.currency();
        boolean exchange = !debitCurrency.equals(creditCurrency);
        if (!exchange && debitAmount.compareTo(creditAmount) != 0) {
            errors.add("the debit side's %s and the credit side's %s differ, in one currency %s"
                    .formatted(amount(debitAmount), amount(creditAmount), debitCurrency));
        }

        AccountRef unallocated = debit.is(UNALLOCATED) ? debit : credit.is(UNALLOCATED) ? credit : null;
        if (category != null && unallocated == null) {
            errors.add("Exp. Item '%s' is a category, and neither Debit nor Credit is UNALLOCATED".formatted(row.item()));
        }

        Party borrower = null;
        if (debit.requiresCounterparty() || credit.requiresCounterparty()) {
            if (row.loanCounterparty() != null) {
                borrower = new Party(row.loanCounterparty(), Counterparty.Kind.PERSON);
            } else {
                borrower = new Party(UNASSIGNED, null);
                warnings.add("LOAN_CA is empty on a row with %s, which requires a counterparty: '%s' is used (Sum %s %s on %s)"
                        .formatted(debit.requiresCounterparty() ? debit.code() : credit.code(), UNASSIGNED,
                                amount(row.sum()), row.currency(), ExcelValues.format(row.date())));
            }
        } else if (row.loanCounterparty() != null) {
            warnings.add("LOAN_CA '%s' is ignored: neither account requires a counterparty"
                    .formatted(row.loanCounterparty()));
        }

        List<Line> lines = new ArrayList<>();
        lines.add(line(credit, creditCurrency, creditAmount.negate(), category, borrower));
        if (exchange) {
            long fxExchange = chart.account(FX_EXCHANGE).id();
            lines.add(new Line(fxExchange, creditCurrency, creditAmount, null, null));
            lines.add(new Line(fxExchange, debitCurrency, debitAmount.negate(), null, null));
        }
        lines.add(line(debit, debitCurrency, debitAmount, category, borrower));
        if (row.family()) {
            splitWithFamily(row, debit, unallocated, category, debitAmount, debitCurrency, lines, errors, warnings);
        }
        if (!errors.isEmpty()) {
            return new RowResult.Failed(errors, warnings);
        }

        EntryKind kind = kind(row, debit, credit, category, exchange, debitAmount, creditAmount);
        Party payee = row.counteragent() == null ? null : new Party(row.counteragent(), Counterparty.Kind.MERCHANT);
        String memo = row.comment() != null && !row.comment().equals(row.counteragent()) ? row.comment() : null;
        return new RowResult.Entry(new EntryPlan(row.externalRef(), row.date(), kind, payee, memo, lines, fxGain),
                warnings);
    }

    /**
     * The posting of one opening balance: the account's displayed balance turned into a posting by rule 4.
     *
     * @return null if the row names no account of the user, with the reason added to {@code errors}
     */
    Line openingBalance(OpeningBalanceRow row, List<String> errors, List<String> warnings) {
        AccountRef account = chart.accountsByCode().get(row.code());
        if (account == null) {
            errors.add("there is no account with the code " + row.code());
            return null;
        }
        Party counterparty = null;
        if (account.requiresCounterparty()) {
            counterparty = new Party(UNASSIGNED, null);
            warnings.add("%s requires a counterparty, and the file names none: '%s' is used"
                    .formatted(account.code(), UNASSIGNED));
        }
        BigDecimal amount = account.type() == AccountType.ASSET ? row.amount() : row.amount().negate();
        return new Line(account.id(), row.currency(), amount, null, counterparty);
    }

    /** One entry for the opening balances of one date, posted against OPENING_BALANCE in each currency (rule 10). */
    EntryPlan openingEntry(LocalDate date, List<Line> balances) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        balances.forEach(line -> totals.merge(line.currency(), line.amount(), BigDecimal::add));
        List<Line> lines = new ArrayList<>(balances);
        long openingBalance = chart.account(OPENING_BALANCE).id();
        totals.forEach((currency, total) -> {
            // Balances that cancel out, such as an asset bought on credit, leave nothing to post.
            if (total.signum() != 0) {
                lines.add(new Line(openingBalance, currency, total.negate(), null, null));
            }
        });
        return new EntryPlan("xls-opening:" + date, date, EntryKind.OPENING_BALANCE, null, null, lines, false);
    }

    /**
     * Rule 7 with the workbook's own split: the family's part is minus FAMILY_EXP, and the user's own part is the
     * rest of the UNALLOCATED posting, so the entry balances exactly. Where the workbook rounded both halves up, its
     * DEBIT_CHANGE shows a cent more than that own part, which is worth a warning.
     */
    private void splitWithFamily(TransactionRow row, AccountRef debit, AccountRef unallocated, CategoryRef category,
            BigDecimal debitAmount, String debitCurrency, List<Line> lines, List<String> errors, List<String> warnings) {
        if (unallocated == null || !unallocated.equals(debit)) {
            errors.add("Family is '%s', and Debit is not UNALLOCATED".formatted(TransactionRow.FAMILY_YES));
            return;
        }
        if (category == null) {
            errors.add("Family is '%s', and Exp. Item names no category".formatted(TransactionRow.FAMILY_YES));
            return;
        }
        if (!row.familyCurrency().equals(debitCurrency)) {
            errors.add("CUR_FAMILY is %s, and the UNALLOCATED posting is in %s"
                    .formatted(row.familyCurrency(), debitCurrency));
            return;
        }
        if (chart.sharedAccount() == null) {
            errors.add("Family is '%s', and the account FAMILY_DEBT does not exist".formatted(TransactionRow.FAMILY_YES));
            return;
        }
        BigDecimal other = row.familyExpense().negate();
        BigDecimal own = debitAmount.subtract(other);
        lines.set(lines.size() - 1, new Line(debit.id(), debitCurrency, own, category.id(), null));
        lines.add(new Line(chart.sharedAccount().id(), row.familyCurrency(), other, null, null));
        if (row.debitChange() == null) {
            warnings.add("DEBIT_CHANGE is empty or not a number, so the family split of Sum %s can't be checked"
                    .formatted(amount(row.sum())));
        } else if (own.compareTo(row.debitChange().negate()) != 0) {
            warnings.add("family split of Sum %s: the own part is %s, and the workbook's (minus DEBIT_CHANGE) is %s; the family's part is %s"
                    .formatted(amount(row.sum()), amount(own), amount(row.debitChange().negate()), amount(other)));
        }
    }

    /** The category on UNALLOCATED, the counterparty on an account that requires one. */
    private static Line line(AccountRef account, String currency, BigDecimal amount, CategoryRef category,
            Party borrower) {
        return new Line(account.id(), currency, amount,
                category != null && account.is(UNALLOCATED) ? category.id() : null,
                account.requiresCounterparty() ? borrower : null);
    }

    /** A hint for the UI (rule 6): what the entry would be if it had been entered through a form. */
    private static EntryKind kind(TransactionRow row, AccountRef debit, AccountRef credit, CategoryRef category,
            boolean exchange, BigDecimal debitAmount, BigDecimal creditAmount) {
        if (category != null) {
            if (row.family()) {
                return EntryKind.SHARED_EXPENSE;
            }
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
            warnings.add("CODE_ITEM is %s, and Exp. Item '%s' has %s".formatted(
                    row.itemCode() == null ? "empty" : row.itemCode(), row.item(),
                    expected == null ? "no code" : "the code " + expected));
        }
    }

    private AccountRef account(String name, String column, List<String> errors) {
        AccountRef account = chart.accountsByName().get(name);
        if (account == null) {
            errors.add("%s '%s' is not in the accounts file".formatted(column, name));
        }
        return account;
    }

    private static void checkLength(String name, String column, List<String> errors) {
        if (name != null && name.codePointCount(0, name.length()) > Workbook.NAME_MAX_LENGTH) {
            errors.add("%s is longer than %d characters".formatted(column, Workbook.NAME_MAX_LENGTH));
        }
    }

    private static String amount(BigDecimal amount) {
        return Money.normalize(amount).toPlainString();
    }
}
