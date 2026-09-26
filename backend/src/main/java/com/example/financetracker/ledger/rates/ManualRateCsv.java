package com.example.financetracker.ledger.rates;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.financetracker.ledger.RuleViolationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

/**
 * A CSV file of manual rates: UTF-8, possibly with a byte order mark, and the columns date, base, quote and rate in
 * any order, such as {@code 2026-09-01,EUR,RUB,95.50}. Dates are ISO dates; the rate has a decimal point.
 */
final class ManualRateCsv {

    static final List<String> COLUMNS = List.of("date", "base", "quote", "rate");
    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get();

    private ManualRateCsv() {
    }

    /**
     * The file's rates, all valid.
     *
     * @throws RuleViolationException listing every problem, by row as in a spreadsheet, where the header is row 1
     */
    static List<ManualRate> read(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new RuleViolationException("the file is not UTF-8 text");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<CSVRecord> records;
        try {
            records = FORMAT.parse(new StringReader(text)).getRecords();
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            throw new RuleViolationException("the file is not valid CSV: " + e.getMessage());
        }
        if (records.isEmpty()) {
            throw new RuleViolationException("the file is empty");
        }

        Map<String, Integer> columns = new HashMap<>();
        List<String> header = records.getFirst().toList();
        for (int i = 0; i < header.size(); i++) {
            columns.putIfAbsent(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        List<String> missing = COLUMNS.stream().filter(column -> !columns.containsKey(column)).toList();
        if (!missing.isEmpty()) {
            throw new RuleViolationException("the file needs the columns %s in its first row; %s missing"
                    .formatted(String.join(",", COLUMNS), String.join(", ", missing)
                            + (missing.size() == 1 ? " is" : " are")));
        }

        List<ManualRate> rates = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, Long> seen = new HashMap<>();
        for (CSVRecord record : records.subList(1, records.size())) {
            long row = record.getRecordNumber();
            List<String> rowProblems = new ArrayList<>();
            LocalDate date = date(value(record, columns, "date"), rowProblems);
            BigDecimal rate = rate(value(record, columns, "rate"), rowProblems);
            ManualRate manual = new ManualRate(date, value(record, columns, "base").toUpperCase(Locale.ROOT),
                    value(record, columns, "quote").toUpperCase(Locale.ROOT), rate);
            if (rowProblems.isEmpty()) {
                rowProblems.addAll(manual.problems());
            }
            if (rowProblems.isEmpty()) {
                Long earlier = seen.putIfAbsent(manual.date() + " " + manual.currency(), row);
                if (earlier != null) {
                    rowProblems.add("row %d has a rate for %s on %s already".formatted(earlier, manual.currency(),
                            manual.date()));
                }
            }
            rowProblems.forEach(problem -> problems.add("row %d: %s".formatted(row, problem)));
            if (rowProblems.isEmpty()) {
                rates.add(manual);
            }
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        if (rates.isEmpty()) {
            throw new RuleViolationException("the file has no rates below its first row");
        }
        return rates;
    }

    private static String value(CSVRecord record, Map<String, Integer> columns, String column) {
        int index = columns.get(column);
        return index < record.size() ? record.get(index).trim() : "";
    }

    private static LocalDate date(String text, List<String> problems) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            problems.add("'%s' is not a date such as 2026-09-25".formatted(text));
            return null;
        }
    }

    private static BigDecimal rate(String text, List<String> problems) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            problems.add("the rate '%s' is not a number such as 95.50".formatted(text));
            return null;
        }
    }
}
