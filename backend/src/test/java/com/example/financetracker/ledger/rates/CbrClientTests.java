package com.example.financetracker.ledger.rates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.rates.CbrClient.CbrFormatException;
import com.example.financetracker.ledger.rates.CbrClient.CbrRate;
import org.junit.jupiter.api.Test;

/** {@link CbrClient}'s reading of XML_dynamic.asp's answer (D-852), as the CBR sent it on 2026-11-15. */
class CbrClientTests {

    static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    /** Its name attribute in Russian, so that the encoding matters. */
    static final String ANSWER = """
            <?xml version="1.0" encoding="windows-1251"?>
            <ValCurs ID="R01239" DateRange1="01.11.2026" DateRange2="04.11.2026" name="Динамика курса евро">
            <Record Date="04.11.2026" Id="R01239"><Nominal>1</Nominal><Value>90,8120</Value><VunitRate>90,812</VunitRate></Record>
            <Record Date="03.11.2026" Id="R01239"><Nominal>1</Nominal><Value>90,4635</Value><VunitRate>90,4635</VunitRate></Record>
            </ValCurs>
            """;

    static byte[] answer(String xml) {
        return xml.getBytes(WINDOWS_1251);
    }

    @Test
    void theRatesAreReadOldestFirstWithTheirDecimalComma() {
        List<CbrRate> rates = CbrClient.parse(answer(ANSWER));

        assertThat(rates).extracting(CbrRate::date)
                .containsExactly(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4));
        assertThat(rates.getFirst().perEuro()).isEqualByComparingTo("90.4635");
        assertThat(rates.getLast().perEuro()).isEqualByComparingTo("90.812");
    }

    @Test
    void theValueIsDividedByItsNominal() {
        List<CbrRate> rates = CbrClient.parse(answer(ANSWER.replace("<Nominal>1</Nominal><Value>90,4635",
                "<Nominal>10</Nominal><Value>904,635")));

        assertThat(rates.getFirst().perEuro()).isEqualByComparingTo("90.4635");
    }

    @Test
    void anAnswerWithoutRecordsHasNoRates() {
        assertThat(CbrClient.parse(answer("""
                <?xml version="1.0" encoding="windows-1251"?>
                <ValCurs ID="R01239" DateRange1="01.01.2027" DateRange2="02.01.2027" name="Foreign Currency Market Dynamic">
                </ValCurs>
                """))).isEmpty();
    }

    @Test
    void anythingUnexpectedRefusesTheWholeAnswer() {
        assertThatThrownBy(() -> CbrClient.parse(answer(ANSWER.replace("Id=\"R01239\"><Nominal>1</Nominal><Value>90,4635",
                "Id=\"R01235\"><Nominal>1</Nominal><Value>90,4635"))))
                .isInstanceOf(CbrFormatException.class).hasMessage("a record has the currency id 'R01235'");
        assertThatThrownBy(() -> CbrClient.parse(answer(ANSWER.replace("<Nominal>1</Nominal><Value>90,4635",
                "<Nominal>0</Nominal><Value>90,4635"))))
                .isInstanceOf(CbrFormatException.class).hasMessage("the nominal or the value on 2026-11-03 is not positive");
        assertThatThrownBy(() -> CbrClient.parse(answer(ANSWER.replace("90,4635</Value>", "-90,4635</Value>"))))
                .isInstanceOf(CbrFormatException.class).hasMessage("the nominal or the value on 2026-11-03 is not positive");
        assertThatThrownBy(() -> CbrClient.parse(answer(ANSWER.replace("90,4635</Value>", "n/a</Value>"))))
                .isInstanceOf(CbrFormatException.class).hasMessage("the value 'n/a' on 2026-11-03 is not a number");
        assertThatThrownBy(() -> CbrClient.parse(answer(ANSWER.replace("03.11.2026", "2026-11-03"))))
                .isInstanceOf(CbrFormatException.class).hasMessage("'2026-11-03' is not a date");
        assertThatThrownBy(() -> CbrClient.parse(answer(ANSWER.replace("03.11.2026", "04.11.2026"))))
                .isInstanceOf(CbrFormatException.class).hasMessage("2026-11-04 appears twice");
        assertThatThrownBy(() -> CbrClient.parse("<html>Maintenance</html>".getBytes(WINDOWS_1251)))
                .isInstanceOf(CbrFormatException.class).hasMessage("the answer is not the CBR's ValCurs");
        assertThatThrownBy(() -> CbrClient.parse(answer("<ValCurs><Record")))
                .isInstanceOf(CbrFormatException.class).hasMessageStartingWith("the answer is not valid XML");
    }
}
