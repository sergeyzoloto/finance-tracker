package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.report.AccountBalance;
import com.example.financetracker.ledger.report.IntegrityViolation;

/**
 * What one run of the importer did, or in a dry run would have done. {@link ImportReportMarkdown} renders it.
 *
 * @param entriesByKind the entries written, per kind, in the order of {@link EntryKind}
 * @param errors rows that can't be imported as they are. One or more of them abort a commit.
 * @param skipped rows left out on purpose, such as rows imported before
 * @param zeroFxRowsSkipped the rows of the FX-gain category with Sum 0, which are among {@code skipped}
 * @param fxRowsToReview the rows of the FX-gain category with a Sum, imported as income
 * @param balances the displayed balance of every account after the import, as the ledger would have it after a dry
 *        run too
 * @param integrityViolations {@code ReportService.integrityCheck} after the import; empty for a sound ledger
 */
public record ImportReport(String userId, boolean commit, Outcome outcome, Instant startedAt, List<FileSummary> files,
        Map<EntryKind, Integer> entriesByKind, ReferenceCounts referenceData, List<Problem> errors,
        List<Problem> warnings, List<Problem> skipped, int zeroFxRowsSkipped, List<FxRow> fxRowsToReview,
        List<AccountBalance> balances, List<IntegrityViolation> integrityViolations) {

    public enum Outcome {
        /** Everything was rolled back, as a dry run always is. */
        DRY_RUN,
        COMMITTED,
        /** A commit with row errors: everything was rolled back. */
        ABORTED
    }

    public int entriesWritten() {
        return entriesByKind.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * @param role which of the importer's inputs the file is, such as "transactions"
     * @param rows its data rows, without the header
     */
    public record FileSummary(String role, String name, String sha256, int rows) {
    }

    public record ReferenceCounts(int accountsCreated, int accountsUpdated, int categoriesCreated,
            int categoriesUpdated, int counterpartiesCreated) {
    }

    /**
     * An error, warning or skipped row.
     *
     * @param row the row in the spreadsheet, where the header is row 1; null for the file as a whole
     */
    public record Problem(String file, Integer row, String message) {
    }

    public record FxRow(int row, LocalDate date, BigDecimal sum, String currency, String debit, String credit,
            String comment) {
    }
}
