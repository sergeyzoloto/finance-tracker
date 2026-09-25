package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.importer.Workbook.optional;
import static com.example.financetracker.ledger.importer.Workbook.required;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One row of the journal, with the columns the importer reads. The workbook's other columns are formulas over these
 * and are ignored (rule 13); DEBIT_CHANGE and CODE_ITEM are only compared, to warn where the import differs.
 * Optional text is null when empty.
 *
 * @param row the row in the spreadsheet, where the header is row 1
 * @param externalRef the row's identity: "xls:", the SHA-256 of its business columns, ":", and which of the
 *        identical rows in the file it is, from 1. The business columns are those of {@link #COLUMNS} from Date to
 *        LOAN_CA, the ones typed in rather than computed, normalized: text trimmed, numbers without formatting,
 *        the date as ISO 8601, and NULL in LOAN_CA as empty.
 * @param item Exp. Item: a category's name, or "-" for none
 * @param loanCounterparty LOAN_CA, the counterparty of the posting to an account that requires one
 * @param familyExpense FAMILY_EXP, minus the family's part of the row; read only on a Family row
 * @param debitChange DEBIT_CHANGE, the debit account's displayed change; read only on a Family row, and null there
 *        too if it isn't a number
 * @param itemCode CODE_ITEM, the code of Exp. Item, with typos fixed
 */
record TransactionRow(int row, String externalRef, LocalDate date, String item, BigDecimal sum, String currency,
        String counteragent, String comment, String debit, String credit, boolean family, String debitCurrency,
        BigDecimal debitSum, String creditCurrency, BigDecimal creditSum, String loanCounterparty,
        String familyCurrency, BigDecimal familyExpense, BigDecimal familyIncome, BigDecimal debitChange,
        String itemCode) {

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
    static final String DEBIT_CHANGE = "DEBIT_CHANGE";
    static final String CODE_ITEM = "CODE_ITEM";

    static final List<String> COLUMNS = List.of(DATE, ITEM, SUM, CURRENCY, COUNTERAGENT, COMMENT, DEBIT, CREDIT, FAMILY,
            CUR_DEBIT, SUM_DEBIT, CUR_CREDIT, SUM_CREDIT, LOAN_CA, CUR_FAMILY, FAMILY_EXP, FAMILY_INC, DEBIT_CHANGE,
            CODE_ITEM);

    static final String FAMILY_YES = "да";

    /**
     * The row, or null if it can't be read, with the reasons added to {@code errors}.
     *
     * @param externalRefs gives the external ref for the normalized business columns of a row, counting identical
     *        rows as they come
     */
    static TransactionRow parse(CsvTable.Row row, Function<String, String> externalRefs, List<String> errors) {
        int errorsBefore = errors.size();
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
            errors.add("Family is '%s', not '%s' or empty".formatted(familyText, FAMILY_YES));
        }
        String debitCurrency = text(row.get(CUR_DEBIT));
        BigDecimal debitSum = optional(row, SUM_DEBIT, ExcelValues::amount, errors);
        String creditCurrency = text(row.get(CUR_CREDIT));
        BigDecimal creditSum = optional(row, SUM_CREDIT, ExcelValues::amount, errors);
        String loanCounterparty = formulaText(row.get(LOAN_CA));
        String familyCurrency = null;
        BigDecimal familyExpense = null;
        BigDecimal debitChange = null;
        if (family) {
            familyCurrency = required(row, CUR_FAMILY, Function.identity(), errors);
            familyExpense = required(row, FAMILY_EXP, ExcelValues::amount, errors);
            debitChange = derivedAmount(row.get(DEBIT_CHANGE));
        }
        BigDecimal familyIncome = optional(row, FAMILY_INC, ExcelValues::amount, errors);
        String itemCode = formulaText(row.get(CODE_ITEM));
        if (errors.size() > errorsBefore) {
            return null;
        }

        String identity = Stream.of(date.toString(), item, plain(sum), currency, counteragent, comment, debit, credit,
                        family ? FAMILY_YES : "", debitCurrency, plain(debitSum), creditCurrency, plain(creditSum),
                        loanCounterparty)
                .map(value -> value == null ? "" : value)
                .collect(Collectors.joining("\u001F"));
        return new TransactionRow(row.number(), externalRefs.apply(identity), date, item, sum, currency, counteragent,
                comment, debit, credit, family, debitCurrency, debitSum, creditCurrency, creditSum, loanCounterparty,
                familyCurrency, familyExpense, familyIncome, debitChange,
                itemCode == null ? null : Workbook.code(itemCode));
    }

    /** A formula column that is only compared: a value that isn't a number reads as null, and isn't an error. */
    private static BigDecimal derivedAmount(String value) {
        try {
            return value.isEmpty() ? null : ExcelValues.amount(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(String value) {
        return value.isEmpty() ? null : value;
    }

    private static String formulaText(String value) {
        return Workbook.NULL.equals(value) ? null : text(value);
    }

    /** The same text for the same number, however the workbook formatted it: 1500, 1 500.00 and 1500.0 agree. */
    private static String plain(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
