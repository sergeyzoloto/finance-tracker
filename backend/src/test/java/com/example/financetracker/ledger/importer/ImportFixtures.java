package com.example.financetracker.ledger.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;

/**
 * The synthetic export of src/test/resources/import/ (invented names and amounts, the real export's format: D-61), and
 * ways to change it, for the importer's tests. Each row of its files is on one line.
 */
public final class ImportFixtures {

    /** The name of UNALLOCATED in the synthetic accounts file. */
    public static final String UNALLOCATED = "Свободный остаток";
    /** The owner's personal rows: no family flag with an item and no FAMILY_DEBT (rows 7 and 8 are family rows without an item or amount). */
    public static final Set<String> PERSONAL_IDS = Set.of("1", "7", "17", "18", "19", "20", "21", "22", "24", "25");

    private ImportFixtures() {
    }

    public static byte[] bytes(String name) {
        try {
            return new ClassPathResource("import/" + name).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ImportFile file(String name) {
        return new ImportFile(name, bytes(name));
    }

    /** The partner's rows as the import page uploads them: his transactions file through the filter (D-140). */
    public static byte[] partnerRows() {
        return PartnerFilter.filter(file("partner-transactions.csv"), UNALLOCATED);
    }

    /**
     * D-151's upload of a partner's transactions file in either layout (D-177): his family rows' seven columns, as the
     * import page and PartnerFilter make them.
     */
    public static byte[] seatRows(byte[] partnerTransactions) {
        return PartnerFilter.filter(new ImportFile("partner.csv", partnerTransactions), UNALLOCATED);
    }

    /** The whole synthetic export, for one run. */
    public static ImportRequest request(byte[] transactions, byte[] partner, Long ledger, Long seat, boolean commit) {
        return request(transactions, partner, ledger, seat, null, commit);
    }

    /** The whole synthetic export, for one run "up to" a date (D-165). */
    public static ImportRequest request(byte[] transactions, byte[] partner, Long ledger, Long seat, LocalDate upTo,
            boolean commit) {
        return new ImportRequest(file("accounts.csv"), file("categories.csv"),
                new ImportFile("transactions.csv", transactions), file("daily-balance.csv"), file("daily-change.csv"),
                partner == null ? null : new ImportFile("partner-family-rows.csv", partner), ledger, seat, upTo,
                commit);
    }

    /** The file with only the header and the rows whose first field, the ID, is one of these. */
    public static byte[] onlyRows(byte[] csv, Set<String> ids) {
        List<String> kept = new ArrayList<>();
        List<String> lines = lines(csv);
        kept.add(lines.getFirst());
        lines.subList(1, lines.size()).stream().filter(l -> ids.contains(l.substring(0, l.indexOf(';'))))
                .forEach(kept::add);
        return join(kept);
    }

    /** The file without the rows with these IDs. */
    public static byte[] withoutRows(byte[] csv, Set<String> ids) {
        List<String> kept = new ArrayList<>();
        List<String> lines = lines(csv);
        kept.add(lines.getFirst());
        lines.subList(1, lines.size()).stream().filter(l -> !ids.contains(l.substring(0, l.indexOf(';'))))
                .forEach(kept::add);
        return join(kept);
    }

    /** The file with one field of the row with this ID replaced, by the column's index. */
    public static byte[] withField(byte[] csv, String id, int column, String value) {
        List<String> lines = new ArrayList<>(lines(csv));
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(";", -1);
            if (fields[0].equals(id)) {
                fields[column] = value;
                lines.set(i, String.join(";", fields));
            }
        }
        return join(lines);
    }

    /** The file with an extra line at the end. */
    public static byte[] withLine(byte[] csv, String line) {
        List<String> lines = new ArrayList<>(lines(csv));
        lines.add(line);
        return join(lines);
    }

    public static List<String> lines(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8);
        return List.of(text.split("\r\n"));
    }

    private static byte[] join(List<String> lines) {
        return (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
