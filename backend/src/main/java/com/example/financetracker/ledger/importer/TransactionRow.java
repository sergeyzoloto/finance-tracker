package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.importer.Workbook.optional;
import static com.example.financetracker.ledger.importer.Workbook.required;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.example.financetracker.ledger.family.ImportSync;

/**
 * One row of a user's transactions file (D-235's 32 columns, D4b): the typed columns, ID to LOAN_CA, which the import
 * maps, and the 17 the workbook computes from them, CODE_ITEM to EXP_ITEM_CODE ({@link Computed}), from which the
 * import takes the accounts and categories (D-228) and which the reconciliation compares with what it builds (D-231).
 * A row the app made carries {@code app-<id>} or {@code app-f<id>} in its ID (D-236, D-244): it is compared with the
 * ledger and never written (D-259). Optional text is null when empty.
 *
 * @param row the row in the spreadsheet, where the header is row 1
 * @param id the row's permanent ID (D-86): a positive whole number, never reused
 * @param contentHash the SHA-256 of the row's business content ({@link ImportSync#contentHash}), the columns typed in
 *        rather than computed, from Date to LOAN_CA, normalized: text trimmed, numbers without formatting, the date as
 *        ISO 8601, and NULL in LOAN_CA as empty. An edit of the row in Excel changes it; a new export doesn't.
 * @param item Exp. Item: a category's name, or "-" for none
 * @param loanCounterparty LOAN_CA, the counterparty of the posting to an account that requires one
 * @param familyCurrency CUR_FAMILY, the currency of a family row's family part (D-74); read only on a Family row
 * @param familyExpense FAMILY_EXP, minus the family's part of a family expense; read only on a Family row
 * @param familyIncome FAMILY_INC, the family's part of a family income; read only on a Family row
 * @param itemCode CODE_ITEM, the code of Exp. Item, with typos fixed
 * @param flagged whether the row's Family column is set, as the file has it: {@link #withoutFamily} keeps it (D-192)
 * @param appId the ID of a row the app made ({@code app-<id>}, {@code app-f<id>}); null for an Excel row, whose ID is
 *        {@code id} (which is 0 for an app row)
 * @param computed the workbook's computed columns as the file has them; null for a row made in a test
 */
record TransactionRow(int row, long id, String contentHash, LocalDate date, String item, BigDecimal sum,
        String currency, String counteragent, String comment, String debit, String credit, boolean family,
        String debitCurrency, BigDecimal debitSum, String creditCurrency, BigDecimal creditSum,
        String loanCounterparty, String familyCurrency, BigDecimal familyExpense, BigDecimal familyIncome,
        String itemCode, boolean flagged, String appId, Computed computed) {

    /**
     * The columns the workbook computes (D-237), as the file has them; codes with the workbook's typos fixed
     * ({@link Workbook#code}).
     *
     * @param itemType TYPE: INCOME, EXPENSE, or CASHFLOW for a row without a category
     * @param debitType TYPE_DEBIT, ASSET or LIABILITIES
     * @param debitAccount COUNT_DEBIT, the code and the currency of the debit side
     */
    record Computed(String itemType, String debitCode, String creditCode, String debitType, String creditType,
            String debitAccount, String creditAccount, BigDecimal debitChange, BigDecimal creditChange,
            String familyCurrency, BigDecimal familyExpense, BigDecimal familyIncome, BigDecimal checkCorrection,
            BigDecimal loanSum, String loanCurrency, String expItemCode) {
    }

    /** A row whose Family column is as {@code family} says. */
    TransactionRow(int row, long id, String contentHash, LocalDate date, String item, BigDecimal sum,
            String currency, String counteragent, String comment, String debit, String credit, boolean family,
            String debitCurrency, BigDecimal debitSum, String creditCurrency, BigDecimal creditSum,
            String loanCounterparty, String familyCurrency, BigDecimal familyExpense, BigDecimal familyIncome,
            String itemCode) {
        this(row, id, contentHash, date, item, sum, currency, counteragent, comment, debit, credit, family,
                debitCurrency, debitSum, creditCurrency, creditSum, loanCounterparty, familyCurrency, familyExpense,
                familyIncome, itemCode, family, null, null);
    }

    static final String ID = "ID";
    static final String DATE = "Date";
    static final String ITEM = "Exp. Item";
    static final String SUM = "Sum";
    static final String CURRENCY = "Currency";
    static final String COUNTERAGENT = "Counteragent";
    static final String COMMENT = "Comment";
    static final String DEBIT = "Debit";
    static final String CREDIT = "Credit";
    static final String FAMILY = "Family";
    static final String CUR_DEBIT = "CUR_DEBIT";
    static final String SUM_DEBIT = "SUM_DEBIT";
    static final String CUR_CREDIT = "CUR_CREDIT";
    static final String SUM_CREDIT = "SUM_CREDIT";
    static final String LOAN_CA = "LOAN_CA";
    static final String CUR_FAMILY = "CUR_FAMILY";
    static final String FAMILY_EXP = "FAMILY_EXP";
    static final String FAMILY_INC = "FAMILY_INC";
    static final String CODE_ITEM = "CODE_ITEM";
    static final String TYPE = "TYPE";
    static final String CODE_DEBIT = "CODE_DEBIT";
    static final String CODE_CREDIT = "CODE_CREDIT";
    static final String TYPE_DEBIT = "TYPE_DEBIT";
    static final String TYPE_CREDIT = "TYPE_CREDIT";
    static final String COUNT_DEBIT = "COUNT_DEBIT";
    static final String COUNT_CREDIT = "COUNT_CREDIT";
    static final String DEBIT_CHANGE = "DEBIT_CHANGE";
    static final String CREDIT_CHANGE = "CREDIT_CHANGE";
    static final String CHECK_CORR = "CHECK_CORR";
    static final String LOAN_SUM = "LOAN_SUM";
    static final String LOAN_CUR = "LOAN_CUR";
    static final String EXP_ITEM_CODE = "EXP_ITEM_CODE";

    /** The ID of a row the app made: a personal entry's (D-236) or a family record's (D-244). */
    static final java.util.regex.Pattern APP_ID = java.util.regex.Pattern.compile("app-f?[1-9][0-9]{0,17}");

    static final String FAMILY_YES = "да";

    /** The side's currency: its own column if the row gives one, else the row's. */
    String debitCurrencyOrRow() {
        return debitCurrency != null ? debitCurrency : currency;
    }

    String creditCurrencyOrRow() {
        return creditCurrency != null ? creditCurrency : currency;
    }

    /** The side's amount: its own column if the row gives a currency for it, else Sum. */
    BigDecimal debitAmount() {
        return debitSum != null ? debitSum : sum;
    }

    BigDecimal creditAmount() {
        return creditSum != null ? creditSum : sum;
    }

    /** The row's family part, FAMILY_EXP + FAMILY_INC: the change of Excel's FAMILY_DEBT by the row (D-68). */
    BigDecimal familyPart() {
        BigDecimal expense = familyExpense == null ? BigDecimal.ZERO : familyExpense;
        return familyIncome == null ? expense : expense.add(familyIncome);
    }

    /** The row as it is, but not a family row: a family row without a category is a transfer (D-72). */
    TransactionRow withoutFamily() {
        return new TransactionRow(row, id, contentHash, date, item, sum, currency, counteragent, comment, debit, credit,
                false, debitCurrency, debitSum, creditCurrency, creditSum, loanCounterparty, null, null, null,
                itemCode, flagged, appId, computed);
    }

    /** Whether the app made the row (D-259): never written by the import. */
    boolean isApp() {
        return appId != null;
    }

    /** The row's ID as the file writes it: the Excel ID, or the app's. */
    String excelId() {
        return appId != null ? appId : String.valueOf(id);
    }

    /**
     * The row, or null if it can't be read, with the reasons added to {@code errors}. The reasons never hold a value of
     * the row: the report names the row by its number and ID. An ID is a positive whole number (D-86) or an app row's
     * (D-236, D-244).
     */
    static TransactionRow parse(CsvTable.Row row, List<String> errors) {
        int errorsBefore = errors.size();
        String idText = row.get(ID);
        String appId = APP_ID.matcher(idText).matches() ? idText : null;
        Long id = appId != null ? Long.valueOf(0) : required(row, ID, TransactionRow::id, errors);
        LocalDate date = required(row, DATE, ExcelValues::date, errors);
        String item = required(row, ITEM, Function.identity(), errors);
        BigDecimal sum = required(row, SUM, ExcelValues::amount, errors);
        String currency = required(row, CURRENCY, Function.identity(), errors);
        String counteragent = text(row.get(COUNTERAGENT));
        String comment = text(row.get(COMMENT));
        String debit = required(row, DEBIT, Function.identity(), errors);
        String credit = required(row, CREDIT, Function.identity(), errors);
        String familyText = row.get(FAMILY);
        boolean family = FAMILY_YES.equals(familyText);
        if (!family && !familyText.isEmpty()) {
            errors.add("Family is neither '%s' nor empty".formatted(FAMILY_YES));
        }
        String debitCurrency = text(row.get(CUR_DEBIT));
        BigDecimal debitSum = optional(row, SUM_DEBIT, ExcelValues::amount, errors);
        String creditCurrency = text(row.get(CUR_CREDIT));
        BigDecimal creditSum = optional(row, SUM_CREDIT, ExcelValues::amount, errors);
        String loanCounterparty = formulaText(row.get(LOAN_CA));
        // The workbook's computed columns, on every row (D-231): CUR_FAMILY is the debit side's currency, FAMILY_EXP
        // and FAMILY_INC are 0 but on a family row.
        String familyCurrency = required(row, CUR_FAMILY, Function.identity(), errors);
        BigDecimal familyExpense = amountOrZero(row, FAMILY_EXP, errors);
        BigDecimal familyIncome = amountOrZero(row, FAMILY_INC, errors);
        String itemCode = formulaText(row.get(CODE_ITEM));
        Computed computed = new Computed(row.get(TYPE), code(row.get(CODE_DEBIT)), code(row.get(CODE_CREDIT)),
                row.get(TYPE_DEBIT), row.get(TYPE_CREDIT), row.get(COUNT_DEBIT), row.get(COUNT_CREDIT),
                amountOrZero(row, DEBIT_CHANGE, errors), amountOrZero(row, CREDIT_CHANGE, errors), familyCurrency,
                familyExpense, familyIncome, amountOrZero(row, CHECK_CORR, errors), amountOrZero(row, LOAN_SUM, errors), row.get(LOAN_CUR),
                code(Objects.requireNonNullElse(formulaText(row.get(EXP_ITEM_CODE)), "")));
        if (computed.debitCode().isEmpty() || computed.creditCode().isEmpty()) {
            errors.add("CODE_DEBIT or CODE_CREDIT is empty");
        }
        if (errors.size() > errorsBefore) {
            return null;
        }
        String hash = contentHash(date, item, sum, currency, counteragent, comment, debit, credit, family,
                debitCurrency, debitSum, creditCurrency, creditSum, loanCounterparty);
        return new TransactionRow(row.number(), id, hash, date, item, sum, currency, counteragent, comment, debit,
                credit, family, debitCurrency, debitSum, creditCurrency, creditSum, loanCounterparty,
                family ? familyCurrency : null, family ? familyExpense : null, family ? familyIncome : null,
                itemCode == null ? null : Workbook.code(itemCode), family, appId, computed);
    }

    /** A computed number; empty is 0, as a formula that finds nothing to add up. */
    private static BigDecimal amountOrZero(CsvTable.Row row, String column, List<String> errors) {
        BigDecimal amount = optional(row, column, ExcelValues::amount, errors);
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /** A code as the ledger stores it; NULL, a formula's nothing, as empty. */
    private static String code(String value) {
        return Workbook.NULL.equals(value) ? "" : Workbook.code(value);
    }

    /**
     * The row's content hash (D-139) from its typed columns, Date to LOAN_CA, as {@link #parse} reads them: optional text
     * null when empty, LOAN_CA null for NULL. The export hashes the rows it writes with it too (D4a).
     */
    static String contentHash(LocalDate date, String item, BigDecimal sum, String currency, String counteragent,
            String comment, String debit, String credit, boolean family, String debitCurrency, BigDecimal debitSum,
            String creditCurrency, BigDecimal creditSum, String loanCounterparty) {
        return ImportSync.contentHash(date.toString(), item, plain(sum), currency, counteragent, comment, debit, credit,
                family ? FAMILY_YES : "", debitCurrency, plain(debitSum), creditCurrency, plain(creditSum),
                loanCounterparty);
    }

    /** An ID: a positive whole number (D-86). */
    static Long id(String value) {
        if (!value.matches("[1-9][0-9]{0,17}")) {
            throw new IllegalArgumentException("not a positive whole number");
        }
        return Long.valueOf(value);
    }

    private static String text(String value) {
        return value.isEmpty() ? null : value;
    }

    private static String formulaText(String value) {
        return Workbook.NULL.equals(value) ? null : text(value);
    }

    /** The same text for the same number, however the workbook formatted it: 1500, 1 500.00 and 1500.0 agree. */
    static String plain(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
