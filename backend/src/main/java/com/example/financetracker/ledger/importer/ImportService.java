package com.example.financetracker.ledger.importer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.Account;
import com.example.financetracker.ledger.AccountRepository;
import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.CounterpartyRepository;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.ImportBatch;
import com.example.financetracker.ledger.ImportBatchRepository;
import com.example.financetracker.ledger.JournalEntryRepository;
import com.example.financetracker.ledger.Json;
import com.example.financetracker.ledger.LedgerCategory;
import com.example.financetracker.ledger.LedgerCategoryRepository;
import com.example.financetracker.ledger.UserSettings;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.ImportedCommand;
import com.example.financetracker.ledger.domain.InvalidEntryException;
import com.example.financetracker.ledger.domain.PostingLine;
import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.Chart.CategoryRef;
import com.example.financetracker.ledger.importer.EntryPlan.Line;
import com.example.financetracker.ledger.importer.EntryPlan.Party;
import com.example.financetracker.ledger.importer.ImportReport.FileSummary;
import com.example.financetracker.ledger.importer.ImportReport.FxRow;
import com.example.financetracker.ledger.importer.ImportReport.Outcome;
import com.example.financetracker.ledger.importer.ImportReport.Problem;
import com.example.financetracker.ledger.importer.ImportReport.ReferenceCounts;
import com.example.financetracker.ledger.importer.Workbook.AccountRow;
import com.example.financetracker.ledger.importer.Workbook.CategoryRow;
import com.example.financetracker.ledger.importer.Workbook.OpeningBalanceRow;
import com.example.financetracker.ledger.importer.Workbook.Sheet;
import com.example.financetracker.ledger.report.ReportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports the owner's Excel ledger for one user: the accounts and categories first, created or updated by code, then
 * one entry per row of the journal, then the opening balances. The rules for a row are in {@link EntryMapper}; the
 * entries are written through {@link EntryService}, so the ledger's rules hold for them as for any other.
 * <p>
 * The whole run is one transaction. A dry run rolls it back at the end, after the report has read the balances, and
 * a commit rolls it back too if any row can't be imported, so a commit saves everything or nothing. Each row is
 * written under its own savepoint, so a row the database refuses doesn't stop the rows after it.
 * <p>
 * Running it again over the same files is safe: a row whose external ref the user already has is skipped.
 */
@Service
public class ImportService {

    /** The balances after the import include every entry, whatever its date. */
    private static final LocalDate ALL_DATES = LocalDate.of(9999, 12, 31);

    private final AccountRepository accounts;
    private final LedgerCategoryRepository categories;
    private final CounterpartyRepository counterparties;
    private final JournalEntryRepository journal;
    private final ImportBatchRepository batches;
    private final UserSettingsRepository settings;
    private final EntryService entries;
    private final ReportService reports;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final TransactionTemplate savepoint;

    ImportService(AccountRepository accounts, LedgerCategoryRepository categories,
            CounterpartyRepository counterparties, JournalEntryRepository journal, ImportBatchRepository batches,
            UserSettingsRepository settings, EntryService entries, ReportService reports, JdbcClient jdbc,
            ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.categories = categories;
        this.counterparties = counterparties;
        this.journal = journal;
        this.batches = batches;
        this.settings = settings;
        this.entries = entries;
        this.reports = reports;
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public ImportReport run(ImportRequest request) {
        Instant startedAt = Instant.now();
        Sheet<AccountRow> accountRows = Workbook.accounts(request.accounts());
        Sheet<CategoryRow> categoryRows = Workbook.categories(request.categories());
        Sheet<TransactionRow> transactionRows = Workbook.transactions(request.transactions());
        Sheet<OpeningBalanceRow> openingRows = request.openingBalances() == null
                ? null
                : Workbook.openingBalances(request.openingBalances());
        return transaction.execute(status -> {
            ImportReport report = new Run(request, startedAt).execute(accountRows, categoryRows, transactionRows,
                    openingRows);
            if (report.outcome() != Outcome.COMMITTED) {
                status.setRollbackOnly();
            }
            return report;
        });
    }

    /** One run's state. */
    private final class Run {

        private final String userId;
        private final boolean commit;
        private final Instant startedAt;
        private final List<FileSummary> files = new ArrayList<>();
        private final Map<EntryKind, Integer> entriesByKind = new EnumMap<>(EntryKind.class);
        private final List<Problem> errors = new ArrayList<>();
        private final List<Problem> warnings = new ArrayList<>();
        private final List<Problem> skipped = new ArrayList<>();
        private final List<FxRow> fxRows = new ArrayList<>();
        private int zeroFxRows;
        private int accountsCreated;
        private int accountsUpdated;
        private int categoriesCreated;
        private int categoriesUpdated;
        private int counterpartiesCreated;
        /** The user's counterparties by lowercase name, as the database's unique index compares them. */
        private Map<String, Long> counterpartyIds;
        private Set<String> externalRefs;
        private long batchId;

        Run(ImportRequest request, Instant startedAt) {
            this.userId = request.userId();
            this.commit = request.commit();
            this.startedAt = startedAt;
        }

        ImportReport execute(Sheet<AccountRow> accountRows, Sheet<CategoryRow> categoryRows,
                Sheet<TransactionRow> transactionRows, Sheet<OpeningBalanceRow> openingRows) {
            read("accounts", accountRows);
            read("categories", categoryRows);
            read("transactions", transactionRows);
            if (openingRows != null) {
                read("opening balances", openingRows);
            }

            Chart chart = chart(accountRows, categoryRows);
            EntryMapper mapper = new EntryMapper(chart);
            counterpartyIds = counterparties.findAllByUserIdOrderByName(userId).stream()
                    .collect(Collectors.toMap(c -> key(c.name()), Counterparty::id, (first, second) -> first));
            externalRefs = new HashSet<>(journal.findExternalRefsByUserId(userId));
            ImportFile transactionsFile = transactionRows.file();
            batchId = batches.save(new ImportBatch(null, userId, transactionsFile.name(), transactionsFile.sha256(),
                    !commit, null, null, null)).id();

            for (TransactionRow row : transactionRows.rows()) {
                importRow(transactionsFile.name(), row, mapper.map(row));
            }
            if (openingRows != null) {
                importOpeningBalances(openingRows, mapper);
            }
            checkDeferredConstraints();

            Outcome outcome = !commit ? Outcome.DRY_RUN : errors.isEmpty() ? Outcome.COMMITTED : Outcome.ABORTED;
            ImportReport report = new ImportReport(userId, commit, outcome, startedAt, files, entriesByKind,
                    new ReferenceCounts(accountsCreated, accountsUpdated, categoriesCreated, categoriesUpdated,
                            counterpartiesCreated),
                    errors, warnings, skipped, zeroFxRows, fxRows, reports.balances(userId, ALL_DATES),
                    reports.integrityCheck(userId));
            batches.save(new ImportBatch(batchId, userId, transactionsFile.name(), transactionsFile.sha256(), !commit,
                    null, Instant.now(), summary(report)));
            return report;
        }

        private void read(String role, Sheet<?> sheet) {
            ImportFile file = sheet.file();
            files.add(new FileSummary(role, file.name(), file.sha256(), sheet.size()));
            errors.addAll(sheet.errors());
            skipped.addAll(sheet.skipped());
        }

        /**
         * Creates or updates the accounts and categories of the files, by code, and makes sure the system accounts
         * exist (rules 4, 8, 9 and 10).
         */
        private Chart chart(Sheet<AccountRow> accountRows, Sheet<CategoryRow> categoryRows) {
            Map<String, Account> accountsByCode = accounts.findAllByUserIdOrderByCode(userId).stream()
                    .collect(Collectors.toMap(Account::code, Function.identity()));
            Map<String, AccountRef> accountsByName = new HashMap<>();
            for (AccountRow row : accountRows.rows()) {
                Account existing = accountsByCode.get(row.code());
                Account wanted = existing == null
                        ? new Account(null, userId, row.code(), row.name(), row.type(), null,
                                row.requiresCounterparty(), false, null, null)
                        : new Account(existing.id(), userId, existing.code(), row.name(), row.type(),
                                existing.defaultCurrency(), row.requiresCounterparty(), existing.isSystem(),
                                existing.archivedAt(), existing.createdAt());
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
                    accountsByName.put(row.name(), accountRef(account));
                }
            }
            systemAccount(AccountRole.OPENING_BALANCE, "Opening balance", accountsByCode);
            systemAccount(AccountRole.FX_EXCHANGE, "Currency exchange", accountsByCode);

            Map<String, LedgerCategory> categoriesByCode = categories.findAllByUserIdOrderByName(userId).stream()
                    .collect(Collectors.toMap(LedgerCategory::code, Function.identity()));
            Map<String, CategoryRef> categoriesByName = new HashMap<>();
            for (CategoryRow row : categoryRows.rows()) {
                LedgerCategory existing = categoriesByCode.get(row.code());
                if (existing != null && existing.type() != row.type()) {
                    // Decided on 2026-09-25: a category's type never changes.
                    errors.add(new Problem(categoryRows.file().name(), row.row(),
                            "the category %s is %s, and a category's type can't change to %s"
                                    .formatted(row.code(), existing.type(), row.type())));
                    continue;
                }
                LedgerCategory wanted = new LedgerCategory(existing == null ? null : existing.id(), userId,
                        row.code(), row.name(), row.type(), existing == null ? null : existing.archivedAt());
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
                    categoriesByName.put(row.name(),
                            new CategoryRef(category.id(), category.code(), category.type()));
                }
            }

            Map<String, AccountRef> refsByCode = accountsByCode.values().stream()
                    .collect(Collectors.toMap(Account::code, ImportService::accountRef));
            // Rule 7: the account the settings name, else FAMILY_DEBT.
            AccountRef shared = settings.findById(userId)
                    .map(UserSettings::sharedAccountId)
                    .flatMap(id -> refsByCode.values().stream().filter(ref -> ref.id() == id).findFirst())
                    .orElse(refsByCode.get(AccountRole.SHARED.defaultCode()));
            return new Chart(accountsByName, refsByCode, categoriesByName, shared);
        }

        /** Rules 9 and 10: an EQUITY account the importer posts to on its own, created if the user lacks it. */
        private void systemAccount(AccountRole role, String name, Map<String, Account> accountsByCode) {
            String code = role.defaultCode();
            if (!accountsByCode.containsKey(code)) {
                accountsByCode.put(code, accounts.save(
                        new Account(null, userId, code, name, AccountType.EQUITY, null, false, true, null, null)));
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

        private void importRow(String file, TransactionRow row, RowResult result) {
            if (result instanceof RowResult.Entry entry && importedBefore(file, row.row(), entry.plan())) {
                // Its warnings were reported when it was imported.
                return;
            }
            result.warnings().forEach(warning -> warnings.add(new Problem(file, row.row(), warning)));
            switch (result) {
                case RowResult.Entry entry -> {
                    if (write(file, row.row(), entry.plan()) && entry.plan().fxGain()) {
                        fxRows.add(new FxRow(row.row(), row.date(), row.sum(), row.currency(), row.debit(),
                                row.credit(), row.comment()));
                    }
                }
                case RowResult.Skipped skip -> {
                    skipped.add(new Problem(file, row.row(), skip.reason()));
                    if (skip.zeroFxGain()) {
                        zeroFxRows++;
                    }
                }
                case RowResult.Failed failure -> errors.add(new Problem(file, row.row(),
                        String.join("; ", failure.errors())));
            }
        }

        /** One entry per date, from that date's rows; a row that can't be read is left out and is an error. */
        private void importOpeningBalances(Sheet<OpeningBalanceRow> sheet, EntryMapper mapper) {
            String file = sheet.file().name();
            Map<LocalDate, List<Line>> linesByDate = new TreeMap<>();
            Map<LocalDate, List<Problem>> warningsByDate = new HashMap<>();
            for (OpeningBalanceRow row : sheet.rows()) {
                List<String> rowErrors = new ArrayList<>();
                List<String> rowWarnings = new ArrayList<>();
                Line line = mapper.openingBalance(row, rowErrors, rowWarnings);
                rowWarnings.forEach(warning -> warningsByDate.computeIfAbsent(row.date(), date -> new ArrayList<>())
                        .add(new Problem(file, row.row(), warning)));
                if (line == null) {
                    errors.add(new Problem(file, row.row(), String.join("; ", rowErrors)));
                } else {
                    linesByDate.computeIfAbsent(row.date(), date -> new ArrayList<>()).add(line);
                }
            }
            linesByDate.forEach((date, lines) -> {
                EntryPlan plan = mapper.openingEntry(date, lines);
                // The entry spans rows, so its problems are the file's, with the date.
                if (!importedBefore(file, null, plan)) {
                    warnings.addAll(warningsByDate.getOrDefault(date, List.of()));
                    write(file, null, plan);
                }
            });
        }

        /** Whether the user has the entry already; it is then skipped. */
        private boolean importedBefore(String file, Integer row, EntryPlan plan) {
            if (!externalRefs.contains(plan.externalRef())) {
                return false;
            }
            skipped.add(new Problem(file, row, label(row, plan) + "imported before"));
            return true;
        }

        /**
         * Writes the entry, creating the counterparties it names. The entry and its new counterparties are written
         * under one savepoint and rolled back together if the entry is refused.
         *
         * @param row the row the entry comes from, or null for opening balances
         * @return whether the entry was written
         */
        private boolean write(String file, Integer row, EntryPlan plan) {
            Map<String, Long> created = new HashMap<>();
            try {
                savepoint.executeWithoutResult(status -> entries.createImported(userId, command(plan, created),
                        batchId, plan.externalRef()));
            } catch (InvalidEntryException e) {
                errors.add(new Problem(file, row, label(row, plan) + String.join("; ", e.violations())));
                return false;
            } catch (DataAccessException e) {
                errors.add(new Problem(file, row, label(row, plan) + "the database refuses the entry: " + cause(e)));
                return false;
            }
            counterpartyIds.putAll(created);
            counterpartiesCreated += created.size();
            externalRefs.add(plan.externalRef());
            entriesByKind.merge(plan.kind(), 1, Integer::sum);
            return true;
        }

        /** What a problem is about, when there is no one row to name. */
        private static String label(Integer row, EntryPlan plan) {
            return row == null ? "the opening balances of " + ExcelValues.format(plan.date()) + ": " : "";
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
                id = counterparties.save(new Counterparty(null, userId, party.name(), party.kind(), null)).id();
                created.put(key, id);
            }
            return id;
        }

        /**
         * Runs the checks the database defers to the commit now, so that a dry run meets them too, and a commit
         * reports them instead of failing.
         */
        private void checkDeferredConstraints() {
            try {
                savepoint.executeWithoutResult(status -> jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update());
            } catch (DataAccessException e) {
                errors.add(new Problem(null, null, "the database's checks at commit fail: " + cause(e)));
            }
        }

        private Json summary(ImportReport report) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("outcome", report.outcome());
            summary.put("entries", report.entriesByKind());
            summary.put("errors", report.errors().size());
            summary.put("warnings", report.warnings().size());
            summary.put("skipped", report.skipped().size());
            try {
                return new Json(json.writeValueAsString(summary));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static AccountRef accountRef(Account account) {
        return new AccountRef(account.id(), account.code(), account.type(), account.requiresCounterparty());
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String cause(DataAccessException e) {
        return NestedExceptionUtils.getMostSpecificCause(e).getMessage();
    }
}
