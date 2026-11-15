package com.example.financetracker.ledger.rates;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;

/**
 * Downloads and reads the Bank of Russia's official EUR rate in roubles (D-852), from XML_dynamic.asp for a range of
 * days and the currency id {@value #EURO_ID}. The answer is windows-1251 XML:
 * {@code <ValCurs ID="R01239" …><Record Date="03.11.2026" Id="R01239"><Nominal>1</Nominal><Value>90,4635</Value>…}
 * with a decimal comma. Roubles for one euro are {@code Value / Nominal}, as exchange_rate stores every rate. Checked
 * against the CBR's answer of 2026-11-15.
 */
@Component
class CbrClient {

    /** The CBR's id of the euro. */
    static final String EURO_ID = "R01239";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final DateTimeFormatter QUERY_DAY = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    /** One day's rate: roubles for one euro. */
    record CbrRate(LocalDate date, BigDecimal perEuro) {
    }

    private final RestClient http;
    private final CbrProperties properties;

    CbrClient(RestClient.Builder builder, CbrProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(Duration.ofSeconds(60));
        this.http = builder.requestFactory(requests).build();
        this.properties = properties;
    }

    /** The rates from {@code from} to {@code to}, oldest first. */
    List<CbrRate> rates(LocalDate from, LocalDate to) {
        String url = UriComponentsBuilder.fromUri(properties.url())
                .queryParam("date_req1", from.format(QUERY_DAY))
                .queryParam("date_req2", to.format(QUERY_DAY))
                .queryParam("VAL_NM_RQ", EURO_ID)
                .build().toUriString();
        byte[] body = http.get().uri(url).retrieve().body(byte[].class);
        if (body == null || body.length == 0) {
            throw new CbrFormatException("the CBR sent nothing");
        }
        return parse(body);
    }

    /**
     * Reads the answer. Its XML declaration names windows-1251, which the parser decodes. Any record that isn't the
     * euro's, with a nominal that isn't positive or a value that isn't a positive number, refuses the whole answer.
     */
    static List<CbrRate> parse(byte[] xml) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // No DTDs and no external entities: the file is data, not markup to expand.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        List<CbrRate> rates = new ArrayList<>();
        boolean root = false;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            LocalDate date = null;
            String nominal = null;
            String value = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "ValCurs" -> root = true;
                        case "Record" -> {
                            String id = reader.getAttributeValue(null, "Id");
                            if (!EURO_ID.equals(id)) {
                                throw new CbrFormatException("a record has the currency id '%s'".formatted(id));
                            }
                            date = date(reader.getAttributeValue(null, "Date"));
                            nominal = null;
                            value = null;
                        }
                        case "Nominal" -> nominal = reader.getElementText();
                        case "Value" -> value = reader.getElementText();
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("Record")) {
                    rates.add(new CbrRate(date, perEuro(nominal, value, date)));
                }
            }
        } catch (XMLStreamException e) {
            throw new CbrFormatException("the answer is not valid XML: " + e.getMessage());
        }
        if (!root) {
            throw new CbrFormatException("the answer is not the CBR's ValCurs");
        }
        rates.sort(Comparator.comparing(CbrRate::date));
        for (int i = 1; i < rates.size(); i++) {
            if (rates.get(i).date().equals(rates.get(i - 1).date())) {
                throw new CbrFormatException("%s appears twice".formatted(rates.get(i).date()));
            }
        }
        return rates;
    }

    private static BigDecimal perEuro(String nominalText, String valueText, LocalDate date) {
        BigDecimal nominal = number(nominalText, "nominal", date);
        BigDecimal value = number(valueText, "value", date);
        if (nominal.signum() <= 0 || value.signum() <= 0) {
            throw new CbrFormatException("the nominal or the value on %s is not positive".formatted(date));
        }
        // exchange_rate.rate is NUMERIC(19, 8).
        BigDecimal rate = value.divide(nominal, 8, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rate.signum() <= 0 || rate.precision() - rate.scale() > 11) {
            throw new CbrFormatException("the rate on %s is out of range".formatted(date));
        }
        return rate;
    }

    private static BigDecimal number(String text, String what, LocalDate date) {
        try {
            return new BigDecimal(text == null ? "" : text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new CbrFormatException("the %s '%s' on %s is not a number".formatted(what, text, date));
        }
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text == null ? "" : text, DAY);
        } catch (DateTimeParseException e) {
            throw new CbrFormatException("'%s' is not a date".formatted(text));
        }
    }

    /** An answer that doesn't look as the CBR's does; nothing of it is loaded. */
    static class CbrFormatException extends RuntimeException {

        CbrFormatException(String message) {
            super(message);
        }
    }
}
