package com.example.financetracker.ledger.importer;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.importer.ImportReport.Problem;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * A sheet exported from Excel as CSV: UTF-8, possibly with a byte order mark, comma separated, fields quoted where
 * needed, lines ending in CRLF or LF. The first row names the columns. Values come back trimmed.
 *
 * @param problems what makes the file as a whole unreadable; there are no rows then
 */
record CsvTable(List<Row> rows, List<Problem> problems) {

    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get();

    /** Reads the file, which must have at least the required columns, in any order and among others. */
    static CsvTable read(ImportFile file, List<String> requiredColumns) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.content()))
                    .toString();
        } catch (CharacterCodingException e) {
            return failed(file, "the file is not UTF-8 text");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }

        List<CSVRecord> records;
        try (CSVParser parser = FORMAT.parse(new StringReader(text))) {
            records = parser.getRecords();
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            return failed(file, "the file is not valid CSV: " + e.getMessage());
        }
        if (records.isEmpty()) {
            return failed(file, "the file is empty");
        }

        Map<String, Integer> columns = new HashMap<>();
        List<String> header = records.getFirst().toList();
        for (int i = 0; i < header.size(); i++) {
            String name = ExcelValues.trim(header.get(i));
            if (!name.isEmpty() && columns.putIfAbsent(name, i) != null) {
                return failed(file, "the column '%s' appears twice".formatted(name));
            }
        }
        List<String> missing = requiredColumns.stream().filter(column -> !columns.containsKey(column)).toList();
        if (!missing.isEmpty()) {
            return failed(file, "missing columns: " + String.join(", ", missing));
        }

        List<Row> rows = new ArrayList<>();
        for (CSVRecord record : records.subList(1, records.size())) {
            rows.add(new Row(Math.toIntExact(record.getRecordNumber()), columns, record.toList()));
        }
        return new CsvTable(rows, List.of());
    }

    private static CsvTable failed(ImportFile file, String problem) {
        return new CsvTable(List.of(), List.of(new Problem(file.name(), null, problem)));
    }

    /**
     * One data row.
     *
     * @param number the row in the spreadsheet, where the header is row 1
     */
    record Row(int number, Map<String, Integer> columns, List<String> values) {

        /** The trimmed value, empty if the row ends before the column. */
        String get(String column) {
            Integer index = columns.get(column);
            if (index == null) {
                throw new IllegalArgumentException("The file has no column '%s'".formatted(column));
            }
            return index < values.size() ? ExcelValues.trim(values.get(index)) : "";
        }

        boolean isBlank() {
            return values.stream().allMatch(value -> ExcelValues.trim(value).isEmpty());
        }
    }
}
