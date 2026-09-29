package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.EntryDraft;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.InvalidEntryException;
import com.example.financetracker.ledger.domain.LedgerContext;
import com.example.financetracker.ledger.domain.LedgerReferences;
import com.example.financetracker.ledger.domain.LedgerReferences.AccountInfo;
import com.example.financetracker.ledger.domain.LedgerReferences.CategoryInfo;
import com.example.financetracker.ledger.domain.LedgerValidator;
import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.domain.PostingLine;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes, reads and searches the journal entries of a ledger. Callers pass the {@link LedgerScope} that LedgerAccess
 * resolved (rule 11); nothing here reads the security context. A command builds the entry, {@link LedgerValidator}
 * checks it, and the entry is written with its postings in one transaction. The rows the entry refers to stay locked
 * until then, so the database's own checks are a backstop that users don't meet.
 */
@Service
public class EntryService {

    /** Rule 7, for a user without settings. */
    static final BigDecimal DEFAULT_SHARE_RATIO = new BigDecimal("0.50");

    private static final List<String> ROLE_CODES = Arrays.stream(AccountRole.values())
            .map(AccountRole::defaultCode)
            .toList();

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;
    private final LedgerCategoryRepository categories;
    private final CounterpartyRepository counterparties;
    private final UserSettingsRepository settings;
    private final JdbcClient jdbc;
    private final LedgerValidator validator = new LedgerValidator();

    EntryService(JournalEntryRepository entries, AccountRepository accounts, LedgerCategoryRepository categories,
            CounterpartyRepository counterparties, UserSettingsRepository settings, JdbcClient jdbc) {
        this.entries = entries;
        this.accounts = accounts;
        this.categories = categories;
        this.counterparties = counterparties;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    /** @throws InvalidEntryException naming every problem with the entry */
    @Transactional
    public EntryView create(LedgerScope ledger, EntryCommand command) {
        EntryDraft draft = validDraft(ledger, command);
        return EntryView.of(entries.save(JournalEntry.create(ledger, draft, null, null, Instant.now())));
    }

    /**
     * Creates an entry read from a file, like {@link #create}. The entry keeps its import batch and its identity in
     * the file, which the database holds unique per ledger.
     *
     * @param externalRef the entry's identity in the file, as the importer builds it
     * @throws InvalidEntryException naming every problem with the entry
     * @throws org.springframework.dao.DuplicateKeyException if the ledger already has an entry with this external ref
     */
    @Transactional
    public EntryView createImported(LedgerScope ledger, EntryCommand command, long importBatchId,
            String externalRef) {
        EntryDraft draft = validDraft(ledger, command);
        return EntryView.of(entries.save(JournalEntry.create(ledger, draft, importBatchId, externalRef,
                Instant.now())));
    }

    /**
     * Replaces the entry's fields and all its postings with those the command builds.
     *
     * @param expectedVersion the version the caller read
     * @throws EntryNotFoundException if the ledger has no such entry
     * @throws OptimisticLockingFailureException if the entry is no longer at {@code expectedVersion}
     * @throws InvalidEntryException naming every problem with the new entry
     */
    @Transactional
    public EntryView update(LedgerScope ledger, long entryId, int expectedVersion, EntryCommand command) {
        JournalEntry entry = find(ledger, entryId);
        requireVersion(entry, expectedVersion);
        EntryDraft draft = validDraft(ledger, command);
        try {
            return EntryView.of(entries.save(entry.replacedBy(draft, Instant.now())));
        } catch (OptimisticLockingFailureException e) {
            // Changed by a concurrent transaction since it was read above.
            throw stale(entryId, expectedVersion, e);
        }
    }

    /**
     * Deletes the entry with its postings.
     *
     * @param expectedVersion the version the caller read
     * @throws EntryNotFoundException if the ledger has no such entry
     * @throws OptimisticLockingFailureException if the entry is no longer at {@code expectedVersion}
     */
    @Transactional
    public void delete(LedgerScope ledger, long entryId, int expectedVersion) {
        JournalEntry entry = find(ledger, entryId);
        requireVersion(entry, expectedVersion);
        try {
            entries.delete(entry);
        } catch (OptimisticLockingFailureException e) {
            throw stale(entryId, expectedVersion, e);
        }
    }

    /** @throws EntryNotFoundException if the ledger has no such entry */
    // The entry and its postings are read by separate queries; one snapshot keeps them consistent.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryView get(LedgerScope ledger, long entryId) {
        return EntryView.of(find(ledger, entryId));
    }

    /**
     * One page of the ledger's entries that match the filter, newest first: by entry date, then by id, both
     * descending.
     *
     * @param page the page's number, from 0
     * @param size the most entries a page holds
     */
    // The count, the page and its postings are read by separate queries; one snapshot keeps them consistent.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryPage search(LedgerScope ledger, EntryFilter filter, int page, int size) {
        // Only fixed fragments go into the SQL; every value is a parameter.
        StringBuilder where = new StringBuilder("e.ledger_id = :ledgerId");
        Map<String, Object> params = new HashMap<>();
        params.put("ledgerId", ledger.ledgerId());
        if (filter.from() != null) {
            where.append(" AND e.entry_date >= :from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            where.append(" AND e.entry_date <= :to");
            params.put("to", filter.to());
        }
        if (filter.accountId() != null) {
            where.append(" AND EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id AND p.account_id = :accountId)");
            params.put("accountId", filter.accountId());
        }
        if (filter.categoryId() != null) {
            where.append(" AND EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id AND p.category_id = :categoryId)");
            params.put("categoryId", filter.categoryId());
        }
        if (filter.counterpartyId() != null) {
            where.append(" AND (e.payee_id = :counterpartyId OR EXISTS"
                    + " (SELECT FROM posting p WHERE p.entry_id = e.id AND p.counterparty_id = :counterpartyId))");
            params.put("counterpartyId", filter.counterpartyId());
        }
        if (filter.text() != null) {
            where.append(" AND (e.memo ILIKE :text OR payee.name ILIKE :text)");
            params.put("text", "%" + likeLiteral(filter.text()) + "%");
        }
        // The composite foreign key (ledger_id, payee_id) keeps the payee in the entry's ledger.
        String from = " FROM journal_entry e LEFT JOIN counterparty payee ON payee.id = e.payee_id WHERE " + where;

        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<EntryView> headers = jdbc.sql("SELECT e.id, e.version, e.entry_date, e.kind, e.payee_id, e.memo" + from
                        + " ORDER BY e.entry_date DESC, e.id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .query(EntryService::header)
                .list();
        return new EntryPage(withPostings(ledger, headers), page, size, total,
                Math.toIntExact((total + size - 1) / size));
    }

    /** The text with LIKE's wildcards escaped by backslash, which is PostgreSQL's default escape character. */
    private static String likeLiteral(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** An entry's own columns, without its postings; {@link #withPostings} adds them. */
    private static EntryView header(ResultSet row, int rowNum) throws SQLException {
        String kind = row.getString("kind");
        return new EntryView(row.getLong("id"), row.getInt("version"), row.getObject("entry_date", LocalDate.class),
                kind == null ? null : EntryKind.valueOf(kind), row.getObject("payee_id", Long.class),
                row.getString("memo"), List.of());
    }

    /** The entries of the ledger with their postings in order, which are read in one query for all of them. */
    private List<EntryView> withPostings(LedgerScope ledger, List<EntryView> headers) {
        if (headers.isEmpty()) {
            return headers;
        }
        Map<Long, List<PostingLine>> postings = new LinkedHashMap<>();
        headers.forEach(entry -> postings.put(entry.id(), new ArrayList<>()));
        jdbc.sql("""
                SELECT p.entry_id, p.account_id, p.currency, p.amount, p.category_id, p.counterparty_id
                FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE e.ledger_id = :ledgerId AND p.entry_id IN (:entryIds)
                ORDER BY p.entry_id, p.line_no""")
                .param("ledgerId", ledger.ledgerId())
                .param("entryIds", postings.keySet())
                .query(row -> {
                    postings.get(row.getLong("entry_id")).add(new PostingLine(row.getLong("account_id"),
                            row.getString("currency"), Money.normalize(row.getBigDecimal("amount")),
                            row.getObject("category_id", Long.class), row.getObject("counterparty_id", Long.class)));
                });
        return headers.stream()
                .map(e -> new EntryView(e.id(), e.version(), e.entryDate(), e.kind(), e.payeeId(), e.memo(),
                        List.copyOf(postings.get(e.id()))))
                .toList();
    }

    private JournalEntry find(LedgerScope ledger, long entryId) {
        return entries.find(ledger, entryId)
                .orElseThrow(() -> new EntryNotFoundException(entryId));
    }

    private static void requireVersion(JournalEntry entry, int expectedVersion) {
        if (entry.version() != expectedVersion) {
            throw stale(entry.id(), expectedVersion, null);
        }
    }

    private static OptimisticLockingFailureException stale(long entryId, int expectedVersion, Throwable cause) {
        String message = "Journal entry %d has changed since version %d. Reload it and try again."
                .formatted(entryId, expectedVersion);
        return new OptimisticLockingFailureException(message, cause);
    }

    private EntryDraft validDraft(LedgerScope ledger, EntryCommand command) {
        EntryDraft draft = command.draft(context(ledger));
        validator.check(draft, references(ledger, draft));
        return draft;
    }

    /** The accounts commands post to by role, and the settings of the personal ledger's member. */
    private LedgerContext context(LedgerScope ledger) {
        Optional<UserSettings> userSettings = settings.findById(ledger.userId());
        Map<String, Long> idsByCode = accounts.findAllByCode(ledger, ROLE_CODES).stream()
                .collect(Collectors.toMap(Account::code, Account::id));
        Map<AccountRole, Long> roles = new EnumMap<>(AccountRole.class);
        for (AccountRole role : AccountRole.values()) {
            Long id = idsByCode.get(role.defaultCode());
            if (id != null) {
                roles.put(role, id);
            }
        }
        userSettings.map(UserSettings::sharedAccountId).ifPresent(id -> roles.put(AccountRole.SHARED, id));
        return new LedgerContext(roles,
                userSettings.map(UserSettings::defaultShareRatio).orElse(DEFAULT_SHARE_RATIO));
    }

    /** The ledger's rows among those the entry refers to, locked until the transaction ends. */
    private LedgerReferences references(LedgerScope ledger, EntryDraft draft) {
        return new LedgerReferences(
                lookup(ledger, draft.accountIds(), accounts::lockAll, Account::id,
                        a -> new AccountInfo(a.code(), a.type(), a.requiresCounterparty())),
                lookup(ledger, draft.categoryIds(), categories::lockAll, LedgerCategory::id,
                        c -> new CategoryInfo(c.code(), c.type())),
                lookup(ledger, draft.counterpartyIds(), counterparties::lockAll, Counterparty::id,
                        c -> c).keySet());
    }

    private static <T, V> Map<Long, V> lookup(LedgerScope ledger, Set<Long> ids,
            BiFunction<LedgerScope, Collection<Long>, List<T>> find, Function<T, Long> id, Function<T, V> value) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return find.apply(ledger, ids).stream().collect(Collectors.toMap(id, value));
    }
}
