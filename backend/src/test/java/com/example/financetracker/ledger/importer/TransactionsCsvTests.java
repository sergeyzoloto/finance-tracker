package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import com.example.financetracker.ledger.importer.ExcelRow.Item;
import com.example.financetracker.ledger.importer.ExcelRow.Side;
import org.junit.jupiter.api.Test;

/** The transactions file as the export writes it (D-240), and the import reading it back. */
class TransactionsCsvTests {

    static final String HEADER = "ID;Date;Exp. Item;Sum;Currency;Counteragent;Comment;Debit;Credit;Family;CUR_DEBIT;"
            + "SUM_DEBIT;CUR_CREDIT;SUM_CREDIT;LOAN_CA;CODE_ITEM;TYPE;CODE_DEBIT;CODE_CREDIT;TYPE_DEBIT;TYPE_CREDIT;"
            + "COUNT_DEBIT;COUNT_CREDIT;DEBIT_CHANGE;CREDIT_CHANGE;CUR_FAMILY;FAMILY_EXP;FAMILY_INC;CHECK_CORR;LOAN_SUM;"
            + "LOAN_CUR;EXP_ITEM_CODE";

    private static final Side CARD = new Side("Карта «Север»", "CARD", ExcelRow.ASSET);
    private static final Side UNALLOCATED = new Side("Свободный остаток", "UNALLOCATED", ExcelRow.LIABILITIES);

    @Test
    void anEmptyFileIsTheByteOrderMarkAndTheHeader() {
        byte[] file = write(List.of());
        assertThat(file).isEqualTo(("﻿" + HEADER + "\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(String.join(";", ExcelRow.HEADER)).isEqualTo(HEADER);
    }

    @Test
    void aValueIsQuotedOnlyWhenItHoldsASemicolonAQuoteOrALineBreak() {
        ExcelRow row = new ExcelRow("7", LocalDate.of(2024, 3, 9), new Item("Продукты", "FOOD", "EXPENSE"),
                new BigDecimal("1308.8900"), "RUB", "ООО \"Ромашка\"", "на неделю; и ещё\nстрока", UNALLOCATED, CARD,
                false, null, null, null, null, "");
        String text = new String(write(List.of(row)), StandardCharsets.UTF_8);
        String line = text.substring(text.indexOf("\r\n") + 2, text.length() - 2);
        assertThat(line).startsWith("7;09.03.2024;Продукты;1308.89;RUB;\"ООО \"\"Ромашка\"\"\";"
                + "\"на неделю; и ещё\nстрока\";Свободный остаток;Карта «Север»;;");
        // Every line ends in CRLF; the only bare LF is the one inside the quoted comment.
        assertThat(text.split("\r\n", -1)).hasSize(3);
        assertThat(text.replace("\r\n", "").chars().filter(c -> c == '\n').count()).isEqualTo(1);
    }

    @Test
    void numbersAreAsStoredWithoutTrailingZerosSeparatorsOrExponent() {
        assertThat(ExcelValues.write(new BigDecimal("1308.8900"))).isEqualTo("1308.89");
        assertThat(ExcelValues.write(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(ExcelValues.write(new BigDecimal("0.0000"))).isEqualTo("0");
        assertThat(ExcelValues.write(new BigDecimal("-0.000000001"))).isEqualTo("-0.000000001");
        assertThat(ExcelValues.write(new BigDecimal("1234567.123456789"))).isEqualTo("1234567.123456789");
    }

    @Test
    void theImportReadsTheFileBackAsWritten() {
        ExcelRow row = new ExcelRow("12", LocalDate.of(2024, 1, 31), new Item("Продукты", "FOOD", "EXPENSE"),
                new BigDecimal("-4.5"), "EUR", "Лавка; угол", "сдача \"мелочью\"", UNALLOCATED, CARD, true, null, null,
                null, null, "");
        Workbook.Sheet<TransactionRow> sheet = Workbook.transactions(new ImportFile("t.csv", write(List.of(row))));
        assertThat(sheet.errors()).isEmpty();
        TransactionRow read = sheet.rows().getFirst();
        assertThat(read.id()).isEqualTo(12);
        assertThat(read.date()).isEqualTo(LocalDate.of(2024, 1, 31));
        assertThat(read.sum()).isEqualByComparingTo("-4.5");
        assertThat(read.counteragent()).isEqualTo("Лавка; угол");
        assertThat(read.comment()).isEqualTo("сдача \"мелочью\"");
        assertThat(read.family()).isTrue();
        assertThat(read.familyExpense()).isEqualByComparingTo("2.25");
        assertThat(read.itemCode()).isEqualTo("FOOD");
        assertThat(read.contentHash()).isEqualTo(row.contentHash());
        // CsvTable reads every column of the header.
        CsvTable table = CsvTable.read(new ImportFile("t.csv", write(List.of(row))), ExcelRow.HEADER);
        assertThat(table.problems()).isEmpty();
        assertThat(ExcelRow.HEADER.stream().map(table.rows().getFirst()::get).toList())
                .isEqualTo(Arrays.asList(row.columns().toArray(String[]::new)));
    }

    private static byte[] write(List<ExcelRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TransactionsCsv.write(rows, out);
        return out.toByteArray();
    }
}
