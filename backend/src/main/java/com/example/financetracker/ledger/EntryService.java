package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
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
import com.example.financetracker.ledger.domain.InvalidEntryException;
import com.example.financetracker.ledger.domain.LedgerContext;
import com.example.financetracker.ledger.domain.LedgerReferences;
import com.example.financetracker.ledger.domain.LedgerReferences.AccountInfo;
import com.example.financetracker.ledger.domain.LedgerReferences.CategoryInfo;
import com.example.financetracker.ledger.domain.LedgerValidator;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads journal entries. Callers pass the user id, the Keycloak "sub" claim (rule 11); nothing here reads
 * the security context. A command builds the entry, {@link LedgerValidator} checks it, and the entry is written with
 * its postings in one transaction. The rows the entry refers to stay locked until then, so the database's own checks
 * are a backstop that users don't meet.
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
    private final LedgerValidator validator = new LedgerValidator();

    EntryService(JournalEntryRepository entries, AccountRepository accounts, LedgerCategoryRepository categories,
            CounterpartyRepository counterparties, UserSettingsRepository settings) {
        this.entries = entries;
        this.accounts = accounts;
        this.categories = categories;
        this.counterparties = counterparties;
        this.settings = settings;
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
