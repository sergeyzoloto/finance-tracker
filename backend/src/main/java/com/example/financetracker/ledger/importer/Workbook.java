package com.example.financetracker.ledger.importer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.importer.ImportReport.Problem;

/**
 * The Excel ledger's CSV exports, read into typed rows: the accounts, the categories, the owner's journal and the
 * partner's family rows (D-140). The daily files are {@link DailyFile}'s. A row that can't be read is a row error, and
 * the rows after it are still read; no error holds a value of the row.
 */
final class Workbook {

    static final String ACCOUNT_NAME = "Статьи баланса";
    static final String ACCOUNT_TYPE = "Актив/пассив";
    static final String CATEGORY_NAME = "Статьи движения средств";
    static final String CATEGORY_TYPE = "Доходы/расходы";
    static final String LINE = "Line";

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

    /** The accounts file: columns "Balance sheet items" (the name), "Актив/пассив" (the type) and "Line" (the code). */
    static Sheet<AccountRow> accounts(ImportFile file) {
        CsvTable table = CsvTable.read(file, List.of(ACCOUNT_NAME, ACCOUNT_TYPE, LINE));
        Sheet<AccountRow> sheet = new Sheet<>(file, table);
        Set<String> names = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (CsvTable.Row row : sheet.dataRows()) {
            List<String> errors = new ArrayList<>();
            String name = name(row, ACCOUNT_NAME, errors);
            // The journal names accounts in any case ("Moneycard V-Pay" for "Moneycard V-PAY"), so names are unique
            // whatever their case.
            String code = code(row, errors);
            String excelType = row.get(ACCOUNT_TYPE);
            AccountType type = code == null ? null : accountType(excelType, code);
            if (code != null && type == null) {
                errors.add("Актив/пассив is neither ASSET nor LIABILITIES");
            }
            unique(name == null ? null : name.toLowerCase(Locale.ROOT), names, "name", errors);
            unique(code, codes, "code", errors);
            if (errors.isEmpty()) {
                sheet.add(new AccountRow(row.number(), name, code, type, REQUIRE_COUNTERPARTY.contains(code)));
            } else {
                sheet.error(row, errors);
            }
        }
        return sheet;
    }

    /**
     * The categories file: columns "Статьи движения средств" (the name), "Доходы/расходы" (the type) and "Line" (the
     * code). The row whose code is NULL stands for "no category" and is skipped.
     */
    static Sheet<CategoryRow> categories(ImportFile file) {
        CsvTable table = CsvTable.read(file, List.of(CATEGORY_NAME, CATEGORY_TYPE, LINE));
        Sheet<CategoryRow> sheet = new Sheet<>(file, table);
        Set<String> names = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (CsvTable.Row row : sheet.dataRows()) {
            if (NULL.equals(row.get(LINE))) {
                sheet.skip(row, "Line NULL means \"no category\"");
                continue;
            }
            List<String> errors = new ArrayList<>();
            String name = name(row, CATEGORY_NAME, errors);
            String code = code(row, errors);
            String typeText = row.get(CATEGORY_TYPE);
            CategoryType type = switch (typeText) {
                case "INCOME" -> CategoryType.INCOME;
                case "EXPENSE" -> CategoryType.EXPENSE;
                default -> null;
            };
            if (type == null) {
                errors.add("Доходы/расходы is neither INCOME nor EXPENSE");
            }
            unique(name, names, "name", errors);
            unique(code, codes, "code", errors);
            if (errors.isEmpty()) {
                sheet.add(new CategoryRow(row.number(), name, code, type));
            } else {
                sheet.error(row, errors);
            }
        }
        return sheet;
    }

    /** The owner's journal; see {@link TransactionRow}. An ID in an earlier row too is an error of the later row. */
    static Sheet<TransactionRow> transactions(ImportFile file) {
        CsvTable table = CsvTable.read(file, TransactionRow.COLUMNS);
        Sheet<TransactionRow> sheet = new Sheet<>(file, table);
        Set<Long> ids = new HashSet<>();
        for (CsvTable.Row row : sheet.dataRows()) {
            List<String> errors = new ArrayList<>();
            TransactionRow transaction = TransactionRow.parse(row, errors);
            if (transaction != null && !ids.add(transaction.id())) {
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
     * The partner's family rows as the import page uploads them (D-78, D-140). The whole file is refused if it has any
     * column but {@link PartnerRow#COLUMNS}, or any row that isn't a family row with an item: then something other than
     * the page's filter made it, and nothing of it is read.
     *
     * @throws PartnerFileRefused naming the columns and the rows (by number only) that break the filter
     */
    static Sheet<PartnerRow> partner(ImportFile file) {
        // Read with no column required, so that a file with other columns, such as his raw transactions file, is
        // refused for them rather than read as one with columns missing.
        CsvTable table = CsvTable.read(file, List.of());
        List<String> refusals = new ArrayList<>();
        table.rows().stream().findFirst().ifPresent(row -> {
            if (!Set.copyOf(PartnerRow.COLUMNS).containsAll(row.columns().keySet())) {
                refusals.add("the partner's file has a column the import doesn't take: only "
                        + String.join(", ", PartnerRow.COLUMNS) + " leave the laptop");
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

    private static String name(CsvTable.Row row, String column, List<String> errors) {
        String name = required(row, column, Function.identity(), errors);
        if (name != null && name.codePointCount(0, name.length()) > NAME_MAX_LENGTH) {
            errors.add("%s is longer than %d characters".formatted(column, NAME_MAX_LENGTH));
        }
        return name;
    }

    private static String code(CsvTable.Row row, List<String> errors) {
        String code = required(row, LINE, Workbook::code, errors);
        if (code != null && code.length() > CODE_MAX_LENGTH) {
            errors.add("Line is longer than %d characters".formatted(CODE_MAX_LENGTH));
        }
        return code;
    }

    private static void unique(String value, Set<String> seen, String what, List<String> errors) {
        if (value != null && !seen.add(value)) {
            errors.add("the %s is in an earlier row too".formatted(what));
        }
    }

    /** An account as the accounts file defines it. */
    record AccountRow(int row, String name, String code, AccountType type, boolean requiresCounterparty) {
    }

    /** A category as the categories file defines it. */
    record CategoryRow(int row, String name, String code, CategoryType type) {
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
