package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Values as the Excel ledger's CSV exports write them. */
final class ExcelValues {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    /** A decimal number, with an exponent where Excel writes a floating-point residue (-1.42109E-14). */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    private ExcelValues() {
    }

    /**
     * The text without spaces around it, counting the no-break spaces Excel writes as whitespace, which
     * {@link String#strip} leaves.
     */
    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * A number such as {@code 385}, {@code 57.5}, {@code "-1 500.00 "} or {@code -1.42109E-14}: a point before the
     * decimals, spaces, no-break spaces or narrow no-break spaces anywhere, as thousand separators or trailing, and an
     * exponent where Excel writes one. Read as a decimal, never a float: an exponent's value is kept exactly, without
     * an exponent in its scale. A dash alone is 0, as Excel's accounting format shows it (an agent's choice, D3b).
     *
     * @throws IllegalArgumentException if it isn't such a number; the message never holds the value
     */
    static BigDecimal amount(String value) {
        StringBuilder digits = new StringBuilder(value.length());
        value.chars().filter(c -> !isSpace((char) c)).forEach(c -> digits.append((char) c));
        if (digits.toString().equals("-") || digits.toString().equals("\u2013")) {
            return BigDecimal.ZERO;
        }
        if (!NUMBER.matcher(digits).matches()) {
            throw new IllegalArgumentException("not a number");
        }
        BigDecimal number = new BigDecimal(digits.toString());
        return number.scale() < 0 ? number.setScale(0) : number;
    }

    /**
     * A date as dd.MM.yyyy.
     *
     * @throws IllegalArgumentException if it isn't one; the message never holds the value
     */
    static LocalDate date(String value) {
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not a date in the format dd.MM.yyyy", e);
        }
    }

    /** The date as the workbook writes it, for messages. */
    static String format(LocalDate date) {
        return DATE.format(date);
    }

    /** The lowercase hex SHA-256 of the bytes. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isSpace(char c) {
        // isSpaceChar covers U+00A0 and U+202F, which isWhitespace leaves out.
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
