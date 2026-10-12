package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.importer.Workbook.required;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import com.example.financetracker.ledger.family.ImportSync;

/**
 * One of the partner's family rows as the import page uploads it (D-78, D-140): only a row with Family = "да" and an
 * item other than "-", and only what the mapping needs, which is the side on UNALLOCATED. The page reads his
 * transactions file in the browser and sends these columns, never his accounts, counterparties, comments or other
 * rows; his daily files are never uploaded.
 *
 * @param row the row in the uploaded file, where the header is row 1
 * @param id the row's permanent ID in his file (D-86)
 * @param item Exp. Item: a category's name
 * @param expense whether UNALLOCATED is the row's debit side: a family expense (or, with a negative amount, a refund);
 *        else its credit side, a family income
 * @param currency the currency of the UNALLOCATED side
 * @param amount the UNALLOCATED side's amount, signed as in the file
 * @param contentHash the SHA-256 of the uploaded columns, as {@link ImportSync#contentHash}
 */
record PartnerRow(int row, long id, LocalDate date, String item, boolean expense, String currency, BigDecimal amount,
        String contentHash) {

    static final String ID = "ID";
    static final String DATE = "Date";
    static final String ITEM = "Exp. Item";
    static final String FAMILY = "Family";
    /** Which side of the row is UNALLOCATED: "Debit" or "Credit". */
    static final String SIDE = "Side";
    static final String CURRENCY = "Currency";
    static final String SUM = "Sum";

    /** Exactly these columns; the server refuses a file with any other (D-140). */
    static final List<String> COLUMNS = List.of(ID, DATE, ITEM, FAMILY, SIDE, CURRENCY, SUM);

    static final String DEBIT = "Debit";
    static final String CREDIT = "Credit";

    /** The row, or null if it can't be read, with the reasons added to {@code errors}; no reason holds a value. */
    static PartnerRow parse(CsvTable.Row row, List<String> errors) {
        int before = errors.size();
        Long id = required(row, ID, TransactionRow::id, errors);
        LocalDate date = required(row, DATE, ExcelValues::date, errors);
        String item = required(row, ITEM, Function.identity(), errors);
        String side = required(row, SIDE, Function.identity(), errors);
        if (side != null && !side.equals(DEBIT) && !side.equals(CREDIT)) {
            errors.add("Side is neither Debit nor Credit: neither side of the row is UNALLOCATED");
        }
        String currency = required(row, CURRENCY, Function.identity(), errors);
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            errors.add("Currency is not a currency code");
        }
        BigDecimal amount = required(row, SUM, ExcelValues::amount, errors);
        if (errors.size() > before) {
            return null;
        }
        return new PartnerRow(row.number(), id, date, item, side.equals(DEBIT), currency, amount,
                ImportSync.contentHash(date.toString(), item, side, currency, TransactionRow.plain(amount)));
    }
}
