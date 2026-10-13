package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.financetracker.WallClock;
import com.example.financetracker.ledger.Account;
import com.example.financetracker.ledger.AccountRepository;
import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.CounterpartyRepository;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.ImportBatch;
import com.example.financetracker.ledger.ImportBatchRepository;
import com.example.financetracker.ledger.Json;
import com.example.financetracker.ledger.LedgerCategory;
import com.example.financetracker.ledger.LedgerCategoryRepository;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.ImportedCommand;
import com.example.financetracker.ledger.domain.InvalidEntryException;
import com.example.financetracker.ledger.domain.PostingLine;
import com.example.financetracker.ledger.family.FamilyImportSync;
import com.example.financetracker.ledger.family.FamilyLedgerService;
import com.example.financetracker.ledger.family.FamilyMemberView;
import com.example.financetracker.ledger.family.FamilyRecordChanges;
import com.example.financetracker.ledger.family.FamilyRecordService;
import com.example.financetracker.ledger.family.FamilyRecordView;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.family.ImportSync;
import com.example.financetracker.ledger.family.MemberStatus;
import com.example.financetracker.ledger.family.NewFamilyRecord;
import com.example.financetracker.ledger.family.NewSettlement;
import com.example.financetracker.ledger.family.RecordSplit;
import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.Chart.CategoryRef;
import com.example.financetracker.ledger.importer.EntryPlan.Party;
import com.example.financetracker.ledger.importer.ImportPlanner.FamilyItem;
import com.example.financetracker.ledger.importer.ImportPlanner.Planned;
import com.example.financetracker.ledger.importer.ImportPlanner.RecordSpec;
import com.example.financetracker.ledger.importer.ImportPlanner.SettlementSpec;
import com.example.financetracker.ledger.importer.ImportPlanner.Source;
import com.example.financetracker.ledger.importer.ImportReport.Count;
import com.example.financetracker.ledger.importer.ImportReport.FamilyTarget;
import com.example.financetracker.ledger.importer.ImportReport.FileSummary;
import com.example.financetracker.ledger.importer.ImportReport.FxRow;
import com.example.financetracker.ledger.importer.ImportReport.MessageCount;
import com.example.financetracker.ledger.importer.ImportReport.Outcome;
import com.example.financetracker.ledger.importer.ImportReport.Period;
import com.example.financetracker.ledger.importer.ImportReport.Problem;
import com.example.financetracker.ledger.importer.ImportReport.ReferenceCounts;
import com.example.financetracker.ledger.importer.ImportReport.RowRef;
import com.example.financetracker.ledger.importer.ImportReport.Totals;
import com.example.financetracker.ledger.importer.Reconciler.Key;
import com.example.financetracker.ledger.importer.Workbook.AccountRow;
import com.example.financetracker.ledger.importer.Workbook.CategoryRow;
import com.example.financetracker.ledger.importer.Workbook.Sheet;
import com.example.financetracker.ledger.report.ReportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports the owner's Excel ledger (D3b): his personal rows into his personal ledger, and his family rows, his rows on
 * FAMILY_DEBT, the lumps before the partner's ledger starts and the partner's family rows into the family budget and
 * the partner's seat he names (D-82, D-141), as {@link ImportPlanner} maps them; then it reconciles his balances with
 * his daily balance file ({@link Reconciler}). Entries are written through {@link EntryService} and the family budget's
 * records through {@link FamilyRecordService}, so the ledger's rules hold for them as for any other.
 * <p>
 * Each row is recognised again by its permanent ID (D-86, D-142): {@code xls:<ID>} in the personal ledger, and
 * {@code xls:<seat>:<ID>} in the family budget, the seat being the one whose file the row comes from. A row the import
 * wrote before with the same content is unchanged; with another content it is written over the way the app changes
 * it, unless it was changed in the app since, which is a conflict that the import leaves alone (D-139); one deleted in
 * the app stays deleted and is a conflict too (D-113, D-150); one the files no longer have is reported, never deleted
 * (D-138).
 * <p>
 * The whole run is one transaction. A dry run rolls it back at the end, after the report has read the balances, and
 * a commit rolls it back too if any row can't be imported, so a commit saves everything or nothing. Each row is
 * written under its own savepoint, so a row the database refuses doesn't stop the rows after it.
 */
@Service
public class ImportService {

    private final AccountRepository accounts;
    private final LedgerCategoryRepository categories;
    private final CounterpartyRepository counterparties;
    private final ImportBatchRepository batches;
    private final EntryService entries;
    private final ReportService reports;
    private final LedgerAccess access;
    private final FamilySwitch familySwitch;
    private final FamilyLedgerService familyLedgers;
    private final FamilyRecordService records;
    private final FamilyImportSync familySync;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final TransactionTemplate savepoint;

    ImportService(AccountRepository accounts, LedgerCategoryRepository categories,
            CounterpartyRepository counterparties, ImportBatchRepository batches, EntryService entries,
            ReportService reports, LedgerAccess access, FamilySwitch familySwitch, FamilyLedgerService familyLedgers,
            FamilyRecordService records, FamilyImportSync familySync, JdbcClient jdbc, ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.categories = categories;
        this.counterparties = counterparties;
        this.batches = batches;
        this.entries = entries;
        this.reports = reports;
        this.access = access;
        this.familySwitch = familySwitch;
        this.familyLedgers = familyLedgers;
        this.records = records;
        this.familySync = familySync;
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * @param personal the personal ledger of the owner, who imports
     * @throws PartnerFileRefused if the partner's file holds what the import page's filter never lets through (D-140)
     * @throws NotFoundException if the family budget or the seat isn't one the owner may import into, or the family
     *         budgets are switched off (D-25)
     * @throws ConflictException if the seat is a member with an account
     */
    public ImportReport run(LedgerScope personal, ImportRequest request) {
        Instant startedAt = WallClock.now();
        Sheet<PartnerRow> partnerRows = request.partnerTransactions() == null ? null
                : Workbook.partner(request.partnerTransactions());
        Sheet<AccountRow> accountRows = Workbook.accounts(request.accounts());
        Sheet<CategoryRow> categoryRows = Workbook.categories(request.categories());
        Sheet<TransactionRow> transactionRows = Workbook.transactions(request.transactions());
        // D-146: the daily files are read up to the latest transaction of both files, and D-69's tail starts in the
        // older one's month; both from every row of the files, the rows after "Import up to" included.
        LocalDate ownerLatest = transactionRows.rows().stream().map(TransactionRow::date).max(LocalDate::compareTo)
                .orElse(null);
        LocalDate partnerLatest = partnerRows == null ? null : partnerRows.rows().stream().map(PartnerRow::date)
                .max(LocalDate::compareTo).orElse(null);
        LocalDate partnerFirst = partnerRows == null ? null : partnerRows.rows().stream().map(PartnerRow::date)
                .min(LocalDate::compareTo).orElse(null);
        LocalDate latest = Stream.of(ownerLatest, partnerLatest).filter(d -> d != null).max(LocalDate::compareTo)
                .orElse(null);
        // D-165: the rows after "Import up to" are left out, and the daily files and the reconciliation stop at it.
        LocalDate upTo = request.upTo();
        AfterCutoff after = upTo == null ? AfterCutoff.NONE : AfterCutoff.cut(upTo, transactionRows, partnerRows);
        LocalDate cutoff = upTo != null && latest != null && upTo.isBefore(latest) ? upTo : latest;
        List<Problem> dailyErrors = new ArrayList<>();
        DailyFile balance = DailyFile.read(request.dailyBalance(), cutoff, dailyErrors);
        DailyFile change = DailyFile.read(request.dailyChange(), cutoff, dailyErrors);
        return transaction.execute(status -> {
            Run run = new Run(personal, request, startedAt);
            ImportReport report = run.execute(accountRows, categoryRows, transactionRows, partnerRows, balance, change,
                    dailyErrors, ownerLatest, partnerLatest, partnerFirst, cutoff, after);
            if (report.outcome() != Outcome.COMMITTED) {
                status.setRollbackOnly();
            }
            return report;
        });
    }

    /** The Excel IDs of the rows dated after {@code upTo}. */
    private static <T> Set<Long> ids(List<T> rows, Function<T, LocalDate> date, Function<T, Long> id, LocalDate upTo) {
        return rows.stream().filter(row -> date.apply(row).isAfter(upTo)).map(id).collect(Collectors.toSet());
    }

    /**
     * D-165: "Import up to" and the rows of each transactions file after it, by Excel ID, which are neither imported
     * nor missing (D-138).
     */
    private record AfterCutoff(LocalDate upTo, Set<Long> owner, Set<Long> partner, int ownerRead, int partnerRead) {

        static final AfterCutoff NONE = new AfterCutoff(null, Set.of(), Set.of(), 0, 0);

        /** Takes the rows after {@code upTo} out of both files, and keeps their IDs. */
        static AfterCutoff cut(LocalDate upTo, Sheet<TransactionRow> owner, Sheet<PartnerRow> partner) {
            Set<Long> ownerIds = ids(owner.rows(), TransactionRow::date, TransactionRow::id, upTo);
            Set<Long> partnerIds = partner == null ? Set.of()
                    : ids(partner.rows(), PartnerRow::date, PartnerRow::id, upTo);
            int ownerRead = owner.rows().size();
            int partnerRead = partner == null ? 0 : partner.rows().size();
            owner.removeRows(row -> row.date().isAfter(upTo));
            if (partner != null) {
                partner.removeRows(row -> row.date().isAfter(upTo));
            }
            return new AfterCutoff(upTo, ownerIds, partnerIds, ownerRead, partnerRead);
        }

        /** Whether a row known from an earlier import is left alone: dated after the date, or a row of the files that is. */
        boolean leftAlone(Set<Long> ids, String excelId, LocalDate stored) {
            if (upTo == null) {
                return false;
            }
            if (stored != null && stored.isAfter(upTo)) {
                return true;
            }
            int end = excelId.indexOf(':');
            String id = end < 0 ? excelId : excelId.substring(0, end);
            return id.matches("[0-9]{1,18}") && ids.contains(Long.parseLong(id));
        }

        List<ImportReport.AfterCutoff> counts(boolean partnerRead) {
            if (upTo == null) {
                return List.of();
            }
            List<ImportReport.AfterCutoff> counts = new ArrayList<>(List.of(
                    new ImportReport.AfterCutoff("OWNER", ownerRead, owner.size())));
            if (partnerRead) {
                counts.add(new ImportReport.AfterCutoff(Source.PARTNER.name(), this.partnerRead, partner.size()));
            }
            return counts;
        }
    }

    /** What happened to the rows of one source and kind. */
    private static final class Tally {
        int created;
        int updated;
        int unchanged;
        int conflicts;
        int failed;
        int skipped;
        int notImported;
    }

    /** A family budget's side of the run: the owner's scope in it, and the seats. */
    private record FamilyContext(LedgerScope scope, FamilyTarget target) {
    }

    /** One run's state. */
    private final class Run {

        private final LedgerScope personal;
        private final ImportRequest request;
        private final boolean commit;
        private final Instant startedAt;
        private final List<FileSummary> files = new ArrayList<>();
        private final Map<String, Tally> tallies = new TreeMap<>();
        private final List<Problem> errors = new ArrayList<>();
        private final List<Problem> warnings = new ArrayList<>();
        private final List<Problem> skipped = new ArrayList<>();
        private final List<RowRef> conflicts = new ArrayList<>();
        private final List<RowRef> missing = new ArrayList<>();
        private final List<FxRow> fxRows = new ArrayList<>();
        private final List<Drift> drift = new ArrayList<>();
        /** D-169: the owner's D-84 rows and the partner's family incomes they carry. */
        private final List<Reconciler.Carried> carriedByTheOwner = new ArrayList<>();
        private final List<Reconciler.Carried> carriedByThePartner = new ArrayList<>();
        private final Set<String> currencies = new TreeSet<>();
        private final Set<String> familyCurrencies = new TreeSet<>();
        private LocalDate from;
        private LocalDate to;
        private int zeroFxRows;
        private int accountsCreated;
        private int accountsUpdated;
        private int categoriesCreated;
        private int categoriesUpdated;
        private int familyCategoriesCreated;
        private int categoriesMerged;
        private int counterpartiesCreated;
        /** The ledger's counterparties by lowercase name, as the database's unique index compares them. */
        private Map<String, Long> counterpartyIds;
        private Map<String, PersonalKnown> personalKnown;
        private Set<String> deletedInTheApp;
        private Map<String, ImportSync.Known> familyKnown = Map.of();
        private Map<String, Long> familyCategoryIds = Map.of();
        private Chart chart;
        private FamilyContext family;
        private long batchId;
        private int writes;

        Run(LedgerScope personal, ImportRequest request, Instant startedAt) {
            this.personal = personal;
            this.request = request;
            this.commit = request.commit();
            this.startedAt = startedAt;
        }

        ImportReport execute(Sheet<AccountRow> accountRows, Sheet<CategoryRow> categoryRows,
                Sheet<TransactionRow> transactionRows, Sheet<PartnerRow> partnerRows, DailyFile balance,
                DailyFile change, List<Problem> dailyErrors, LocalDate ownerLatest, LocalDate partnerLatest,
                LocalDate partnerFirst, LocalDate cutoff, AfterCutoff after) {
            read("accounts", accountRows);
            read("categories", categoryRows);
            read("transactions", transactionRows);
            if (partnerRows != null) {
                read("partner's transactions", partnerRows);
            }
            files.add(new FileSummary("daily balance", balance.file().name(), balance.file().sha256(),
                    balance.values().size()));
            files.add(new FileSummary("daily change", change.file().name(), change.file().sha256(),
                    change.values().size()));
            errors.addAll(dailyErrors);
            family = family();
            if (family != null && partnerRows == null) {
                errors.add(new Problem(null, null, "with a family budget, the partner's family rows are needed too "
                        + "(D-82): choose his transactions file"));
            }

            Map<String, AccountRef> accountsByName = accounts(accountRows);
            Map<String, CategoryRow> categoryRowsByName = categoryRows.rows().stream()
                    .collect(Collectors.toMap(CategoryRow::name, Function.identity(), (a, b) -> a));
            long ownerId = family == null ? 0 : family.target().ownerMemberId();
            long seatId = family == null ? 0 : family.target().seatMemberId();
            ImportPlanner planner = new ImportPlanner(accountsByName, categoryRowsByName, ownerId, seatId);

            // What each row becomes.
            List<Map.Entry<TransactionRow, Planned>> owner = new ArrayList<>();
            for (TransactionRow row : transactionRows.rows()) {
                owner.add(Map.entry(row, planner.plan(row)));
                period(row.date());
            }
            List<Map.Entry<PartnerRow, Planned>> partner = new ArrayList<>();
            if (partnerRows != null) {
                for (PartnerRow row : partnerRows.rows()) {
                    partner.add(Map.entry(row, planner.partner(row)));
                    period(row.date());
                }
            }
            // D-83's months before the partner's first family row, whatever "Import up to" leaves out (D-165).
            List<RecordSpec> lumps = family != null && partnerFirst != null ? planner.lumps(change, partnerFirst)
                    : List.of();
            List<FamilyItem> familyItems = new ArrayList<>();
            owner.forEach(e -> {
                if (e.getValue() instanceof ImportPlanner.Family f) {
                    familyItems.addAll(f.items());
                }
            });
            familyItems.addAll(lumps);
            partner.forEach(e -> {
                if (e.getValue() instanceof ImportPlanner.Family f) {
                    familyItems.addAll(f.items());
                }
            });
            familyItems.forEach(item -> {
                currencies.add(item.currency());
                if (item instanceof RecordSpec) {
                    familyCurrencies.add(item.currency());
                }
            });
            transactionRows.rows().forEach(row -> {
                currencies.add(row.debitCurrencyOrRow());
                currencies.add(row.creditCurrencyOrRow());
            });

            familyCategoryIds = familyCategories(familyItems);
            chart = chart(accountsByName, categoryRows);
            EntryMapper mapper = new EntryMapper(chart);
            counterpartyIds = counterparties.findAll(personal).stream()
                    .collect(Collectors.toMap(c -> key(c.name()), Counterparty::id, (first, second) -> first));
            personalKnown = personalKnown(personal);
            deletedInTheApp = deletedInTheApp(personal);
            if (family != null) {
                familyKnown = familySync.known(family.scope(), familyItems.stream().map(FamilyItem::ref).toList());
            }
            findMissing(transactionRows, familyItems, partnerRows != null, after);
            ImportFile transactionsFile = transactionRows.file();
            batchId = batches.save(new ImportBatch(null, personal.userId(), personal.ledgerId(),
                    transactionsFile.name(), transactionsFile.sha256(), !commit, null, null, null)).id();
            analyze(personal);

            // D-62: the opening balances from the first row of the daily balance file.
            if (!balance.isEmpty()) {
                Map<DailyFile.Column, BigDecimal> opening = new LinkedHashMap<>();
                LocalDate first = balance.values().firstKey();
                balance.accountColumns().forEach((column, account) -> opening.merge(account,
                        balance.value(first, column), BigDecimal::add));
                RowResult result = mapper.openingEntry(first, opening);
                importPersonal(balance.file().name(), null, null, "OPENING_BALANCE", result);
            }
            for (Map.Entry<TransactionRow, Planned> e : owner) {
                TransactionRow row = e.getKey();
                String file = transactionsFile.name();
                e.getValue().warnings().forEach(w -> warnings.add(new Problem(file, row.row(), id(row.id()), w)));
                switch (e.getValue()) {
                    case ImportPlanner.Personal p -> {
                        RowResult result = mapper.map(p.row());
                        importPersonal(file, row.row(), id(row.id()), kindOf(result), result);
                        if (result instanceof RowResult.Entry entry && entry.plan().fxGain()) {
                            fxRows.add(new FxRow(row.row(), id(row.id()), row.date(), row.sum(), row.currency()));
                        }
                    }
                    case ImportPlanner.Family f -> f.items().forEach(item -> importFamily(file, item));
                    case ImportPlanner.NotImported n -> {
                        tally(Source.OWNER_SEAT, "INCOME").notImported++;
                        carriedByTheOwner.add(n.carried());
                        skipped.add(new Problem(file, row.row(), id(row.id()), n.reason()));
                    }
                    case ImportPlanner.Skipped s -> {
                        tally(Source.OWNER_FAMILY, "ZERO").skipped++;
                        skipped.add(new Problem(file, row.row(), id(row.id()), s.reason()));
                    }
                    case ImportPlanner.Failed failure -> {
                        tally(Source.OWNER_FAMILY, "ROW").failed++;
                        failure.errors().forEach(m -> errors.add(new Problem(file, row.row(), id(row.id()), m)));
                    }
                }
            }
            lumps.forEach(lump -> importFamily(change.file().name(), lump));
            for (Map.Entry<PartnerRow, Planned> e : partner) {
                PartnerRow row = e.getKey();
                String file = partnerRows.file().name();
                switch (e.getValue()) {
                    case ImportPlanner.Family f -> {
                        f.items().forEach(item -> importFamily(file, item));
                        Reconciler.Carried carried = planner.carried(row);
                        if (carried != null) {
                            carriedByThePartner.add(carried);
                        }
                    }
                    case ImportPlanner.Skipped s -> {
                        tally(Source.PARTNER, "ZERO").skipped++;
                        skipped.add(new Problem(file, row.row(), id(row.id()), s.reason()));
                    }
                    case ImportPlanner.Failed failure -> {
                        tally(Source.PARTNER, "ROW").failed++;
                        failure.errors().forEach(m -> errors.add(new Problem(file, row.row(), id(row.id()), m)));
                    }
                    default -> throw new IllegalStateException("A partner's row is a family row or none");
                }
            }
            String deferred = deferredConstraintsFail(personal);
            if (deferred != null) {
                errors.add(new Problem(null, null, deferred));
            }

            Set<String> comparedCodes = accountRows.rows().stream().map(AccountRow::code).collect(Collectors.toSet());
            LocalDate older = Stream.of(ownerLatest, partnerLatest).filter(d -> d != null).min(LocalDate::compareTo)
                    .orElse(null);
            // D-169: the owner's hand-rounded halves of the partner's incomes that D-84 leaves out.
            Reconciler.OwnerHalves ownerHalves = Reconciler.ownerHalf(carriedByTheOwner, carriedByThePartner);
            drift.addAll(ownerHalves.drift());
            ImportReport.Reconciliation reconciliation = balance.isEmpty() || cutoff == null ? null
                    : Reconciler.reconcile(appBalances(personal), balance, drift, familyCurrencies, comparedCodes,
                            cutoff, older.withDayOfMonth(1), ownerHalves.months());
            if (reconciliation != null) {
                reconciliation.appOnly().forEach(a -> warnings.add(new Problem(null, null,
                        "an account of the app has a balance and no column in the daily balance file")));
            }

            Outcome outcome = !commit ? Outcome.DRY_RUN : errors.isEmpty() ? Outcome.COMMITTED : Outcome.ABORTED;
            ImportReport report = new ImportReport(personal.userId(), commit, outcome, startedAt, files,
                    family == null ? null : family.target(), from == null ? null : new Period(from, to),
                    after.upTo(), after.counts(partnerRows != null),
                    List.copyOf(currencies), counts(), totals(), missing, conflicts, errors, warnings,
                    distinct(errors), distinct(warnings), distinct(skipped),
                    new ReferenceCounts(accountsCreated, accountsUpdated, categoriesCreated, categoriesUpdated,
                            familyCategoriesCreated, categoriesMerged, counterpartiesCreated),
                    zeroFxRows, fxRows, reconciliation, reports.integrityCheck(personal));
            batches.save(new ImportBatch(batchId, personal.userId(), personal.ledgerId(), transactionsFile.name(),
                    transactionsFile.sha256(), !commit, null, WallClock.now(), summary(report)));
            return report;
        }

        private void read(String role, Sheet<?> sheet) {
            ImportFile file = sheet.file();
            files.add(new FileSummary(role, file.name(), file.sha256(), sheet.size()));
            errors.addAll(sheet.errors());
            skipped.addAll(sheet.skipped());
        }

        private void period(LocalDate date) {
            from = from == null || date.isBefore(from) ? date : from;
            to = to == null || date.isAfter(to) ? date : to;
        }

        // --- The family budget and the seat (D-141) ----------------------------------------------------------------

        /**
         * The family budget the owner names, as a member of it, and the partner's seat in it: an ACTIVE member
         * without an account. Another user's budget or seat answers like a missing one (404).
         */
        private FamilyContext family() {
            if (request.familyLedgerId() == null) {
                return null;
            }
            if (!familySwitch.enabled()) {
                throw new NotFoundException("Family budgets are switched off");
            }
            LedgerScope scope = access.member(personal.userId(), request.familyLedgerId());
            FamilyMemberView seat = familyLedgers.members(scope).stream()
                    .filter(m -> m.id() == request.seatMemberId() && m.status() == MemberStatus.ACTIVE)
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Member %d not found".formatted(request.seatMemberId())));
            if (seat.hasAccount()) {
                throw new ConflictException("The partner's seat is a member without an account: %s has one"
                        .formatted(seat.displayName()));
            }
            String name = familyLedgers.get(scope).name();
            return new FamilyContext(scope, new FamilyTarget(scope.ledgerId(), name, scope.memberId(), seat.id(),
                    seat.displayName()));
        }

        /**
         * The family categories of the family records (D-11): the budget's own with the code, else a new one, into
         * which the owner's personal category with the code merges. By code.
         */
        private Map<String, Long> familyCategories(List<FamilyItem> items) {
            Map<String, Long> ids = new HashMap<>();
            if (family == null) {
                return ids;
            }
            Map<String, RecordSpec> byCode = new TreeMap<>();
            items.stream().filter(RecordSpec.class::isInstance).map(RecordSpec.class::cast)
                    .forEach(spec -> byCode.putIfAbsent(spec.categoryCode(), spec));
            byCode.forEach((code, spec) -> {
                try {
                    FamilyLedgerService.ImportedCategory category = savepoint.execute(status -> familyLedgers
                            .importCategory(family.scope(), personal, code, spec.categoryName(), spec.categoryType()));
                    ids.put(code, category.id());
                    familyCategoriesCreated += category.created() ? 1 : 0;
                    categoriesMerged += category.merged() ? 1 : 0;
                } catch (ConflictException | DataAccessException e) {
                    errors.add(new Problem(request.categories().name(), null,
                            "the family budget's category can't be found or made: " + message(e)));
                }
            });
            return ids;
        }

        // --- The chart ----------------------------------------------------------------------------------------------

        /**
         * Creates or updates the personal accounts of the accounts file by code, and makes sure the system accounts
         * exist (rules 4, 8, 9 and 10). By name in lower case.
         */
        private Map<String, AccountRef> accounts(Sheet<AccountRow> accountRows) {
            Map<String, Account> accountsByCode = accounts.findAll(personal).stream()
                    .collect(Collectors.toMap(Account::code, Function.identity()));
            Map<String, AccountRef> accountsByName = new HashMap<>();
            for (AccountRow row : accountRows.rows()) {
                Account existing = accountsByCode.get(row.code());
                Account wanted = existing == null
                        ? new Account(null, personal.userId(), personal.ledgerId(), row.code(), row.name(), row.type(),
                                null, row.requiresCounterparty(), false, null, null)
                        : new Account(existing.id(), existing.userId(), existing.ledgerId(), existing.code(),
                                row.name(), row.type(), existing.defaultCurrency(), row.requiresCounterparty(),
                                existing.isSystem(), existing.archivedAt(), existing.createdAt());
                Account account = existing;
                if (!wanted.equals(existing)) {
                    account = save(accountRows.file(), row.row(), () -> accounts.save(wanted), "the account");
                    if (account != null && existing == null) {
                        accountsCreated++;
                    } else if (account != null) {
                        accountsUpdated++;
                    }
                }
                if (account != null) {
                    accountsByCode.put(account.code(), account);
                    accountsByName.put(row.name().toLowerCase(Locale.ROOT), accountRef(account));
                }
            }
            systemAccount(AccountRole.OPENING_BALANCE, "Opening balance", accountsByCode);
            systemAccount(AccountRole.FX_EXCHANGE, "Currency exchange", accountsByCode);
            refsByCode = accountsByCode.values().stream()
                    .collect(Collectors.toMap(Account::code, ImportService::accountRef));
            return accountsByName;
        }

        private Map<String, AccountRef> refsByCode;

        /**
         * The categories by the file's names: the family budget's for the codes of its records, which the owner's
         * personal rows use too (D-11), and the personal ones, created or updated by code, for the rest.
         */
        private Chart chart(Map<String, AccountRef> accountsByName, Sheet<CategoryRow> categoryRows) {
            Map<String, LedgerCategory> categoriesByCode = categories.findAll(personal).stream()
                    .collect(Collectors.toMap(LedgerCategory::code, Function.identity()));
            Map<String, CategoryRef> categoriesByName = new HashMap<>();
            for (CategoryRow row : categoryRows.rows()) {
                Long familyId = familyCategoryIds.get(row.code());
                if (familyId != null) {
                    categoriesByName.put(row.name(), new CategoryRef(familyId, row.code(), row.type()));
                    continue;
                }
                LedgerCategory existing = categoriesByCode.get(row.code());
                if (existing != null && existing.type() != row.type()) {
                    // Decided on 2026-09-25: a category's type never changes.
                    errors.add(new Problem(categoryRows.file().name(), row.row(),
                            "the category's type can't change"));
                    continue;
                }
                LedgerCategory wanted = new LedgerCategory(existing == null ? null : existing.id(), personal.userId(),
                        personal.ledgerId(), row.code(), row.name(), row.type(),
                        existing == null ? null : existing.archivedAt());
                LedgerCategory category = existing;
                if (!wanted.equals(existing)) {
                    category = save(categoryRows.file(), row.row(), () -> categories.save(wanted), "the category");
                    if (category != null && existing == null) {
                        categoriesCreated++;
                    } else if (category != null) {
                        categoriesUpdated++;
                    }
                }
                if (category != null) {
                    categoriesByName.put(row.name(), new CategoryRef(category.id(), category.code(), category.type()));
                }
            }
            return new Chart(accountsByName, refsByCode, categoriesByName);
        }

        /** Rules 9 and 10: an EQUITY account the importer posts to on its own, created if the ledger lacks it. */
        private void systemAccount(AccountRole role, String name, Map<String, Account> accountsByCode) {
            String code = role.defaultCode();
            if (!accountsByCode.containsKey(code)) {
                accountsByCode.put(code, accounts.save(
                        new Account(null, personal.userId(), personal.ledgerId(), code, name, AccountType.EQUITY, null,
                                false, true, null, null)));
                accountsCreated++;
            }
        }

        /**
         * Writes an account or category of the files under a savepoint. Null if the database refuses it, such as an
         * account whose postings rule out its new type; that is an error of the row.
         */
        private <T> T save(ImportFile file, int row, Supplier<T> save, String what) {
            try {
                return savepoint.execute(status -> save.get());
            } catch (DataAccessException e) {
                errors.add(new Problem(file.name(), row, what + " can't be saved: " + cause(e)));
                return null;
            }
        }

        // --- What the ledgers hold from earlier imports (D-138, D-139, D-150) --------------------------------------

        private void findMissing(Sheet<TransactionRow> transactionRows, List<FamilyItem> familyItems,
                boolean partnerRead, AfterCutoff after) {
            Set<String> personalRefs = transactionRows.rows().stream().map(r -> EntryMapper.reference(r.id()))
                    .collect(Collectors.toSet());
            personalKnown.forEach((ref, known) -> {
                if (known.hash() != null && ref.matches("xls:[0-9]+") && !personalRefs.contains(ref)
                        && !after.leftAlone(after.owner(), ref.substring(4), known.date())) {
                    missing.add(new RowRef(Source.OWNER_PERSONAL.name(), ref.substring(4),
                            "no longer in the owner's transactions file (D-138)"));
                }
            });
            if (family == null) {
                return;
            }
            Set<String> familyRefs = familyItems.stream().map(FamilyItem::ref).collect(Collectors.toSet());
            List<Long> seats = new ArrayList<>(List.of(family.target().ownerMemberId()));
            if (partnerRead) {
                seats.add(family.target().seatMemberId());
            }
            for (long seat : seats) {
                String prefix = "xls:" + seat + ":";
                boolean owners = seat == family.target().ownerMemberId();
                liveFamilyRefs(family.scope(), prefix).forEach((ref, date) -> {
                    String excelId = ref.substring(prefix.length());
                    if (!familyRefs.contains(ref)
                            && !after.leftAlone(owners ? after.owner() : after.partner(), excelId, date)) {
                        missing.add(new RowRef(owners ? "OWNER" : Source.PARTNER.name(), excelId,
                                "no longer in the files (D-138)"));
                    }
                });
            }
        }

        // --- Personal entries ---------------------------------------------------------------------------------------

        private void importPersonal(String file, Integer row, String excelId, String kind, RowResult result) {
            result.warnings().forEach(w -> warnings.add(new Problem(file, row, excelId, w)));
            switch (result) {
                case RowResult.Entry entry -> writePersonal(file, row, excelId, entry.plan());
                case RowResult.Skipped skip -> {
                    tally(Source.OWNER_PERSONAL, skip.zeroFxGain() ? "FX_GAIN" : "ZERO").skipped++;
                    skipped.add(new Problem(file, row, excelId, skip.reason()));
                    if (skip.zeroFxGain()) {
                        zeroFxRows++;
                    }
                }
                case RowResult.Failed failure -> {
                    tally(Source.OWNER_PERSONAL, kind).failed++;
                    errors.add(new Problem(file, row, excelId, String.join("; ", failure.errors())));
                }
            }
        }

        /** Writes or keeps one personal entry by D-139's rules, under a savepoint with the counterparties it names. */
        private void writePersonal(String file, Integer row, String excelId, EntryPlan plan) {
            String ref = plan.externalRef();
            Tally tally = tally(Source.OWNER_PERSONAL, plan.kind().name());
            if (deletedInTheApp.contains(ref)) {
                tally.conflicts++;
                conflicts.add(new RowRef(Source.OWNER_PERSONAL.name(), excelId == null ? ref : excelId,
                        "deleted in the app after its import (D-150)"));
                return;
            }
            PersonalKnown known = personalKnown.get(ref);
            if (known != null && known.hash() == null) {
                tally.conflicts++;
                conflicts.add(new RowRef(Source.OWNER_PERSONAL.name(), excelId == null ? ref : excelId,
                        "the ledger has an entry with this reference that this import didn't write"));
                return;
            }
            if (known != null && known.hash().equals(plan.contentHash())) {
                tally.unchanged++;
                drift.addAll(plan.drift());
                return;
            }
            if (known != null && known.version() != known.importVersion()) {
                tally.conflicts++;
                conflicts.add(new RowRef(Source.OWNER_PERSONAL.name(), excelId == null ? ref : excelId,
                        "changed in the app since its last import (D-139)"));
                return;
            }
            Map<String, Long> created = new HashMap<>();
            try {
                savepoint.executeWithoutResult(status -> {
                    long id = known == null
                            ? entries.createImported(personal, command(plan, created), batchId, ref).id()
                            : entries.update(personal, known.id(), known.version(), command(plan, created)).id();
                    stampEntry(personal, id, plan.contentHash());
                });
            } catch (InvalidEntryException e) {
                tally.failed++;
                errors.add(new Problem(file, row, excelId, String.join("; ", e.violations())));
                return;
            } catch (RuntimeException e) {
                tally.failed++;
                errors.add(new Problem(file, row, excelId, "the entry can't be written: " + message(e)));
                return;
            }
            counterpartyIds.putAll(created);
            counterpartiesCreated += created.size();
            if (known == null) {
                tally.created++;
            } else {
                tally.updated++;
            }
            drift.addAll(plan.drift());
            wrote();
        }

        /**
         * Every {@link #ANALYZE_EVERY} writes, the planner's statistics of the tables the import fills: they grow from
         * few rows to tens of thousands in this one transaction, and plans made for empty tables would scan them at every
         * row (an agent's choice, D3b). ANALYZE is transactional: a dry run's rollback takes it back too.
         */
        private void wrote() {
            if (++writes % ANALYZE_EVERY == 0) {
                analyze(personal);
            }
        }

        private ImportedCommand command(EntryPlan plan, Map<String, Long> created) {
            List<PostingLine> postings = plan.postings().stream()
                    .map(line -> new PostingLine(line.accountId(), line.currency(), line.amount(), line.categoryId(),
                            counterpartyId(line.counterparty(), created)))
                    .toList();
            return new ImportedCommand(plan.date(), counterpartyId(plan.payee(), created), plan.memo(), plan.kind(),
                    postings);
        }

        private Long counterpartyId(Party party, Map<String, Long> created) {
            if (party == null) {
                return null;
            }
            String key = key(party.name());
            Long id = Optional.ofNullable(counterpartyIds.get(key)).orElseGet(() -> created.get(key));
            if (id == null) {
                id = counterparties.save(new Counterparty(null, personal.userId(), personal.ledgerId(), party.name(),
                        party.kind(), null)).id();
                created.put(key, id);
            }
            return id;
        }

        // --- The family budget's records and settlements ----------------------------------------------------------

        /** Writes or keeps one family record or settlement by D-139's rules (ImportSync), under a savepoint. */
        private void importFamily(String file, FamilyItem item) {
            Tally tally = tally(item.source(), item.kind());
            Integer row = item.row() == 0 ? null : item.row();
            if (family == null) {
                tally.failed++;
                errors.add(new Problem(file, row, item.excelId(), "a family row needs the family budget and the "
                        + "partner's seat: choose them (D-141)"));
                return;
            }
            ImportSync.Known known = familyKnown.get(item.ref());
            switch (ImportSync.recognise(known, item.hash())) {
                case UNCHANGED -> {
                    tally.unchanged++;
                    drift.addAll(item.drift());
                    return;
                }
                case CONFLICT -> {
                    tally.conflicts++;
                    conflicts.add(new RowRef(item.source().name(), item.excelId(),
                            "changed in the app since its last import (D-139)"));
                    return;
                }
                case DELETED -> {
                    tally.conflicts++;
                    conflicts.add(new RowRef(item.source().name(), item.excelId(),
                            "deleted in the app after its import (D-113)"));
                    return;
                }
                case NEW, EDITED -> {
                }
            }
            Map<String, Long> created = new HashMap<>();
            try {
                savepoint.executeWithoutResult(status -> {
                    long id = known == null ? create(item, created) : update(item, known, created);
                    familySync.stamp(family.scope(), id, item.ref(), item.hash());
                });
            } catch (RuleViolationException e) {
                tally.failed++;
                String reasons = e.details().isEmpty() ? String.join("; ", e.violations())
                        : e.details().stream().map(v -> v.code() + ": " + v.message()).collect(Collectors.joining("; "));
                errors.add(new Problem(file, row, item.excelId(), reasons));
                return;
            } catch (RuntimeException e) {
                tally.failed++;
                errors.add(new Problem(file, row, item.excelId(), "the family budget refuses it: " + message(e)));
                return;
            }
            counterpartyIds.putAll(created);
            counterpartiesCreated += created.size();
            if (known == null) {
                tally.created++;
            } else {
                tally.updated++;
            }
            drift.addAll(item.drift());
            wrote();
        }

        private long create(FamilyItem item, Map<String, Long> created) {
            LedgerScope scope = family.scope();
            long owner = family.target().ownerMemberId();
            long seat = family.target().seatMemberId();
            return switch (item) {
                case RecordSpec spec -> records.createForImport(scope, personal, new NewFamilyRecord(spec.type(), spec.date(),
                        categoryId(spec), spec.amount(), null, spec.ownerPays() ? owner : seat,
                        spec.ownerPays() ? accountId(spec.accountCode()) : null, false, split(spec), spec.note(),
                        spec.currency(), spec.accountAmount(), spec.ownerPays() ? spec.accountCurrency() : null,
                        spec.refund(), counterparty(spec.counterparty(), Counterparty.Kind.PERSON, created),
                        counterparty(spec.payee(), Counterparty.Kind.MERCHANT, created)));
                case SettlementSpec spec -> records.settleForImport(scope, personal, new NewSettlement(spec.date(),
                        spec.amount(), spec.ownerPays() ? owner : seat, spec.ownerPays() ? seat : owner, null,
                        accountId(spec.accountCode()), false, spec.currency(), spec.accountAmount(),
                        spec.accountCurrency(), counterparty(spec.counterparty(), Counterparty.Kind.PERSON, created),
                        spec.throughExchange()));
            };
        }

        /**
         * D-139's update of a record nobody changed in the app: every field as the row has it now, the way the app
         * changes a record. A row that changed its kind (expense, refund, income, settlement, who paid) can't be
         * changed in place: an error, for the owner to settle by hand.
         */
        private long update(FamilyItem item, ImportSync.Known known, Map<String, Long> created) {
            LedgerScope scope = family.scope();
            long owner = family.target().ownerMemberId();
            long seat = family.target().seatMemberId();
            FamilyRecordView current = records.get(scope, known.recordId());
            switch (item) {
                case RecordSpec spec -> {
                    long payer = spec.ownerPays() ? owner : seat;
                    if (!current.type().equals(spec.type()) || current.refund() != spec.refund()
                            || current.payer().memberId() != payer) {
                        throw new ConflictException("the row changed its kind since its last import (an expense, a "
                                + "refund, an income or who paid): it can't be changed in place; change it in the app");
                    }
                    return records.updateForImport(scope, personal, known.recordId(), known.version(), new FamilyRecordChanges(
                            categoryId(spec), false, null, split(spec), spec.date(), spec.amount(), payer,
                            spec.ownerPays() ? accountId(spec.accountCode()) : null, false, spec.ownerPays(),
                            spec.note(), spec.currency(), spec.accountAmount(),
                            spec.ownerPays() ? spec.accountCurrency() : null,
                            counterparty(spec.counterparty(), Counterparty.Kind.PERSON, created), spec.ownerPays(),
                            counterparty(spec.payee(), Counterparty.Kind.MERCHANT, created)));
                }
                case SettlementSpec spec -> {
                    long payer = spec.ownerPays() ? owner : seat;
                    if (!current.type().equals("SETTLEMENT") || current.payer().memberId() != payer) {
                        throw new ConflictException("the row changed its kind since its last import (who paid the "
                                + "settlement): it can't be changed in place; change it in the app");
                    }
                    if (spec.throughExchange() && !current.currency().equals(spec.currency())) {
                        throw new ConflictException("a conversion's currency changed since its last import: it can't "
                                + "be changed in place; change it in the app");
                    }
                    return records.updateForImport(scope, personal, known.recordId(), known.version(), new FamilyRecordChanges(
                            null, false, null, null, spec.date(), spec.amount(), null,
                            spec.throughExchange() ? null : accountId(spec.accountCode()), false, false, null,
                            spec.currency(), spec.throughExchange() ? null : spec.accountAmount(),
                            spec.throughExchange() ? null : spec.accountCurrency(),
                            spec.throughExchange() ? null
                                    : counterparty(spec.counterparty(), Counterparty.Kind.PERSON, created),
                            false, null));
                }
            }
        }

        private long categoryId(RecordSpec spec) {
            Long id = familyCategoryIds.get(spec.categoryCode());
            if (id == null) {
                throw new ConflictException("the family budget has no category for the row");
            }
            return id;
        }

        private long accountId(String code) {
            AccountRef account = chart.accountsByCode().get(code);
            if (account == null) {
                throw new ConflictException("the account isn't in the ledger");
            }
            return account.id();
        }

        /** 50/50 between the owner and the seat (D-12), or the whole on the owner (D-73). */
        private RecordSplit split(RecordSpec spec) {
            long owner = family.target().ownerMemberId();
            if (spec.ownerOnly()) {
                return new RecordSplit(RecordSplit.Method.ONE_MEMBER, List.of(), owner);
            }
            return new RecordSplit(RecordSplit.Method.PERCENT, List.of(
                    new RecordSplit.ShareInput(owner, 5000, null),
                    new RecordSplit.ShareInput(family.target().seatMemberId(), 5000, null)), null);
        }

        private Long counterparty(String name, Counterparty.Kind kind, Map<String, Long> created) {
            return name == null ? null : counterpartyId(new Party(name, kind), created);
        }

        // --- The report ---------------------------------------------------------------------------------------------

        private Tally tally(Source source, String kind) {
            return tallies.computeIfAbsent(source.name() + " " + kind, k -> new Tally());
        }

        private List<Count> counts() {
            List<Count> counts = new ArrayList<>();
            tallies.forEach((key, t) -> {
                String[] parts = key.split(" ", 2);
                counts.add(new Count(parts[0], parts[1], t.created, t.updated, t.unchanged, t.conflicts, t.failed,
                        t.skipped, t.notImported));
            });
            return counts;
        }

        private Totals totals() {
            int created = 0, updated = 0, unchanged = 0, conflicted = 0, failed = 0, skip = 0, notImported = 0;
            for (Tally t : tallies.values()) {
                created += t.created;
                updated += t.updated;
                unchanged += t.unchanged;
                conflicted += t.conflicts;
                failed += t.failed;
                skip += t.skipped;
                notImported += t.notImported;
            }
            return new Totals(created, updated, unchanged, missing.size(), conflicted, failed, skip, notImported);
        }

        private Json summary(ImportReport report) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("outcome", report.outcome());
            summary.put("totals", report.totals());
            summary.put("errors", report.errors().size());
            summary.put("warnings", report.warnings().size());
            summary.put("reconciliation", report.reconciliation() == null ? null : report.reconciliation().passed());
            try {
                return new Json(json.writeValueAsString(summary));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // --- The database, by the scope of the ledger it reads -------------------------------------------------------

    /** How many writes between two refreshes of the planner's statistics. */
    static final int ANALYZE_EVERY = 500;

    /** The planner's statistics of the tables the import fills, for the ledger's statements to come. */
    private void analyze(LedgerScope personal) {
        try {
            savepoint.executeWithoutResult(status -> jdbc.sql("""
                    ANALYZE journal_entry, posting, account, counterparty, category, family_record, family_share,
                            family_entry_link, family_record_change""").update());
        } catch (DataAccessException e) {
            // Only slower without it: the statements' results are the same.
        }
    }

    /** An entry an earlier import wrote: its id, the hash and version it left, and its version now. */
    private record PersonalKnown(long id, String hash, int importVersion, int version, LocalDate date) {
    }

    private Map<String, PersonalKnown> personalKnown(LedgerScope personal) {
        Map<String, PersonalKnown> known = new HashMap<>();
        jdbc.sql("""
                SELECT id, external_ref, import_hash, coalesce(import_version, -1) AS import_version, version,
                       entry_date
                FROM journal_entry WHERE ledger_id = :ledgerId AND external_ref IS NOT NULL""")
                .param("ledgerId", personal.ledgerId())
                .query(row -> {
                    known.put(row.getString("external_ref"), new PersonalKnown(row.getLong("id"),
                            row.getString("import_hash"), row.getInt("import_version"), row.getInt("version"),
                            row.getObject("entry_date", LocalDate.class)));
                });
        return known;
    }

    private Set<String> deletedInTheApp(LedgerScope personal) {
        return new HashSet<>(jdbc.sql("SELECT external_ref FROM imported_entry_deleted WHERE ledger_id = :ledgerId")
                .param("ledgerId", personal.ledgerId()).query(String.class).list());
    }

    /** The references of the live records of the family ledger with this prefix (a seat's, D-142), with their dates. */
    private Map<String, LocalDate> liveFamilyRefs(LedgerScope family, String prefix) {
        Map<String, LocalDate> refs = new TreeMap<>();
        jdbc.sql("""
                SELECT external_ref, record_date FROM family_record
                WHERE ledger_id = :ledgerId AND deleted_at IS NULL AND starts_with(external_ref, :prefix)""")
                .param("ledgerId", family.ledgerId()).param("prefix", prefix)
                .query(row -> {
                    refs.put(row.getString("external_ref"), row.getObject("record_date", LocalDate.class));
                });
        return refs;
    }

    /** D-139: what the entry's row held, and its version right after this write. */
    private void stampEntry(LedgerScope personal, long entryId, String hash) {
        jdbc.sql("""
                UPDATE journal_entry SET import_hash = :hash, import_version = version
                WHERE id = :id AND ledger_id = :ledgerId""")
                .param("hash", hash).param("id", entryId).param("ledgerId", personal.ledgerId())
                .update();
    }

    /**
     * Each account's displayed change by date and currency, in the personal ledger: ASSET as the sum of its postings,
     * the others as minus the sum (rule 4); the family budgets' debt accounts as FAMILY_DEBT, the budget's own only.
     */
    private Map<Key, NavigableMap<LocalDate, BigDecimal>> appBalances(LedgerScope personal) {
        Map<Key, NavigableMap<LocalDate, BigDecimal>> balances = new HashMap<>();
        jdbc.sql("""
                SELECT CASE WHEN a.family_ledger_id IS NOT NULL THEN 'FAMILY_DEBT' ELSE a.code END AS code, a.type,
                       p.currency, e.entry_date, sum(p.amount) AS amount
                FROM posting p
                JOIN journal_entry e ON e.id = p.entry_id
                JOIN account a ON a.id = p.account_id
                WHERE e.ledger_id = :ledgerId AND a.ledger_id = :ledgerId
                GROUP BY 1, a.type, p.currency, e.entry_date""")
                .param("ledgerId", personal.ledgerId())
                .query(row -> {
                    BigDecimal amount = row.getBigDecimal("amount");
                    if (!"ASSET".equals(row.getString("type"))) {
                        amount = amount.negate();
                    }
                    balances.computeIfAbsent(new Key(row.getString("code"), row.getString("currency")),
                            k -> new TreeMap<>())
                            .merge(row.getObject("entry_date", LocalDate.class), amount, BigDecimal::add);
                });
        return balances;
    }

    /**
     * Runs the checks the database defers to the commit now, so that a dry run meets them too, and a commit reports
     * them instead of failing. They cover what this transaction wrote, which is the personal ledger's and its family
     * budget's.
     *
     * @return what fails, or null
     */
    private String deferredConstraintsFail(LedgerScope personal) {
        try {
            savepoint.executeWithoutResult(status -> jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update());
            return null;
        } catch (DataAccessException e) {
            return "the database's checks at commit fail for ledger %d: %s".formatted(personal.ledgerId(), cause(e));
        }
    }

    // --- Helpers -------------------------------------------------------------------------------------------------

    private static String id(long id) {
        return String.valueOf(id);
    }

    /** The kind of a personal row for the report, before it is written. */
    private static String kindOf(RowResult result) {
        return result instanceof RowResult.Entry entry ? entry.plan().kind().name() : "ROW";
    }

    /** Each distinct message, with its count and the first 20 Excel IDs (or row numbers) that have it. */
    static List<MessageCount> distinct(Collection<Problem> problems) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (Problem problem : problems) {
            rows.computeIfAbsent(problem.message(), m -> new ArrayList<>()).add(problem.excelId() != null
                    ? problem.excelId() : problem.row() != null ? "row " + problem.row() : "file " + problem.file());
        }
        List<MessageCount> summary = new ArrayList<>();
        rows.forEach((message, ids) -> summary.add(new MessageCount(message, ids.size(),
                List.copyOf(ids.subList(0, Math.min(20, ids.size()))))));
        return summary;
    }

    private static AccountRef accountRef(Account account) {
        return new AccountRef(account.id(), account.code(), account.type(), account.requiresCounterparty());
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String message(RuntimeException e) {
        if (e instanceof DataAccessException data) {
            return cause(data);
        }
        if (e instanceof OptimisticLockingFailureException) {
            return "it changed meanwhile";
        }
        return e.getMessage();
    }

    private static String cause(DataAccessException e) {
        return NestedExceptionUtils.getMostSpecificCause(e).getMessage();
    }
}
