package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.example.financetracker.ledger.history.HistoryService;
import com.example.financetracker.ledger.history.RecordContents;
import com.example.financetracker.ledger.history.RecordType;
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
    private final HistoryService history;
    private final LedgerValidator validator = new LedgerValidator();

    EntryService(JournalEntryRepository entries, AccountRepository accounts, LedgerCategoryRepository categories,
            CounterpartyRepository counterparties, UserSettingsRepository settings, JdbcClient jdbc,
            LedgerAccess access, ObjectProvider<FamilyPayments> familyPayments, FamilySwitch familySwitch,
            HistoryService history) {
        this.entries = entries;
        this.accounts = accounts;
        this.categories = categories;
        this.counterparties = counterparties;
        this.settings = settings;
        this.jdbc = jdbc;
        this.access = access;
        this.familyPayments = familyPayments;
        this.familySwitch = familySwitch;
        this.history = history;
    }

    /** @throws InvalidEntryException naming every problem with the entry */
    @Transactional
    public EntryView create(LedgerScope ledger, EntryCommand command) {
        // The ledger's row first, as every writer takes it (HIST-4 part 2b): before the validation's FOR SHARE locks
        // of accounts, categories and counterparties and before any entry row, so no deadlock with a restore.
        history.takeRevision(ledger.ledgerId());
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
        history.takeRevision(ledger.ledgerId());
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
        JournalEntry replaced = entry.replacedBy(draft, WallClock.now());
        // D-995: a save that changes no field writes nothing, so it makes no version and takes no revision.
        if (history.unchanged(ledger.ledgerId(), RecordType.ENTRY, entryId, contentOf(replaced))) {
            return EntryView.of(entry);
        }
        history.takeRevision(ledger.ledgerId());
        try {
            return EntryView.of(entries.save(replaced));
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
        history.takeRevision(ledger.ledgerId());
        try {
            Map<String, Object> last = history.capture(RecordType.ENTRY, ledger.ledgerId(), entryId).orElse(null);
            entries.delete(entry);
            if (last != null) {
                history.deleted(ledger.ledgerId(), RecordType.ENTRY, entryId, last);
            }
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
        String matched = matched(ledger, filter, params, false);
        params.put("limit", size);
        params.put("offset", (long) page * size);
        // One pass over the matching set gives the page and, as a window over all of it, the total (ENT-3b, D-897).
        long[] total = {-1};
        List<EntryView> headers = jdbc.sql(matched + """
                        SELECT e.id, e.version, e.entry_date, e.kind, e.payee_id, e.memo, count(*) OVER () AS total
                        FROM journal_entry e JOIN m ON m.id = e.id
                        ORDER BY e.entry_date DESC, e.id DESC LIMIT :limit OFFSET :offset""")
                .params(params)
                .query((row, n) -> {
                    total[0] = row.getLong("total");
                    return header(row, n);
                })
                .list();
        if (total[0] < 0) {
            // No row on this page: the total is 0 for the first page, else the matching set's size.
            total[0] = page == 0 ? 0 : jdbc.sql(matched + " SELECT count(*) FROM m").params(params)
                    .query(Long.class).single();
        }
        return new EntryPage(withPostings(ledger, headers), page, size, total[0],
                Math.toIntExact((total[0] + size - 1) / size));
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
        // The matching entries, with the payee their Payee cell shows when it shows none of the entry's own (D-523),
        // worked out once (ENT-3b, D-897) for both parts of the answer.
        String matched = matched(ledger, filter, params, true);
        // The counts: how many entries, their dates, and the distinct payees, categories and accounts the cells show.
        nestedLoops(ledger, false);
        EntrySummary counts = jdbc.sql(matched + """
                , pl AS MATERIALIZED (
                    SELECT p.entry_id AS id, p.account_id, p.category_id, p.counterparty_id
                    FROM m JOIN posting p ON p.entry_id = m.id
                ), fx AS (
                    SELECT a.id FROM account a WHERE a.ledger_id = :ledgerId AND a.code = :fx
                ), ent AS (
                    SELECT id, bool_or(counterparty_id IS NOT NULL) AS has_counterparty,
                           bool_or(category_id IS NOT NULL) AS has_category,
                           bool_or(account_id NOT IN (SELECT id FROM fx)) AS has_other_account
                    FROM pl GROUP BY id
                ), payees AS (
                    SELECT m.id, lower(c.name) AS name FROM m
                    JOIN counterparty c ON c.id = m.payee_id AND c.ledger_id = :ledgerId
                    UNION ALL
                    SELECT pl.id, lower(c.name) FROM pl
                    JOIN m ON m.id = pl.id AND m.payee_id IS NULL
                    JOIN counterparty c ON c.id = pl.counterparty_id AND c.ledger_id = :ledgerId
                    UNION ALL
                    SELECT m.id, lower(m.record_payee) FROM m LEFT JOIN ent ON ent.id = m.id
                    WHERE m.payee_id IS NULL AND m.record_payee IS NOT NULL
                      AND NOT coalesce(ent.has_counterparty, FALSE)
                ), categories AS (
                    SELECT id, category_id FROM pl WHERE category_id IS NOT NULL
                    UNION ALL
                    SELECT m.id, c.id FROM m
                    JOIN family_entry_link l ON l.entry_id = m.id AND l.detached_at IS NULL
                    JOIN family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                    JOIN category c ON c.id = r.category_id AND c.ledger_id = r.ledger_id
                    LEFT JOIN ent ON ent.id = m.id
                    WHERE NOT coalesce(ent.has_category, FALSE)
                ), accounts AS (
                    SELECT pl.account_id FROM pl LEFT JOIN ent ON ent.id = pl.id
                    WHERE pl.category_id IS NULL
                      AND (pl.account_id NOT IN (SELECT id FROM fx) OR NOT coalesce(ent.has_other_account, FALSE))
                )
                SELECT (SELECT count(*) FROM m) AS count,
                       (SELECT min(entry_date) FROM m) AS first_date,
                       (SELECT max(entry_date) FROM m) AS last_date,
                       (SELECT count(DISTINCT name) FROM payees) AS payees,
                       (SELECT count(*) FROM m) - (SELECT count(DISTINCT id) FROM payees) AS without_payee,
                       (SELECT count(DISTINCT category_id) FROM categories) AS categories,
                       (SELECT count(*) FROM m) - (SELECT count(DISTINCT id) FROM categories) AS without_category,
                       (SELECT count(DISTINCT account_id) FROM accounts) AS accounts""")
                .params(params)
                .query((row, n) -> new EntrySummary(row.getLong("count"),
                        row.getObject("first_date", LocalDate.class), row.getObject("last_date", LocalDate.class),
                        row.getLong("payees"), row.getLong("without_payee"), row.getLong("categories"),
                        row.getLong("without_category"), row.getLong("accounts"), List.of()))
                .single();
        nestedLoops(ledger, true);
        // describeEntry's rule: per currency the sum of the entry's positive lines, its sign the opposite of the net of
        // its categorized lines (rule 5): below 0 an income or a refund (+), above 0 an expense (−), else a transfer.
        // UX-18, D-796 to D-798: a family record counts through the user's matched share entry (a live SHARE link),
        // once per record, with its full amount in the record's currency, the currency the share is posted in (F8a);
        // what of it isn't the entry's own amount falls on the other members. The paying side of a counted record
        // (a live PAYMENT link of a FAMILY_PAYMENT entry) leaves Transfers, its money being in the record's full amount.
        // With the family switch off nothing is counted (D-802).
        // It reads no payee: the matching set again, without the family records' payees (ENT-3b, D-897).
        List<EntrySummary.CurrencyTotals> currencies = jdbc.sql(matched(ledger, filter, params, false)
                + EntryClassification.ctes("m", "", true) + """
                , amounts AS (
                    SELECT id, currency, amount FROM lines WHERE amount IS NOT NULL
                )
                SELECT a.currency,
                       coalesce(sum(a.amount) FILTER (WHERE d.net < 0), 0) AS incoming,
                       coalesce(sum(a.amount) FILTER (WHERE d.net > 0), 0) AS outgoing,
                       coalesce(sum(a.amount) FILTER (WHERE d.net = 0
                                AND a.id NOT IN (SELECT id FROM shown_once)), 0) AS transfers,
                       coalesce(sum(c.full_amount - a.amount) FILTER (WHERE d.net < 0), 0) AS received_others,
                       coalesce(sum(c.full_amount - a.amount) FILTER (WHERE d.net > 0), 0) AS spent_others
                FROM amounts a JOIN direction d ON d.id = a.id
                LEFT JOIN counted c ON c.id = a.id AND c.currency = a.currency
                GROUP BY a.currency ORDER BY a.currency""")
                .params(params)
                .query((row, n) -> {
                    BigDecimal incoming = row.getBigDecimal("incoming");
                    BigDecimal outgoing = row.getBigDecimal("outgoing");
                    BigDecimal receivedOthers = row.getBigDecimal("received_others");
                    BigDecimal spentOthers = row.getBigDecimal("spent_others");
                    return new EntrySummary.CurrencyTotals(row.getString("currency"), Money.normalize(incoming),
                            Money.normalize(outgoing), Money.normalize(row.getBigDecimal("transfers")),
                            Money.normalize(outgoing.add(spentOthers)), Money.normalize(spentOthers),
                            Money.normalize(incoming.add(receivedOthers)), Money.normalize(receivedOthers));
                })
                .list()
                .stream()
                // A currency whose only figures were counted payments has nothing left to show.
                .filter(t -> t.incoming().signum() != 0 || t.outgoing().signum() != 0 || t.transfers().signum() != 0)
                .toList();
        return new EntrySummary(counts.count(), counts.firstDate(), counts.lastDate(), counts.payees(),
                counts.withoutPayee(), counts.categories(), counts.withoutCategory(), counts.accounts(), currencies);
    }

    /**
     * The entries the filters match, except the filters of the tab's own dimension, grouped by that dimension (ENT-3,
     * D-873, D-881): per value how many entries have it and My share per currency, from the same classification as
     * {@link #summary} ({@link EntryClassification}, D-893), so that with one value per entry the rows' amounts add up
     * to the summary's In and Out over the same filters without that dimension's. Categories, payees and accounts are
     * the ones the Category, Payee and Accounts cells show, as the summary counts them; payees by name ignoring case
     * (D-531). Entries with no category or payee are one row without a key. Read only, in one snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryBreakdown breakdown(LedgerScope ledger, EntryBreakdown.By by, EntryFilter filter) {
        Map<String, Object> params = new HashMap<>();
        params.put("fx", AccountRole.FX_EXCHANGE.defaultCode());
        String matched = matched(ledger, filter.without(by), params, by == EntryBreakdown.By.PAYEE);
        // dim (id, entry_date, key, name): the values each matching entry has in the dimension, as the summary's
        // categories, payees and accounts CTEs find them.
        // Categories and accounts name themselves by their key, so their names are looked up once per row, after grouping;
        // payees are named by the entries' own spelling. Duplicates (an entry with two lines of one category) are
        // counted once by count(DISTINCT id) below.
        String dim = switch (by) {
            case CATEGORY -> """
                    , dim AS (
                        SELECT m.id, m.entry_date, p.category_id::text AS key, NULL::text AS name
                        FROM m JOIN posting p ON p.entry_id = m.id
                        WHERE p.category_id IS NOT NULL
                        UNION ALL
                        SELECT m.id, m.entry_date, r.category_id::text, NULL FROM m
                        JOIN family_entry_link l ON l.entry_id = m.id AND l.detached_at IS NULL
                        JOIN family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                        WHERE r.category_id IS NOT NULL
                          AND NOT EXISTS (SELECT FROM posting p WHERE p.entry_id = m.id AND p.category_id IS NOT NULL)
                    )""";
            case PAYEE -> """
                    , dim AS (
                        SELECT m.id, m.entry_date, lower(c.name) AS key, c.name AS name FROM m
                        JOIN counterparty c ON c.id = m.payee_id AND c.ledger_id = :ledgerId
                        UNION ALL
                        SELECT m.id, m.entry_date, lower(c.name), c.name FROM m
                        JOIN posting p ON p.entry_id = m.id
                        JOIN counterparty c ON c.id = p.counterparty_id AND c.ledger_id = :ledgerId
                        WHERE m.payee_id IS NULL
                        UNION ALL
                        SELECT m.id, m.entry_date, lower(m.record_payee), m.record_payee FROM m
                        WHERE m.payee_id IS NULL AND m.record_payee IS NOT NULL
                          AND NOT EXISTS (SELECT FROM posting p WHERE p.entry_id = m.id AND p.counterparty_id IS NOT NULL)
                    )""";
            case ACCOUNT -> """
                    , fx AS (
                        SELECT a.id FROM account a WHERE a.ledger_id = :ledgerId AND a.code = :fx
                    ), dim AS (
                        SELECT m.id, m.entry_date, p.account_id::text AS key, NULL::text AS name
                        FROM m JOIN posting p ON p.entry_id = m.id
                        WHERE p.category_id IS NULL
                          AND (p.account_id NOT IN (SELECT id FROM fx)
                               OR NOT EXISTS (SELECT FROM posting q WHERE q.entry_id = m.id
                                              AND q.account_id NOT IN (SELECT id FROM fx)))
                    )""";
        };
        // The rows' names: by id from the dimension's table, or the newest entry's spelling of a payee.
        String rowsSql = switch (by) {
            case CATEGORY -> "SELECT k.key, c.name, count(DISTINCT k.id) AS entries FROM keyed k"
                    + " LEFT JOIN category c ON c.id = k.key::bigint GROUP BY k.key, c.name";
            case ACCOUNT -> "SELECT k.key, a.name, count(DISTINCT k.id) AS entries FROM keyed k"
                    + " LEFT JOIN account a ON a.id = k.key::bigint GROUP BY k.key, a.name";
            case PAYEE -> "SELECT key, (array_agg(name ORDER BY entry_date DESC, id DESC))[1] AS name,"
                    + " count(DISTINCT id) AS entries FROM keyed GROUP BY key";
        };
        // One statement (ENT-3b, D-897): the rows' counts as 'row' lines and My share as 'share' lines.
        record Counted(String key, String name, long entries) { }
        record Share(String key, String currency, BigDecimal incoming, BigDecimal outgoing) { }
        List<Counted> counts = new ArrayList<>();
        List<Share> shares = new ArrayList<>();
        jdbc.sql(matched + EntryClassification.ctes("m", "") + dim + """
                , keyed AS (
                    SELECT id, entry_date, key, name FROM dim
                    UNION ALL
                    SELECT m.id, m.entry_date, NULL, NULL FROM m WHERE NOT EXISTS (SELECT FROM dim d WHERE d.id = m.id)
                ), rows AS (
                    """ + rowsSql + """
                ), shares AS (
                    SELECT k.key, p.currency,
                           coalesce(sum(p.amount) FILTER (WHERE d.net < 0), 0) AS incoming,
                           coalesce(sum(p.amount) FILTER (WHERE d.net > 0), 0) AS outgoing
                    FROM (SELECT DISTINCT id, key FROM keyed) k
                    JOIN direction d ON d.id = k.id JOIN posting p ON p.entry_id = k.id AND p.amount > 0
                    GROUP BY k.key, p.currency
                )
                SELECT 'row' AS part, key, name, entries, NULL::text AS currency, NULL::numeric AS incoming,
                       NULL::numeric AS outgoing FROM rows
                UNION ALL
                SELECT 'share', key, NULL, NULL::bigint, currency, incoming, outgoing FROM shares
                ORDER BY part, currency""")
                .params(params)
                .query(row -> {
                    if (row.getString("part").equals("row")) {
                        counts.add(new Counted(row.getString("key"), row.getString("name"), row.getLong("entries")));
                    } else {
                        shares.add(new Share(row.getString("key"), row.getString("currency"),
                                row.getBigDecimal("incoming"), row.getBigDecimal("outgoing")));
                    }
                });
        String none = by == EntryBreakdown.By.CATEGORY ? "No category" : "No payee";
        List<EntryBreakdown.Row> rows = counts.stream()
                .filter(c -> c.key() != null || c.entries() > 0)
                .map(c -> new EntryBreakdown.Row(c.key(), c.key() == null ? none : c.name(), c.entries(),
                        shares.stream()
                                .filter(s -> Objects.equals(s.key(), c.key()))
                                .filter(s -> s.incoming().signum() != 0 || s.outgoing().signum() != 0)
                                .map(s -> new EntryBreakdown.Amounts(s.currency(), Money.normalize(s.incoming()),
                                        Money.normalize(s.outgoing())))
                                .toList()))
                .sorted(Comparator.comparingLong(EntryBreakdown.Row::entries).reversed()
                        .thenComparing(EntryBreakdown.Row::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(EntryBreakdown.Row::key, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        return new EntryBreakdown(by, rows);
    }

    /**
     * The filtered entries' My share by calendar month (ENT-4, D-882, D-883): In and Out per month and currency from the
     * summary's classification ({@link EntryClassification}), over the 12 calendar months ending with the filter's To
     * month, or without a To the month of the latest matching entry, whatever the filter's From, less the months before
     * the first matching entry. Two statements in one snapshot: the matching entries' first and last day, then the months.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryMonthly monthly(LedgerScope ledger, EntryFilter filter) {
        // Every filter but From: its first and last matching day.
        Map<String, Object> params = new HashMap<>();
        LocalDate[] days = jdbc.sql(matched(ledger, filter.withFrom(null), params, false)
                + " SELECT min(entry_date) AS first_day, max(entry_date) AS last_day FROM m")
                .params(params)
                .query((row, n) -> new LocalDate[] {row.getObject("first_day", LocalDate.class),
                        row.getObject("last_day", LocalDate.class)})
                .single();
        if (days[0] == null) {
            return new EntryMonthly(null, null, List.of());
        }
        YearMonth last = filter.to() != null ? YearMonth.from(filter.to()) : YearMonth.from(days[1]);
        YearMonth window = last.minusMonths(MONTHLY_SPAN - 1);
        YearMonth first = YearMonth.from(days[0]).isAfter(window) ? YearMonth.from(days[0]) : window;
        Map<String, Object> monthParams = new HashMap<>();
        List<EntryMonthly.Month> months = jdbc.sql(matched(ledger, filter.withFrom(first.atDay(1)), monthParams, false)
                + EntryClassification.ctes("m", "") + """
                SELECT to_char(m.entry_date, 'YYYY-MM') AS month, p.currency,
                       coalesce(sum(p.amount) FILTER (WHERE d.net < 0), 0) AS incoming,
                       coalesce(sum(p.amount) FILTER (WHERE d.net > 0), 0) AS outgoing
                FROM m JOIN direction d ON d.id = m.id
                JOIN posting p ON p.entry_id = m.id AND p.amount > 0
                GROUP BY 1, 2 ORDER BY 1, 2""")
                .params(monthParams)
                .query((row, n) -> new EntryMonthly.Month(row.getString("month"), row.getString("currency"),
                        Money.normalize(row.getBigDecimal("incoming")), Money.normalize(row.getBigDecimal("outgoing"))))
                .list()
                .stream()
                // A month of transfers alone has no My share.
                .filter(m -> m.incoming().signum() != 0 || m.outgoing().signum() != 0)
                .toList();
        return new EntryMonthly(first.toString(), last.toString(), months);
    }

    /** The monthly chart's span: the filter's last month and the 11 before it (ENT-4, D-882). */
    private static final int MONTHLY_SPAN = 12;

    /**
     * The distinct payees that family records show on the ledger's entries (UX-12, D-548): every name the Payee cell
     * can show through {@link RecordPayees}, with the family budgets it comes from. Names are one when equal by the
     * database's {@code lower()} (D-531); a name is spelled as on the newest entry that shows it (D-554). Sorted by
     * name ignoring case, each name's budgets by name. Empty with the family switch off, for a family ledger and
     * without family links. Read only; nothing is stored.
     */
    @Transactional(readOnly = true)
    public List<FamilyPayee> familyPayees(LedgerScope ledger) {
        Map<String, Object> params = new HashMap<>();
        params.put("ledgerId", ledger.ledgerId());
        RecordPayees.params(params, ledger, familySwitch.enabled());
        Map<String, FamilyPayee> payees = new LinkedHashMap<>();
        jdbc.sql("WITH n AS (SELECT rp.name, rp.family_ledger_id, e.entry_date, e.id FROM journal_entry e"
                        + RecordPayees.joinSet("rp") + """

                    WHERE e.ledger_id = :ledgerId AND rp.name IS NOT NULL
                      AND EXISTS (SELECT FROM family_entry_link l WHERE l.entry_id = e.id)
                ), names AS (
                    SELECT lower(name) AS k, (array_agg(name ORDER BY entry_date DESC, id DESC))[1] AS name
                    FROM n GROUP BY lower(name)
                ), budgets AS (
                    SELECT DISTINCT lower(name) AS k, family_ledger_id FROM n
                )
                SELECT names.k, names.name, f.id AS budget_id, f.name AS budget_name
                FROM names JOIN budgets b ON b.k = names.k JOIN ledger f ON f.id = b.family_ledger_id
                ORDER BY names.k, names.name, lower(f.name), f.id""")
                .params(params)
                .query(row -> {
                    String name = row.getString("name");
                    payees.computeIfAbsent(row.getString("k"), k -> new FamilyPayee(name, new ArrayList<>()))
                            .budgets().add(new FamilyPayee.Budget(row.getLong("budget_id"),
                                    row.getString("budget_name")));
                });
        return payees.values().stream()
                .map(p -> new FamilyPayee(p.name(), List.copyOf(p.budgets())))
                .toList();
    }

    /**
     * The one statement that still needs the planner workaround, and only it (PERF-1, D-972 to D-975): the summary's
     * counts. It joins the family records' payees ({@link RecordPayees#joinSet}) and then reads that set (23,997 rows on
     * the dump) in six passes; the planner takes the set for 12 rows (it multiplies the selectivities of a chain of eight
     * joins), so it plans index nested loops that read the other CTEs again (the whole summary of All time: 540 ms against
     * 462; with Expenses and one category 838 against 477). With {@code enable_nestloop = off} for it (and on again, {@code SET LOCAL}
     * lasting to the end of the transaction, for the currencies statement, which reads no payee and does better with
     * every plan) every request of D-894 and the default view meets its limit. No other statement sets it: the list, the
     * breakdowns and the monthly chart are faster without (D-973).
     */
    private void nestedLoops(LedgerScope ledger, boolean on) {
        // A planner setting: no ledger table is read (the scope is only the rule's, ArchitectureTests).
        jdbc.sql(on ? "SET LOCAL enable_nestloop = on" : "SET LOCAL enable_nestloop = off").update();
    }

    /** Whether the filters read the family records' payees, which the page's list then joins (and sets the same). */
    private static boolean readsRecordPayee(EntryFilter filter) {
        return filter.text() != null || !filter.counterpartyIds().isEmpty() || !filter.payees().isEmpty();
    }

    /**
     * The entries the filter matches as the CTEs {@code b} (every filter but Direction) and {@code m} (Direction too),
     * both with the columns {@code id, entry_date, payee_id, kind} and, with {@code recordPayee}, {@code record_payee}:
     * the entry's family record's payee that the reader may see (D-523). Worked out once per request (ENT-3b, D-897) and
     * shared by everything the statement computes: the page, the count, the summary, the classification, the breakdown.
     * The Direction filter classifies {@code b} as the summary does ({@link EntryClassification}, D-893, D-880), so that
     * the entries a direction shows add up to that row of the summary. A statement continues with {@code ,} or
     * {@code SELECT}.
     */
    private String matched(LedgerScope ledger, EntryFilter filter, Map<String, Object> params, boolean recordPayee) {
        String cols = "e.id, e.entry_date, e.payee_id, e.kind" + (recordPayee ? ", rp.name AS record_payee" : "");
        StringBuilder sql = new StringBuilder("WITH b AS MATERIALIZED (SELECT " + cols
                + matching(ledger, filter, params, recordPayee) + ")");
        if (!filter.directions().isEmpty() && filter.directions().size() < EntryFilter.Direction.values().length) {
            sql.append(EntryClassification.ctes("b", "b_"))
                    .append(", m AS MATERIALIZED (SELECT b.* FROM b JOIN b_classified c ON c.id = b.id"
                            + " WHERE c.net IN (:directions))");
            params.put("directions", filter.directions().stream().map(d -> d.net).toList());
        } else {
            sql.append(", m AS (SELECT * FROM b)");
        }
        return sql.toString();
    }

    /**
     * The FROM and WHERE of the ledger's entries that match every filter but Direction, aliased {@code e}, with the
     * entry's payee as {@code payee} and, when {@code recordPayee} or a filter needs it, its family record's payee as
     * {@code rp} ({@link RecordPayees#joinSet}, worked out once). Only fixed fragments go into the SQL; every value is a
     * parameter, put into {@code params}.
     */
    private String matching(LedgerScope ledger, EntryFilter filter, Map<String, Object> params,
            boolean recordPayee) {
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
        if (!filter.accountIds().isEmpty()) {
            where.append(" AND EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id AND p.account_id IN (:accountIds))");
            params.put("accountIds", filter.accountIds());
        }
        if (!filter.categoryIds().isEmpty()) {
            // A categorized line, or (UX-9, D-478) for an entry without one a family link whose record has the
            // category, read as families() reads links: exactly the entries whose Category cell shows it. One set of
            // entry ids, worked out once (ENT-3b, D-897), in place of two subqueries per entry.
            where.append(" AND e.id IN (SELECT p.entry_id FROM posting p WHERE p.category_id IN (:categoryIds)"
                    + " UNION SELECT l.entry_id FROM family_entry_link l JOIN family_record r ON r.id = l.record_id"
                    + " AND r.ledger_id = l.family_ledger_id WHERE l.detached_at IS NULL"
                    + " AND r.category_id IN (:categoryIds)"
                    + " AND NOT EXISTS (SELECT FROM posting q WHERE q.entry_id = l.entry_id AND q.category_id IS NOT NULL))");
            params.put("categoryIds", filter.categoryIds());
        }
        // The Payee filter (D-873, D-888): any of its counterparties or names, exactly the entries whose Payee cell
        // shows one of them.
        List<String> payee = new ArrayList<>();
        // An entry with neither a payee nor a line's counterparty shows its family record's payee (UX-11, D-523).
        String recordPayeeOnly = "(e.payee_id IS NULL AND NOT EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id"
                + " AND p.counterparty_id IS NOT NULL))";
        if (!filter.counterpartyIds().isEmpty()) {
            // The entry's payee or a line's counterparty, or for an entry with neither its family record's payee by a
            // chosen counterparty's name, ignoring case.
            payee.add("e.payee_id IN (:counterpartyIds) OR EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id"
                    + " AND p.counterparty_id IN (:counterpartyIds)) OR (" + recordPayeeOnly
                    + " AND lower(rp.name) IN (SELECT lower(c.name) FROM counterparty c"
                    + " WHERE c.id IN (:counterpartyIds) AND c.ledger_id = :ledgerId))");
            params.put("counterpartyIds", filter.counterpartyIds());
        }
        if (!filter.payees().isEmpty()) {
            // UX-12, D-548: by name, ignoring case as D-531 does: the entry's payee, a line's counterparty, or for an
            // entry with neither its shown family record's payee (null with the switch off).
            String names = "(SELECT lower(n) FROM unnest(CAST(:payees AS text[])) n)";
            payee.add("lower(payee.name) IN " + names + " OR EXISTS (SELECT FROM posting p"
                    + " JOIN counterparty pc ON pc.id = p.counterparty_id AND pc.ledger_id = :ledgerId"
                    + " WHERE p.entry_id = e.id AND lower(pc.name) IN " + names + ")"
                    + " OR (" + recordPayeeOnly + " AND lower(rp.name) IN " + names + ")");
            params.put("payees", filter.payees().toArray(String[]::new));
        }
        if (!payee.isEmpty()) {
            where.append(" AND (").append(String.join(" OR ", payee)).append(")");
        }
        if (filter.text() != null) {
            where.append(" AND (e.memo ILIKE :text OR payee.name ILIKE :text OR rp.name ILIKE :text)");
            params.put("text", "%" + likeLiteral(filter.text()) + "%");
        }
        if (filter.origins().size() == 1) {
            // D-880: Family is an entry with a live family link, Personal one without.
            where.append(filter.origins().contains(EntryFilter.Origin.FAMILY) ? " AND " : " AND NOT ")
                    .append("EXISTS (SELECT FROM family_entry_link l WHERE l.entry_id = e.id AND l.detached_at IS NULL)");
        }
        // The family record's payee is joined only for what reads it: the summary's and the breakdown's payees, and the
        // filters by payee, counterparty and text.
        boolean needsRecordPayee = recordPayee || readsRecordPayee(filter);
        // The composite foreign key (ledger_id, payee_id) keeps the payee in the entry's ledger.
        return " FROM journal_entry e LEFT JOIN counterparty payee ON payee.id = e.payee_id"
                + (needsRecordPayee ? RecordPayees.joinSet("rp") : "") + " WHERE " + where;
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

    /** The content a version holds for this entry (ADR 0006, 5.2), for comparing with the live one. */
    static Map<String, Object> contentOf(JournalEntry entry) {
        return RecordContents.of(entry);
    }
}
