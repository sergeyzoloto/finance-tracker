package com.example.financetracker.ledger.rates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.example.financetracker.ledger.rates.EcbClient.EcbDay;
import com.example.financetracker.ledger.rates.EcbClient.EcbFormatException;
import org.junit.jupiter.api.Test;

/** Reading the ECB's files, in the form they had on 2026-09-26, shortened. */
class EcbClientTests {

    static final String DAILY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" \
            xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
            \t<gesmes:subject>Reference rates</gesmes:subject>
            \t<gesmes:Sender>
            \t\t<gesmes:name>European Central Bank</gesmes:name>
            \t</gesmes:Sender>
            \t<Cube>
            \t\t<Cube time='2026-09-25'>
            \t\t\t<Cube currency='USD' rate='1.1403'/>
            \t\t\t<Cube currency='JPY' rate='179.70'/>
            \t\t\t<Cube currency='IDR' rate='20427.22'/>
            \t\t</Cube>
            \t</Cube>
            </gesmes:Envelope>
            """;

    /** Newest day first, N/A where a currency had no rate, and an empty column at the end of every line. */
    static final String HISTORY = """
            Date,USD,JPY,RUB,TRL,
            2026-09-25,1.1403,179.7,N/A,N/A,
            2022-03-01,1.1162,128.54,117.201,N/A,
            2004-12-31,1.3621,139.65,N/A,1836200,
            """;

    @Test
    void readsTheDailyFile() {
        EcbDay day = EcbClient.parseDaily(DAILY.getBytes(StandardCharsets.UTF_8));

        assertThat(day.date()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(day.rates()).containsExactly(Map.entry("USD", new BigDecimal("1.1403")),
                Map.entry("JPY", new BigDecimal("179.70")), Map.entry("IDR", new BigDecimal("20427.22")));
    }

    @Test
    void readsTheHistoryAndLeavesOutCurrenciesWithoutARateThatDay() {
        List<EcbDay> days = EcbClient.parseHistory(zip("eurofxref-hist.csv", HISTORY));

        assertThat(days).extracting(EcbDay::date).containsExactly(LocalDate.of(2026, 9, 25),
                LocalDate.of(2022, 3, 1), LocalDate.of(2004, 12, 31));
        assertThat(days.get(0).rates()).containsOnlyKeys("USD", "JPY");
        assertThat(days.get(1).rates()).containsEntry("RUB", new BigDecimal("117.201"));
        assertThat(days.get(2).rates()).containsEntry("TRL", new BigDecimal("1836200"));
    }

    @Test
    void refusesFilesThatDoNotLookLikeTheEcbs() {
        assertThatThrownBy(() -> EcbClient.parseDaily("<html>Maintenance</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EcbFormatException.class).hasMessage("the daily file has no rates");
        assertThatThrownBy(() -> EcbClient.parseDaily(DAILY.replace("1.1403", "-1").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EcbFormatException.class).hasMessage("the rate -1 of USD on 2026-09-25 is out of range");
        assertThatThrownBy(() -> EcbClient.parseHistory("not a zip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EcbFormatException.class).hasMessage("the history has no CSV file");
        assertThatThrownBy(() -> EcbClient.parseHistory(zip("eurofxref-hist.csv", "Day,USD\n2026-09-25,1.14\n")))
                .isInstanceOf(EcbFormatException.class).hasMessage("the history doesn't start with the column Date");
    }

    /** No DTD is read, so an entity can't pull in a local file. */
    @Test
    void readsNoDocumentTypeDefinition() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE Envelope [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <Envelope><Cube><Cube time='2026-09-25'><Cube currency='USD' rate='&secret;'/></Cube></Cube></Envelope>
                """;

        assertThatThrownBy(() -> EcbClient.parseDaily(xxe.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EcbFormatException.class);
    }

    static byte[] zip(String name, String content) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
