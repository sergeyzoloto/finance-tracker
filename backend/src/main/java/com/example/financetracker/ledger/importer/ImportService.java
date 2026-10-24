package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
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
import com.example.financetracker.ledger.access.MemberRole;
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
 * <p>
 * Once the partner's seat has an account (D3c), each member imports only his own file (D-175): the owner's import takes
 * no partner's file, and the partner imports his own, with the owner's layout (D-177), as {@link MemberPlanner} reads
 * it: his personal rows into his personal ledger, his payment side on the records of his family rows (D-181), his
 * side on the settlements his settlement rows match (D-178), his benefit rows checked (D-179). The family debt is
 * reconciled up to the earlier of the seats' latest committed cutoffs (D-175).
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

    /** A family budget's side of the run: the uploader's scope in it, and the seats. */
    private record FamilyContext(LedgerScope scope, FamilyTarget target, Mode mode) {
    }

    /** Which import a run is (D-175). */
    enum Mode {
        /** The owner's, with the partner's place a member without an account, whose rows he uploads. */
        OWNER,
        /** The owner's own file only, once the partner's place has an account. */
        OWNER_AFTER_CLAIM,
        /** A member's own file, once he took his seat. */
        MEMBER
    }

    /** D-181: a member's side of a record as his import left it. */
    private record SideRow(String ref, long recordId, String sideHash, String appHash) {
    }

    /** A record of a member's family rows, as the import reads it. */
    private record MemberRecord(long id, String currency, long payerId, int version, BigDecimal share) {
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
        /** OWNER_PERSONAL, or MEMBER_PERSONAL in a member's own import. */
        private Source personalSource = Source.OWNER_PERSONAL;
        private final MemberTally memberTally = new MemberTally();
        private Map<String, MemberRecord> memberRecords = Map.of();
        private Map<Long, SideRow> sideRows = Map.of();
        /** D-219: what the ledger holds of the rows earlier imports wrote, to compare with what they map to now. */
        private Map<String, StoredEntry> personalStored = Map.of();
        private Map<Long, StoredRecord> familyStored = Map.of();
        private Map<Long, AccountRef> accountsById = Map.of();
        private final List<RowRef> appEdited = new ArrayList<>();
        private final List<ImportReport.AppEdit> appEdits = new ArrayList<>();
        private final List<RowRef> replanned = new ArrayList<>();
        private final List<RowRef> restamped = new ArrayList<>();
        private final List<RowRef> notReplannable = new ArrayList<>();
        private final List<String> twinsMerged = new ArrayList<>();

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
            if (family != null && family.mode() == Mode.OWNER && partnerRows == null) {
                errors.add(new Problem(null, null, "with a family budget, the partner's family rows are needed too "
                        + "(D-82): choose his transactions file"));
            }

            Map<String, AccountRef> accountsByName = accounts(accountRows);
            Map<String, CategoryRow> categoryRowsByName = categoryRows.rows().stream()
                    .collect(Collectors.toMap(CategoryRow::name, Function.identity(), (a, b) -> a));
            long ownerId = family == null ? 0 : family.target().ownerMemberId();
            long seatId = family == null ? 0 : family.target().seatMemberId();
            if (family != null && family.mode() == Mode.MEMBER) {
                return member(accountRows, categoryRows, transactionRows, balance, accountsByName, categoryRowsByName,
                        ownerLatest, cutoff, after);
            }
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
            // D-83's months before the partner's first family row, whatever "Import up to" leaves out (D-165); once his
            // place has an account and his rows aren't uploaded any more, his first record's (an agent's choice, D3c).
            LocalDate lumpsBefore = family != null && family.mode() == Mode.OWNER_AFTER_CLAIM
                    ? seatFirst(family.scope(), seatId) : partnerFirst;
            List<RecordSpec> lumps = family != null && lumpsBefore != null ? planner.lumps(change, lumpsBefore)
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
            if (family != null && family.mode() == Mode.OWNER_AFTER_CLAIM) {
                // The partner's rows are his own import's now, but their records are in the owner's family debt too.
                LocalDate joined = familyLedgers.members(family.scope()).stream().filter(m -> m.id() == ownerId)
                        .findFirst().orElseThrow().joinDate();
                familyCurrencies.addAll(recordCurrencies(family.scope(), ownerId, joined));
            }
            transactionRows.rows().forEach(row -> {
                currencies.add(row.debitCurrencyOrRow());
                currencies.add(row.creditCurrencyOrRow());
            });

            familyCategoryIds = familyCategories(familyItems, categoryRows);
            chart = chart(accountsByName, categoryRows);
            EntryMapper mapper = new EntryMapper(chart);
            counterpartyIds = counterparties.findAll(personal).stream()
                    .collect(Collectors.toMap(c -> key(c.name()), Counterparty::id, (first, second) -> first));
            personalKnown = personalKnown(personal);
            personalStored = personalStored(personal);
            deletedInTheApp = deletedInTheApp(personal);
            if (family != null) {
                familyKnown = familySync.known(family.scope(), familyItems.stream().map(FamilyItem::ref).toList());
                familyStored = familyStored(family.scope());
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
            // D-169: the owner's hand-rounded halves of the partner's incomes that D-84 leaves out; once his place has
            // an account, his incomes are his records, up to the cutoff.
            if (family != null && family.mode() == Mode.OWNER_AFTER_CLAIM && cutoff != null) {
                carriedByThePartner.addAll(seatIncomes(family.scope(), seatId, cutoff));
                // D-12 against Excel's exact halves for the records of the partner's file, which his rows no longer
                // bring: as a member's import explains the other member's records (an agent's choice, D3c).
                LocalDate joined = familyLedgers.members(family.scope()).stream().filter(m -> m.id() == ownerId)
                        .findFirst().orElseThrow().joinDate();
                drift.addAll(othersRecordsDrift(family.scope(), ownerId, joined));
            }
            Reconciler.OwnerHalves ownerHalves = Reconciler.ownerHalf(carriedByTheOwner, carriedByThePartner);
            drift.addAll(ownerHalves.drift());
            // D-175: the seats this run imports; the others' latest committed cutoffs limit the family debt.
            List<Long> seats = new ArrayList<>();
            if (family != null) {
                seats.add(ownerId);
                if (partnerRows != null) {
                    seats.add(seatId);
                }
            }
            LocalDate familyDebtUpTo = family == null ? null : othersCutoff(family.scope(), seats);
            ImportReport.Reconciliation reconciliation = balance.isEmpty() || cutoff == null ? null
                    : Reconciler.reconcile(appBalances(personal), balance, drift, familyCurrencies, comparedCodes,
                            cutoff, older.withDayOfMonth(1), ownerHalves.months(), familyDebtUpTo);
            return finish(transactionsFile, partnerRows != null, after, reconciliation, seats, cutoff, null);
        }

        /** The report, the import batch, and on a commit each seat's cutoff (D-175). */
        private ImportReport finish(ImportFile transactionsFile, boolean partnerRead, AfterCutoff after,
                ImportReport.Reconciliation reconciliation, List<Long> seats, LocalDate cutoff,
                ImportReport.MemberSummary member) {
            if (reconciliation != null) {
                reconciliation.appOnly().forEach(a -> warnings.add(new Problem(null, null,
                        "an account of the app has a balance and no column in the daily balance file")));
            }
            Outcome outcome = !commit ? Outcome.DRY_RUN : errors.isEmpty() ? Outcome.COMMITTED : Outcome.ABORTED;
            if (outcome == Outcome.COMMITTED && family != null && cutoff != null) {
                seats.forEach(seat -> storeCutoff(family.scope(), seat, cutoff));
            }
            ImportReport report = new ImportReport(personal.userId(), commit, outcome, startedAt, files,
                    family == null ? null : family.target(), from == null ? null : new Period(from, to),
                    after.upTo(), after.counts(partnerRead),
                    List.copyOf(currencies), counts(), totals(), missing, conflicts, errors, warnings,
                    distinct(errors), distinct(warnings), distinct(skipped),
                    new ReferenceCounts(accountsCreated, accountsUpdated, categoriesCreated, categoriesUpdated,
                            familyCategoriesCreated, categoriesMerged, counterpartiesCreated),
                    zeroFxRows, fxRows, reconciliation, reports.integrityCheck(personal), member,
                    new ImportReport.Sync(List.copyOf(appEdited), List.copyOf(appEdits), List.copyOf(replanned),
                            List.copyOf(restamped), List.copyOf(notReplannable), List.copyOf(twinsMerged)));
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
         * The family budget the uploader names, as a member of it, and the seat whose rows the file has besides his
         * own: an ACTIVE member. Another user's budget or seat answers like a missing one (404). Which import it is
         * (D-175): the owner's with a place without an account, whose rows he uploads; the owner's own file only once
         * that place has an account, which takes no partner's file; or, with his own seat named, a member's own import
         * of the seat he took, split with the one other ACTIVE member.
         */
        private FamilyContext family() {
            if (request.familyLedgerId() == null) {
                return null;
            }
            if (!familySwitch.enabled()) {
                throw new NotFoundException("Family budgets are switched off");
            }
            LedgerScope scope = access.member(personal.userId(), request.familyLedgerId());
            List<FamilyMemberView> members = familyLedgers.members(scope);
            FamilyMemberView seat = members.stream()
                    .filter(m -> m.id() == request.seatMemberId() && m.status() == MemberStatus.ACTIVE)
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Member %d not found".formatted(request.seatMemberId())));
            String name = familyLedgers.get(scope).name();
            if (seat.id() == scope.memberId()) {
                if (request.partnerTransactions() != null) {
                    throw new ConflictException("You import your own files only (D-175): the partner's transactions "
                            + "file isn't taken");
                }
                if (!seat.claimedSeat()) {
                    throw new ConflictException("Your own import takes over the rows an import wrote for the place you "
                            + "took, and you took no place in this family budget (D-175)");
                }
                List<FamilyMemberView> others = members.stream()
                        .filter(m -> m.id() != seat.id() && m.status() == MemberStatus.ACTIVE).toList();
                if (others.size() != 1) {
                    throw new ConflictException(("Your import splits your family rows with one other member, and this "
                            + "family budget has %d other active members").formatted(others.size()));
                }
                FamilyMemberView other = others.getFirst();
                personalSource = Source.MEMBER_PERSONAL;
                return new FamilyContext(scope, new FamilyTarget(scope.ledgerId(), name, scope.memberId(), other.id(),
                        other.displayName(), Mode.MEMBER.name()), Mode.MEMBER);
            }
            if (scope.role() != MemberRole.OWNER) {
                throw new ConflictException("Only an owner of the family budget imports the rows of another place; "
                        + "your own import names your own place (D-175)");
            }
            Mode mode = Mode.OWNER;
            if (seat.hasAccount()) {
                if (request.partnerTransactions() != null) {
                    throw new ConflictException(("%s has an account now and imports their own file (D-175): the "
                            + "partner's transactions file is taken only for a place without an account. Leave it out")
                            .formatted(seat.displayName()));
                }
                mode = Mode.OWNER_AFTER_CLAIM;
            }
            return new FamilyContext(scope, new FamilyTarget(scope.ledgerId(), name, scope.memberId(), seat.id(),
                    seat.displayName(), mode.name()), mode);
        }

        /**
         * The family categories of the family records (D-11): the budget's own with the code, else a new one, into
         * which the owner's personal category with the code merges. By code. Then, since D3e, every other code of the
         * categories file that the budget has already, of the same type, whichever rows use it: once the partner's
         * place imports its own file, the owner's run no longer has the partner's records, and their codes would
         * otherwise become personal categories beside the family's. A personal category of such a code that no posting
         * uses merges into the family's (D-11); one that postings use stays, with a warning, and the code is the
         * family's all the same.
         */
        private Map<String, Long> familyCategories(List<FamilyItem> items, Sheet<CategoryRow> categoryRows) {
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
            Map<String, LedgerCategory> familyByCode = categories.findAll(family.scope()).stream()
                    .collect(Collectors.toMap(LedgerCategory::code, Function.identity(), (a, b) -> a));
            Map<String, LedgerCategory> mineByCode = categories.findAll(personal).stream()
                    .collect(Collectors.toMap(LedgerCategory::code, Function.identity(), (a, b) -> a));
            for (CategoryRow row : categoryRows.rows()) {
                LedgerCategory familyCategory = familyByCode.get(row.code());
                if (familyCategory == null || familyCategory.type() != row.type() || ids.containsKey(row.code())) {
                    continue;
                }
                ids.put(row.code(), familyCategory.id());
                LedgerCategory twin = mineByCode.get(row.code());
                if (twin == null || twin.type() != row.type()) {
                    continue;
                }
                long used = categoryPostings(personal, twin.id());
                if (used > 0) {
                    warnings.add(new Problem(categoryRows.file().name(), row.row(), ("a personal category of yours has the "
                            + "family budget's code and %d postings: not merged (D-11); its rows map to the family "
                            + "category").formatted(used)));
                    continue;
                }
                try {
                    FamilyLedgerService.ImportedCategory category = savepoint.execute(status -> familyLedgers
                            .importCategory(family.scope(), personal, row.code(), familyCategory.name(), row.type()));
                    categoriesMerged += category.merged() ? 1 : 0;
                    twinsMerged.add(row.code());
                } catch (ConflictException | DataAccessException e) {
                    errors.add(new Problem(request.categories().name(), row.row(),
                            "your personal category can't merge into the family budget's: " + message(e)));
                }
            }
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
            accountsById = refsByCode.values().stream().collect(Collectors.toMap(AccountRef::id, Function.identity()));
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
                    missing.add(new RowRef(personalSource.name(), ref.substring(4),
                            "no longer in the transactions file (D-138)"));
                }
            });
            if (family == null) {
                return;
            }
            Set<String> familyRefs = familyItems.stream().map(FamilyItem::ref).collect(Collectors.toSet());
            // A member's row that his file still has is not missing, whatever became of it in this run.
            Set<String> memberRows = family.mode() != Mode.MEMBER ? Set.of() : transactionRows.rows().stream()
                    .map(row -> ImportPlanner.ref(family.target().ownerMemberId(), String.valueOf(row.id())))
                    .collect(Collectors.toSet());
            List<Long> seats = new ArrayList<>(List.of(family.target().ownerMemberId()));
            if (partnerRead) {
                seats.add(family.target().seatMemberId());
            }
            for (long seat : seats) {
                String prefix = "xls:" + seat + ":";
                boolean owners = seat == family.target().ownerMemberId();
                liveFamilyRefs(family.scope(), prefix).forEach((ref, date) -> {
                    String excelId = ref.substring(prefix.length());
                    if (!familyRefs.contains(ref) && !memberRows.contains(ref)
                            && !after.leftAlone(owners ? after.owner() : after.partner(), excelId, date)) {
                        missing.add(new RowRef(owners ? family.mode() == Mode.MEMBER ? "MEMBER" : "OWNER"
                                : Source.PARTNER.name(), excelId, "no longer in the files (D-138)"));
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
                    tally(personalSource, skip.zeroFxGain() ? "FX_GAIN"
                            : skip.reason().equals(EntryMapper.SAME_ACCOUNT_NOOP) ? "SAME_ACCOUNT_NOOP" : "ZERO").skipped++;
                    skipped.add(new Problem(file, row, excelId, skip.reason()));
                    if (skip.zeroFxGain()) {
                        zeroFxRows++;
                    }
                }
                case RowResult.Failed failure -> {
                    tally(personalSource, kind).failed++;
                    errors.add(new Problem(file, row, excelId, String.join("; ", failure.errors())));
                }
            }
        }

        /** Writes or keeps one personal entry by D-139's rules, under a savepoint with the counterparties it names. */
        private void writePersonal(String file, Integer row, String excelId, EntryPlan plan) {
            String ref = plan.externalRef();
            Tally tally = tally(personalSource, plan.kind().name());
            if (deletedInTheApp.contains(ref)) {
                tally.conflicts++;
                conflicts.add(new RowRef(personalSource.name(), excelId == null ? ref : excelId,
                        "deleted in the app after its import (D-150)"));
                return;
            }
            PersonalKnown known = personalKnown.get(ref);
            if (known != null && known.hash() == null) {
                tally.conflicts++;
                conflicts.add(new RowRef(personalSource.name(), excelId == null ? ref : excelId,
                        "the ledger has an entry with this reference that this import didn't write"));
                return;
            }
            StoredEntry stored = known == null ? null : personalStored.get(ref);
            ImportSync.Outcome outcome = ImportSync.recognise(known == null ? null
                    : new ImportSync.Known(known.id(), known.hash(), known.importVersion(), known.version(), false),
                    plan.contentHash(), stored != null && stored.holds(plan));
            String name = excelId == null ? ref : excelId;
            switch (outcome) {
                case UNCHANGED -> {
                    tally.unchanged++;
                    drift.addAll(plan.drift());
                    return;
                }
                case RESTAMP -> {
                    stampEntry(personal, known.id(), plan.contentHash());
                    tally.unchanged++;
                    restamped.add(new RowRef(personalSource.name(), name, RESTAMPED));
                    drift.addAll(plan.drift());
                    return;
                }
                case APP_EDITED -> {
                    tally.unchanged++;
                    appEdited.add(new RowRef(personalSource.name(), name, APP_EDITED));
                    drift.addAll(plan.drift());
                    appEditedDrift(name, plan, stored);
                    return;
                }
                case CONFLICT, DELETED -> {
                    tally.conflicts++;
                    conflicts.add(new RowRef(personalSource.name(), name,
                            "changed in the app since its last import (D-139)"));
                    return;
                }
                case NEW, EDITED, REPLANNED -> {
                }
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
            if (outcome == ImportSync.Outcome.REPLANNED) {
                replanned.add(new RowRef(personalSource.name(), name, REPLANNED));
            }
            drift.addAll(plan.drift());
            wrote();
        }

        /**
         * D-223: what an entry changed in the app moves on each account's displayed balance, beside what its row maps
         * to (rule 4: ASSET as the sum, the others as minus the sum), on each one's date: explained as APP_EDITED, and
         * listed per account and currency.
         */
        private void appEditedDrift(String excelId, EntryPlan plan, StoredEntry stored) {
            record Change(String code, String currency, LocalDate date) {
            }
            Map<Change, BigDecimal> changes = new LinkedHashMap<>();
            for (StoredLine line : stored.lines()) {
                AccountRef account = accountsById.get(line.accountId());
                changes.merge(new Change(account.code(), line.currency(), stored.date()), shown(account, line.amount()),
                        BigDecimal::add);
            }
            for (EntryPlan.Line line : plan.postings()) {
                AccountRef account = accountsById.get(line.accountId());
                changes.merge(new Change(account.code(), line.currency(), plan.date()),
                        shown(account, line.amount()).negate(), BigDecimal::add);
            }
            Map<Key, BigDecimal> byAccount = new TreeMap<>();
            changes.forEach((change, amount) -> {
                if (amount.signum() == 0) {
                    return;
                }
                drift.add(new Drift(change.code(), change.currency(), change.date(), amount, Drift.Reason.APP_EDITED));
                byAccount.merge(Reconciler.key(change.code(), change.currency(), familyCurrencies), amount,
                        BigDecimal::add);
            });
            byAccount.forEach((key, amount) -> {
                if (amount.signum() != 0) {
                    appEdits.add(new ImportReport.AppEdit(personalSource.name(), excelId, stored.date(), key.account(),
                            key.currency(), strip(amount)));
                }
            });
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
            if (known != null && !known.deleted() && family.mode() == Mode.OWNER_AFTER_CLAIM
                    && (item.source() == Source.OWNER_SEAT || item.source() == Source.OWNER_LUMP)
                    && known.contentHash().equals(item.hash())) {
                // D-219, D-196: the partner's place pays these records now; the owner's import never writes them.
                tally.unchanged++;
                notReplannable.add(new RowRef(item.source().name(), item.excelId(), NOT_REPLANNABLE));
                drift.addAll(item.drift());
                return;
            }
            StoredRecord stored = known == null || known.deleted() ? null : familyStored.get(known.recordId());
            ImportSync.Outcome outcome = ImportSync.recognise(known, item.hash(),
                    stored != null && stored.equals(planned(item)));
            switch (outcome) {
                case UNCHANGED -> {
                    tally.unchanged++;
                    drift.addAll(item.drift());
                    return;
                }
                case RESTAMP -> {
                    familySync.stamp(family.scope(), known.recordId(), item.ref(), item.hash());
                    tally.unchanged++;
                    restamped.add(new RowRef(item.source().name(), item.excelId(), RESTAMPED));
                    drift.addAll(item.drift());
                    return;
                }
                case APP_EDITED -> {
                    tally.unchanged++;
                    appEdited.add(new RowRef(item.source().name(), item.excelId(), APP_EDITED));
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
                case NEW, EDITED, REPLANNED -> {
                }
            }
            if (family.mode() == Mode.OWNER_AFTER_CLAIM && item.source() == Source.OWNER_SEAT) {
                // D-196: the place has an account now; what it paid for the owner is the partner's to record.
                tally.failed++;
                errors.add(new Problem(file, row, item.excelId(), D73_AFTER_CLAIM));
                return;
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
            if (outcome == ImportSync.Outcome.REPLANNED) {
                replanned.add(new RowRef(item.source().name(), item.excelId(), REPLANNED));
            }
            drift.addAll(item.drift());
            wrote();
        }

        /**
         * D-219: what the record of a family row would be as the current code maps it, in the fields whose change in
         * the app changes the record's version: its type and sign, date, currency, amount, category, payer, payee and
         * split. The paying side isn't among them, since a member changes his own account without a new version (D-111);
         * a member's side has its own hashes (D-181).
         */
        private StoredRecord planned(FamilyItem item) {
            long owner = family.target().ownerMemberId();
            long seat = family.target().seatMemberId();
            return switch (item) {
                case RecordSpec spec -> new StoredRecord(spec.type(), spec.refund(), spec.date(), spec.currency(),
                        magnitude(spec.amount()), familyCategoryIds.get(spec.categoryCode()),
                        spec.ownerPays() ? owner : seat, null, spec.ownerOnly() ? "ONE_MEMBER " + owner + ":-"
                                : "PERCENT " + Math.min(owner, seat) + ":5000," + Math.max(owner, seat) + ":5000");
                case SettlementSpec spec -> new StoredRecord("SETTLEMENT", false, spec.date(), spec.currency(),
                        magnitude(spec.amount()), null, spec.ownerPays() ? owner : seat, spec.ownerPays() ? seat : owner,
                        null);
            };
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

        // --- A member's own import (D-175 to D-181, D-188) ----------------------------------------------------------

        /**
         * A member's own import: his personal rows as the owner's are, his family rows on the records an import of his
         * rows wrote before (or new ones), his side on the settlements his settlement rows match, his benefit rows
         * checked, and his reconciliation with his family debt compared as movement since his first day (D-180).
         */
        private ImportReport member(Sheet<AccountRow> accountRows, Sheet<CategoryRow> categoryRows,
                Sheet<TransactionRow> transactionRows, DailyFile balance, Map<String, AccountRef> accountsByName,
                Map<String, CategoryRow> categoryRowsByName, LocalDate latest, LocalDate cutoff, AfterCutoff after) {
            long me = family.target().ownerMemberId();
            LocalDate joinDate = familyLedgers.members(family.scope()).stream().filter(m -> m.id() == me).findFirst()
                    .orElseThrow().joinDate();
            MemberPlanner planner = new MemberPlanner(accountsByName, categoryRowsByName, me,
                    family.target().seatMemberId());
            List<MemberPlanner.Plan> plans = new ArrayList<>();
            for (TransactionRow row : transactionRows.rows()) {
                plans.add(planner.plan(row));
                period(row.date());
            }
            List<FamilyItem> familyItems = new ArrayList<>();
            plans.forEach(plan -> {
                if (plan instanceof MemberPlanner.FamilyRow row) {
                    familyItems.add(row.spec());
                    familyCurrencies.add(row.spec().currency());
                }
            });
            familyCurrencies.addAll(recordCurrencies(family.scope(), me, joinDate));
            transactionRows.rows().forEach(row -> {
                currencies.add(row.debitCurrencyOrRow());
                currencies.add(row.creditCurrencyOrRow());
            });

            familyCategoryIds = familyCategories(familyItems, categoryRows);
            chart = chart(accountsByName, categoryRows);
            EntryMapper mapper = new EntryMapper(chart);
            counterpartyIds = counterparties.findAll(personal).stream()
                    .collect(Collectors.toMap(c -> key(c.name()), Counterparty::id, (first, second) -> first));
            personalKnown = personalKnown(personal);
            personalStored = personalStored(personal);
            deletedInTheApp = deletedInTheApp(personal);
            familyKnown = familySync.known(family.scope(), familyItems.stream().map(FamilyItem::ref).toList());
            familyStored = familyStored(family.scope());
            memberRecords = memberRecords(family.scope(), me);
            sideRows = sideRows(personal);
            findMissing(transactionRows, familyItems, false, after);
            ImportFile transactionsFile = transactionRows.file();
            batchId = batches.save(new ImportBatch(null, personal.userId(), personal.ledgerId(),
                    transactionsFile.name(), transactionsFile.sha256(), !commit, null, null, null)).id();
            analyze(personal);

            // D-62, with his family debt left to his claim (D-180): compared as movement since his first day.
            if (!balance.isEmpty()) {
                Map<DailyFile.Column, BigDecimal> opening = new LinkedHashMap<>();
                LocalDate first = balance.values().firstKey();
                balance.accountColumns().forEach((column, account) -> opening.merge(account,
                        balance.value(first, column), BigDecimal::add));
                Map<String, BigDecimal> firstFamilyDebt = new TreeMap<>();
                RowResult result = mapper.openingEntry(first, opening, firstFamilyDebt);
                importPersonal(balance.file().name(), null, null, "OPENING_BALANCE", result);
                firstFamilyDebt.forEach((currency, amount) -> drift.add(new Drift(Reconciler.FAMILY_DEBT, currency,
                        first, amount.negate(), Drift.Reason.CLAIM_OPENING)));
            }
            drift.addAll(claimOpening(family.scope(), personal, me));
            drift.addAll(claimShares(family.scope(), me, joinDate));

            String file = transactionsFile.name();
            List<MemberPlanner.SettlementRow> settlementRows = new ArrayList<>();
            List<MemberPlanner.ConversionRow> conversionRows = new ArrayList<>();
            List<MemberPlanner.Benefit> benefits = new ArrayList<>();
            for (MemberPlanner.Plan plan : plans) {
                TransactionRow row = plan.row();
                String excelId = id(row.id());
                switch (plan) {
                    case MemberPlanner.Personal personalRow -> {
                        RowResult result = mapper.map(personalRow.row());
                        importPersonal(file, row.row(), excelId, kindOf(result), result);
                        if (result instanceof RowResult.Entry entry && entry.plan().fxGain()) {
                            fxRows.add(new FxRow(row.row(), excelId, row.date(), row.sum(), row.currency()));
                        }
                    }
                    case MemberPlanner.FamilyRow familyRow -> memberFamily(file, familyRow);
                    case MemberPlanner.SettlementRow settlement -> {
                        settlement.warnings().forEach(w -> warnings.add(new Problem(file, row.row(), excelId, w)));
                        settlementRows.add(settlement);
                    }
                    case MemberPlanner.ConversionRow conversion -> conversionRows.add(conversion);
                    case MemberPlanner.Benefit benefit -> {
                        tally(Source.MEMBER_BENEFIT, "INCOME").notImported++;
                        benefits.add(benefit);
                    }
                    case MemberPlanner.Refused refused -> {
                        tally(Source.MEMBER_FAMILY, "ROW").failed++;
                        refused.errors().forEach(m -> errors.add(new Problem(file, row.row(), excelId, m)));
                    }
                    case MemberPlanner.Skipped skippedRow -> {
                        tally(Source.MEMBER_FAMILY, "ZERO").skipped++;
                        skipped.add(new Problem(file, row.row(), excelId, skippedRow.reason()));
                    }
                }
            }
            memberSettlements(file, settlementRows, conversionRows, joinDate);
            memberBenefits(benefits, joinDate, cutoff);
            drift.addAll(othersRecordsDrift(family.scope(), me, joinDate));
            String deferred = deferredConstraintsFail(personal);
            if (deferred != null) {
                errors.add(new Problem(null, null, deferred));
            }

            Set<String> comparedCodes = accountRows.rows().stream().map(AccountRow::code).collect(Collectors.toSet());
            LocalDate familyDebtUpTo = othersCutoff(family.scope(), List.of(me));
            ImportReport.Reconciliation reconciliation = balance.isEmpty() || cutoff == null || latest == null ? null
                    : Reconciler.reconcile(appBalances(personal), balance, drift, familyCurrencies, comparedCodes,
                            cutoff, latest.withDayOfMonth(1), List.of(), familyDebtUpTo);
            return finish(transactionsFile, false, after, reconciliation, List.of(me), cutoff,
                    memberTally.summary());
        }

        /**
         * One of his family rows (D-181): the record an import of his rows wrote, recognised by D-151's hash, with his
         * payment side on it; a new record if no import wrote one. CUR_FAMILY must be the record's currency, and his
         * FAMILY_EXP or FAMILY_INC his share within 0.01 (D-177).
         */
        private void memberFamily(String file, MemberPlanner.FamilyRow familyRow) {
            RecordSpec spec = familyRow.spec();
            TransactionRow row = familyRow.row();
            String excelId = spec.excelId();
            Integer number = row.row();
            familyRow.warnings().forEach(w -> warnings.add(new Problem(file, number, excelId, w)));
            memberTally.familyRows++;
            Tally tally = tally(Source.MEMBER_FAMILY, spec.kind());
            ImportSync.Known known = familyKnown.get(spec.ref());
            MemberRecord current = known == null || known.deleted() ? null : memberRecords.get(spec.ref());
            if (current != null) {
                List<String> problems = new ArrayList<>();
                if (!current.currency().equals(row.familyCurrency())) {
                    problems.add("CUR_FAMILY isn't the family record's currency (D-177)");
                }
                if (current.payerId() != family.target().ownerMemberId()) {
                    problems.add("the family record's payer is no longer you: your payment side can't go on it");
                }
                if (!problems.isEmpty()) {
                    tally.failed++;
                    problems.forEach(m -> errors.add(new Problem(file, number, excelId, m)));
                    return;
                }
                if (current.share() != null
                        && row.familyPart().abs().subtract(current.share().abs()).abs().compareTo(TOLERANCE) > 0) {
                    memberTally.shareWarnings++;
                    warnings.add(new Problem(file, number, excelId,
                            "FAMILY_EXP or FAMILY_INC isn't your share of the family record (D-177)"));
                }
            }
            StoredRecord stored = known == null || known.deleted() ? null : familyStored.get(known.recordId());
            ImportSync.Outcome outcome = ImportSync.recognise(known, spec.hash(),
                    stored != null && stored.equals(planned(spec)));
            switch (outcome) {
                case RESTAMP, APP_EDITED -> {
                    if (outcome == ImportSync.Outcome.RESTAMP) {
                        familySync.stamp(family.scope(), known.recordId(), spec.ref(), spec.hash());
                        restamped.add(new RowRef(Source.MEMBER_FAMILY.name(), excelId, RESTAMPED));
                    } else {
                        appEdited.add(new RowRef(Source.MEMBER_FAMILY.name(), excelId, APP_EDITED));
                    }
                    tally.unchanged++;
                    memberTally.recordsUnchanged++;
                    drift.addAll(spec.drift());
                    memberSide(file, number, excelId, row, known.recordId(), familyRow.sideHash(), spec);
                    return;
                }
                case DELETED -> {
                    tally.conflicts++;
                    memberTally.recordConflicts++;
                    conflicts.add(new RowRef(Source.MEMBER_FAMILY.name(), excelId,
                            "deleted in the app after its import (D-113)"));
                    return;
                }
                case CONFLICT -> {
                    tally.conflicts++;
                    memberTally.recordConflicts++;
                    conflicts.add(new RowRef(Source.MEMBER_FAMILY.name(), excelId,
                            "changed in the app since its last import (D-139)"));
                    memberSide(file, number, excelId, row, known.recordId(), familyRow.sideHash(), spec);
                    return;
                }
                case UNCHANGED -> {
                    tally.unchanged++;
                    memberTally.recordsUnchanged++;
                    drift.addAll(spec.drift());
                    memberSide(file, number, excelId, row, known.recordId(), familyRow.sideHash(), spec);
                    return;
                }
                case NEW, EDITED, REPLANNED -> {
                }
            }
            // A new record, or an edited one with his payment side in the same change (D-89's amount goes with it).
            Map<String, Long> created = new HashMap<>();
            SideRow side = known == null ? null : sideRows.get(known.recordId());
            try {
                savepoint.executeWithoutResult(status -> {
                    long id = known == null ? create(spec, created) : update(spec, known, created);
                    familySync.stamp(family.scope(), id, spec.ref(), spec.hash());
                    saveSide(personal, EntryMapper.reference(row.id()), id, familyRow.sideHash(),
                            appHash(personal, family.scope(), id));
                });
            } catch (RuleViolationException e) {
                tally.failed++;
                String reasons = e.details().isEmpty() ? String.join("; ", e.violations())
                        : e.details().stream().map(v -> v.code() + ": " + v.message()).collect(Collectors.joining("; "));
                errors.add(new Problem(file, number, excelId, reasons));
                return;
            } catch (RuntimeException e) {
                tally.failed++;
                errors.add(new Problem(file, number, excelId, "the family budget refuses it: " + message(e)));
                return;
            }
            counterpartyIds.putAll(created);
            counterpartiesCreated += created.size();
            if (known == null) {
                tally.created++;
                memberTally.recordsCreated++;
                memberTally.sidesAttached++;
            } else {
                tally.updated++;
                if (outcome == ImportSync.Outcome.REPLANNED) {
                    replanned.add(new RowRef(Source.MEMBER_FAMILY.name(), excelId, REPLANNED));
                } else {
                    memberTally.recordsEdited.add(excelId);
                }
                if (side == null) {
                    memberTally.sidesAttached++;
                } else {
                    memberTally.sidesEdited++;
                }
            }
            drift.addAll(spec.drift());
            wrote();
        }

        /**
         * His payment side on a record he paid (D-181): attached the first time, written again when his row's side
         * changed and the app's didn't since, a conflict when both did. Neither the record's version nor its journal
         * changes (D-111).
         */
        private void memberSide(String file, Integer number, String excelId, TransactionRow row, long recordId,
                String sideHash, RecordSpec spec) {
            Tally tally = tally(Source.MEMBER_FAMILY, "PAYMENT_SIDE");
            SideRow known = sideRows.get(recordId);
            if (known != null && known.sideHash().equals(sideHash)) {
                tally.unchanged++;
                memberTally.sidesUnchanged++;
                return;
            }
            if (known != null && !known.appHash().equals(appHash(personal, family.scope(), recordId))) {
                tally.conflicts++;
                memberTally.sideConflicts++;
                conflicts.add(new RowRef(Source.MEMBER_FAMILY.name(), excelId,
                        "your payment side changed in the app since its last import (D-181)"));
                return;
            }
            Map<String, Long> created = new HashMap<>();
            try {
                savepoint.executeWithoutResult(status -> {
                    records.updateForImport(family.scope(), personal, recordId, null, new FamilyRecordChanges(null,
                            false, null, null, null, null, null, accountId(spec.accountCode()), false, true,
                            spec.note(), null, spec.accountAmount(), spec.accountCurrency(),
                            counterparty(spec.counterparty(), Counterparty.Kind.PERSON, created), true,
                            counterparty(spec.payee(), Counterparty.Kind.MERCHANT, created)));
                    saveSide(personal, EntryMapper.reference(row.id()), recordId, sideHash,
                            appHash(personal, family.scope(), recordId));
                });
            } catch (RuleViolationException e) {
                tally.failed++;
                String reasons = e.details().isEmpty() ? String.join("; ", e.violations())
                        : e.details().stream().map(v -> v.code() + ": " + v.message()).collect(Collectors.joining("; "));
                errors.add(new Problem(file, number, excelId, "your payment side: " + reasons));
                return;
            } catch (RuntimeException e) {
                tally.failed++;
                errors.add(new Problem(file, number, excelId, "your payment side can't go on the family record: "
                        + message(e)));
                return;
            }
            counterpartyIds.putAll(created);
            counterpartiesCreated += created.size();
            tally.updated++;
            if (known == null) {
                memberTally.sidesAttached++;
            } else {
                memberTally.sidesEdited++;
            }
            wrote();
        }

        /**
         * D-178: his settlement rows and conversions. A row an earlier import of his matched keeps its settlements;
         * the others are matched by {@link SettlementMatcher} to the settlements his side isn't on yet, from his join
         * date, and his side goes on them. Unmatched rows are errors; the other member's settlements left without his
         * side are warnings. D-188's date drift explains a pair on either side of a month-end.
         */
        private void memberSettlements(String file, List<MemberPlanner.SettlementRow> settlementRows,
                List<MemberPlanner.ConversionRow> conversionRows, LocalDate joinDate) {
            long me = family.target().ownerMemberId();
            Map<String, List<SideRow>> byRef = new HashMap<>();
            // A side D-198 matched to several rows of his holds their references joined (xls:65+66+67).
            Map<String, SideRow> groupOf = new HashMap<>();
            sideRows.values().forEach(side -> {
                if (side.ref().contains(GROUP)) {
                    for (String id : side.ref().substring(EntryMapper.REFERENCE_PREFIX.length()).split(Pattern.quote(GROUP))) {
                        groupOf.put(id, side);
                    }
                } else {
                    byRef.computeIfAbsent(side.ref(), r -> new ArrayList<>()).add(side);
                }
            });
            Map<SideRow, List<MemberPlanner.SettlementRow>> knownGroups = new LinkedHashMap<>();
            Map<String, MemberPlanner.SettlementRow> rowsById = new HashMap<>();
            Map<String, MemberPlanner.ConversionRow> conversionsById = new HashMap<>();
            List<SettlementMatcher.Row> toMatch = new ArrayList<>();
            List<SettlementMatcher.ConversionRow> conversionsToMatch = new ArrayList<>();
            for (MemberPlanner.SettlementRow row : settlementRows) {
                memberTally.settlementRows++;
                String excelId = id(row.row().id());
                SideRow group = groupOf.get(excelId);
                if (group != null) {
                    memberTally.settlementsKnown++;
                    knownGroups.computeIfAbsent(group, g -> new ArrayList<>()).add(row);
                    continue;
                }
                List<SideRow> known = byRef.get(EntryMapper.reference(row.row().id()));
                if (known != null) {
                    memberTally.settlementsKnown++;
                    knownSettlement(file, row.row(), excelId, row.sideHash(), known, "SETTLEMENT",
                            spec -> row.spec());
                    continue;
                }
                rowsById.put(excelId, row);
                SettlementSpec spec = row.spec();
                toMatch.add(new SettlementMatcher.Row(excelId, spec.date(), spec.currency(), spec.amount(),
                        spec.ownerPays(), side(spec)));
            }
            for (MemberPlanner.ConversionRow row : conversionRows) {
                memberTally.conversions++;
                String excelId = id(row.row().id());
                List<SideRow> known = byRef.get(EntryMapper.reference(row.row().id()));
                if (known != null) {
                    memberTally.settlementsKnown++;
                    knownSettlement(file, row.row(), excelId, row.sideHash(), known, "CONVERSION",
                            currency -> row.legs().stream().filter(leg -> leg.currency().equals(currency))
                                    .findFirst().orElseThrow());
                    continue;
                }
                conversionsById.put(excelId, row);
                conversionsToMatch.add(new SettlementMatcher.ConversionRow(excelId, row.row().date(), row.legs()
                        .stream().map(leg -> new SettlementMatcher.Leg(leg.currency(), leg.amount(), leg.ownerPays()))
                        .toList()));
            }
            knownGroups.forEach((side, rows) -> knownGroup(file, side, rows));
            List<SettlementMatcher.Candidate> candidates = openSettlements(family.scope(), personal, me, joinDate)
                    .stream().filter(c -> !sideRows.containsKey(c.id())).toList();
            SettlementMatcher.Result result = SettlementMatcher.match(toMatch, conversionsToMatch, candidates);
            Set<Long> matched = new HashSet<>();
            for (SettlementMatcher.Match match : result.matches()) {
                match.settlements().forEach(c -> matched.add(c.id()));
                if (match.tier() == 0) {
                    MemberPlanner.ConversionRow row = conversionsById.get(match.excelId());
                    attachSettlement(file, row.row(), match, row.sideHash(), "CONVERSION",
                            currency -> row.legs().stream().filter(leg -> leg.currency().equals(currency))
                                    .findFirst().orElseThrow(), false);
                } else {
                    MemberPlanner.SettlementRow row = rowsById.get(match.excelId());
                    attachSettlement(file, row.row(), match, row.sideHash(), "SETTLEMENT", currency -> row.spec(),
                            false);
                }
            }
            for (SettlementMatcher.GroupMatch group : result.groups()) {
                matched.add(group.settlement().id());
                attachGroup(file, group.rows().stream().map(r -> rowsById.get(r.excelId())).toList(),
                        group.settlement(), false);
            }
            for (SettlementMatcher.Ambiguous tie : result.ambiguous()) {
                tie.settlements().forEach(c -> matched.add(c.id()));
                ambiguous(file, tie, rowsById);
            }
            memberTally.byDate += result.byDate().size();
            for (SettlementMatcher.Row row : result.unmatched()) {
                MemberPlanner.SettlementRow settlement = rowsById.get(row.excelId());
                unmatched(file, settlement.row(), row.excelId(), "SETTLEMENT", row.date(), row.currency(),
                        row.amount());
            }
            for (SettlementMatcher.ConversionRow row : result.unmatchedConversions()) {
                MemberPlanner.ConversionRow conversion = conversionsById.get(row.excelId());
                unmatched(file, conversion.row(), row.excelId(), "CONVERSION", row.date(), conversion.row().currency(),
                        conversion.row().sum());
            }
            candidates.stream().filter(c -> !matched.contains(c.id())).forEach(c -> {
                memberTally.ownerSettlementsWithoutSide++;
                warnings.add(new Problem(null, null, "settlement " + c.id(), ("the settlement of %s in %s of %s has no "
                        + "side of yours: none of your rows matched it (D-178)").formatted(c.date(), c.currency(),
                                c.amount().toPlainString())));
            });
        }

        /**
         * D-202: what a settlement row of his would be one side on, as {@link #attachGroup} requires of D-198's rows: the
         * account in the row's own currency with nothing else entered, and the counterparty; else null.
         */
        private static String side(SettlementSpec spec) {
            return spec.accountAmount() == null && spec.currency().equals(spec.accountCurrency())
                    ? spec.accountCode() + '\u001f' + Objects.requireNonNullElse(spec.counterparty(), "")
                    : null;
        }

        /**
         * D-202: his rows of a date that its settlements take in more than one way, each an error naming both sides; the
         * settlements stay without his side, and aren't counted as left without one.
         */
        private void ambiguous(String file, SettlementMatcher.Ambiguous tie, Map<String, MemberPlanner.SettlementRow> rowsById) {
            String his = tie.rows().stream().map(SettlementMatcher.Row::excelId).collect(Collectors.joining(", "));
            String theirs = tie.settlements().stream()
                    .map(c -> "%s%s".formatted(c.amount().toPlainString(),
                            c.excelId() == null ? "" : " (the other member's Excel ID " + c.excelId() + ")"))
                    .collect(Collectors.joining(", "));
            SettlementMatcher.Row first = tie.rows().getFirst();
            for (SettlementMatcher.Row row : tie.rows()) {
                memberTally.ambiguous++;
                tally(Source.MEMBER_SETTLEMENT, "SETTLEMENT").failed++;
                errors.add(new Problem(file, rowsById.get(row.excelId()).row().row(), row.excelId(), ("AMBIGUOUS_SETTLEMENT: "
                        + "your rows %s of %s in %s and the settlements of that date %s match in more than one way, or "
                        + "the date has more than %d of either (D-202): make the amounts or accounts tell them apart")
                        .formatted(his, first.date(), first.currency(), theirs, SettlementMatcher.MOST_ON_A_DATE)));
            }
        }

        /**
         * D-198: several settlement rows of his on one date that add up to one settlement. His side of it is one, on one
         * account: so the rows must be on one account, in the settlement's currency, with one counterparty; else each is
         * an error naming why. The side keeps the rows' references together (xls:65+66+67) and their hashes as one.
         */
        private void attachGroup(String file, List<MemberPlanner.SettlementRow> rows, SettlementMatcher.Candidate settlement,
                boolean edit) {
            Tally tally = tally(Source.MEMBER_SETTLEMENT, "SETTLEMENT");
            SettlementSpec first = rows.getFirst().spec();
            boolean oneSide = rows.stream().allMatch(r -> r.spec().accountCode().equals(first.accountCode())
                    && r.spec().accountCurrency().equals(settlement.currency()) && r.spec().accountAmount() == null
                    && Objects.equals(r.spec().counterparty(), first.counterparty()));
            String ids = rows.stream().map(r -> id(r.row().id())).collect(Collectors.joining(", "));
            if (!oneSide) {
                for (MemberPlanner.SettlementRow row : rows) {
                    tally.failed++;
                    memberTally.unmatched++;
                    errors.add(new Problem(file, row.row().row(), id(row.row().id()), ("your rows %s add up to the "
                            + "settlement of %s in %s of %s, but they aren't all on one account in %s: your side of a "
                            + "settlement is one account; put them on one, or make them one row (D-198)").formatted(ids,
                                    settlement.date(), settlement.currency(), settlement.amount().toPlainString(),
                                    settlement.currency())));
                }
                return;
            }
            String ref = groupRef(rows);
            String hash = groupHash(rows);
            Map<String, Long> created = new HashMap<>();
            try {
                savepoint.executeWithoutResult(status -> {
                    records.updateForImport(family.scope(), personal, settlement.id(), null,
                            new FamilyRecordChanges(null, false, null, null, null, null, null,
                                    accountId(first.accountCode()), false, false, null, null, null,
                                    first.accountCurrency(),
                                    counterparty(first.counterparty(), Counterparty.Kind.PERSON, created), false, null,
                                    false));
                    saveSide(personal, ref, settlement.id(), hash, appHash(personal, family.scope(), settlement.id()));
                });
            } catch (RuleViolationException e) {
                tally.failed += rows.size();
                String reasons = e.details().isEmpty() ? String.join("; ", e.violations())
                        : e.details().stream().map(v -> v.code() + ": " + v.message()).collect(Collectors.joining("; "));
                rows.forEach(row -> errors.add(new Problem(file, row.row().row(), id(row.row().id()),
                        "your side of the settlement: " + reasons)));
                return;
            } catch (RuntimeException e) {
                tally.failed += rows.size();
                rows.forEach(row -> errors.add(new Problem(file, row.row().row(), id(row.row().id()),
                        "your side can't go on the settlement: " + message(e))));
                return;
            }
            counterpartyIds.putAll(created);
            counterpartiesCreated += created.size();
            tally.updated += rows.size();
            if (edit) {
                memberTally.sidesEdited++;
            } else {
                memberTally.tier2Many++;
            }
            rows.forEach(row -> drift.addAll(row.spec().drift()));
            wrote();
        }

        /** D-198's group recognised again: unchanged, written again, or a conflict, as {@link #knownSettlement}. */
        private void knownGroup(String file, SideRow side, List<MemberPlanner.SettlementRow> rows) {
            Tally tally = tally(Source.MEMBER_SETTLEMENT, "SETTLEMENT");
            List<SettlementMatcher.Candidate> settlement = settlementsOf(family.scope(),
                    family.target().ownerMemberId(), List.of(side.recordId()));
            if (!side.ref().equals(groupRef(rows)) || settlement.isEmpty()) {
                tally.conflicts += rows.size();
                rows.forEach(row -> conflicts.add(new RowRef(Source.MEMBER_SETTLEMENT.name(), id(row.row().id()),
                        "the rows your side of the settlement was matched to have changed: not all of them are in "
                                + "the file, or the settlement is gone (D-198)")));
                return;
            }
            if (side.sideHash().equals(groupHash(rows))) {
                tally.unchanged += rows.size();
                rows.forEach(row -> drift.addAll(row.spec().drift()));
                return;
            }
            if (!side.appHash().equals(appHash(personal, family.scope(), side.recordId()))) {
                tally.conflicts += rows.size();
                rows.forEach(row -> conflicts.add(new RowRef(Source.MEMBER_SETTLEMENT.name(), id(row.row().id()),
                        "your side of the settlement changed in the app since its last import (D-181)")));
                return;
            }
            attachGroup(file, rows, settlement.getFirst(), true);
        }

        /** D-178's error for a row of his that no settlement matches: with its date, currency and amount. */
        private void unmatched(String file, TransactionRow row, String excelId, String kind, LocalDate date,
                String currency, BigDecimal amount) {
            memberTally.unmatched++;
            tally(Source.MEMBER_SETTLEMENT, kind).failed++;
            errors.add(new Problem(file, row.row(), excelId, ("no counterpart: no settlement of %s in %s of %s matches it "
                    + "(D-178)").formatted(date, currency, amount.stripTrailingZeros().toPlainString())));
        }

        /** His side on the settlements a row of his matched: each in the settlement's currency, or a conversion's leg on FX_EXCHANGE. */
        private void attachSettlement(String file, TransactionRow row, SettlementMatcher.Match match, String sideHash,
                String kind, Function<String, SettlementSpec> specOf, boolean edit) {
            Tally tally = tally(Source.MEMBER_SETTLEMENT, kind);
            String excelId = id(row.id());
            if (match.settlements().size() > 1 && match.tier() == 2) {
                SettlementSpec spec = specOf.apply(match.settlements().getFirst().currency());
                if (spec.accountAmount() != null) {
                    tally.failed++;
                    errors.add(new Problem(file, row.row(), excelId, "a row that matches several settlements is in "
                            + "their currency on your account too (D-178)"));
                    return;
                }
            }
            Map<String, Long> created = new HashMap<>();
            try {
                savepoint.executeWithoutResult(status -> {
                    for (SettlementMatcher.Candidate settlement : match.settlements()) {
                        SettlementSpec spec = specOf.apply(settlement.currency());
                        boolean exchange = spec.throughExchange();
                        records.updateForImport(family.scope(), personal, settlement.id(), null,
                                new FamilyRecordChanges(null, false, null, null, null, null, null,
                                        accountId(spec.accountCode()), false, false, null, null,
                                        exchange || match.settlements().size() > 1 ? null : spec.accountAmount(),
                                        exchange ? settlement.currency() : spec.accountCurrency(),
                                        exchange ? null : counterparty(spec.counterparty(), Counterparty.Kind.PERSON,
                                                created), false, null, exchange));
                        saveSide(personal, EntryMapper.reference(row.id()), settlement.id(), sideHash,
                                appHash(personal, family.scope(), settlement.id()));
                    }
                });
            } catch (RuleViolationException e) {
                tally.failed++;
                String reasons = e.details().isEmpty() ? String.join("; ", e.violations())
                        : e.details().stream().map(v -> v.code() + ": " + v.message()).collect(Collectors.joining("; "));
                errors.add(new Problem(file, row.row(), excelId, "your side of the settlement: " + reasons));
                return;
            } catch (RuntimeException e) {
                tally.failed++;
                errors.add(new Problem(file, row.row(), excelId, "your side can't go on the settlement: " + message(e)));
                return;
            }
            counterpartyIds.putAll(created);
            counterpartiesCreated += created.size();
            tally.updated++;
            if (edit) {
                memberTally.sidesEdited++;
            } else {
                switch (match.tier()) {
                    case 0 -> memberTally.conversionsMatched++;
                    case 1 -> memberTally.tier1++;
                    case 2 -> memberTally.tier2++;
                    default -> memberTally.tier3++;
                }
            }
            for (SettlementMatcher.Candidate settlement : match.settlements()) {
                SettlementSpec spec = specOf.apply(settlement.currency());
                // His row's own drift (the minor unit), and D-188's: Excel moves on his row's date, the app on the
                // settlement's.
                if (match.settlements().size() == 1 || spec.throughExchange()) {
                    drift.addAll(spec.drift());
                }
                settlementDateDrift(row, settlement, spec);
            }
            wrote();
        }

        /** D-188: the settlement on its date in the app, his row on its own in Excel. */
        private void settlementDateDrift(TransactionRow row, SettlementMatcher.Candidate settlement,
                SettlementSpec spec) {
            if (settlement.date().equals(row.date())) {
                return;
            }
            if (!YearMonth.from(settlement.date()).equals(YearMonth.from(row.date()))) {
                memberTally.straddling.add(new ImportReport.Straddling(id(row.id()), row.date(), settlement.date(),
                        settlement.currency(), settlement.amount()));
            }
            // His family debt: down by what he paid, up by what he received (a liability, as displayed).
            BigDecimal debt = settlement.pays() ? settlement.amount().negate() : settlement.amount();
            drift.add(new Drift(Reconciler.FAMILY_DEBT, settlement.currency(), row.date(), debt.negate(),
                    Drift.Reason.SETTLEMENT_DATE));
            drift.add(new Drift(Reconciler.FAMILY_DEBT, settlement.currency(), settlement.date(), debt,
                    Drift.Reason.SETTLEMENT_DATE));
            if (spec.throughExchange()) {
                return;
            }
            AccountRef account = chart.accountsByCode().get(spec.accountCode());
            if (account == null) {
                return;
            }
            BigDecimal side = spec.accountAmount() == null ? settlement.amount() : spec.accountAmount();
            BigDecimal posting = settlement.pays() ? side.negate() : side;
            BigDecimal shown = account.type() == AccountType.ASSET ? posting : posting.negate();
            drift.add(new Drift(account.code(), spec.accountCurrency(), row.date(), shown.negate(),
                    Drift.Reason.SETTLEMENT_DATE));
            drift.add(new Drift(account.code(), spec.accountCurrency(), settlement.date(), shown,
                    Drift.Reason.SETTLEMENT_DATE));
        }

        /** A settlement row an earlier import of his matched: unchanged, written again, or a conflict (D-181). */
        private void knownSettlement(String file, TransactionRow row, String excelId, String sideHash,
                List<SideRow> known, String kind, Function<String, SettlementSpec> specOf) {
            Tally tally = tally(Source.MEMBER_SETTLEMENT, kind);
            List<SettlementMatcher.Candidate> settlements = settlementsOf(family.scope(),
                    family.target().ownerMemberId(), known.stream().map(SideRow::recordId).toList());
            if (known.getFirst().sideHash().equals(sideHash)) {
                tally.unchanged++;
                for (SettlementMatcher.Candidate settlement : settlements) {
                    SettlementSpec spec = specOf.apply(settlement.currency());
                    if (settlements.size() == 1 || spec.throughExchange()) {
                        drift.addAll(spec.drift());
                    }
                    settlementDateDrift(row, settlement, spec);
                }
                return;
            }
            boolean appChanged = known.stream().anyMatch(side -> !side.appHash().equals(appHash(personal,
                    family.scope(), side.recordId())));
            if (appChanged) {
                tally.conflicts++;
                conflicts.add(new RowRef(Source.MEMBER_SETTLEMENT.name(), excelId,
                        "your side of the settlement changed in the app since its last import (D-181)"));
                return;
            }
            int tier = settlements.size() > 1 ? 2 : kind.equals("CONVERSION") ? 0 : 1;
            attachSettlement(file, row, new SettlementMatcher.Match(excelId, row.date(), settlements, tier), sideHash,
                    kind, specOf, true);
        }

        /**
         * D-179: per month and currency from his join date, his benefit rows against his shares of the family incomes
         * the other members received; each month that differs by more than 0.01 is a warning with both sums.
         */
        private void memberBenefits(List<MemberPlanner.Benefit> benefits, LocalDate joinDate, LocalDate cutoff) {
            record Month(LocalDate first, String currency) {
            }
            Map<Month, BigDecimal> rows = new TreeMap<>(Comparator.comparing(Month::first)
                    .thenComparing(Month::currency));
            Map<Month, Integer> counts = new HashMap<>();
            memberTally.benefitRows = benefits.size();
            for (MemberPlanner.Benefit benefit : benefits) {
                if (benefit.row().date().isBefore(joinDate)) {
                    continue;
                }
                Month month = new Month(benefit.row().date().withDayOfMonth(1), benefit.currency());
                rows.merge(month, benefit.amount(), BigDecimal::add);
                counts.merge(month, 1, Integer::sum);
            }
            Map<Month, BigDecimal> shares = new HashMap<>();
            if (cutoff != null) {
                incomeShares(family.scope(), family.target().ownerMemberId(), joinDate, cutoff).forEach(share -> {
                    Month month = new Month(share.month(), share.currency());
                    shares.merge(month, share.amount(), BigDecimal::add);
                    rows.putIfAbsent(month, BigDecimal.ZERO);
                });
            }
            rows.forEach((month, sum) -> {
                BigDecimal share = shares.getOrDefault(month, BigDecimal.ZERO);
                boolean differs = sum.subtract(share).abs().compareTo(TOLERANCE) > 0;
                memberTally.benefitMonths.add(new ImportReport.BenefitMonth(month.first(), month.currency(),
                        counts.getOrDefault(month, 0), strip(sum), strip(share), differs));
                if (differs) {
                    warnings.add(new Problem(null, null, ("benefit rows of %s in %s: %s, your shares of the family "
                            + "incomes the other members received: %s (D-179)").formatted(YearMonth.from(month.first()),
                                    month.currency(), strip(sum).toPlainString(), strip(share).toPlainString())));
                }
            });
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

    /** D-196: the owner's D-73 row once the partner's place has an account. */
    static final String D73_AFTER_CLAIM = "a row on FAMILY_DEBT against UNALLOCATED is an expense the partner's place paid "
            + "for you alone (D-73); the partner has taken his place and records it from his own file, so it can't come "
            + "from yours any more: leave it out of your file, or ask him to record it (D-196)";

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

    // --- A member's own import and the seats' cutoffs (D-175 to D-181) ---------------------------------------------

    /** D-198: what joins the Excel IDs of a group's references. */
    static final String GROUP = "+";

    private static String groupRef(List<MemberPlanner.SettlementRow> rows) {
        return EntryMapper.REFERENCE_PREFIX + rows.stream().map(r -> r.row().id()).sorted()
                .map(String::valueOf).collect(Collectors.joining(GROUP));
    }

    private static String groupHash(List<MemberPlanner.SettlementRow> rows) {
        return ImportSync.contentHash(rows.stream().sorted(Comparator.comparingLong(r -> r.row().id()))
                .map(MemberPlanner.SettlementRow::sideHash).toArray(String[]::new));
    }

    /** D-180's difference of 0.01 per account, currency and month-end, and D-177's and D-179's checks'. */
    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private static BigDecimal strip(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 2 ? stripped.setScale(2) : stripped;
    }

    /** D-175: the earliest of the latest committed cutoffs of the seats other than {@code seats}; null for none. */
    private LocalDate othersCutoff(LedgerScope family, List<Long> seats) {
        return jdbc.sql("""
                SELECT min(cutoff) FROM family_import_cutoff
                WHERE ledger_id = :ledgerId AND NOT (member_id = ANY (:seats))""")
                .param("ledgerId", family.ledgerId()).param("seats", seats.toArray(Long[]::new))
                .query(LocalDate.class).optional().orElse(null);
    }

    /** D-175: a committed import of the seat's file up to {@code cutoff}, by the member who acts. */
    private void storeCutoff(LedgerScope family, long seat, LocalDate cutoff) {
        jdbc.sql("""
                INSERT INTO family_import_cutoff (ledger_id, member_id, cutoff, imported_by_member_id)
                VALUES (:ledgerId, :seat, :cutoff, :memberId)
                ON CONFLICT (ledger_id, member_id) DO UPDATE
                    SET cutoff = EXCLUDED.cutoff, imported_by_member_id = EXCLUDED.imported_by_member_id,
                        imported_at = now()""")
                .param("ledgerId", family.ledgerId()).param("seat", seat).param("cutoff", cutoff)
                .param("memberId", family.memberId())
                .update();
    }

    /** The date of the first record an import wrote from the seat's file (D-142), deleted ones too; null for none. */
    private LocalDate seatFirst(LedgerScope family, long seat) {
        return jdbc.sql("""
                SELECT min(record_date) FROM family_record
                WHERE ledger_id = :ledgerId AND starts_with(external_ref, :prefix)""")
                .param("ledgerId", family.ledgerId()).param("prefix", "xls:" + seat + ":")
                .query(LocalDate.class).optional().orElse(null);
    }

    /** D-169 after the claim: the incomes of the seat's file that the owner's D-84 rows carry, up to the cutoff. */
    private List<Reconciler.Carried> seatIncomes(LedgerScope family, long seat, LocalDate cutoff) {
        return jdbc.sql("""
                SELECT r.record_date, r.currency, abs(r.base_amount) AS amount
                FROM family_record r JOIN category c ON c.id = r.category_id AND c.ledger_id = r.ledger_id
                WHERE r.ledger_id = :ledgerId AND starts_with(r.external_ref, :prefix) AND r.deleted_at IS NULL
                  AND r.type = 'INCOME' AND r.currency = :currency AND c.code = ANY (:codes)
                  AND r.record_date <= :cutoff""")
                .param("ledgerId", family.ledgerId()).param("prefix", "xls:" + seat + ":")
                .param("currency", ImportPlanner.CARRIED_CURRENCY)
                .param("codes", ImportPlanner.CARRIED_BY_THE_PARTNER.toArray(String[]::new))
                .param("cutoff", cutoff)
                .query((row, n) -> new Reconciler.Carried(row.getObject("record_date", LocalDate.class),
                        row.getString("currency"), row.getBigDecimal("amount")))
                .list();
    }

    /** The records of a member's own file (D-142), by reference: currency, payer, version and his share. */
    private Map<String, MemberRecord> memberRecords(LedgerScope family, long me) {
        Map<String, MemberRecord> records = new HashMap<>();
        jdbc.sql("""
                SELECT r.id, r.external_ref, r.currency, r.payer_member_id, r.version, s.amount AS share
                FROM family_record r
                LEFT JOIN family_share s ON s.record_id = r.id AND s.member_id = :me
                WHERE r.ledger_id = :ledgerId AND starts_with(r.external_ref, :prefix)""")
                .param("ledgerId", family.ledgerId()).param("me", me).param("prefix", "xls:" + me + ":")
                .query(row -> {
                    records.put(row.getString("external_ref"), new MemberRecord(row.getLong("id"),
                            row.getString("currency"), row.getLong("payer_member_id"), row.getInt("version"),
                            row.getBigDecimal("share")));
                });
        return records;
    }

    /** The currencies of the records from the join date on that he pays, receives or has a share in. */
    private Set<String> recordCurrencies(LedgerScope family, long me, LocalDate joinDate) {
        return new TreeSet<>(jdbc.sql("""
                SELECT DISTINCT r.currency FROM family_record r
                WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL AND r.type IN ('EXPENSE', 'INCOME')
                  AND r.record_date >= :joinDate
                  AND (r.payer_member_id = :me
                       OR EXISTS (SELECT FROM family_share s WHERE s.record_id = r.id AND s.member_id = :me))""")
                .param("ledgerId", family.ledgerId()).param("me", me).param("joinDate", joinDate)
                .query(String.class).list());
    }

    /** His sides that his imports attached (D-181), by record. */
    private Map<Long, SideRow> sideRows(LedgerScope personal) {
        Map<Long, SideRow> sides = new HashMap<>();
        jdbc.sql("""
                SELECT external_ref, record_id, side_hash, app_hash FROM imported_family_side
                WHERE ledger_id = :ledgerId""")
                .param("ledgerId", personal.ledgerId())
                .query(row -> {
                    sides.put(row.getLong("record_id"), new SideRow(row.getString("external_ref"),
                            row.getLong("record_id"), row.getString("side_hash"), row.getString("app_hash")));
                });
        return sides;
    }

    /** D-181: his side of the record as his import left it. */
    private void saveSide(LedgerScope personal, String ref, long recordId, String sideHash, String appHash) {
        jdbc.sql("""
                INSERT INTO imported_family_side (ledger_id, external_ref, record_id, side_hash, app_hash)
                VALUES (:ledgerId, :ref, :recordId, :sideHash, :appHash)
                ON CONFLICT (ledger_id, record_id) DO UPDATE
                    SET external_ref = EXCLUDED.external_ref, side_hash = EXCLUDED.side_hash,
                        app_hash = EXCLUDED.app_hash, imported_at = now()""")
                .param("ledgerId", personal.ledgerId()).param("ref", ref).param("recordId", recordId)
                .param("sideHash", sideHash).param("appHash", appHash)
                .update();
    }

    /**
     * D-181: what the app holds of the member's side of the record, his own entry for it: each line's account,
     * currency, amount and counterparty, and the entry's payee and note. A change in the app changes it.
     */
    private String appHash(LedgerScope personal, LedgerScope family, long recordId) {
        List<String> fields = new ArrayList<>();
        jdbc.sql("""
                SELECT e.payee_id, e.memo, p.account_id, p.currency, p.amount, p.counterparty_id
                FROM family_entry_link l
                JOIN journal_entry e ON e.id = l.entry_id
                JOIN posting p ON p.entry_id = e.id
                WHERE l.family_ledger_id = :familyId AND l.record_id = :recordId AND l.member_id = :memberId
                  AND l.link_type IN ('PAYMENT', 'SETTLEMENT') AND l.detached_at IS NULL
                  AND e.ledger_id = :ledgerId
                ORDER BY e.id, p.line_no""")
                .param("familyId", family.ledgerId()).param("recordId", recordId)
                .param("memberId", family.memberId()).param("ledgerId", personal.ledgerId())
                .query(row -> {
                    fields.add(String.valueOf(row.getObject("payee_id")));
                    fields.add(row.getString("memo"));
                    fields.add(String.valueOf(row.getLong("account_id")));
                    fields.add(row.getString("currency"));
                    fields.add(TransactionRow.plain(row.getBigDecimal("amount")));
                    fields.add(String.valueOf(row.getObject("counterparty_id")));
                });
        return ImportSync.contentHash(fields.toArray(String[]::new));
    }

    private static final String SETTLEMENT_CANDIDATE = """
            SELECT r.id, r.record_date, r.currency, abs(r.base_amount) AS amount, r.payer_member_id = :me AS pays,
                   CASE WHEN r.external_ref ~ '^xls:[0-9]+:[0-9]+:[A-Z]{3}$'
                        THEN regexp_replace(r.external_ref, ':[A-Z]{3}$', '') END AS conversion,
                   CASE WHEN r.external_ref ~ '^xls:[0-9]+:[0-9]+$' THEN split_part(r.external_ref, ':', 3) END AS excel_id
            FROM family_record r
            WHERE r.ledger_id = :ledgerId AND r.type = 'SETTLEMENT' AND r.deleted_at IS NULL
              AND (r.payer_member_id = :me OR r.payee_member_id = :me)""";

    private static SettlementMatcher.Candidate candidate(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new SettlementMatcher.Candidate(row.getLong("id"), row.getObject("record_date", LocalDate.class),
                row.getString("currency"), row.getBigDecimal("amount").stripTrailingZeros(), row.getBoolean("pays"),
                row.getString("conversion"), row.getString("excel_id"));
    }

    /**
     * D-178: the settlements between him and another member, from his join date on, whose side of his is still on
     * "Payments without a specified account": those his settlement rows may match.
     */
    private List<SettlementMatcher.Candidate> openSettlements(LedgerScope family, LedgerScope personal, long me,
            LocalDate joinDate) {
        return jdbc.sql(SETTLEMENT_CANDIDATE + """
                  AND r.record_date >= :joinDate
                  AND EXISTS (SELECT FROM family_entry_link l
                              JOIN posting p ON p.entry_id = l.entry_id
                              JOIN account a ON a.id = p.account_id
                              WHERE l.family_ledger_id = r.ledger_id AND l.record_id = r.id AND l.member_id = :me
                                AND l.link_type = 'SETTLEMENT' AND l.detached_at IS NULL
                                AND a.ledger_id = :personalId AND a.code = 'UNSPECIFIED_PAYMENTS' AND a.is_system)
                ORDER BY r.record_date, r.id""")
                .param("ledgerId", family.ledgerId()).param("me", me).param("joinDate", joinDate)
                .param("personalId", personal.ledgerId())
                .query(ImportService::candidate)
                .list();
    }

    /** The settlements with these ids between him and another member, live. */
    private List<SettlementMatcher.Candidate> settlementsOf(LedgerScope family, long me, List<Long> ids) {
        return jdbc.sql(SETTLEMENT_CANDIDATE + " AND r.id = ANY (:ids) ORDER BY r.record_date, r.id")
                .param("ledgerId", family.ledgerId()).param("me", me).param("ids", ids.toArray(Long[]::new))
                .query(ImportService::candidate)
                .list();
    }

    /** D-179's other side: his shares of the incomes the other members received, by month and currency. */
    private record MonthShare(LocalDate month, String currency, BigDecimal amount) {
    }

    private List<MonthShare> incomeShares(LedgerScope family, long me, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT date_trunc('month', r.record_date)::date AS month, r.currency, sum(abs(s.amount)) AS amount
                FROM family_record r JOIN family_share s ON s.record_id = r.id AND s.member_id = :me
                WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL AND r.type = 'INCOME'
                  AND r.payer_member_id <> :me AND r.record_date BETWEEN :from AND :to
                GROUP BY 1, 2""")
                .param("ledgerId", family.ledgerId()).param("me", me).param("from", from).param("to", to)
                .query((row, n) -> new MonthShare(row.getObject("month", LocalDate.class), row.getString("currency"),
                        row.getBigDecimal("amount")))
                .list();
    }

    /**
     * D-12 against Excel's exact halves for the records of the other members' files (and the app's) that he has a
     * share in, from his join date: his share less its exact part ({@code share_bp} of the amount), on the family debt
     * and the other way on the equity (D-68's SHARES). A share without its basis points has none.
     */
    private List<Drift> othersRecordsDrift(LedgerScope family, long me, LocalDate joinDate) {
        return jdbc.sql("""
                SELECT r.type, r.record_date, r.currency, r.base_amount, s.amount AS share, s.share_bp
                FROM family_record r JOIN family_share s ON s.record_id = r.id AND s.member_id = :me
                WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL AND r.type IN ('EXPENSE', 'INCOME')
                  AND r.record_date >= :joinDate AND s.share_bp IS NOT NULL
                  AND (r.external_ref IS NULL OR NOT starts_with(r.external_ref, :prefix))""")
                .param("ledgerId", family.ledgerId()).param("me", me).param("joinDate", joinDate)
                .param("prefix", "xls:" + me + ":")
                .query((row, n) -> {
                    BigDecimal exact = row.getBigDecimal("base_amount").multiply(BigDecimal.valueOf(row.getInt("share_bp")))
                            .divide(BigDecimal.valueOf(10000));
                    BigDecimal difference = row.getBigDecimal("share").subtract(exact);
                    boolean expense = row.getString("type").equals("EXPENSE");
                    LocalDate date = row.getObject("record_date", LocalDate.class);
                    String currency = row.getString("currency");
                    BigDecimal debt = expense ? difference : difference.negate();
                    return List.of(new Drift(Reconciler.FAMILY_DEBT, currency, date, debt, Drift.Reason.SHARES),
                            new Drift("UNALLOCATED", currency, date, debt.negate(), Drift.Reason.SHARES));
                })
                .list().stream().flatMap(List::stream).filter(d -> d.amount().signum() != 0).toList();
    }

    /** D-180: the opening balance his claim posted (D-31), as his debt account shows it, by date and currency. */
    private List<Drift> claimOpening(LedgerScope family, LedgerScope personal, long me) {
        return jdbc.sql("""
                SELECT e.entry_date, p.currency, -sum(p.amount) AS shown
                FROM family_entry_link l
                JOIN journal_entry e ON e.id = l.entry_id
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                WHERE l.family_ledger_id = :familyId AND l.member_id = :me AND l.link_type = 'OPENING_BALANCE'
                  AND l.detached_at IS NULL AND e.ledger_id = :ledgerId AND a.family_ledger_id = :familyId
                GROUP BY e.entry_date, p.currency""")
                .param("familyId", family.ledgerId()).param("me", me).param("ledgerId", personal.ledgerId())
                .query((row, n) -> new Drift(Reconciler.FAMILY_DEBT, row.getString("currency"),
                        row.getObject("entry_date", LocalDate.class), row.getBigDecimal("shown"),
                        Drift.Reason.CLAIM_OPENING))
                .list();
    }

    /**
     * D-180, D-12: the part of the claim's opening balance that D-12's cut of his shares before the join date makes
     * differ from Excel's exact halves, as SHARES, taken out of CLAIM_OPENING, so that what is left of the opening's
     * difference is Excel's own (D3c-0: about -0.03 in RUB). The two add up to what the opening explains anyway.
     */
    private List<Drift> claimShares(LedgerScope family, long me, LocalDate joinDate) {
        return jdbc.sql("""
                SELECT r.currency,
                       sum(CASE WHEN r.type = 'EXPENSE' THEN 1 ELSE -1 END
                           * (s.amount - r.base_amount * s.share_bp / 10000.0)) AS cut
                FROM family_record r JOIN family_share s ON s.record_id = r.id AND s.member_id = :me
                WHERE r.ledger_id = :ledgerId AND r.deleted_at IS NULL AND r.type IN ('EXPENSE', 'INCOME')
                  AND r.record_date < :joinDate AND s.share_bp IS NOT NULL
                GROUP BY r.currency""")
                .param("ledgerId", family.ledgerId()).param("me", me).param("joinDate", joinDate)
                .query((row, n) -> List.of(
                        new Drift(Reconciler.FAMILY_DEBT, row.getString("currency"), joinDate,
                                row.getBigDecimal("cut"), Drift.Reason.SHARES),
                        new Drift(Reconciler.FAMILY_DEBT, row.getString("currency"), joinDate,
                                row.getBigDecimal("cut").negate(), Drift.Reason.CLAIM_OPENING)))
                .list().stream().flatMap(List::stream).filter(d -> d.amount().signum() != 0).toList();
    }

    /** What a member's own import counts beyond the tallies (D-175 to D-181, D-188). */
    private static final class MemberTally {
        int familyRows;
        int recordsUnchanged;
        final List<String> recordsEdited = new ArrayList<>();
        int recordsCreated;
        int recordConflicts;
        int sidesAttached;
        int sidesUnchanged;
        int sidesEdited;
        int sideConflicts;
        int settlementRows;
        int tier1;
        int tier2;
        int tier3;
        int tier2Many;
        int byDate;
        int ambiguous;
        int settlementsKnown;
        int conversions;
        int conversionsMatched;
        int unmatched;
        int ownerSettlementsWithoutSide;
        int benefitRows;
        final List<ImportReport.BenefitMonth> benefitMonths = new ArrayList<>();
        final List<ImportReport.Straddling> straddling = new ArrayList<>();
        int shareWarnings;

        ImportReport.MemberSummary summary() {
            return new ImportReport.MemberSummary(familyRows, recordsUnchanged, List.copyOf(recordsEdited),
                    recordsCreated, recordConflicts, sidesAttached, sidesUnchanged, sidesEdited, sideConflicts,
                    settlementRows, tier1, tier2, tier3, tier2Many, byDate, ambiguous, settlementsKnown, conversions,
                    conversionsMatched, unmatched,
                    ownerSettlementsWithoutSide, benefitRows, List.copyOf(benefitMonths), List.copyOf(straddling),
                    shareWarnings);
        }
    }

    // --- D-219: what earlier imports wrote, as the ledger holds it now --------------------------------------------

    static final String RESTAMPED = "the ledger holds what the row maps to; its hash is stored again (D-219)";
    static final String APP_EDITED = "changed in the app, the file unchanged: left as it is (D-219)";
    static final String REPLANNED = "re-planned: the current code maps it differently (D-219)";
    static final String NOT_REPLANNABLE = "can't be re-planned after the claim: the partner's place pays it (D-196, "
            + "D-219)";

    /** A personal entry an import wrote, as it stands: counterparties by name in lower case, as the import finds them. */
    private record StoredEntry(LocalDate date, String kind, String payee, String memo, List<StoredLine> lines) {

        /** Whether the entry is what the plan would write: its date, kind, payee and note, and every posting in order. */
        boolean holds(EntryPlan plan) {
            if (!date.equals(plan.date()) || !kind.equals(plan.kind().name()) || !Objects.equals(payee, name(plan.payee()))
                    || !Objects.equals(memo, plan.memo()) || lines.size() != plan.postings().size()) {
                return false;
            }
            for (int i = 0; i < lines.size(); i++) {
                StoredLine stored = lines.get(i);
                EntryPlan.Line line = plan.postings().get(i);
                if (stored.accountId() != line.accountId() || !stored.currency().equals(line.currency())
                        || stored.amount().compareTo(line.amount()) != 0
                        || !Objects.equals(stored.categoryId(), line.categoryId())
                        || !Objects.equals(stored.counterparty(), name(line.counterparty()))) {
                    return false;
                }
            }
            return true;
        }

        private static String name(Party party) {
            return party == null ? null : key(party.name());
        }
    }

    private record StoredLine(long accountId, String currency, BigDecimal amount, Long categoryId,
            String counterparty) {
    }

    /**
     * A family record an import wrote, in the fields D-219 compares: its amount without its sign, the split as its
     * method and each member's basis points by member id ({@code PERCENT 12:5000,13:5000}, {@code ONE_MEMBER 12:-}).
     */
    private record StoredRecord(String type, boolean refund, LocalDate date, String currency, BigDecimal amount,
            Long categoryId, long payer, Long payee, String split) {
    }

    private static BigDecimal magnitude(BigDecimal amount) {
        return amount.abs().stripTrailingZeros();
    }

    /** Rule 4: an account's displayed change for a posting. */
    private static BigDecimal shown(AccountRef account, BigDecimal amount) {
        return account.type() == AccountType.ASSET ? amount : amount.negate();
    }

    /** The entries of the personal ledger that an import wrote, by reference, with their postings in order. */
    private Map<String, StoredEntry> personalStored(LedgerScope personal) {
        Map<String, StoredEntry> stored = new HashMap<>();
        jdbc.sql("""
                SELECT e.external_ref, e.kind, e.entry_date, py.name AS payee, e.memo, p.account_id, p.currency,
                       p.amount, p.category_id, cp.name AS counterparty
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                LEFT JOIN counterparty py ON py.id = e.payee_id AND py.ledger_id = :ledgerId
                LEFT JOIN counterparty cp ON cp.id = p.counterparty_id AND cp.ledger_id = :ledgerId
                WHERE e.ledger_id = :ledgerId AND e.external_ref IS NOT NULL AND e.import_hash IS NOT NULL
                ORDER BY e.id, p.line_no""")
                .param("ledgerId", personal.ledgerId())
                .query(row -> {
                    String ref = row.getString("external_ref");
                    StoredEntry entry = stored.get(ref);
                    if (entry == null) {
                        String payee = row.getString("payee");
                        entry = new StoredEntry(row.getObject("entry_date", LocalDate.class), row.getString("kind"),
                                payee == null ? null : key(payee), row.getString("memo"), new ArrayList<>());
                        stored.put(ref, entry);
                    }
                    String counterparty = row.getString("counterparty");
                    entry.lines().add(new StoredLine(row.getLong("account_id"), row.getString("currency"),
                            row.getBigDecimal("amount"), row.getObject("category_id", Long.class),
                            counterparty == null ? null : key(counterparty)));
                });
        return stored;
    }

    /** The live records of the family ledger that an import wrote, by id, in D-219's fields. */
    private Map<Long, StoredRecord> familyStored(LedgerScope family) {
        Map<Long, StoredRecord> stored = new HashMap<>();
        jdbc.sql("""
                SELECT r.id, r.type, r.base_amount < 0 AS refund, r.record_date, r.currency, r.base_amount,
                       r.category_id, r.payer_member_id, r.payee_member_id,
                       r.split_method || ' ' || coalesce((
                           SELECT string_agg(s.member_id || ':' || coalesce(s.share_bp::text, '-'), ','
                                             ORDER BY s.member_id)
                           FROM family_share s WHERE s.record_id = r.id), '') AS split
                FROM family_record r
                WHERE r.ledger_id = :ledgerId AND r.external_ref IS NOT NULL AND r.deleted_at IS NULL""")
                .param("ledgerId", family.ledgerId())
                .query(row -> {
                    stored.put(row.getLong("id"), new StoredRecord(row.getString("type"), row.getBoolean("refund"),
                            row.getObject("record_date", LocalDate.class), row.getString("currency"),
                            magnitude(row.getBigDecimal("base_amount")), row.getObject("category_id", Long.class),
                            row.getLong("payer_member_id"), row.getObject("payee_member_id", Long.class),
                            row.getString("split")));
                });
        return stored;
    }

    /** How many postings of the personal ledger use the category. */
    private long categoryPostings(LedgerScope personal, long categoryId) {
        return jdbc.sql("""
                SELECT count(*) FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE e.ledger_id = :ledgerId AND p.category_id = :categoryId""")
                .param("ledgerId", personal.ledgerId()).param("categoryId", categoryId)
                .query(Long.class).single();
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
