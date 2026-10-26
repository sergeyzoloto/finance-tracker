package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.report.IntegrityViolation;

/**
 * What one run of the importer did, or in a dry run would have done (D3b). {@link ImportReportMarkdown} renders it, and
 * the import page shows it. It names rows by their number and Excel ID; no message holds a value of a row.
 *
 * @param family the family budget and the partner's seat the family rows went into; null without one
 * @param period the first and the last date of the rows read (up to {@code upTo})
 * @param upTo "Import up to" (D-165); null for none
 * @param afterCutoff per transactions file (OWNER, PARTNER), the rows read and those dated after {@code upTo}, which
 *        are neither imported nor missing (D-165); empty without {@code upTo}
 * @param currencies every currency of the rows read
 * @param counts per source and kind, what happened to its rows (D-139)
 * @param totals the sums of {@code counts}, with the missing rows (D-138)
 * @param missing rows the ledger has from an earlier import, dated within the file's period (D-256), that the files no
 *        longer have (D-138): reported, never deleted
 * @param conflicts rows changed in the app since their last import, or deleted there (D-139, D-150, D-113): left alone
 * @param errors rows that can't be imported as they are. One or more of them abort a commit.
 * @param errorSummary, warningSummary, skippedSummary each distinct message with its count and the first Excel IDs
 * @param chart the accounts and categories the file's rows name that the import creates or renames (D-228, D-254)
 * @param idGaps the Excel IDs missing between the file's lowest and highest, as ranges (D-231 c): a warning
 * @param reconciliation the ledger against the transactions file, per row and per month-end (D-231, D-255, D-256)
 * @param integrityViolations {@code ReportService.integrityCheck} after the import; empty for a sound ledger
 * @param member what a member's own import did with his family, settlement and benefit rows (D-175 to D-181); null
 *        for the owner's
 * @param sync D-219's groups of rows known from an earlier import, and the personal categories merged into the family
 *        budget's (D-11, D3e)
 */
public record ImportReport(String userId, boolean commit, Outcome outcome, Instant startedAt, List<FileSummary> files,
        FamilyTarget family, Period period, LocalDate upTo, List<AfterCutoff> afterCutoff, List<String> currencies, List<Count> counts, Totals totals,
        List<RowRef> missing, List<RowRef> conflicts, List<Problem> errors, List<Problem> warnings,
        List<MessageCount> errorSummary, List<MessageCount> warningSummary, List<MessageCount> skippedSummary,
        ReferenceCounts referenceData, ChartChanges chart, List<String> idGaps, int zeroFxRowsSkipped,
        List<FxRow> fxRowsToReview, Reconciliation reconciliation, List<IntegrityViolation> integrityViolations,
        MemberSummary member, Sync sync) {

    public enum Outcome {
        /** Everything was rolled back, as a dry run always is. */
        DRY_RUN,
        COMMITTED,
        /** A commit with row errors: everything was rolled back. */
        ABORTED
    }

    /** What a commit would write or wrote: created and updated entries and records. */
    public int written() {
        return totals.created() + totals.updated();
    }

    /**
     * @param role which of the importer's inputs the file is, such as "transactions"
     * @param rows its data rows, without the header
     */
    public record FileSummary(String role, String name, String sha256, int rows) {
    }

    /**
     * The family budget and the seats, by their names in the app (data, never in tracked files, D-141).
     *
     * @param ownerMemberId the uploader's own seat: the owner's, or in a member's own import his (D-175)
     * @param seatMemberId the other seat: the partner's place, or in a member's own import the member he splits his
     *        new family records with
     * @param mode {@code OWNER} for the owner's import with a place without an account, whose rows he uploads;
     *        {@code OWNER_AFTER_CLAIM} once that place has an account and the owner imports only his own file; and
     *        {@code MEMBER} for a member's own import (D-175)
     */
    public record FamilyTarget(long ledgerId, String ledgerName, long ownerMemberId, long seatMemberId,
            String seatName, String mode) {
    }

    /**
     * A member's own import (D-175 to D-181, D-188): what became of his family rows, his settlement rows, his
     * conversions and his benefit rows. The errors and warnings themselves are the report's.
     *
     * @param recordsEdited the Excel IDs of his family rows whose records the import changed (D-190)
     * @param sidesAttached his payment side put on a record for the first time (D-181)
     * @param tier1 settlements matched by date and amount, {@code tier2} one of his rows against several on its date,
     *        {@code tier3} by amount within 7 days (D-178); {@code tier2Many} several rows of his against one on its date
     *        (D-198), counted once per settlement
     * @param byDate his rows matched by resolving a tied date as a whole (D-202), also counted in their tier
     * @param ambiguous his rows refused as AMBIGUOUS_SETTLEMENT (D-202)
     * @param settlementsKnown settlement rows of an earlier import of his, recognised by their Excel ID
     * @param ownerSettlementsWithoutSide the other member's settlements from his join date on without his side (D-178)
     * @param benefitMonths per month and currency, his benefit rows against his shares of the family incomes the other
     *        members received (D-179)
     * @param straddling matched settlements whose two dates lie on either side of a month-end (D-188)
     */
    public record MemberSummary(int familyRows, int recordsUnchanged, List<String> recordsEdited, int recordsCreated,
            int recordConflicts, int sidesAttached, int sidesUnchanged, int sidesEdited, int sideConflicts,
            int settlementRows, int tier1, int tier2, int tier3, int tier2Many, int byDate, int ambiguous,
            int settlementsKnown, int conversions,
            int conversionsMatched, int unmatched, int ownerSettlementsWithoutSide, int benefitRows,
            List<BenefitMonth> benefitMonths, List<Straddling> straddling, int shareWarnings) {
    }

    /** D-179: a month's benefit rows and his shares of the incomes others received, in one currency. */
    public record BenefitMonth(LocalDate month, String currency, int rows, BigDecimal benefitRows, BigDecimal shares,
            boolean differs) {
    }

    /** D-188: a settlement row of his and the settlement it matched, on either side of a month-end. */
    public record Straddling(String excelId, LocalDate rowDate, LocalDate settlementDate, String currency,
            BigDecimal amount) {
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    /**
     * D-219: what became of rows an earlier import wrote, beyond unchanged, updated and in conflict. None of these
     * groups stops an import by itself.
     *
     * @param appEdited "changed in the app, the file unchanged": what the current code maps the row to isn't what the
     *        ledger holds, the row is as imported last, and the entry or record was changed in the app since. Left
     *        alone, never written over
     * @param appEdits D-223: per row of {@code appEdited} that is a personal entry, account and currency, what the
     *        ledger holds less what the row maps to, which the reconciliation explains as APP_EDITED
     * @param replanned "re-planned: the current code maps it differently": the row is as imported last, nobody changed
     *        it in the app, and the current code maps it otherwise; written over as an edited row
     * @param restamped the ledger holds what the row maps to, but under another hash: the hash is stored again, nothing
     *        else is written
     * @param notReplannable "can't be re-planned after the claim" (D-196): the owner's D-73 rows, whose records the
     *        partner's place pays; listed, never written (D-83's lumps aren't read since D4b, D-232)
     * @param categoriesMerged the codes of the personal categories, unused, merged into the family budget's category of
     *        the same code (D-11, D3e); a dry run reports them, a commit merges them
     * @param appRowsUnchanged the rows the app made ({@code app-…}) that are what the ledger holds (D-259)
     * @param appRows the rows the app made that aren't: changed in the file, or not a row of the user's; listed, never
     *        written (D-259)
     */
    public record Sync(List<RowRef> appEdited, List<AppEdit> appEdits, List<RowRef> replanned, List<RowRef> restamped,
            List<RowRef> notReplannable, List<String> categoriesMerged, int appRowsUnchanged, List<RowRef> appRows) {
    }

    /**
     * The accounts and categories the file's rows name that the import creates, and those whose name it changes to the
     * file's (D-228, D-229, D-254). A family category keeps its first name.
     */
    public record ChartChanges(List<ChartItem> created, List<Rename> renamed) {
    }

    /** @param kind ACCOUNT, CATEGORY or FAMILY_CATEGORY */
    public record ChartItem(String kind, String code, String name, String type) {
    }

    public record Rename(String kind, String code, String from, String to) {
    }

    /** D-223: a personal entry's change in the app, as one account and currency's displayed balance shows it. */
    public record AppEdit(String source, String excelId, LocalDate date, String account, String currency,
            BigDecimal amount) {
    }

    /**
     * @param source OWNER for the owner's transactions file, PARTNER for the partner's family rows
     * @param rows the file's rows that could be read
     * @param afterCutoff those of them dated after "Import up to" (D-165)
     */
    public record AfterCutoff(String source, int rows, int afterCutoff) {
    }

    /**
     * @param source OWNER_PERSONAL, OWNER_FAMILY, OWNER_SETTLEMENT, OWNER_SEAT, PARTNER, the MEMBER_ ones, or APP for
     *        the rows the app made (D-259)
     * @param kind an entry kind for a personal entry; EXPENSE, REFUND, INCOME, SETTLEMENT or CONVERSION for the family
     *        budget's
     * @param notImported rows the decisions leave out (D-84)
     */
    public record Count(String source, String kind, int created, int updated, int unchanged, int conflicts,
            int failed, int skipped, int notImported) {
    }

    public record Totals(int created, int updated, int unchanged, int missing, int conflicts, int failed, int skipped,
            int notImported) {
    }

    /** A row by its source and Excel ID, and why it is listed. */
    public record RowRef(String source, String excelId, String reason) {
    }

    /**
     * An error, warning or skipped row.
     *
     * @param row the row in its file, where the header is row 1; null for the file as a whole
     * @param excelId the row's Excel ID, where it has one
     */
    public record Problem(String file, Integer row, String excelId, String message) {

        public Problem(String file, Integer row, String message) {
            this(file, row, null, message);
        }
    }

    /** A distinct message, how many rows have it, and the first of their Excel IDs (or row numbers). */
    public record MessageCount(String message, int count, List<String> rows) {
    }

    public record ReferenceCounts(int accountsCreated, int accountsUpdated, int categoriesCreated,
            int categoriesUpdated, int familyCategoriesCreated, int categoriesMerged, int counterpartiesCreated) {
    }

    public record FxRow(int row, String excelId, LocalDate date, BigDecimal sum, String currency) {
    }

    /**
     * The ledger against the transactions file (D-231 as D-255 and D-256 amend it), from the file only. Per row
     * ({@code rows}): the workbook's computed columns against what the typed columns compute, and what the import builds
     * against those columns. Per month-end from the file's first row date to the cutoff: each account's displayed change
     * in the ledger since the day before the file's first row date against what the file's rows move on it, per code
     * and currency, with FAMILY_DEBT, UNALLOCATED and RESERVE as one sum per currency ({@code EQUITY+FAMILY_DEBT}). A
     * difference the import's rules explain (D-68: the shares' rounding, amounts rounded to the minor unit or to 4
     * decimals; D-223's APP_EDITED; D-188's SETTLEMENT_DATE; NO_ROW, the ledger's entries no row stands for) is
     * explained; any other above 0.01 fails it, as does any row that fails.
     *
     * @param from the file's first row date: the movements start the day before it
     * @param cutoff the file's last row date, or "Import up to" if earlier (D-165)
     * @param lines per account and currency
     * @param failures every point whose unexplained difference is above 0.01, first 500
     * @param explained per account, currency and rule, the explained difference at the cutoff
     * @param appOnly the app's accounts with a movement in the period and no row of the file in that currency
     *        (FX_EXCHANGE and the like, which the workbook hasn't, aren't listed)
     * @param rows D-231 (a)
     */
    public record Reconciliation(boolean passed, LocalDate from, LocalDate cutoff, int pointsCompared,
            LocalDate lastCompared, List<ReconciliationLine> lines, List<ReconciliationPoint> failures,
            List<ExplainedAmount> explained, List<String> appOnly, RowLevel rows) {
    }

    /**
     * D-231 (a), per row.
     *
     * @param rows the rows checked, the app's included
     * @param built the Excel rows whose build was compared
     * @param explainedRows those whose difference a rule explains
     * @param failed the rows with a difference no rule explains
     * @param failures their differences, first 500
     * @param explained per rule, account and currency, what the rules explain
     */
    public record RowLevel(int rows, int built, int explainedRows, int failed, List<RowDifference> failures,
            List<ExplainedAmount> explained) {
    }

    /**
     * A row's difference: a computed column against what its typed columns compute ({@code what} names the column), or
     * what the import builds on an account and currency against what the row's columns move ({@code what} names them).
     * A text column's difference has no amounts.
     */
    public record RowDifference(String excelId, int row, String what, BigDecimal file, BigDecimal computed,
            BigDecimal explained, BigDecimal unexplained) {
    }

    /**
     * @param account the account's code; EQUITY+FAMILY_DEBT for the family debt, UNALLOCATED and RESERVE (D-255)
     * @param compared the month-ends compared
     * @param worstUnexplained the largest unexplained difference, in absolute value
     * @param lastExcel what the file's rows move up to the cutoff, {@code lastApp} the ledger's movement,
     *        {@code lastExplained} the explained part
     */
    public record ReconciliationLine(String account, String currency, int compared, int failed,
            LocalDate firstFailure, BigDecimal worstUnexplained, BigDecimal lastExcel, BigDecimal lastApp,
            BigDecimal lastExplained) {
    }

    public record ReconciliationPoint(LocalDate date, String account, String currency, BigDecimal excel,
            BigDecimal app, BigDecimal explained, BigDecimal unexplained) {
    }

    public record ExplainedAmount(String account, String currency, String reason, BigDecimal amount) {
    }
}
