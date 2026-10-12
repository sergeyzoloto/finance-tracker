package com.example.financetracker.ledger.importer;

import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.ledger.importer.ImportReport.Count;
import com.example.financetracker.ledger.importer.ImportReport.FileSummary;
import com.example.financetracker.ledger.importer.ImportReport.MessageCount;
import com.example.financetracker.ledger.importer.ImportReport.Reconciliation;
import com.example.financetracker.ledger.importer.ImportReport.ReconciliationLine;
import com.example.financetracker.ledger.importer.ImportReport.ReconciliationPoint;
import com.example.financetracker.ledger.importer.ImportReport.RowRef;

/**
 * An {@link ImportReport} as Markdown, for the owner to read (the command line's report). It names rows by their
 * number and Excel ID, as the report does, and holds no value of a row but the reconciliation's balances.
 */
public final class ImportReportMarkdown {

    private ImportReportMarkdown() {
    }

    public static String render(ImportReport report) {
        StringBuilder md = new StringBuilder("# Import report\n\n");
        md.append(switch (report.outcome()) {
            case DRY_RUN -> "**Dry run:** everything was rolled back, nothing was saved. Run with `--commit` to save.";
            case COMMITTED -> "**Committed:** the rows below were saved.";
            case ABORTED -> "**Aborted:** %d row error(s), so nothing was saved. Fix them and run again."
                    .formatted(report.errors().size());
        }).append("\n\n");
        md.append("- Started: ").append(report.startedAt()).append('\n');
        if (report.period() != null) {
            md.append("- Rows from ").append(report.period().from()).append(" to ").append(report.period().to())
                    .append('\n');
        }
        md.append("- Currencies: ").append(String.join(", ", report.currencies())).append('\n');
        if (report.family() != null) {
            md.append("- Family budget %d, seat %d (the partner's)\n".formatted(report.family().ledgerId(),
                    report.family().seatMemberId()));
        }
        md.append("- Files:\n");
        for (FileSummary file : report.files()) {
            md.append("  - %s: %d rows, SHA-256 `%s`\n".formatted(file.role(), file.rows(), file.sha256()));
        }

        var totals = report.totals();
        md.append("\n## Summary\n\n");
        table(md, List.of("", "Rows"), "|---|---:|", List.of(
                List.of("Created", String.valueOf(totals.created())),
                List.of("Updated (D-139)", String.valueOf(totals.updated())),
                List.of("Unchanged", String.valueOf(totals.unchanged())),
                List.of("Missing from the files (D-138)", String.valueOf(totals.missing())),
                List.of("Conflicts (D-139, D-150)", String.valueOf(totals.conflicts())),
                List.of("Not imported (D-84)", String.valueOf(totals.notImported())),
                List.of("Skipped", String.valueOf(totals.skipped())),
                List.of("Row errors", String.valueOf(report.errors().size())),
                List.of("Warnings", String.valueOf(report.warnings().size()))));

        md.append("\n### By source and kind\n\n");
        List<List<String>> counts = new ArrayList<>();
        for (Count c : report.counts()) {
            counts.add(List.of(c.source(), c.kind(), n(c.created()), n(c.updated()), n(c.unchanged()),
                    n(c.conflicts()), n(c.failed()), n(c.skipped()), n(c.notImported())));
        }
        table(md, List.of("Source", "Kind", "Created", "Updated", "Unchanged", "Conflicts", "Errors", "Skipped",
                "Not imported"), "|---|---|---:|---:|---:|---:|---:|---:|---:|", counts);

        var reference = report.referenceData();
        md.append(("\nAccounts: %d created, %d updated. Categories: %d created, %d updated; family categories %d "
                + "created, %d personal ones merged into them. Counterparties: %d created.\n").formatted(
                        reference.accountsCreated(), reference.accountsUpdated(), reference.categoriesCreated(),
                        reference.categoriesUpdated(), reference.familyCategoriesCreated(),
                        reference.categoriesMerged(), reference.counterpartiesCreated()));

        rows(md, "Missing from the files (D-138)", report.missing());
        rows(md, "Conflicts", report.conflicts());
        messages(md, "Errors", report.errorSummary());
        messages(md, "Warnings", report.warningSummary());
        messages(md, "Skipped and not imported", report.skippedSummary());
        reconciliation(md, report.reconciliation());
        md.append("\n## Integrity check\n\n").append(report.integrityViolations().isEmpty()
                ? "Every currency adds up." : report.integrityViolations().size() + " violation(s).").append('\n');
        return md.toString();
    }

    private static void rows(StringBuilder md, String title, List<RowRef> rows) {
        md.append("\n## ").append(title).append(" (").append(rows.size()).append(")\n\n");
        if (rows.isEmpty()) {
            md.append("None.\n");
            return;
        }
        List<List<String>> table = new ArrayList<>();
        rows.forEach(r -> table.add(List.of(r.source(), r.excelId(), r.reason())));
        table(md, List.of("Source", "Excel ID", "Why"), "|---|---|---|", table);
    }

    private static void messages(StringBuilder md, String title, List<MessageCount> messages) {
        md.append("\n## ").append(title).append("\n\n");
        if (messages.isEmpty()) {
            md.append("None.\n");
            return;
        }
        List<List<String>> table = new ArrayList<>();
        messages.forEach(m -> table.add(List.of(m.message(), n(m.count()), String.join(", ", m.rows()))));
        table(md, List.of("Message", "Rows", "First Excel IDs"), "|---|---:|---|", table);
    }

    private static void reconciliation(StringBuilder md, Reconciliation r) {
        md.append("\n## Reconciliation with the daily balance file\n\n");
        if (r == null) {
            md.append("Not run: no daily balance file could be read.\n");
            return;
        }
        md.append(("**%s.** %d points compared up to %s; the files are read up to %s (D-146); from %s on, nothing "
                + "fails (D-69).\n\n").formatted(r.passed() ? "Passed" : "FAILED", r.pointsCompared(), r.lastCompared(),
                        r.cutoff(), r.tailFrom()));
        List<List<String>> lines = new ArrayList<>();
        for (ReconciliationLine line : r.lines()) {
            lines.add(List.of(line.account(), line.currency(), n(line.compared()), n(line.failed()),
                    String.valueOf(line.firstFailure() == null ? "" : line.firstFailure()),
                    line.worstUnexplained().toPlainString(), plain(line.lastExcel()), plain(line.lastApp()),
                    plain(line.lastExplained())));
        }
        table(md, List.of("Account", "Currency", "Points", "Failed", "First failure", "Worst unexplained",
                "Excel (last)", "App (last)", "Explained (last)"), "|---|---|---:|---:|---|---:|---:|---:|---:|", lines);
        if (!r.withoutFamilyRows().isEmpty()) {
            md.append("\nCurrencies without family rows (D-147): FAMILY_DEBT and the equity compared as one sum.\n\n");
            List<List<String>> free = new ArrayList<>();
            r.withoutFamilyRows().forEach(c -> free.add(List.of(c.currency(), plain(c.appFamilyDebt()),
                    plain(c.excelFamilyDebt()), c.ok() ? "ok" : "NOT 0")));
            table(md, List.of("Currency", "App FAMILY_DEBT", "Excel FAMILY_DEBT (explained)", "App's is 0"),
                    "|---|---:|---:|---|", free);
        }
        if (!r.explained().isEmpty()) {
            md.append("\nExplained at the last point compared (D-68):\n\n");
            List<List<String>> explained = new ArrayList<>();
            r.explained().forEach(e -> explained.add(List.of(e.account(), e.currency(), e.reason(),
                    e.amount().toPlainString())));
            table(md, List.of("Account", "Currency", "Rule", "Amount"), "|---|---|---|---:|", explained);
        }
        points(md, "Failures", r.failures());
        points(md, "The tail, not failing (D-69)", r.tail());
        if (!r.appOnly().isEmpty()) {
            md.append("\nThe app's accounts with a balance and no column in Excel: ")
                    .append(String.join(", ", r.appOnly())).append('\n');
        }
    }

    private static void points(StringBuilder md, String title, List<ReconciliationPoint> points) {
        md.append("\n### ").append(title).append(" (").append(points.size()).append(")\n\n");
        if (points.isEmpty()) {
            return;
        }
        List<List<String>> table = new ArrayList<>();
        points.stream().limit(200).forEach(p -> table.add(List.of(p.date().toString(), p.account(), p.currency(),
                plain(p.excel()), plain(p.app()), plain(p.explained()), plain(p.unexplained()))));
        table(md, List.of("Date", "Account", "Currency", "Excel", "App", "Explained", "Unexplained"),
                "|---|---|---|---:|---:|---:|---:|", table);
    }

    private static String plain(java.math.BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }

    private static String n(int count) {
        return String.valueOf(count);
    }

    private static void table(StringBuilder md, List<String> header, String rule, List<List<String>> rows) {
        md.append("| ").append(String.join(" | ", header)).append(" |\n").append(rule).append('\n');
        for (List<String> row : rows) {
            md.append("| ").append(String.join(" | ", row.stream().map(ImportReportMarkdown::cell).toList()))
                    .append(" |\n");
        }
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }
}
