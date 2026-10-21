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
 * @param missing rows the ledger has from an earlier import that the files no longer have (D-138): reported, never
 *        deleted
 * @param conflicts rows changed in the app since their last import, or deleted there (D-139, D-150, D-113): left alone
 * @param errors rows that can't be imported as they are. One or more of them abort a commit.
 * @param errorSummary, warningSummary, skippedSummary each distinct message with its count and the first Excel IDs
 * @param reconciliation the owner's balances against his daily balance file (D-63, D-67, D-68, D-69, D-146, D-147)
 * @param integrityViolations {@code ReportService.integrityCheck} after the import; empty for a sound ledger
 * @param member what a member's own import did with his family, settlement and benefit rows (D-175 to D-181); null
 *        for the owner's
 */
public record ImportReport(String userId, boolean commit, Outcome outcome, Instant startedAt, List<FileSummary> files,
        FamilyTarget family, Period period, LocalDate upTo, List<AfterCutoff> afterCutoff, List<String> currencies, List<Count> counts, Totals totals,
        List<RowRef> missing, List<RowRef> conflicts, List<Problem> errors, List<Problem> warnings,
        List<MessageCount> errorSummary, List<MessageCount> warningSummary, List<MessageCount> skippedSummary,
        ReferenceCounts referenceData, int zeroFxRowsSkipped, List<FxRow> fxRowsToReview,
        Reconciliation reconciliation, List<IntegrityViolation> integrityViolations, MemberSummary member) {

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
     * @param source OWNER for the owner's transactions file, PARTNER for the partner's family rows
     * @param rows the file's rows that could be read
     * @param afterCutoff those of them dated after "Import up to" (D-165)
     */
    public record AfterCutoff(String source, int rows, int afterCutoff) {
    }

    /**
     * @param source OWNER_PERSONAL, OWNER_FAMILY, OWNER_SETTLEMENT, OWNER_SEAT, OWNER_LUMP or PARTNER
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
     * The owner's balances, as the import leaves his ledger, against his daily balance file at every month-end up to
     * the cutoff (D-63): per account code and currency, Excel's as read (D-199: no cut to cents), UNALLOCATED and
     * RESERVE as one equity (D-67), the family budget's debt account as FAMILY_DEBT. A difference that the import's own
     * rules explain (D-68: the shares' rounding, amounts rounded to the minor unit or to 4 decimals) is explained; any
     * other above 0.01 fails it, but from the month of the older export on (D-69), where it is only shown. In a
     * currency with no family row on either side, FAMILY_DEBT and the equity are one sum, and the app's FAMILY_DEBT
     * there is 0 at the last month-end compared (D-147).
     *
     * @param cutoff the latest transaction of both files: the daily files are read up to it (D-146)
     * @param tailFrom the first day of the older export's month: from it on nothing fails (D-69)
     * @param lines per account and currency
     * @param failures every point whose unexplained difference is above 0.01 before the tail, first 500
     * @param tail every point of the tail with a difference
     * @param withoutFamilyRows D-147's currencies, with the app's and Excel's FAMILY_DEBT at the last compared point
     * @param explained per account, currency and rule, the explained difference at the last compared point
     * @param appOnly the app's accounts with a balance and no column in Excel (FX_EXCHANGE and the like aren't listed)
     * @param ownerHalf D-169's months, explained or not
     * @param familyDebtUpTo D-175: the family debt compared up to this day only, the earlier of the other members'
     *        latest committed imports; null when nothing limits it
     * @param waiting the family debt's points after {@code familyDebtUpTo} whose difference is above 0.01: "waiting
     *        for the other member's import", not failed (D-175), first 500
     */
    public record Reconciliation(boolean passed, LocalDate cutoff, LocalDate tailFrom, int pointsCompared,
            LocalDate lastCompared, List<ReconciliationLine> lines, List<ReconciliationPoint> failures,
            List<ReconciliationPoint> tail, List<CurrencyWithoutFamilyRows> withoutFamilyRows,
            List<ExplainedAmount> explained, List<String> appOnly, List<OwnerHalf> ownerHalf,
            LocalDate familyDebtUpTo, List<ReconciliationPoint> waiting) {
    }

    /**
     * D-169, per month and currency: the owner's D-84 rows less half of the partner's family incomes they carry,
     * exactly; explained when within half a cent per income, otherwise left unexplained, whole.
     *
     * @param month the month's first day
     * @param incomes how many of the partner's family incomes the month has
     */
    public record OwnerHalf(LocalDate month, String currency, BigDecimal ownerRows, BigDecimal partnerIncomes,
            int incomes, BigDecimal ownerHalf, boolean explained) {
    }

    /**
     * @param account the account's code; EQUITY for UNALLOCATED and RESERVE, FAMILY_DEBT for the family budget's debt
     *        account, EQUITY+FAMILY_DEBT for D-147's sum
     * @param worstUnexplained the largest unexplained difference before the tail, in absolute value
     * @param waiting the points after the reconciliation's {@code familyDebtUpTo}, not compared (D-175)
     */
    public record ReconciliationLine(String account, String currency, int compared, int failed,
            LocalDate firstFailure, BigDecimal worstUnexplained, BigDecimal lastExcel, BigDecimal lastApp,
            BigDecimal lastExplained, int waiting) {
    }

    public record ReconciliationPoint(LocalDate date, String account, String currency, BigDecimal excel,
            BigDecimal app, BigDecimal explained, BigDecimal unexplained) {
    }

    public record CurrencyWithoutFamilyRows(String currency, BigDecimal appFamilyDebt, BigDecimal excelFamilyDebt,
            boolean ok) {
    }

    public record ExplainedAmount(String account, String currency, String reason, BigDecimal amount) {
    }
}
