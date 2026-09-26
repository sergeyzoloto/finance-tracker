package com.example.financetracker.ledger.rates;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Downloads and reads the ECB's euro reference rates: units of a currency for one euro, per day.
 * <ul>
 * <li>The daily file is XML: {@code <Cube time='2026-09-25'>} holds one {@code <Cube currency='USD' rate='1.1403'/>}
 * per currency.
 * <li>The history is a ZIP file with one CSV file, newest day first: a column Date, then one column per currency, and
 * an empty column at the end. A currency without a rate that day, such as RUB since 2 March 2022, reads N/A.
 * </ul>
 * Both were checked against the ECB's files on 2026-09-26.
 */
@Component
class EcbClient {

    /** The history's CSV is about 2 MB; anything much larger isn't the ECB's file. */
    private static final long MAX_CSV_BYTES = 50L * 1024 * 1024;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /** One publication day. */
    record EcbDay(LocalDate date, Map<String, BigDecimal> rates) {
    }

    private final RestClient http;
    private final EcbProperties properties;

    EcbClient(RestClient.Builder builder, EcbProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(Duration.ofSeconds(60));
        this.http = builder.requestFactory(requests).build();
        this.properties = properties;
    }

    /** The latest day's rates. */
    EcbDay daily() {
        return parseDaily(download(properties.dailyUrl().toString()));
    }

    /** Every day's rates, newest first. */
    List<EcbDay> history() {
        return parseHistory(download(properties.historyUrl().toString()));
    }

    private byte[] download(String url) {
        byte[] body = http.get().uri(url).retrieve().body(byte[].class);
        if (body == null || body.length == 0) {
            throw new EcbFormatException("the ECB sent nothing from " + url);
        }
        return body;
    }

    static EcbDay parseDaily(byte[] xml) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // No DTDs and no external entities: the file is data, not markup to expand.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        LocalDate date = null;
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT || !reader.getLocalName().equals("Cube")) {
                    continue;
                }
                String time = reader.getAttributeValue(null, "time");
                String currency = reader.getAttributeValue(null, "currency");
                if (time != null) {
                    if (date != null) {
                        throw new EcbFormatException("the daily file has more than one day");
                    }
                    date = date(time);
                } else if (currency != null) {
                    if (date == null) {
                        throw new EcbFormatException("the daily file has a rate outside a day");
                    }
                    put(rates, currency, reader.getAttributeValue(null, "rate"), date);
                }
            }
        } catch (XMLStreamException e) {
            throw new EcbFormatException("the daily file is not valid XML: " + e.getMessage());
        }
        if (date == null || rates.isEmpty()) {
            throw new EcbFormatException("the daily file has no rates");
        }
        return new EcbDay(date, rates);
    }

    static List<EcbDay> parseHistory(byte[] zip) {
        String csv = null;
        try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
                if (!entry.isDirectory() && entry.getName().endsWith(".csv")) {
                    if (csv != null) {
                        throw new EcbFormatException("the history has more than one CSV file");
                    }
                    csv = read(entries);
                }
            }
        } catch (IOException e) {
            throw new EcbFormatException("the history is not a valid ZIP file: " + e.getMessage());
        }
        if (csv == null) {
            throw new EcbFormatException("the history has no CSV file");
        }

        List<CSVRecord> records;
        try {
            records = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get()
                    .parse(new StringReader(csv)).getRecords();
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            throw new EcbFormatException("the history is not valid CSV: " + e.getMessage());
        }
        if (records.isEmpty() || !records.getFirst().get(0).trim().equals("Date")) {
            throw new EcbFormatException("the history doesn't start with the column Date");
        }
        List<String> header = records.getFirst().toList().stream().map(String::trim).toList();
        List<EcbDay> days = new ArrayList<>();
        for (CSVRecord record : records.subList(1, records.size())) {
            LocalDate date = date(record.get(0).trim());
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            for (int i = 1; i < header.size() && i < record.size(); i++) {
                String value = record.get(i).trim();
                // The last column has no name and no values.
                if (!header.get(i).isEmpty() && !value.isEmpty() && !value.equals("N/A")) {
                    put(rates, header.get(i), value, date);
                }
            }
            days.add(new EcbDay(date, rates));
        }
        if (days.isEmpty()) {
            throw new EcbFormatException("the history has no days");
        }
        days.sort(Comparator.comparing(EcbDay::date).reversed());
        return days;
    }

    private static String read(InputStream entry) throws IOException {
        byte[] content = entry.readNBytes(Math.toIntExact(MAX_CSV_BYTES + 1));
        if (content.length > MAX_CSV_BYTES) {
            throw new EcbFormatException("the history's CSV file is larger than %d bytes".formatted(MAX_CSV_BYTES));
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private static void put(Map<String, BigDecimal> rates, String currency, String value, LocalDate date) {
        if (!CURRENCY.matcher(currency).matches() || currency.equals(RateBook.EURO)) {
            throw new EcbFormatException("'%s' on %s is not a currency code".formatted(currency, date));
        }
        BigDecimal rate;
        try {
            rate = new BigDecimal(value == null ? "" : value);
        } catch (NumberFormatException e) {
            throw new EcbFormatException("the rate '%s' of %s on %s is not a number".formatted(value, currency, date));
        }
        // exchange_rate.rate is NUMERIC(19, 8).
        if (rate.signum() <= 0 || rate.stripTrailingZeros().scale() > 8 || rate.precision() - rate.scale() > 11) {
            throw new EcbFormatException("the rate %s of %s on %s is out of range".formatted(value, currency, date));
        }
        if (rates.put(currency, rate) != null) {
            throw new EcbFormatException("%s appears twice on %s".formatted(currency, date));
        }
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new EcbFormatException("'%s' is not a date".formatted(text));
        }
    }

    /** A file that doesn't look as the ECB's files do; nothing of it is loaded. */
    static class EcbFormatException extends RuntimeException {

        EcbFormatException(String message) {
            super(message);
        }
    }
}
