package com.example.financetracker.ledger.importer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The transactions file as the Excel ledger's CSV export writes it (D-240): UTF-8 with a byte order mark, separated by
 * semicolons, lines ending in CRLF, the header {@link ExcelRow#HEADER} first. A value is quoted only when it holds a
 * semicolon, a quote or a line break, its quotes doubled. {@link CsvTable} reads it back.
 */
public final class TransactionsCsv {

    private static final String BOM = "﻿";
    private static final String CRLF = "\r\n";

    private TransactionsCsv() {
    }

    /** Writes the header and the rows to {@code out}, which it flushes and leaves open. */
    public static void write(Iterable<ExcelRow> rows, OutputStream out) {
        try {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write(BOM);
            line(writer, ExcelRow.HEADER);
            for (ExcelRow row : rows) {
                line(writer, row.columns());
            }
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void line(Writer writer, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(';');
            }
            writer.write(field(values.get(i)));
        }
        writer.write(CRLF);
    }

    /** The value as one field: quoted, its quotes doubled, only if it holds a semicolon, a quote or a line break. */
    static String field(String value) {
        if (value.indexOf(';') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
