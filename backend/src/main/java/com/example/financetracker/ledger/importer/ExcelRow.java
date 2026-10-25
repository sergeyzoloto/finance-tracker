package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.example.financetracker.ledger.domain.AccountRole;

/**
 * One row of the Excel ledger's transactions file with all its 32 columns (D-235): the typed ones, ID to LOAN_CA, and
 * the 17 that Excel computes from them and from the accounts and categories, CODE_ITEM to EXP_ITEM_CODE, by Excel's
 * own rules (D-237), which reproduce every row of both members' exports of 2026-10-21 but where Excel's CSV cut a
 * number to 10 significant digits. The export writes it (D4a); D4b checks each incoming row against it.
 * <p>
 * Text is as the file writes it: empty for none. An amount is as stored, never rounded.
 *
 * @param id the row's ID: its Excel ID, or {@code app-…} for a row the app made (D-236)
 * @param item Exp. Item, with the category's code and type
 * @param debit the Debit account, with its code and type as the workbook's accounts file has them
 * @param debitCurrency CUR_DEBIT, or null when the debit side is in the row's currency
 * @param debitSum SUM_DEBIT, with {@code debitCurrency}
 */
public record ExcelRow(String id, LocalDate date, Item item, BigDecimal sum, String currency, String counteragent,
        String comment, Side debit, Side credit, boolean family, String debitCurrency, BigDecimal debitSum,
        String creditCurrency, BigDecimal creditSum, String loanCounterparty) {

    /** The workbook's account types (rule 4 maps LIABILITIES to LIABILITY and EQUITY). */
    public static final String ASSET = "ASSET";
    public static final String LIABILITIES = "LIABILITIES";

    /** The columns in the import's order (D-235). */
    public static final List<String> HEADER = List.of(TransactionRow.ID, TransactionRow.DATE, TransactionRow.ITEM,
            TransactionRow.SUM, TransactionRow.CURRENCY, TransactionRow.COUNTERAGENT, TransactionRow.COMMENT,
            TransactionRow.DEBIT, TransactionRow.CREDIT, TransactionRow.FAMILY, TransactionRow.CUR_DEBIT,
            TransactionRow.SUM_DEBIT, TransactionRow.CUR_CREDIT, TransactionRow.SUM_CREDIT, TransactionRow.LOAN_CA,
            TransactionRow.CODE_ITEM, "TYPE", "CODE_DEBIT", "CODE_CREDIT", "TYPE_DEBIT", "TYPE_CREDIT", "COUNT_DEBIT",
            "COUNT_CREDIT", "DEBIT_CHANGE", "CREDIT_CHANGE", TransactionRow.CUR_FAMILY, TransactionRow.FAMILY_EXP,
            TransactionRow.FAMILY_INC, "CHECK_CORR", "LOAN_SUM", "LOAN_CUR", "EXP_ITEM_CODE");

    /** The accounts whose rows Excel counts in LOAN_SUM: those that require a counterparty (rule 8). */
    private static final Set<String> LOAN_CODES = Set.of(AccountRole.LOANS.defaultCode(), Workbook.CREDITOR_DEBT);
    private static final String UNALLOCATED = AccountRole.UNALLOCATED.defaultCode();

    /**
     * An account as a row names it.
     *
     * @param type {@link #ASSET} or {@link #LIABILITIES}
     */
    public record Side(String name, String code, String type) {
    }

    /**
     * Exp. Item.
     *
     * @param code null for none, which CODE_ITEM writes as NULL
     * @param type INCOME, EXPENSE, or CASHFLOW for none
     */
    public record Item(String name, String code, String type) {

        public static final Item NONE = new Item(EntryMapper.NO_CATEGORY, null, "CASHFLOW");
    }

    /** The debit side's currency: CUR_DEBIT if given, else the row's. */
    public String debitCurrencyOrRow() {
        return debitCurrency != null ? debitCurrency : currency;
    }

    public String creditCurrencyOrRow() {
        return creditCurrency != null ? creditCurrency : currency;
    }

    /** The debit side's amount: SUM_DEBIT if the row gives a currency for it, else Sum. */
    public BigDecimal debitAmount() {
        return debitCurrency != null ? debitSum : sum;
    }

    public BigDecimal creditAmount() {
        return creditCurrency != null ? creditSum : sum;
    }

    /** FAMILY_EXP: minus half of a family row's debit side on UNALLOCATED, its expense; else 0. */
    public BigDecimal familyExpense() {
        return family && UNALLOCATED.equals(debit.code()) ? debitAmount().negate().divide(BigDecimal.TWO)
                : BigDecimal.ZERO;
    }

    /** FAMILY_INC: half of a family row's credit side on UNALLOCATED, its income; else 0. */
    public BigDecimal familyIncome() {
        return family && UNALLOCATED.equals(credit.code()) ? creditAmount().divide(BigDecimal.TWO) : BigDecimal.ZERO;
    }

    /**
     * DEBIT_CHANGE: the change of the debit account's balance as the workbook shows it, up for an asset and down for a
     * liability, less the family's part on UNALLOCATED.
     */
    public BigDecimal debitChange() {
        BigDecimal change = ASSET.equals(debit.type()) ? debitAmount() : debitAmount().negate();
        return change.subtract(familyExpense());
    }

    /** CREDIT_CHANGE: as {@link #debitChange()}, down for an asset and up for a liability. */
    public BigDecimal creditChange() {
        BigDecimal change = ASSET.equals(credit.type()) ? creditAmount().negate() : creditAmount();
        return change.subtract(familyIncome());
    }

    /**
     * CHECK_CORR: what the assets' changes leave over the liabilities' (the family's part among them), which only a row
     * in two currencies has; its sign is the other way round on a row from a liability into an asset.
     */
    public BigDecimal checkCorrection() {
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = familyExpense().add(familyIncome());
        if (ASSET.equals(debit.type())) {
            assets = assets.add(debitChange());
        } else {
            liabilities = liabilities.add(debitChange());
        }
        if (ASSET.equals(credit.type())) {
            assets = assets.add(creditChange());
        } else {
            liabilities = liabilities.add(creditChange());
        }
        BigDecimal correction = assets.subtract(liabilities);
        return ASSET.equals(debit.type()) && LIABILITIES.equals(credit.type()) ? correction.negate() : correction;
    }

    /** LOAN_SUM: minus what the row posts to an account that requires a counterparty, on either side. */
    public BigDecimal loanSum() {
        BigDecimal sum = BigDecimal.ZERO;
        if (LOAN_CODES.contains(debit.code())) {
            sum = sum.subtract(debitAmount());
        }
        if (LOAN_CODES.contains(credit.code())) {
            sum = sum.add(creditAmount());
        }
        return sum;
    }

    /** LOAN_CUR: the currency of the side on an account that requires a counterparty, the debit's first. */
    public String loanCurrency() {
        if (LOAN_CODES.contains(debit.code())) {
            return debitCurrencyOrRow();
        }
        return LOAN_CODES.contains(credit.code()) ? creditCurrencyOrRow() : "";
    }

    /** The 32 values in {@link #HEADER}'s order, as the file writes them (D-240). */
    public List<String> columns() {
        String itemCode = item.code() == null ? Workbook.NULL : item.code();
        List<String> columns = new ArrayList<>(HEADER.size());
        columns.add(id);
        columns.add(ExcelValues.format(date));
        columns.add(item.name());
        columns.add(ExcelValues.write(sum));
        columns.add(currency);
        columns.add(counteragent);
        columns.add(comment);
        columns.add(debit.name());
        columns.add(credit.name());
        columns.add(family ? TransactionRow.FAMILY_YES : "");
        columns.add(debitCurrency == null ? "" : debitCurrency);
        columns.add(debitCurrency == null ? "" : ExcelValues.write(debitSum));
        columns.add(creditCurrency == null ? "" : creditCurrency);
        columns.add(creditCurrency == null ? "" : ExcelValues.write(creditSum));
        columns.add(loanCounterparty);
        columns.add(itemCode);
        columns.add(item.type());
        columns.add(debit.code());
        columns.add(credit.code());
        columns.add(debit.type());
        columns.add(credit.type());
        columns.add(debit.code() + "_" + debitCurrencyOrRow());
        columns.add(credit.code() + "_" + creditCurrencyOrRow());
        columns.add(ExcelValues.write(debitChange()));
        columns.add(ExcelValues.write(creditChange()));
        columns.add(debitCurrencyOrRow());
        columns.add(ExcelValues.write(familyExpense()));
        columns.add(ExcelValues.write(familyIncome()));
        columns.add(ExcelValues.write(checkCorrection()));
        columns.add(ExcelValues.write(loanSum()));
        columns.add(loanCurrency());
        columns.add(itemCode);
        return columns;
    }

    /** The content hash the import stores for this row (D-139), {@link TransactionRow#contentHash}. */
    String contentHash() {
        return TransactionRow.contentHash(date, item.name(), sum, currency, text(counteragent), text(comment),
                debit.name(), credit.name(), family, debitCurrency, debitCurrency == null ? null : debitSum,
                creditCurrency, creditCurrency == null ? null : creditSum, loanText());
    }

    /** D-151's hash of a family row's side on UNALLOCATED, which a record of the partner's rows stores. */
    String uploadHash() {
        return MemberPlanner.uploadHash(date, item.name(), UNALLOCATED.equals(debit.code()),
                UNALLOCATED.equals(credit.code()), currency, sum, debitCurrency, debitAmount(), creditCurrency,
                creditAmount());
    }

    /** D-181's hash of a member's own side of a record, which his import stores. */
    String sideHash() {
        return MemberPlanner.sideHash(debit.name(), debitCurrencyOrRow(), debitAmount(), credit.name(),
                creditCurrencyOrRow(), creditAmount(), loanText(), text(counteragent), text(comment));
    }

    /** LOAN_CA as the import reads it: NULL, which a workbook formula writes for none, is none. */
    private String loanText() {
        return Workbook.NULL.equals(loanCounterparty) ? null : text(loanCounterparty);
    }

    private static String text(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
