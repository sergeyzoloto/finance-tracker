package com.example.financetracker.ledger.importer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.importer.ImportReport.Problem;

/**
 * The Excel ledger's CSV exports, read into typed rows: a user's transactions file, with the accounts and categories its
 * rows name (D4b, D-228), and the partner's family rows (D-140). A row that can't be read is a row error, and the rows
 * after it are still read; no error holds a value of the row.
 */
final class Workbook {

    /** Where a workbook formula found nothing, it writes this text; in LOAN_CA and CODE_ITEM it means empty. */
    static final String NULL = "NULL";

    static final String CREDITOR_DEBT = "CREDITOR_DEBT";
    static final String RESERVE = "RESERVE";

    /** Known typos in the workbook's codes, and their fixes. The old codes still resolve, to the fixed ones. */
    static final Map<String, String> CODE_ALIASES = Map.of(
            "COMMISION", "COMMISSION",
            "GEMENTE_FUND", "GEMEENTE_FUND");

    /** Rule 8. */
    private static final Set<String> REQUIRE_COUNTERPARTY = Set.of(AccountRole.LOANS.defaultCode(), CREDITOR_DEBT);
    /** Rule 4: the accounts of the Excel type LIABILITIES that hold the user's own money. */
    private static final Set<String> EQUITY = Set.of(AccountRole.UNALLOCATED.defaultCode(), RESERVE);

    static final int NAME_MAX_LENGTH = 100;
    private static final int CODE_MAX_LENGTH = 50;

    private Workbook() {
    }

    /** A code as the ledger stores it: fixed, if the workbook spells it with a known typo. */
    static String code(String code) {
        return CODE_ALIASES.getOrDefault(code, code);
    }

    /**
     * Rule 4. Of the Excel type LIABILITIES, UNALLOCATED and RESERVE are EQUITY and all other accounts LIABILITY.
     *
     * @return null for a type the workbook doesn't have
     */
    static AccountType accountType(String excelType, String code) {
        return switch (excelType) {
            case "ASSET" -> AccountType.ASSET;
            case "LIABILITIES" -> EQUITY.contains(code) ? AccountType.EQUITY : AccountType.LIABILITY;
            default -> null;
        };
    }

    /**
     * A user's transactions file (D-235, D4b), recognised by its header: exactly the 32 columns of
     * {@link ExcelRow#HEADER}, in that order, whatever the file is called. An ID in an earlier row too is an error of the
     * later row.
     *
     * @throws TransactionsFileRefused for any other header, naming the expected one; nothing is read
     */
    static Sheet<TransactionRow> transactions(ImportFile file) {
        CsvTable table = CsvTable.read(file, List.of());
        if (table.problems().isEmpty() && !table.header().equals(ExcelRow.HEADER)) {
            throw new TransactionsFileRefused(List.of(("the file isn't a transactions file: its header has to be "
                    + "exactly these %d columns, in this order: %s (D-235)").formatted(ExcelRow.HEADER.size(),
                            String.join(";", ExcelRow.HEADER))));
        }
        Sheet<TransactionRow> sheet = new Sheet<>(file, table);
        Set<String> ids = new HashSet<>();
        for (CsvTable.Row row : sheet.dataRows()) {
            List<String> errors = new ArrayList<>();
            TransactionRow transaction = TransactionRow.parse(row, errors);
            if (transaction != null && !ids.add(transaction.excelId())) {
                errors.add("the ID is in an earlier row too (D-86)");
            }
            if (errors.isEmpty()) {
                sheet.add(transaction);
            } else {
                sheet.error(row, errors);
            }
        }
        return sheet;
    }

    /**
     * The accounts and categories of a transactions file (D-228): each code, with the workbook's typos fixed
     * ({@link #CODE_ALIASES}, D-246), its name from Debit, Credit or Exp. Item and its type from TYPE_DEBIT,
     * TYPE_CREDIT or TYPE. A code's names are compared ignoring case, as the journal spells an account in any case
     * (D-261). The whole file is refused if a code has two names or two types, a type the workbook doesn't have, or if
     * a name is two codes' (D-229, D-262). Messages name codes and IDs, never a name or an amount.
     *
     * @param rows the file's rows that could be read, the app's included
     * @throws TransactionsFileRefused with every reason
     */
    static FileChart chart(List<TransactionRow> rows) {
        Map<String, Uses> accounts = new TreeMap<>();
        Map<String, Uses> categories = new TreeMap<>();
        List<TransactionRow> ordered = rows.stream().filter(r -> r.computed() != null)
                .sorted(Comparator.comparing(TransactionRow::isApp).thenComparingLong(TransactionRow::id))
                .toList();
        for (TransactionRow row : ordered) {
            TransactionRow.Computed c = row.computed();
            accounts.computeIfAbsent(c.debitCode(), k -> new Uses()).add(row.debit(), c.debitType(), row.excelId());
            accounts.computeIfAbsent(c.creditCode(), k -> new Uses()).add(row.credit(), c.creditType(),
                    row.excelId());
            if (row.itemCode() != null) {
                categories.computeIfAbsent(row.itemCode(), k -> new Uses()).add(row.item(), c.itemType(),
                        row.excelId());
            }
        }
        List<String> reasons = new ArrayList<>();
        List<AccountRow> accountRows = new ArrayList<>();
        accounts.forEach((code, uses) -> {
            uses.check("account", code, reasons);
            AccountType type = accountType(uses.type(), code);
            if (uses.types.size() == 1 && type == null) {
                reasons.add("the account %s has the type %s, neither ASSET nor LIABILITIES (first in ID %s)"
                        .formatted(code, uses.type(), uses.firstId));
            }
            checkLengths("account", code, uses, reasons);
            accountRows.add(new AccountRow(uses.name(), uses.spellings(), code, type,
                    REQUIRE_COUNTERPARTY.contains(code)));
        });
        List<CategoryRow> categoryRows = new ArrayList<>();
        categories.forEach((code, uses) -> {
            uses.check("category", code, reasons);
            CategoryType type = switch (uses.type()) {
                case "INCOME" -> CategoryType.INCOME;
                case "EXPENSE" -> CategoryType.EXPENSE;
                default -> null;
            };
            if (uses.types.size() == 1 && type == null) {
                reasons.add("the category %s has the type %s, neither INCOME nor EXPENSE (first in ID %s)"
                        .formatted(code, uses.type(), uses.firstId));
            }
            checkLengths("category", code, uses, reasons);
            categoryRows.add(new CategoryRow(uses.name(), uses.spellings(), code, type));
        });
        // D-262: rows name their accounts by name in any case, and their categories by name.
        sharedNames("accounts", accountRows.stream().flatMap(a -> a.spellings().stream()
                .map(name -> Map.entry(name.toLowerCase(Locale.ROOT), a.code()))).toList(), reasons);
        sharedNames("categories", categoryRows.stream().flatMap(c -> c.spellings().stream()
                .map(name -> Map.entry(name, c.code()))).toList(), reasons);
        if (!reasons.isEmpty()) {
            throw new TransactionsFileRefused(reasons);
        }
        return new FileChart(accountRows, categoryRows);
    }

    private static void checkLengths(String what, String code, Uses uses, List<String> reasons) {
        if (code.length() > CODE_MAX_LENGTH) {
            reasons.add("the %s code %s is longer than %d characters".formatted(what, code, CODE_MAX_LENGTH));
        }
        if (uses.spellings().stream().anyMatch(n -> n.codePointCount(0, n.length()) > NAME_MAX_LENGTH)) {
            reasons.add("the %s %s has a name longer than %d characters".formatted(what, code, NAME_MAX_LENGTH));
        }
    }

    private static void sharedNames(String what, List<Map.Entry<String, String>> names, List<String> reasons) {
        Map<String, Set<String>> codes = new TreeMap<>();
        names.forEach(e -> codes.computeIfAbsent(e.getKey(), k -> new java.util.TreeSet<>()).add(e.getValue()));
        codes.values().stream().filter(c -> c.size() > 1).forEach(c -> reasons.add(
                "the %s %s have one name, and rows name them by it (D-262)".formatted(what, String.join(" and ", c))));
    }

    /** One code's spellings and types in a file, in the order of the rows' IDs. */
    private static final class Uses {

        /** By the name in lower case: each spelling and how many rows use it, in the order of first use. */
        private final Map<String, Map<String, Integer>> names = new LinkedHashMap<>();
        private final Map<String, String> firstIdOfName = new LinkedHashMap<>();
        private final Map<String, String> types = new LinkedHashMap<>();
        private String firstId;

        void add(String name, String type, String id) {
            firstId = firstId == null ? id : firstId;
            String key = name.toLowerCase(Locale.ROOT);
            names.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(name, 1, Integer::sum);
            firstIdOfName.putIfAbsent(key, id);
            types.putIfAbsent(type, id);
        }

        void check(String what, String code, List<String> reasons) {
            if (names.size() > 1) {
                reasons.add("the %s %s has %d names in the file, first in the IDs %s (D-229)".formatted(what, code,
                        names.size(), String.join(", ", firstIdOfName.values())));
            }
            if (types.size() > 1) {
                reasons.add("the %s %s has the types %s in the file, first in the IDs %s (D-229)".formatted(what,
                        code, String.join(" and ", types.keySet()), String.join(", ", types.values())));
            }
        }

        /** The spelling most rows use, the first one on a tie (D-261). */
        String name() {
            String best = null;
            int most = 0;
            for (Map.Entry<String, Integer> form : names.values().iterator().next().entrySet()) {
                if (form.getValue() > most) {
                    best = form.getKey();
                    most = form.getValue();
                }
            }
            return best;
        }

        /** Every spelling of its first name: the app's own name is kept when it is one of them (D-261). */
        Set<String> spellings() {
            return Set.copyOf(names.values().iterator().next().keySet());
        }

        String type() {
            return types.keySet().iterator().next();
        }
    }

    /**
     * The partner's family rows as the import page uploads them (D-78, D-140). The whole file is refused if it has any
     * column but {@link PartnerRow#COLUMNS} and {@link PartnerRow#ITEM_COLUMNS} (D-260), or any row that isn't a family
     * row with an item: then something other than the page's filter made it, and nothing of it is read.
     *
     * @throws PartnerFileRefused naming the columns and the rows (by number only) that break the filter
     */
    static Sheet<PartnerRow> partner(ImportFile file) {
        // Read with no column required, so that a file with other columns, such as his raw transactions file, is
        // refused for them rather than read as one with columns missing.
        CsvTable table = CsvTable.read(file, List.of());
        List<String> refusals = new ArrayList<>();
        table.rows().stream().findFirst().ifPresent(row -> {
            Set<String> allowed = new HashSet<>(PartnerRow.COLUMNS);
            allowed.addAll(PartnerRow.ITEM_COLUMNS);
            if (!allowed.containsAll(row.columns().keySet())) {
                refusals.add("the partner's file has a column the import doesn't take: only "
                        + String.join(", ", PartnerRow.COLUMNS) + " (and " + String.join(", ", PartnerRow.ITEM_COLUMNS)
                        + ") leave the laptop");
            }
        });
        for (CsvTable.Row row : refusals.isEmpty() && row0HasFamily(table) ? table.rows() : List.<CsvTable.Row>of()) {
            if (row.isBlank()) {
                continue;
            }
            if (!TransactionRow.FAMILY_YES.equals(row.get(PartnerRow.FAMILY))) {
                refusals.add("row %d of the partner's file isn't a family row (Family = \"%s\")"
                        .formatted(row.number(), TransactionRow.FAMILY_YES));
            } else if (EntryMapper.NO_CATEGORY.equals(row.get(PartnerRow.ITEM))) {
                refusals.add("row %d of the partner's file has no item (\"%s\")".formatted(row.number(),
                        EntryMapper.NO_CATEGORY));
            }
        }
        if (!refusals.isEmpty()) {
            throw new PartnerFileRefused(refusals);
        }
        Sheet<PartnerRow> sheet = new Sheet<>(file, CsvTable.read(file, PartnerRow.COLUMNS));
        Set<Long> ids = new HashSet<>();
        for (CsvTable.Row row : sheet.dataRows()) {
            List<String> errors = new ArrayList<>();
            PartnerRow partner = PartnerRow.parse(row, errors);
            if (partner != null && !ids.add(partner.id())) {
                errors.add("the ID is in an earlier row too (D-86)");
            }
            if (errors.isEmpty()) {
                sheet.add(partner);
            } else {
                sheet.error(row, errors);
            }
        }
        return sheet;
    }

    /** Whether the table's rows have the columns Family and Exp. Item that the filter's refusal reads. */
    private static boolean row0HasFamily(CsvTable table) {
        return table.rows().isEmpty() || table.rows().getFirst().columns().keySet()
                .containsAll(List.of(PartnerRow.FAMILY, PartnerRow.ITEM));
    }

    /**
     * The column's value, parsed; null if it's empty or can't be parsed, which adds an error.
     *
     * @param parse throws IllegalArgumentException, whose message becomes the error
     */
    static <T> T required(CsvTable.Row row, String column, Function<String, T> parse, List<String> errors) {
        String value = row.get(column);
        if (value.isEmpty()) {
            errors.add(column + " is empty");
            return null;
        }
        return parse(column, value, parse, errors);
    }

    /** Like {@link #required}, but an empty value is null without an error. */
    static <T> T optional(CsvTable.Row row, String column, Function<String, T> parse, List<String> errors) {
        String value = row.get(column);
        return value.isEmpty() ? null : parse(column, value, parse, errors);
    }

    private static <T> T parse(String column, String value, Function<String, T> parse, List<String> errors) {
        try {
            return parse.apply(value);
        } catch (IllegalArgumentException e) {
            errors.add(column + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * An account as a transactions file names it (D-228).
     *
     * @param name the spelling of its name that most rows use (D-261)
     * @param spellings every spelling of the name in the file, which differ only in case
     */
    record AccountRow(String name, Set<String> spellings, String code, AccountType type,
            boolean requiresCounterparty) {
    }

    /** A category as a transactions file names it (D-228). */
    record CategoryRow(String name, Set<String> spellings, String code, CategoryType type) {
    }

    /** A transactions file's accounts and categories (D-228). */
    record FileChart(List<AccountRow> accounts, List<CategoryRow> categories) {
    }

    /** The rows of one file that could be read, and what happened to the others. */
    static final class Sheet<T> {

        private final ImportFile file;
        private final CsvTable table;
        private final List<T> rows = new ArrayList<>();
        private final List<Problem> errors = new ArrayList<>();
        private final List<Problem> skipped = new ArrayList<>();

        Sheet(ImportFile file, CsvTable table) {
            this.file = file;
            this.table = table;
            errors.addAll(table.problems());
        }

        /** The rows to read; blank rows are skipped on the way. */
        private List<CsvTable.Row> dataRows() {
            List<CsvTable.Row> dataRows = new ArrayList<>();
            for (CsvTable.Row row : table.rows()) {
                if (row.isBlank()) {
                    skip(row, "the row is empty");
                } else {
                    dataRows.add(row);
                }
            }
            return dataRows;
        }

        private void add(T row) {
            rows.add(row);
        }

        private void error(CsvTable.Row row, List<String> reasons) {
            errors.add(new Problem(file.name(), row.number(), String.join("; ", reasons)));
        }

        private void skip(CsvTable.Row row, String reason) {
            skipped.add(new Problem(file.name(), row.number(), reason));
        }

        ImportFile file() {
            return file;
        }

        /** Takes the rows that match out of {@link #rows()} (D-165's rows after the cutoff), and says how many. */
        int removeRows(Predicate<T> which) {
            int before = rows.size();
            rows.removeIf(which);
            return before - rows.size();
        }

        List<T> rows() {
            return rows;
        }

        /** Rows that can't be read, one problem per row, and a file that can't be read at all. */
        List<Problem> errors() {
            return errors;
        }

        List<Problem> skipped() {
            return skipped;
        }

        /** The file's data rows, the header not counted. */
        int size() {
            return table.rows().size();
        }
    }
}
