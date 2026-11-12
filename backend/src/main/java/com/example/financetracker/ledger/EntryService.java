package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.financetracker.WallClock;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
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
import com.example.financetracker.ledger.family.FamilySwitch;
import org.springframework.beans.factory.ObjectProvider;
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
    private final LedgerAccess access;
    private final ObjectProvider<FamilyPayments> familyPayments;
    private final FamilySwitch familySwitch;
    private final LedgerValidator validator = new LedgerValidator();

    EntryService(JournalEntryRepository entries, AccountRepository accounts, LedgerCategoryRepository categories,
            CounterpartyRepository counterparties, UserSettingsRepository settings, JdbcClient jdbc,
            LedgerAccess access, ObjectProvider<FamilyPayments> familyPayments, FamilySwitch familySwitch) {
        this.entries = entries;
        this.accounts = accounts;
        this.categories = categories;
        this.counterparties = counterparties;
        this.settings = settings;
        this.jdbc = jdbc;
        this.access = access;
        this.familyPayments = familyPayments;
        this.familySwitch = familySwitch;
    }

    /** @throws InvalidEntryException naming every problem with the entry */
    @Transactional
    public EntryView create(LedgerScope ledger, EntryCommand command) {
        EntryDraft draft = validDraft(ledger, command);
        return EntryView.of(entries.save(JournalEntry.create(ledger, draft, null, null, WallClock.now())));
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
                WallClock.now())));
    }

    /**
     * Replaces the entry's fields and all its postings with those the command builds.
     *
     * @param expectedVersion the version the caller read
     * @throws EntryNotFoundException if the ledger has no such entry
     * @throws OptimisticLockingFailureException if the entry is no longer at {@code expectedVersion}
     * @throws InvalidEntryException naming every problem with the new entry
     * @throws ConflictException if a family budget posted the entry, or it is a payment for a family record (D-8)
     */
    @Transactional
    public EntryView update(LedgerScope ledger, long entryId, int expectedVersion, EntryCommand command) {
        JournalEntry entry = find(ledger, entryId);
        requireOwn(ledger, entry);
        requireVersion(entry, expectedVersion);
        EntryDraft draft = validDraft(ledger, command);
        try {
            return EntryView.of(entries.save(entry.replacedBy(draft, WallClock.now())));
        } catch (OptimisticLockingFailureException e) {
            // Changed by a concurrent transaction since it was read above.
            throw stale(entryId, expectedVersion, e);
        }
    }

    /**
     * Deletes the entry with its postings. The payer's own payment for a family expense deletes the expense, with every
     * member's share of it, as the receiver's receipt deletes an income, and a member's own side of a settlement deletes
     * the settlement if they recorded it, while the family budget is switched on (F4c, F4d, D-14).
     *
     * @param expectedVersion the version the caller read
     * @throws EntryNotFoundException if the ledger has no such entry
     * @throws OptimisticLockingFailureException if the entry is no longer at {@code expectedVersion}
     * @throws ConflictException if a family budget posted the entry, or it is a payment for a family record and the
     *         family budget is switched off (D-8)
     */
    @Transactional
    public void delete(LedgerScope ledger, long entryId, int expectedVersion) {
        JournalEntry entry = find(ledger, entryId);
        EntryFamily family = families(ledger, List.of(entry.id())).get(entry.id());
        FamilyPayments payments = familyPayments.getIfAvailable();
        if (family != null && List.of("PAYMENT", "SETTLEMENT").contains(family.link()) && payments != null) {
            requireVersion(entry, expectedVersion);
            payments.deleteRecordOf(ledger, entryId);
            return;
        }
        requireOwn(ledger, entry);
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
        return EntryView.of(find(ledger, entryId), families(ledger, List.of(entryId)).get(entryId));
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
        Map<String, Object> params = new HashMap<>();
        String from = matching(ledger, filter, params);

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

    /**
     * What every entry that matches the filter adds up to, on every page, as the entry list's cells show them (UX-11,
     * D-524). The same entries as {@link #search}: both build their WHERE in {@link #matching}.
     */
    // Two statements; one snapshot keeps them consistent.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntrySummary summary(LedgerScope ledger, EntryFilter filter) {
        Map<String, Object> params = new HashMap<>();
        params.put("fx", AccountRole.FX_EXCHANGE.defaultCode());
        // The matching entries, with the payee their Payee cell shows when it shows none of the entry's own (D-523).
        String matched = "WITH m AS (SELECT e.id, e.entry_date, e.payee_id, rp.name AS record_payee"
                + matching(ledger, filter, params) + ")";
        EntrySummary counts = jdbc.sql(matched + """
                , payees AS (
                    SELECT m.id, lower(c.name) AS name FROM m
                    JOIN counterparty c ON c.id = m.payee_id AND c.ledger_id = :ledgerId
                    UNION ALL
                    SELECT m.id, lower(c.name) FROM m
                    JOIN posting p ON p.entry_id = m.id
                    JOIN counterparty c ON c.id = p.counterparty_id AND c.ledger_id = :ledgerId
                    WHERE m.payee_id IS NULL
                    UNION ALL
                    SELECT m.id, lower(m.record_payee) FROM m
                    WHERE m.payee_id IS NULL AND m.record_payee IS NOT NULL
                      AND NOT EXISTS (SELECT FROM posting p WHERE p.entry_id = m.id AND p.counterparty_id IS NOT NULL)
                ), categories AS (
                    SELECT p.entry_id AS id, p.category_id FROM m JOIN posting p ON p.entry_id = m.id
                    WHERE p.category_id IS NOT NULL
                    UNION ALL
                    SELECT m.id, c.id FROM m
                    JOIN family_entry_link l ON l.entry_id = m.id AND l.detached_at IS NULL
                    JOIN family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                    JOIN category c ON c.id = r.category_id AND c.ledger_id = r.ledger_id
                    WHERE NOT EXISTS (SELECT FROM posting p WHERE p.entry_id = m.id AND p.category_id IS NOT NULL)
                ), fx AS (
                    SELECT a.id FROM account a WHERE a.ledger_id = :ledgerId AND a.code = :fx
                ), accounts AS (
                    SELECT p.account_id FROM m JOIN posting p ON p.entry_id = m.id
                    WHERE p.category_id IS NULL
                      AND (p.account_id NOT IN (SELECT id FROM fx)
                           OR NOT EXISTS (SELECT FROM posting q WHERE q.entry_id = m.id
                                          AND q.account_id NOT IN (SELECT id FROM fx)))
                )
                SELECT (SELECT count(*) FROM m) AS count,
                       (SELECT min(entry_date) FROM m) AS first_date,
                       (SELECT max(entry_date) FROM m) AS last_date,
                       (SELECT count(DISTINCT name) FROM payees) AS payees,
                       (SELECT count(*) FROM m WHERE NOT EXISTS (SELECT FROM payees x WHERE x.id = m.id)) AS without_payee,
                       (SELECT count(DISTINCT category_id) FROM categories) AS categories,
                       (SELECT count(*) FROM m WHERE NOT EXISTS (SELECT FROM categories x WHERE x.id = m.id))
                           AS without_category,
                       (SELECT count(DISTINCT account_id) FROM accounts) AS accounts""")
                .params(params)
                .query((row, n) -> new EntrySummary(row.getLong("count"),
                        row.getObject("first_date", LocalDate.class), row.getObject("last_date", LocalDate.class),
                        row.getLong("payees"), row.getLong("without_payee"), row.getLong("categories"),
                        row.getLong("without_category"), row.getLong("accounts"), List.of()))
                .single();
        // describeEntry's rule: per currency the sum of the entry's positive lines, its sign the opposite of the net of
        // its categorized lines (rule 5): below 0 an income or a refund (+), above 0 an expense (−), else a transfer.
        List<EntrySummary.CurrencyTotals> currencies = jdbc.sql(matched + """
                , direction AS (
                    SELECT m.id, sign(coalesce(sum(p.amount) FILTER (WHERE p.category_id IS NOT NULL), 0)) AS net
                    FROM m JOIN posting p ON p.entry_id = m.id GROUP BY m.id
                ), amounts AS (
                    SELECT p.entry_id AS id, p.currency, sum(p.amount) AS amount
                    FROM m JOIN posting p ON p.entry_id = m.id WHERE p.amount > 0
                    GROUP BY p.entry_id, p.currency
                )
                SELECT a.currency,
                       coalesce(sum(a.amount) FILTER (WHERE d.net < 0), 0) AS incoming,
                       coalesce(sum(a.amount) FILTER (WHERE d.net > 0), 0) AS outgoing,
                       coalesce(sum(a.amount) FILTER (WHERE d.net = 0), 0) AS transfers
                FROM amounts a JOIN direction d ON d.id = a.id
                GROUP BY a.currency ORDER BY a.currency""")
                .params(params)
                .query((row, n) -> new EntrySummary.CurrencyTotals(row.getString("currency"),
                        Money.normalize(row.getBigDecimal("incoming")), Money.normalize(row.getBigDecimal("outgoing")),
                        Money.normalize(row.getBigDecimal("transfers"))))
                .list();
        return new EntrySummary(counts.count(), counts.firstDate(), counts.lastDate(), counts.payees(),
                counts.withoutPayee(), counts.categories(), counts.withoutCategory(), counts.accounts(), currencies);
    }

    /**
     * The FROM and WHERE of the ledger's entries that match the filter, aliased {@code e}, with the entry's payee as
     * {@code payee} and its family record's payee as {@code rp} ({@link RecordPayees}); built once for {@link #search}
     * and {@link #summary}. Only fixed fragments go into the SQL; every value is a parameter, put into {@code params}.
     */
    private String matching(LedgerScope ledger, EntryFilter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("e.ledger_id = :ledgerId");
        params.put("ledgerId", ledger.ledgerId());
        RecordPayees.params(params, ledger, familySwitch.enabled());
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
            // A categorized line, or (UX-9, D-478) for an entry without one a family link whose record has the
            // category, read as families() reads links: exactly the entries whose Category cell shows it.
            where.append(" AND (EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id AND p.category_id = :categoryId)"
                    + " OR (NOT EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id AND p.category_id IS NOT NULL)"
                    + " AND EXISTS (SELECT FROM family_entry_link l JOIN family_record r ON r.id = l.record_id"
                    + " AND r.ledger_id = l.family_ledger_id WHERE l.entry_id = e.id AND l.detached_at IS NULL"
                    + " AND r.category_id = :categoryId)))");
            params.put("categoryId", filter.categoryId());
        }
        if (filter.counterpartyId() != null) {
            // The entry's payee or a line's counterparty, or (UX-11, D-523) for an entry with neither its family
            // record's payee by the chosen counterparty's name, ignoring case: exactly the entries whose Payee cell
            // shows it.
            where.append(" AND (e.payee_id = :counterpartyId OR EXISTS"
                    + " (SELECT FROM posting p WHERE p.entry_id = e.id AND p.counterparty_id = :counterpartyId)"
                    + " OR (e.payee_id IS NULL"
                    + " AND NOT EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id AND p.counterparty_id IS NOT NULL)"
                    + " AND lower(rp.name) = (SELECT lower(c.name) FROM counterparty c"
                    + " WHERE c.id = :counterpartyId AND c.ledger_id = :ledgerId)))");
            params.put("counterpartyId", filter.counterpartyId());
        }
        if (filter.text() != null) {
            where.append(" AND (e.memo ILIKE :text OR payee.name ILIKE :text OR rp.name ILIKE :text)");
            params.put("text", "%" + likeLiteral(filter.text()) + "%");
        }
        // The composite foreign key (ledger_id, payee_id) keeps the payee in the entry's ledger.
        return " FROM journal_entry e LEFT JOIN counterparty payee ON payee.id = e.payee_id" + RecordPayees.join("rp")
                + " WHERE " + where;
    }

    /** The text with LIKE's wildcards escaped by backslash, which is PostgreSQL's default escape character. */
    private static String likeLiteral(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** An entry's own columns, without its postings; {@link #withPostings} adds them. */
    static EntryView header(ResultSet row, int rowNum) throws SQLException {
        String kind = row.getString("kind");
        return new EntryView(row.getLong("id"), row.getInt("version"), row.getObject("entry_date", LocalDate.class),
                kind == null ? null : EntryKind.valueOf(kind), row.getObject("payee_id", Long.class),
                row.getString("memo"), List.of(), null);
    }

    /** The entries of the ledger with their postings in order, which are read in one query for all of them. */
    List<EntryView> withPostings(LedgerScope ledger, List<EntryView> headers) {
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
        Map<Long, EntryFamily> families = families(ledger, postings.keySet());
        return headers.stream()
                .map(e -> new EntryView(e.id(), e.version(), e.entryDate(), e.kind(), e.payeeId(), e.memo(),
                        List.copyOf(postings.get(e.id())), families.get(e.id())))
                .toList();
    }

    /**
     * The family budgets that posted these entries of the ledger, or that they are payments for, by entry id. Detached
     * links (D-19) don't count: those entries are the member's own.
     */
    private Map<Long, EntryFamily> families(LedgerScope ledger, Collection<Long> entryIds) {
        Map<Long, EntryFamily> families = new HashMap<>();
        if (entryIds.isEmpty()) {
            return families;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("ledgerId", ledger.ledgerId());
        params.put("entryIds", entryIds);
        RecordPayees.params(params, ledger, familySwitch.enabled());
        jdbc.sql("""
                SELECT l.entry_id, l.family_ledger_id, f.name, l.record_id, l.link_type, r.type AS record_type,
                       c.id AS category_id, c.name AS category_name, rp.name AS record_payee
                FROM family_entry_link l
                JOIN journal_entry e ON e.id = l.entry_id
                JOIN ledger f ON f.id = l.family_ledger_id
                LEFT JOIN family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                LEFT JOIN category c ON c.id = r.category_id AND c.ledger_id = r.ledger_id"""
                + RecordPayees.join("rp") + """

                WHERE e.ledger_id = :ledgerId AND l.entry_id IN (:entryIds) AND l.detached_at IS NULL""")
                .params(params)
                .query(row -> {
                    families.put(row.getLong("entry_id"), new EntryFamily(row.getLong("family_ledger_id"),
                            row.getString("name"), row.getObject("record_id", Long.class),
                            row.getString("link_type"), true, row.getString("record_type"),
                            row.getObject("category_id", Long.class) == null ? null
                                    : new EntryFamily.RecordCategory(row.getLong("category_id"),
                                            row.getString("category_name")),
                            row.getString("record_payee") == null ? null
                                    : new EntryFamily.RecordPayee(row.getString("record_payee"))));
                });
        return families;
    }

    /**
     * Refuses a change of an entry that a family budget posted, or that is the payer's payment for a family record:
     * it changes through the record (D-8). The payer changes their payment's date, amount, account and note through
     * {@code PATCH /api/entries/{id}/family-payment} (F4c), which changes the record.
     */
    private void requireOwn(LedgerScope ledger, JournalEntry entry) {
        EntryFamily family = families(ledger, List.of(entry.id())).get(entry.id());
        if (family == null) {
            // An entry of the member's that a family budget posted before they left, which posts to the debt account
            // that names the budget again since they returned (F6a, D-26): the budget's correction counts it, so it
            // changes only with the budget's records (D-10).
            jdbc.sql("""
                    SELECT f.name FROM posting p
                    JOIN account a ON a.id = p.account_id AND a.ledger_id = :ledgerId
                    JOIN ledger f ON f.id = a.family_ledger_id
                    WHERE p.entry_id = :entryId
                    LIMIT 1""")
                    .param("ledgerId", ledger.ledgerId()).param("entryId", entry.id())
                    .query(String.class).optional()
                    .ifPresent(name -> {
                        throw new ConflictException(("Entry %d posts to your debt to the family budget \"%s\", which "
                                + "changes only through the family budget").formatted(entry.id(), name));
                    });
            return;
        }
        throw new ConflictException(switch (family.link()) {
            case "PAYMENT" -> "INCOME".equals(family.recordType())
                    ? ("Entry %d is what you received for an income of the family budget \"%s\"; change or delete the "
                            + "income there").formatted(entry.id(), family.ledgerName())
                    : ("Entry %d is your payment for an expense of the family budget \"%s\"; change or delete the "
                            + "expense there").formatted(entry.id(), family.ledgerName());
            case "SETTLEMENT" -> ("Entry %d is your side of a settlement in the family budget \"%s\"; change it "
                    + "there").formatted(entry.id(), family.ledgerName());
            default -> "Entry %d was posted from the family budget \"%s\"; change it there".formatted(entry.id(),
                    family.ledgerName());
        });
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
        List<String> violations = new ArrayList<>(validator.violations(draft, references(ledger, draft)));
        violations.addAll(familyDebtPostings(ledger, draft));
        if (!violations.isEmpty()) {
            throw new InvalidEntryException(violations);
        }
        return draft;
    }

    /** Postings to a family budget's debt account, which only the family budget posts to (D-8, D-10). */
    private List<String> familyDebtPostings(LedgerScope ledger, EntryDraft draft) {
        if (draft.accountIds().isEmpty()) {
            return List.of();
        }
        Map<Long, String> debtAccounts = new HashMap<>();
        jdbc.sql("""
                SELECT id, code FROM account
                WHERE ledger_id = :ledgerId AND id IN (:accountIds) AND family_ledger_id IS NOT NULL""")
                .param("ledgerId", ledger.ledgerId())
                .param("accountIds", draft.accountIds())
                .query(row -> {
                    debtAccounts.put(row.getLong("id"), row.getString("code"));
                });
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < draft.postings().size(); i++) {
            String code = debtAccounts.get(draft.postings().get(i).accountId());
            if (code != null) {
                violations.add(("posting %d (%s): the account is a family budget's debt account, which only the "
                        + "family budget posts to").formatted(i + 1, code));
            }
        }
        return violations;
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
        Map<Long, CategoryInfo> categoryInfos = new HashMap<>(lookup(ledger, draft.categoryIds(),
                categories::lockAll, LedgerCategory::id, c -> new CategoryInfo(c.code(), c.type())));
        Set<Long> others = new HashSet<>(draft.categoryIds());
        others.removeAll(categoryInfos.keySet());
        if (!others.isEmpty() && ledger.type() == LedgerType.PERSONAL) {
            // The family categories of the member's ACTIVE family memberships (D-11, ADR 0003 topic F).
            for (LedgerScope family : access.families(ledger.userId())) {
                categoryInfos.putAll(lookup(family, others, categories::lockAll, LedgerCategory::id,
                        c -> new CategoryInfo(c.code(), c.type())));
            }
        }
        return new LedgerReferences(
                lookup(ledger, draft.accountIds(), accounts::lockAll, Account::id,
                        a -> new AccountInfo(a.code(), a.type(), a.requiresCounterparty())),
                categoryInfos,
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
