package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.importer.ImportReport.FileSummary;
import com.example.financetracker.ledger.importer.ImportReport.FxRow;
import com.example.financetracker.ledger.importer.ImportReport.Problem;
import com.example.financetracker.ledger.report.AccountBalance;
import com.example.financetracker.ledger.report.IntegrityViolation;

/** An {@link ImportReport} as Markdown, for the owner to read. */
public final class ImportReportMarkdown {

    private ImportReportMarkdown() {
    }

    public static String render(ImportReport report) {
        StringBuilder md = new StringBuilder("# Import report\n\n");
        md.append(switch (report.outcome()) {
            case DRY_RUN -> "**Dry run:** everything was rolled back, nothing was saved. Run with `--commit` to save.";
            case COMMITTED -> "**Committed:** the entries below were saved.";
            case ABORTED -> "**Aborted:** %d row error(s), so nothing was saved. Fix them and run again."
                    .formatted(report.errors().size());
        }).append("\n\n");
        md.append("- User (Keycloak sub): `").append(report.userId()).append("`\n");
        md.append("- Started: ").append(report.startedAt()).append('\n');
        md.append("- Files:\n");
        for (FileSummary file : report.files()) {
            md.append("  - %s: `%s`, %d rows, SHA-256 `%s`\n".formatted(file.role(), file.name(), file.rows(),
                    file.sha256()));
        }

        var reference = report.referenceData();
        md.append("\n## Summary\n\n");
        table(md, List.of("", "Count"), "|---|---:|", List.of(
                List.of("Entries " + (report.outcome() == ImportReport.Outcome.COMMITTED ? "saved" : "written, then rolled back"),
                        String.valueOf(report.entriesWritten())),
                List.of("Row errors", String.valueOf(report.errors().size())),
                List.of("Warnings", String.valueOf(report.warnings().size())),
                List.of("Skipped rows", String.valueOf(report.skipped().size())),
                List.of("… of them FX gains with Sum 0", String.valueOf(report.zeroFxRowsSkipped())),
                List.of("FX rows to review", String.valueOf(report.fxRowsToReview().size())),
                List.of("Accounts created / updated",
                        reference.accountsCreated() + " / " + reference.accountsUpdated()),
                List.of("Categories created / updated",
                        reference.categoriesCreated() + " / " + reference.categoriesUpdated()),
                List.of("Counterparties created", String.valueOf(reference.counterpartiesCreated()))));

        md.append("\n### Entries per kind\n\n");
        List<List<String>> kinds = new ArrayList<>();
        report.entriesByKind().forEach((kind, count) -> kinds.add(List.of(kind.name(), String.valueOf(count))));
        kinds.add(List.of("**Total**", "**" + report.entriesWritten() + "**"));
        table(md, List.of("Kind", "Entries"), "|---|---:|", kinds);

        md.append("\n## Row errors\n\n");
        md.append("Rows are spreadsheet rows: the header is row 1.\n\n");
        problems(md, report.errors(), "Reason");

        md.append("\n## Warnings\n\n");
        problems(md, report.warnings(), "Warning");

        md.append("\n## Skipped rows\n\n");
        if (report.skipped().isEmpty()) {
            md.append("None.\n");
        } else {
            Map<List<String>, List<Integer>> groups = new LinkedHashMap<>();
            for (Problem skip : report.skipped()) {
                List<Integer> rows = groups.computeIfAbsent(
                        List.of(Objects.toString(skip.file(), ""), skip.message()), key -> new ArrayList<>());
                if (skip.row() != null) {
                    rows.add(skip.row());
                }
            }
            List<List<String>> lines = new ArrayList<>();
            groups.forEach((key, rows) -> lines.add(List.of(key.get(0), ranges(rows), key.get(1))));
            table(md, List.of("File", "Rows", "Reason"), "|---|---|---|", lines);
        }

        md.append("\n## FX rows to review\n\n");
        md.append("Rows of the category \"").append(EntryMapper.FX_GAIN_CATEGORY)
                .append("\" with a Sum, imported as income. Under rule 9 an exchange's gain is the balance of ")
                .append("FX_EXCHANGE, so these may count it twice.\n\n");
        if (report.fxRowsToReview().isEmpty()) {
            md.append("None.\n");
        } else {
            List<List<String>> lines = new ArrayList<>();
            for (FxRow row : report.fxRowsToReview()) {
                lines.add(List.of(String.valueOf(row.row()), ExcelValues.format(row.date()), amount(row.sum()),
                        row.currency(), row.debit(), row.credit(), Objects.toString(row.comment(), "")));
            }
            table(md, List.of("Row", "Date", "Sum", "Currency", "Debit", "Credit", "Comment"),
                    "|---:|---|---:|---|---|---|---|", lines);
        }

        md.append("\n## Balances\n\n");
        md.append("Displayed balances (rule 4) of every account after the import, per currency")
                .append(report.outcome() == ImportReport.Outcome.COMMITTED ? ".\n\n"
                        : ", as the import would have left them.\n\n");
        if (report.balances().isEmpty()) {
            md.append("None.\n");
        } else {
            List<List<String>> lines = new ArrayList<>();
            for (AccountBalance balance : report.balances()) {
                lines.add(List.of(balance.accountCode(), balance.accountName(), balance.accountType().name(),
                        balance.currency(), amount(balance.balance())));
            }
            table(md, List.of("Code", "Account", "Type", "Currency", "Balance"), "|---|---|---|---|---:|", lines);
        }

        md.append("\n## Integrity check\n\n");
        if (report.integrityViolations().isEmpty()) {
            md.append("OK: in every currency the postings sum to zero, and assets − liabilities − equity is zero.\n");
        } else {
            List<List<String>> lines = new ArrayList<>();
            for (IntegrityViolation violation : report.integrityViolations()) {
                lines.add(List.of(violation.currency(), amount(violation.postingSum()),
                        amount(violation.balanceSheetGap())));
            }
            md.append("**Failed** in these currencies:\n\n");
            table(md, List.of("Currency", "Sum of postings", "Assets − liabilities − equity"), "|---|---:|---:|",
                    lines);
        }
        return md.toString();
    }

    private static void problems(StringBuilder md, List<Problem> problems, String what) {
        if (problems.isEmpty()) {
            md.append("None.\n");
            return;
        }
        List<List<String>> lines = new ArrayList<>();
        for (Problem problem : problems) {
            lines.add(List.of(Objects.toString(problem.file(), "—"), Objects.toString(problem.row(), "—"),
                    problem.message()));
        }
        table(md, List.of("File", "Row", what), "|---|---:|---|", lines);
    }

    private static void table(StringBuilder md, List<String> header, String separator, List<List<String>> rows) {
        md.append(row(header)).append(separator).append('\n');
        rows.forEach(row -> md.append(row(row)));
    }

    private static String row(List<String> cells) {
        StringBuilder line = new StringBuilder("|");
        for (String cell : cells) {
            line.append(' ').append(cell.replace("|", "\\|").replace('\n', ' ')).append(" |");
        }
        return line.append('\n').toString();
    }

    /** Row numbers as ranges, such as "2–40, 42". */
    static String ranges(List<Integer> rows) {
        if (rows.isEmpty()) {
            return "—";
        }
        List<Integer> sorted = rows.stream().sorted().toList();
        List<String> ranges = new ArrayList<>();
        int start = sorted.getFirst();
        int end = start;
        for (int row : sorted.subList(1, sorted.size())) {
            if (row != end + 1) {
                ranges.add(start == end ? String.valueOf(start) : start + "–" + end);
                start = row;
            }
            end = row;
        }
        ranges.add(start == end ? String.valueOf(start) : start + "–" + end);
        return String.join(", ", ranges);
    }

    private static String amount(BigDecimal amount) {
        return Money.normalize(amount).toPlainString();
    }
}
