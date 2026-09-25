package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Values as the Excel exports write them. */
class ExcelValuesTests {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "385; 385",
            "57.5; 57.5",
            "'-1 500.00 '; -1500.00",
            "'1 234.50'; 1234.50",
            "'2 000'; 2000",
            "' -3 000.00 '; -3000.00",
            "0; 0"})
    void amountsIgnoreSpacesOfEveryKind(String text, String expected) {
        assertThat(ExcelValues.amount(text)).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1,5", "1.2.3", "abc", "+5", "5-", "1e3"})
    void anythingElseIsNotAnAmount(String text) {
        assertThatThrownBy(() -> ExcelValues.amount(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'%s' is not a number", text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-03-01", "1/3/2024", "31/02/2024", "01/13/2024", "01/03/24"})
    void datesAreStrictlyDayMonthYear(String text) {
        assertThatThrownBy(() -> ExcelValues.date(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'%s' is not a date in the format dd/MM/yyyy", text);
    }

    @ParameterizedTest
    @CsvSource({"01/11/2017, 2017-11-01", "29/02/2024, 2024-02-29"})
    void datesAreRead(String text, LocalDate expected) {
        assertThat(ExcelValues.date(text)).isEqualTo(expected);
        assertThat(ExcelValues.format(expected)).isEqualTo(text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {"'  text '; text", "' te xt \t'; te xt", "''; ''"})
    void trimRemovesNoBreakSpacesToo(String text, String expected) {
        assertThat(ExcelValues.trim(text)).isEqualTo(expected);
    }
}
