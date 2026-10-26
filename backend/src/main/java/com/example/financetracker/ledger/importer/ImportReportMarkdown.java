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
 * An {@link ImportReport} as Markdown, for the user to read (the command line's report). It names rows by their
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
        if (report.upTo() != null) {
            md.append("- Import up to: ").append(report.upTo()).append(" (D-165); rows after it, neither imported nor "
                    + "missing:");
            report.afterCutoff().forEach(c -> md.append(" %s %d of %d".formatted(c.source(), c.afterCutoff(),
                    c.rows())));
            md.append('\n');
        }
        if (report.family() != null) {
            md.append((switch (report.family().mode()) {
                case "MEMBER" -> "- Family budget %d, your own seat %d (D-175); new family records split with member %d\n"
                        .formatted(report.family().ledgerId(), report.family().ownerMemberId(),
                                report.family().seatMemberId());
                case "OWNER_AFTER_CLAIM" -> "- Family budget %d, seat %d (the partner's, with an account: his rows are his own import's, D-175)\n"
                        .formatted(report.family().ledgerId(), report.family().seatMemberId());
                default -> "- Family budget %d, seat %d (the partner's)\n".formatted(report.family().ledgerId(),
                        report.family().seatMemberId());
            }));
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

        chart(md, report.chart());
        if (!report.idGaps().isEmpty()) {
            md.append("\nGaps in the Excel IDs (D-231): ").append(String.join(", ", report.idGaps())).append('\n');
        }
        member(md, report.member());
        rows(md, "Missing from the files within their period (D-138, D-256)", report.missing());
        rows(md, "Conflicts", report.conflicts());
        sync(md, report.sync());
        messages(md, "Errors", report.errorSummary());
        messages(md, "Warnings", report.warningSummary());
        messages(md, "Skipped and not imported", report.skippedSummary());
        reconciliation(md, report.reconciliation());
        md.append("\n## Integrity check\n\n").append(report.integrityViolations().isEmpty()
                ? "Every currency adds up." : report.integrityViolations().size() + " violation(s).").append('\n');
        return md.toString();
    }

    /** A member's own import (D-175 to D-181, D-188). */
    private static void member(StringBuilder md, ImportReport.MemberSummary m) {
        if (m == null) {
            return;
        }
        md.append("\n## Your family rows, settlements and benefit rows\n\n");
        table(md, List.of("", "Rows"), "|---|---:|", List.of(
                List.of("Family rows", n(m.familyRows())),
                List.of("Family records unchanged", n(m.recordsUnchanged())),
                List.of("Family records edited (D-190)", n(m.recordsEdited().size())),
                List.of("Family records created", n(m.recordsCreated())),
                List.of("Family records in conflict", n(m.recordConflicts())),
                List.of("Payment side attached (D-181)", n(m.sidesAttached())),
                List.of("Payment side unchanged", n(m.sidesUnchanged())),
                List.of("Side edited", n(m.sidesEdited())),
                List.of("Side in conflict", n(m.sideConflicts())),
                List.of("Settlement rows", n(m.settlementRows())),
                List.of("Matched by date and amount (D-178, 1)", n(m.tier1())),
                List.of("Matched to several on the date (2)", n(m.tier2())),
                List.of("Matched within 7 days (3)", n(m.tier3())),
                List.of("Several of your rows against one on the date (D-198)", n(m.tier2Many())),
                List.of("Matched by their date as a whole, in the counts above (D-202)", n(m.byDate())),
                List.of("Ambiguous on their date (D-202)", n(m.ambiguous())),
                List.of("Known from an earlier import", n(m.settlementsKnown())),
                List.of("Conversions", n(m.conversions())),
                List.of("Conversions matched", n(m.conversionsMatched())),
                List.of("Rows without a counterpart", n(m.unmatched())),
                List.of("The other member's settlements without your side", n(m.ownerSettlementsWithoutSide())),
                List.of("Benefit rows (D-179)", n(m.benefitRows())),
                List.of("FAMILY_EXP or FAMILY_INC not your share (D-177)", n(m.shareWarnings()))));
        if (!m.recordsEdited().isEmpty()) {
            md.append("\nEdited family records, by Excel ID: ").append(String.join(", ", m.recordsEdited())).append('\n');
        }
        long differ = m.benefitMonths().stream().filter(ImportReport.BenefitMonth::differs).count();
        md.append("\nBenefit months compared: %d, differing: %d (D-179).\n".formatted(m.benefitMonths().size(), differ));
        md.append("\nMatched settlements on either side of a month-end (D-188): %d.\n".formatted(m.straddling().size()));
        if (!m.straddling().isEmpty()) {
            List<List<String>> table = new ArrayList<>();
            m.straddling().forEach(p -> table.add(List.of(p.excelId(), p.rowDate().toString(),
                    p.settlementDate().toString(), p.currency(), p.amount().toPlainString())));
            table(md, List.of("Excel ID", "Your row", "The settlement", "Currency", "Amount"), "|---|---|---|---|---:|",
                    table);
        }
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

    /** D-219's groups, D-223's amounts and the categories merged (D3e). */
    private static void sync(StringBuilder md, ImportReport.Sync sync) {
        rows(md, "Changed in the app, the file unchanged (D-219)", sync.appEdited());
        if (!sync.appEdits().isEmpty()) {
            md.append("\nExplained as APP_EDITED (D-223):\n\n");
            List<List<String>> table = new ArrayList<>();
            sync.appEdits().forEach(e -> table.add(List.of(e.excelId(), e.date().toString(), e.account(),
                    e.currency(), e.amount().toPlainString())));
            table(md, List.of("Excel ID", "Date", "Account", "Currency", "Amount"), "|---|---|---|---|---:|", table);
        }
        rows(md, "Re-planned: the current code maps it differently (D-219)", sync.replanned());
        rows(md, "Hash stored again (D-219)", sync.restamped());
        rows(md, "Can't be re-planned after the claim (D-196, D-219)", sync.notReplannable());
        md.append("\nRows the app made, as the ledger holds them (D-259): %d.\n".formatted(sync.appRowsUnchanged()));
        rows(md, "Rows the app made, listed and never written (D-259)", sync.appRows());
        md.append("\nPersonal categories merged into the family budget's (D-11): ")
                .append(sync.categoriesMerged().isEmpty() ? "none" : String.join(", ", sync.categoriesMerged()))
                .append(".\n");
    }

    /** The accounts and categories the file's rows name that the import creates or renames (D-228, D-254). */
    private static void chart(StringBuilder md, ImportReport.ChartChanges chart) {
        md.append("\n## Accounts and categories created (%d) and renamed (%d)\n\n".formatted(chart.created().size(),
                chart.renamed().size()));
        if (!chart.created().isEmpty()) {
            List<List<String>> table = new ArrayList<>();
            chart.created().forEach(c -> table.add(List.of(c.kind(), c.code(), c.name(), c.type())));
            table(md, List.of("Kind", "Code", "Name", "Type"), "|---|---|---|---|", table);
        }
        if (!chart.renamed().isEmpty()) {
            md.append('\n');
            List<List<String>> table = new ArrayList<>();
            chart.renamed().forEach(r -> table.add(List.of(r.kind(), r.code(), r.from(), r.to())));
            table(md, List.of("Kind", "Code", "Name in the app", "Name in the file"), "|---|---|---|---|", table);
        }
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
        md.append("\n## Reconciliation with the transactions file (D-231)\n\n");
        if (r == null) {
            md.append("Not run.\n");
            return;
        }
        ImportReport.RowLevel rows = r.rows();
        md.append(("**%s.** Rows: %d checked, %d built and compared, %d explained, %d failed. Month-ends: %d points "
                + "compared from %s to %s, the movements since the day before the first row date (D-256); "
                + "FAMILY_DEBT, UNALLOCATED and RESERVE as one sum per currency (D-255).\n\n").formatted(
                        r.passed() ? "Passed" : "FAILED", rows.rows(), rows.built(), rows.explainedRows(),
                        rows.failed(), r.pointsCompared(), r.from(), r.cutoff()));
        List<List<String>> lines = new ArrayList<>();
        for (ReconciliationLine line : r.lines()) {
            lines.add(List.of(line.account(), line.currency(), n(line.compared()), n(line.failed()),
                    String.valueOf(line.firstFailure() == null ? "" : line.firstFailure()),
                    line.worstUnexplained().toPlainString(), plain(line.lastExcel()), plain(line.lastApp()),
                    plain(line.lastExplained())));
        }
        table(md, List.of("Account", "Currency", "Points", "Failed", "First failure", "Worst unexplained",
                "File (to the cutoff)", "App (to the cutoff)", "Explained"), "|---|---|---:|---:|---|---:|---:|---:|---:|",
                lines);
        if (!r.explained().isEmpty()) {
            md.append("\nExplained at the cutoff (D-68, D-223, D-188, NO_ROW):\n\n");
            List<List<String>> explained = new ArrayList<>();
            r.explained().forEach(e -> explained.add(List.of(e.account(), e.currency(), e.reason(),
                    e.amount().toPlainString())));
            table(md, List.of("Account", "Currency", "Rule", "Amount"), "|---|---|---|---:|", explained);
        }
        if (!rows.explained().isEmpty()) {
            md.append("\nExplained in the rows:\n\n");
            List<List<String>> explained = new ArrayList<>();
            rows.explained().forEach(e -> explained.add(List.of(e.account(), e.currency(), e.reason(),
                    e.amount().toPlainString())));
            table(md, List.of("Account", "Currency", "Rule", "Amount"), "|---|---|---|---:|", explained);
        }
        md.append("\n### Rows that fail (").append(rows.failed()).append(")\n\n");
        if (!rows.failures().isEmpty()) {
            List<List<String>> table = new ArrayList<>();
            rows.failures().stream().limit(200).forEach(d -> table.add(List.of(d.excelId(), d.what(), plain(d.file()),
                    plain(d.computed()), plain(d.explained()), plain(d.unexplained()))));
            table(md, List.of("ID", "What", "File", "Computed or built", "Explained", "Unexplained"),
                    "|---|---|---:|---:|---:|---:|", table);
        }
        points(md, "Failures", r.failures());
        if (!r.appOnly().isEmpty()) {
            md.append("\nThe app's accounts that moved in a currency no row of the file has: ")
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
