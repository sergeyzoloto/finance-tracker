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
 * Writes, reads and searches journal entries. Callers pass the user id, the Keycloak "sub" claim (rule 11); nothing
 * here reads the security context. A command builds the entry, {@link LedgerValidator} checks it, and the entry is
 * written with its postings in one transaction. The rows the entry refers to stay locked until then, so the
 * database's own checks are a backstop that users don't meet.
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
    public EntryView create(String userId, EntryCommand command) {
        EntryDraft draft = validDraft(userId, command);
        return EntryView.of(entries.save(JournalEntry.create(userId, draft, null, null, Instant.now())));
    }

    /**
     * Creates an entry read from a file, like {@link #create}. The entry keeps its import batch and its identity in
     * the file, which the database holds unique per user.
     *
     * @param externalRef the entry's identity in the file, as the importer builds it
     * @throws InvalidEntryException naming every problem with the entry
     * @throws org.springframework.dao.DuplicateKeyException if the user already has an entry with this external ref
     */
    @Transactional
    public EntryView createImported(String userId, EntryCommand command, long importBatchId, String externalRef) {
        EntryDraft draft = validDraft(userId, command);
        return EntryView.of(entries.save(JournalEntry.create(userId, draft, importBatchId, externalRef,
                Instant.now())));
    }

    /**
     * Replaces the entry's fields and all its postings with those the command builds.
     *
     * @param expectedVersion the version the caller read
     * @throws EntryNotFoundException if the user has no such entry
     * @throws OptimisticLockingFailureException if the entry is no longer at {@code expectedVersion}
     * @throws InvalidEntryException naming every problem with the new entry
     */
    @Transactional
    public EntryView update(String userId, long entryId, int expectedVersion, EntryCommand command) {
        JournalEntry entry = find(userId, entryId);
        requireVersion(entry, expectedVersion);
        EntryDraft draft = validDraft(userId, command);
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
     * @throws EntryNotFoundException if the user has no such entry
     * @throws OptimisticLockingFailureException if the entry is no longer at {@code expectedVersion}
     */
    @Transactional
    public void delete(String userId, long entryId, int expectedVersion) {
        JournalEntry entry = find(userId, entryId);
        requireVersion(entry, expectedVersion);
        try {
            entries.delete(entry);
        } catch (OptimisticLockingFailureException e) {
            throw stale(entryId, expectedVersion, e);
        }
    }

    /** @throws EntryNotFoundException if the user has no such entry */
    // The entry and its postings are read by separate queries; one snapshot keeps them consistent.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryView get(String userId, long entryId) {
        return EntryView.of(find(userId, entryId));
    }

    /**
     * One page of the user's entries that match the filter, newest first: by entry date, then by id, both
     * descending.
     *
     * @param page the page's number, from 0
     * @param size the most entries a page holds
     */
    // The count, the page and its postings are read by separate queries; one snapshot keeps them consistent.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryPage search(String userId, EntryFilter filter, int page, int size) {
        // Only fixed fragments go into the SQL; every value is a parameter.
        StringBuilder where = new StringBuilder("e.user_id = :userId");
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
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
        String from = " FROM journal_entry e LEFT JOIN counterparty payee ON payee.id = e.payee_id WHERE " + where;

        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<EntryView> headers = jdbc.sql("SELECT e.id, e.version, e.entry_date, e.kind, e.payee_id, e.memo" + from
                        + " ORDER BY e.entry_date DESC, e.id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .query(EntryService::header)
                .list();
        return new EntryPage(withPostings(headers), page, size, total, Math.toIntExact((total + size - 1) / size));
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

    /** The entries with their postings in order, which are read in one query for all of them. */
    private List<EntryView> withPostings(List<EntryView> headers) {
        if (headers.isEmpty()) {
            return headers;
        }
        Map<Long, List<PostingLine>> postings = new LinkedHashMap<>();
        headers.forEach(entry -> postings.put(entry.id(), new ArrayList<>()));
        jdbc.sql("""
                SELECT entry_id, account_id, currency, amount, category_id, counterparty_id
                FROM posting WHERE entry_id IN (:entryIds) ORDER BY entry_id, line_no""")
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

    private JournalEntry find(String userId, long entryId) {
        return entries.findByIdAndUserId(entryId, userId).orElseThrow(() -> new EntryNotFoundException(entryId));
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

    private EntryDraft validDraft(String userId, EntryCommand command) {
        EntryDraft draft = command.draft(context(userId));
        validator.check(draft, references(userId, draft));
        return draft;
    }

    private LedgerContext context(String userId) {
        Optional<UserSettings> userSettings = settings.findById(userId);
        Map<String, Long> idsByCode = accounts.findAllByUserIdAndCodeIn(userId, ROLE_CODES).stream()
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

    /** The user's rows among those the entry refers to, locked until the transaction ends. */
    private LedgerReferences references(String userId, EntryDraft draft) {
        return new LedgerReferences(
                lookup(userId, draft.accountIds(), accounts::findAllByUserIdAndIdIn, Account::id,
                        a -> new AccountInfo(a.code(), a.type(), a.requiresCounterparty())),
                lookup(userId, draft.categoryIds(), categories::findAllByUserIdAndIdIn, LedgerCategory::id,
                        c -> new CategoryInfo(c.code(), c.type())),
                lookup(userId, draft.counterpartyIds(), counterparties::findAllByUserIdAndIdIn, Counterparty::id,
                        c -> c).keySet());
    }

    private static <T, V> Map<Long, V> lookup(String userId, Set<Long> ids,
            BiFunction<String, Collection<Long>, List<T>> find, Function<T, Long> id, Function<T, V> value) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return find.apply(userId, ids).stream().collect(Collectors.toMap(id, value));
    }
}
