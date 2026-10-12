package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Values as the Excel exports write them (D3b: the export of 2026-10-12's format). */
class ExcelValuesTests {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "385; 385",
            "57.5; 57.5",
            "'-1 500.00 '; -1500.00",
            "'1 234.50'; 1234.50",
            "'2 000'; 2000",
            "'3 000.25 '; 3000.25",
            "' -3 000.00 '; -3000.00",
            "0; 0",
            "' - '; 0",
            "-1.42109E-14; -0.0000000000000142109",
            "2.5e3; 2500",
            "1E+2; 100"})
    void amountsIgnoreSpacesOfEveryKindAndReadExponentsAsDecimals(String text, String expected) {
        BigDecimal amount = ExcelValues.amount(text);
        assertThat(amount).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(amount.scale()).isNotNegative();
    }

    @Test
    void anExponentsResidueIsKeptExactlyAndRoundsToZero() {
        BigDecimal residue = ExcelValues.amount("-1.42109E-14");
        assertThat(residue.toPlainString()).isEqualTo("-0.0000000000000142109");
        assertThat(residue.setScale(2, java.math.RoundingMode.HALF_UP).signum()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1,5", "1.2.3", "abc", "+5", "5-", "1e", "#VALUE!", "--"})
    void anythingElseIsNotAnAmountAndTheMessageHoldsNoValue(String text) {
        assertThatThrownBy(() -> ExcelValues.amount(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-03-01", "1.3.2024", "31.02.2024", "01.13.2024", "01.03.24", "01/03/2024"})
    void datesAreStrictlyDayMonthYearWithPoints(String text) {
        assertThatThrownBy(() -> ExcelValues.date(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a date in the format dd.MM.yyyy");
    }

    @ParameterizedTest
    @CsvSource({"01.11.2017, 2017-11-01", "29.02.2024, 2024-02-29"})
    void datesAreRead(String text, LocalDate expected) {
        assertThat(ExcelValues.date(text)).isEqualTo(expected);
        assertThat(ExcelValues.format(expected)).isEqualTo(text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {"'  text '; text", "' te xt \t'; te xt", "''; ''"})
    void trimRemovesNoBreakSpacesToo(String text, String expected) {
        assertThat(ExcelValues.trim(text)).isEqualTo(expected);
    }
}
