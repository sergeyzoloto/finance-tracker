package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.family.ImportSync;
import com.example.financetracker.ledger.importer.ExcelRow.Item;
import com.example.financetracker.ledger.importer.ExcelRow.Side;
import com.example.financetracker.ledger.importer.ExportSummary.AccountCheck;
import com.example.financetracker.ledger.importer.ExportSummary.SkippedCount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The export of a user's ledger as one transactions file in the import's format (D4a, D-235 to D-243), read only: his
 * personal entries, and the family records that are his rows (D-239): his own Excel rows in a family budget (the
 * references {@code xls:<his member id>:…}), the records of his own rows that his import put his side on
 * ({@code imported_family_side}), and those made in the app that he paid or is a side of. Another member's records
 * are never exported, not even those that move his debt to the family budget: his shares of them are "the other
 * members' records" of D-242's check.
 * <p>
 * Each record is one row, in the form the ledger holds it; one that no single row can stand for (an entry of more than
 * two accounts, such as the opening balances; a side of several rows of his, D-198) is skipped and counted by kind
 * (D4a 2.2). Where the ledger can't tell two forms of a row apart (which side carries Sum, the Family flag on a row
 * without an item, Comment the same as Counteragent, an empty LOAN_CA, a settlement's direction), the row is written in
 * the form whose hash is the one the import stored for it, which is the form the import read; a row with no stored
 * hash, or none that fits, in the first form (an agent's choice, D-245).
 * <p>
 * The period is by the record's date, both ends included (D-241). Rows come with Excel IDs ascending, then the app's
 * by date and id (D-240).
 */
@Service
public class ExportService {

    /** The ID of a row the app made (D-236): a personal entry's id after it, a family record's after {@link #APP_FAMILY}. */
    static final String APP = "app-";
    /** A family record's ID: {@code app-f<record id>}, so that it never collides with an entry's (D-244). */
    static final String APP_FAMILY = APP + "f";

    /** A kind of record skipped as no single row (D4a 2.2). */
    static final String SETTLEMENT_GROUP = "SETTLEMENT_GROUP";
    static final String FAMILY_RECORD = "FAMILY_RECORD";
    static final String FAMILY_SWITCHED_OFF = "FAMILY_SWITCHED_OFF";

    private static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);
    private static final Pattern PERSONAL_REF = Pattern.compile("xls:([0-9]{1,18})");
    private static final String UNALLOCATED = AccountRole.UNALLOCATED.defaultCode();
    private static final String FX_EXCHANGE = AccountRole.FX_EXCHANGE.defaultCode();
    private static final String FAMILY_DEBT = AccountRole.SHARED.defaultCode();

    private final JdbcClient jdbc;
    private final LedgerAccess access;
    private final FamilySwitch familySwitch;

    ExportService(JdbcClient jdbc, LedgerAccess access, FamilySwitch familySwitch) {
        this.jdbc = jdbc;
        this.access = access;
        this.familySwitch = familySwitch;
    }

    /**
     * The rows and the summary of the period, read in one snapshot.
     *
     * @param personal the user's personal ledger
     * @param from the period's first day, or null with {@code to} null for the whole period
     * @throws IllegalArgumentException if only one end is given, or {@code from} is after {@code to}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Export export(LedgerScope personal, LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("A period has both its first day (from) and its last (to), or neither "
                    + "for the whole period");
        }
        if (from != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The period's first day (from) is after its last (to)");
        }
        return new Run(personal, from == null ? EARLIEST : from, to == null ? LATEST : to, from == null).export();
    }

    /** The rows of a period and what D4a's summary says of them. */
    public record Export(List<ExcelRow> rows, ExportSummary summary) {
    }

    // --- What the ledger holds -------------------------------------------------------------------------------------

    private record Account(long id, String code, String name, String type, boolean requiresCounterparty,
            Long familyLedgerId) {

        Side side() {
            return new Side(name, code, type.equals("ASSET") ? ExcelRow.ASSET : ExcelRow.LIABILITIES);
        }
    }

    private record Category(long id, String code, String name, String type) {

        Item item() {
            return new Item(name, code, type);
        }
    }

    private record Line(long accountId, String currency, BigDecimal amount, Long categoryId, String counterparty) {
    }

    private record Entry(long id, LocalDate date, String kind, String externalRef, String importHash, String payee,
            String memo, List<Line> lines) {
    }

    private record Link(long entryId, Long recordId, String type, long familyLedgerId) {
    }

    private record Rec(long id, String type, LocalDate date, String currency, BigDecimal amount, long payer,
            Long payee, Long categoryId, String externalRef, String contentHash) {
    }

    private record SideRow(String ref, long recordId, String sideHash) {
    }

    /** Where an entry's postings go in D-242's check. */
    private enum Part { EXPORTED, OTHERS, SKIPPED }

    private Map<Long, Account> accounts(LedgerScope personal) {
        Map<Long, Account> accounts = new HashMap<>();
        jdbc.sql("""
                SELECT id, code, name, type, requires_counterparty, family_ledger_id
                FROM account WHERE ledger_id = :ledgerId""")
                .param("ledgerId", personal.ledgerId())
                .query((row, n) -> new Account(row.getLong("id"), row.getString("code"), row.getString("name"),
                        row.getString("type"), row.getBoolean("requires_counterparty"),
                        row.getObject("family_ledger_id", Long.class)))
                .list().forEach(account -> accounts.put(account.id(), account));
        return accounts;
    }

    private List<Category> categories(LedgerScope ledger) {
        return jdbc.sql("SELECT id, code, name, type FROM category WHERE ledger_id = :ledgerId")
                .param("ledgerId", ledger.ledgerId())
                .query((row, n) -> new Category(row.getLong("id"), row.getString("code"), row.getString("name"),
                        row.getString("type")))
                .list();
    }

    private List<Entry> entries(LedgerScope personal, LocalDate from, LocalDate to) {
        record Posting(Entry entry, Line line) {
        }
        Map<Long, Entry> entries = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT e.id, e.entry_date, e.kind, e.external_ref, e.import_hash, py.name AS payee, e.memo,
                       p.account_id, p.currency, p.amount, p.category_id, cp.name AS counterparty
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                LEFT JOIN counterparty py ON py.id = e.payee_id AND py.ledger_id = e.ledger_id
                LEFT JOIN counterparty cp ON cp.id = p.counterparty_id AND cp.ledger_id = e.ledger_id
                WHERE e.ledger_id = :ledgerId AND e.entry_date BETWEEN :from AND :to
                ORDER BY e.id, p.line_no""")
                .param("ledgerId", personal.ledgerId()).param("from", from).param("to", to)
                .query((row, n) -> new Posting(new Entry(row.getLong("id"), row.getObject("entry_date", LocalDate.class),
                        row.getString("kind"), row.getString("external_ref"), row.getString("import_hash"),
                        row.getString("payee"), row.getString("memo"), new ArrayList<>()),
                        new Line(row.getLong("account_id"), row.getString("currency").strip(),
                                row.getBigDecimal("amount"), row.getObject("category_id", Long.class),
                                row.getString("counterparty"))))
                .list()
                .forEach(posting -> entries.computeIfAbsent(posting.entry().id(), id -> posting.entry()).lines()
                        .add(posting.line()));
        return List.copyOf(entries.values());
    }

    private List<Link> links(LedgerScope personal, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT l.entry_id, l.record_id, l.link_type, l.family_ledger_id
                FROM family_entry_link l
                JOIN journal_entry e ON e.id = l.entry_id
                WHERE e.ledger_id = :ledgerId AND e.entry_date BETWEEN :from AND :to AND l.detached_at IS NULL""")
                .param("ledgerId", personal.ledgerId()).param("from", from).param("to", to)
                .query((row, n) -> new Link(row.getLong("entry_id"), row.getObject("record_id", Long.class),
                        row.getString("link_type"), row.getLong("family_ledger_id")))
                .list();
    }

    private List<Rec> records(LedgerScope family) {
        return jdbc.sql("""
                SELECT id, type, record_date, currency, base_amount, payer_member_id, payee_member_id, category_id,
                       external_ref, content_hash
                FROM family_record WHERE ledger_id = :ledgerId AND deleted_at IS NULL
                ORDER BY id""")
                .param("ledgerId", family.ledgerId())
                .query((row, n) -> new Rec(row.getLong("id"), row.getString("type"),
                        row.getObject("record_date", LocalDate.class), row.getString("currency").strip(),
                        row.getBigDecimal("base_amount"), row.getLong("payer_member_id"),
                        row.getObject("payee_member_id", Long.class), row.getObject("category_id", Long.class),
                        row.getString("external_ref"), row.getString("content_hash")))
                .list();
    }

    /** The member's own share of each record of the family budget, signed as stored. */
    private Map<Long, BigDecimal> shares(LedgerScope family) {
        Map<Long, BigDecimal> shares = new HashMap<>();
        jdbc.sql("""
                SELECT s.record_id, s.amount FROM family_share s
                JOIN family_record r ON r.id = s.record_id
                WHERE r.ledger_id = :ledgerId AND s.member_id = :memberId""")
                .param("ledgerId", family.ledgerId()).param("memberId", family.memberId())
                .query((row, n) -> shares.put(row.getLong("record_id"), row.getBigDecimal("amount")))
                .list();
        return shares;
    }

    /** D-181: the sides of records the member's own import put on them, by his rows' references. */
    private List<SideRow> sides(LedgerScope personal) {
        return jdbc.sql("SELECT external_ref, record_id, side_hash FROM imported_family_side WHERE ledger_id = :ledgerId")
                .param("ledgerId", personal.ledgerId())
                .query((row, n) -> new SideRow(row.getString("external_ref"), row.getLong("record_id"),
                        row.getString("side_hash")))
                .list();
    }

    /** Each account's displayed balance (rule 4) per currency as of the end of {@code asOf}. */
    private Map<Key, BigDecimal> balances(LedgerScope personal, LocalDate asOf) {
        Map<Key, BigDecimal> balances = new HashMap<>();
        jdbc.sql("""
                SELECT p.account_id, p.currency, sum(p.amount) AS total
                FROM journal_entry e JOIN posting p ON p.entry_id = e.id
                WHERE e.ledger_id = :ledgerId AND e.entry_date <= :asOf
                GROUP BY p.account_id, p.currency""")
                .param("ledgerId", personal.ledgerId()).param("asOf", asOf)
                .query((row, n) -> balances.put(new Key(row.getLong("account_id"), row.getString("currency").strip()),
                        row.getBigDecimal("total")))
                .list();
        return balances;
    }

    private record Key(long accountId, String currency) {
    }

    // --- One export --------------------------------------------------------------------------------------------------

    /** A row and the record behind it, for the order and D-242. */
    private record Written(ExcelRow row, long excelId, LocalDate date, long appId) {
    }

    private final class Run {

        private final LedgerScope personal;
        private final LocalDate from;
        private final LocalDate to;
        private final boolean wholePeriod;
        private final List<Written> written = new ArrayList<>();
        private final Map<String, Integer> skipped = new TreeMap<>();
        /** Each family record's part in D-242's check, by id; one missing is another member's. */
        private final Map<Long, Part> recordParts = new HashMap<>();
        private Map<Long, Account> accounts;
        private final Map<Long, Category> categories = new HashMap<>();
        private Account unallocated;

        Run(LedgerScope personal, LocalDate from, LocalDate to, boolean wholePeriod) {
            this.personal = personal;
            this.from = from;
            this.to = to;
            this.wholePeriod = wholePeriod;
        }

        Export export() {
            accounts = accounts(personal);
            unallocated = accounts.values().stream().filter(a -> UNALLOCATED.equals(a.code())).findFirst()
                    .orElse(null);
            categories(personal).forEach(c -> categories.put(c.id(), c));
            List<LedgerScope> families = familySwitch.enabled() ? access.families(personal.userId()) : List.of();
            families.forEach(family -> categories(family).forEach(c -> categories.put(c.id(), c)));
            List<Entry> entries = entries(personal, from, to);
            Map<Long, Link> links = new HashMap<>();
            links(personal, from, to).forEach(link -> links.put(link.entryId(), link));
            Map<Long, Map<String, Entry>> byRecord = new HashMap<>();
            Map<Long, Entry> entriesById = new HashMap<>();
            entries.forEach(entry -> entriesById.put(entry.id(), entry));
            links.values().forEach(link -> {
                if (link.recordId() != null && entriesById.containsKey(link.entryId())) {
                    byRecord.computeIfAbsent(link.recordId(), id -> new HashMap<>())
                            .put(link.type(), entriesById.get(link.entryId()));
                }
            });
            List<SideRow> sides = families.isEmpty() ? List.of() : sides(personal);
            for (LedgerScope family : families) {
                family(family, byRecord, sides);
            }

            Map<Key, BigDecimal[]> parts = new TreeMap<>(Comparator.comparing((Key k) -> code(k.accountId()))
                    .thenComparing(Key::currency));
            for (Entry entry : entries) {
                Link link = links.get(entry.id());
                Part part;
                if (link == null) {
                    part = personal(entry) ? Part.EXPORTED : Part.SKIPPED;
                } else if (families.isEmpty()) {
                    part = Part.SKIPPED;
                    skip(FAMILY_SWITCHED_OFF);
                } else if (link.recordId() == null) {
                    // A claim's opening balance or a return's correction (D-34, D-26): no record of his.
                    part = Part.SKIPPED;
                    skip(entry.kind());
                } else {
                    part = recordParts.getOrDefault(link.recordId(), Part.OTHERS);
                }
                for (Line line : entry.lines()) {
                    BigDecimal[] sums = parts.computeIfAbsent(new Key(line.accountId(), line.currency()),
                            k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                    sums[part.ordinal()] = sums[part.ordinal()].add(shown(line));
                }
            }
            return new Export(sorted(), summary(parts));
        }

        // --- Personal entries ------------------------------------------------------------------------------------

        /** Writes the entry as one row if it is one; else counts it as skipped. Whether it was written. */
        private boolean personal(Entry entry) {
            Shape shape = shape(entry);
            if (shape == null) {
                skip(entry.kind() == null ? "ENTRY" : entry.kind());
                return false;
            }
            Matcher ref = entry.externalRef() == null ? null : PERSONAL_REF.matcher(entry.externalRef());
            boolean imported = ref != null && ref.matches();
            String id = imported ? ref.group(1) : APP + entry.id();
            String hash = entry.importHash() == null ? null : entry.importHash().strip();
            ExcelRow row = shape.choose(id, hash == null ? null : r -> r.contentHash().equals(hash));
            written.add(new Written(row, imported ? Long.parseLong(ref.group(1)) : -1, entry.date(), entry.id()));
            return true;
        }

        /**
         * The entry as one row: two accounts in one currency, or in two currencies with FX_EXCHANGE between them (rule
         * 9), as the import writes a row (credit side first, the exchange, the debit side); else null. An entry the
         * import wrote keeps its sides; one made in the app has its debit side on the posting that is above 0.
         */
        private Shape shape(Entry entry) {
            List<Line> sides = new ArrayList<>();
            List<Line> exchange = new ArrayList<>();
            Long categoryId = null;
            for (Line line : entry.lines()) {
                Account account = accounts.get(line.accountId());
                if (FX_EXCHANGE.equals(account.code())) {
                    exchange.add(line);
                } else {
                    sides.add(line);
                }
                if (line.categoryId() != null) {
                    if (categoryId != null && !categoryId.equals(line.categoryId())) {
                        return null;
                    }
                    categoryId = line.categoryId();
                }
            }
            if (sides.size() != 2 || exchange.size() != 0 && exchange.size() != 2) {
                return null;
            }
            Line credit = sides.get(0);
            Line debit = sides.get(1);
            boolean imported = entry.externalRef() != null && PERSONAL_REF.matcher(entry.externalRef()).matches();
            if (!imported && debit.amount().signum() < 0) {
                Line other = credit;
                credit = debit;
                debit = other;
            }
            if (exchange.isEmpty()) {
                if (!credit.currency().equals(debit.currency()) || credit.amount().add(debit.amount()).signum() != 0) {
                    return null;
                }
            } else if (credit.currency().equals(debit.currency())
                    || !offsets(exchange, credit) || !offsets(exchange, debit)) {
                return null;
            }
            Item item = categoryId == null ? Item.NONE : categories.get(categoryId).item();
            Account debitAccount = accounts.get(debit.accountId());
            Account creditAccount = accounts.get(credit.accountId());
            Shape shape = new Shape(entry.date(), List.of(item), debitAccount.side(), debit.amount(),
                    debit.currency(), creditAccount.side(), credit.amount().negate(), credit.currency(), true);
            shape.counteragent(entry.payee(), entry.memo(), false);
            shape.loan(counterparty(debitAccount, debit, creditAccount, credit));
            // D-72: a family row without an item is a personal entry, which keeps no Family flag.
            shape.families = item == Item.NONE ? List.of(false, true) : List.of(false);
            return shape;
        }

        /** Whether one of the FX_EXCHANGE lines takes the side back out in its currency (rule 9). */
        private static boolean offsets(List<Line> exchange, Line side) {
            return exchange.stream().anyMatch(line -> line.currency().equals(side.currency())
                    && line.amount().add(side.amount()).signum() == 0);
        }

        // --- The family budget's records ---------------------------------------------------------------------------

        /** The member's rows of one family budget, and each record's part in D-242's check. */
        private void family(LedgerScope family, Map<Long, Map<String, Entry>> byRecord, List<SideRow> allSides) {
            long me = family.memberId();
            Pattern own = Pattern.compile("xls:" + me + ":([0-9]{1,18})(:[A-Z]{3})?");
            String lump = "xls:" + me + ":PL:";
            Map<Long, BigDecimal> shares = shares(family);
            List<Rec> records = records(family);
            Map<Long, Rec> recordsById = new HashMap<>();
            records.forEach(r -> recordsById.put(r.id(), r));
            Map<Long, SideRow> sideOf = new HashMap<>();
            allSides.forEach(side -> {
                if (recordsById.containsKey(side.recordId())) {
                    sideOf.put(side.recordId(), side);
                }
            });
            // The records that make one row each, by the row's key, in the records' order.
            Map<String, List<Rec>> groups = new LinkedHashMap<>();
            Map<String, String> ids = new HashMap<>();
            for (Rec r : records) {
                Matcher mine = r.externalRef() == null ? null : own.matcher(r.externalRef());
                SideRow side = sideOf.get(r.id());
                String key;
                String id;
                if (mine != null && mine.matches()) {
                    key = "xls:" + mine.group(1);
                    id = mine.group(1);
                } else if (r.externalRef() != null && r.externalRef().startsWith(lump)) {
                    key = "app:" + r.id();
                    id = APP_FAMILY + r.id();
                } else if (side != null) {
                    key = side.ref();
                    id = side.ref().substring(EntryMapper.REFERENCE_PREFIX.length());
                } else if (r.externalRef() == null && (r.payer() == me && !r.type().equals("SETTLEMENT")
                        || byRecord.getOrDefault(r.id(), Map.of()).containsKey("SETTLEMENT"))) {
                    key = "app:" + r.id();
                    id = APP_FAMILY + r.id();
                } else {
                    continue;
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                ids.putIfAbsent(key, id);
            }
            groups.forEach((key, group) -> {
                LocalDate date = group.getFirst().date();
                // A record's entries carry its date, so those of a record outside the period aren't read either.
                if (date.isBefore(from) || date.isAfter(to)) {
                    return;
                }
                String id = ids.get(key);
                boolean several = id.contains(ImportService.GROUP);
                ExcelRow row = several ? null : row(family, id, group, byRecord, shares, sideOf);
                group.forEach(r -> recordParts.put(r.id(), row == null ? Part.SKIPPED : Part.EXPORTED));
                if (row == null) {
                    skip(several ? SETTLEMENT_GROUP : FAMILY_RECORD);
                    return;
                }
                written.add(new Written(row, id.startsWith(APP) ? -1 : Long.parseLong(id), date, group.getFirst().id()));
            });
        }

        /** The records' row, or null if they aren't one. */
        private ExcelRow row(LedgerScope family, String id, List<Rec> group, Map<Long, Map<String, Entry>> byRecord,
                Map<Long, BigDecimal> shares, Map<Long, SideRow> sideOf) {
            long me = family.memberId();
            Rec first = group.getFirst();
            Account debt = familyDebt(family);
            SideRow side = sideOf.get(first.id());
            String sideHash = side == null ? null : side.sideHash().strip();
            if (group.stream().allMatch(r -> r.type().equals("SETTLEMENT"))) {
                if (group.stream().anyMatch(r -> !r.date().equals(first.date()))) {
                    return null;
                }
                boolean conversion = group.size() == 2 && !group.get(0).currency().equals(group.get(1).currency());
                return conversion ? conversion(id, group, me, debt, sideHash)
                        : settlement(id, group, byRecord, me, debt, sideHash);
            }
            if (group.size() != 1 || debt == null && first.payer() != me || unallocated == null) {
                return null;
            }
            Map<String, Entry> mine = byRecord.getOrDefault(first.id(), Map.of());
            Predicate<ExcelRow> matches = verifier(first.contentHash(), sideHash);
            Item item = first.categoryId() == null ? Item.NONE : categories.get(first.categoryId()).item();
            boolean expense = first.type().equals("EXPENSE");
            Entry payment = mine.get("PAYMENT");
            if (first.payer() == me && payment != null) {
                Line line = accountLine(payment, family);
                if (line == null) {
                    return null;
                }
                Account account = accounts.get(line.accountId());
                // The paying side, signed as the record: what went from the account for an expense, into it for an
                // income (a refund's the other way).
                BigDecimal paid = expense ? line.amount().negate() : line.amount();
                Shape shape = expense
                        ? new Shape(first.date(), List.of(item), unallocated.side(), first.amount(), first.currency(),
                                account.side(), paid, line.currency(), false)
                        : new Shape(first.date(), List.of(item), account.side(), paid, line.currency(),
                                unallocated.side(), first.amount(), first.currency(), false);
                shape.families = List.of(true);
                shape.counteragent(payment.payee(), payment.memo(), false);
                shape.loan(account.requiresCounterparty() ? line.counterparty() : null);
                return shape.choose(id, matches);
            }
            // His share of a record another member paid, which is his own row: D-73's, a lump (D-83).
            BigDecimal share = shares.get(first.id());
            if (share == null || debt == null || !mine.containsKey("SHARE")) {
                return null;
            }
            List<Item> items = item.code() != null && item.code().equals(ImportPlanner.OTHER_INCOME) && !expense
                    ? List.of(item, Item.NONE) : List.of(item);
            Shape shape = expense
                    ? new Shape(first.date(), items, unallocated.side(), share, first.currency(), debt.side(), share,
                            first.currency(), true)
                    : new Shape(first.date(), items, debt.side(), share, first.currency(), unallocated.side(), share,
                            first.currency(), true);
            shape.counteragent(null, null, true);
            shape.families = List.of(false, true);
            return shape.choose(id, matches);
        }

        /** A settlement, or several of one row (D-178's second tier): his side on its account against FAMILY_DEBT. */
        private ExcelRow settlement(String id, List<Rec> group, Map<Long, Map<String, Entry>> byRecord, long me,
                Account debt, String sideHash) {
            Rec first = group.getFirst();
            boolean pays = first.payer() == me;
            BigDecimal amount = BigDecimal.ZERO;
            BigDecimal sideAmount = BigDecimal.ZERO;
            Line sideLine = null;
            for (Rec r : group) {
                Entry entry = byRecord.getOrDefault(r.id(), Map.of()).get("SETTLEMENT");
                Line line = entry == null ? null : accountLine(entry, null);
                if (line == null || r.payer() == me != pays || !r.currency().equals(first.currency())
                        || sideLine != null && (sideLine.accountId() != line.accountId()
                                || !sideLine.currency().equals(line.currency()))) {
                    return null;
                }
                sideLine = line;
                amount = amount.add(r.amount());
                sideAmount = sideAmount.add(line.amount().abs());
            }
            if (debt == null) {
                return null;
            }
            Account account = accounts.get(sideLine.accountId());
            Shape shape = pays
                    ? new Shape(first.date(), List.of(Item.NONE), debt.side(), amount, first.currency(),
                            account.side(), sideAmount, sideLine.currency(), true)
                    : new Shape(first.date(), List.of(Item.NONE), account.side(), sideAmount, sideLine.currency(),
                            debt.side(), amount, first.currency(), true);
            shape.counteragent(null, null, true);
            shape.loan(account.requiresCounterparty() ? sideLine.counterparty() : null);
            shape.families = List.of(false, true);
            String hash = group.size() == 1 ? first.contentHash() : null;
            return shape.choose(id, verifier(hash, sideHash));
        }

        /**
         * D-71: a conversion of his family debt, two settlements through FX_EXCHANGE, as one row with FAMILY_DEBT on
         * both sides: the debit side the leg that lowers his debt (he pays), the credit side the one that raises it.
         */
        private ExcelRow conversion(String id, List<Rec> legs, long me, Account debt, String sideHash) {
            if (debt == null) {
                return null;
            }
            Rec debitLeg = legs.get(0).payer() == me || legs.get(1).payer() != me ? legs.get(0) : legs.get(1);
            Rec creditLeg = debitLeg == legs.get(0) ? legs.get(1) : legs.get(0);
            BigDecimal debitAmount = debitLeg.payer() == me ? debitLeg.amount() : debitLeg.amount().negate();
            BigDecimal creditAmount = creditLeg.payer() == me ? creditLeg.amount().negate() : creditLeg.amount();
            Shape shape = new Shape(debitLeg.date(), List.of(Item.NONE), debt.side(), debitAmount,
                    debitLeg.currency(), debt.side(), creditAmount, creditLeg.currency(), true);
            shape.counteragent(null, null, true);
            shape.families = List.of(false, true);
            Predicate<ExcelRow> legsMatch = legs.stream().allMatch(r -> r.contentHash() != null)
                    ? row -> legs.stream().allMatch(r -> r.contentHash().strip().equals(
                            ImportSync.contentHash(row.contentHash(), r.currency())))
                    : null;
            Predicate<ExcelRow> sideMatch = sideHash == null ? null : row -> row.sideHash().equals(sideHash);
            return shape.choose(id, legsMatch == null ? sideMatch
                    : sideMatch == null ? legsMatch : legsMatch.and(sideMatch));
        }

        /**
         * A record's stored hashes as a check of a row: its content hash, which is either the whole row's (the owner's
         * rows) or, for the partner's rows uploaded before his claim, D-151's; and his side's (D-181). Null for none.
         */
        private static Predicate<ExcelRow> verifier(String contentHash, String sideHash) {
            String content = contentHash == null ? null : contentHash.strip();
            Predicate<ExcelRow> record = content == null ? null
                    : row -> row.contentHash().equals(content) || row.uploadHash().equals(content);
            Predicate<ExcelRow> side = sideHash == null ? null : row -> row.sideHash().equals(sideHash);
            return record == null ? side : side == null ? record : record.and(side);
        }

        /**
         * The line of a member's own entry for a record that isn't on his debt to the family budget: his account, or
         * the placeholder of "Specify later"; FX_EXCHANGE only for a side that is on it alone. Null if there's none.
         *
         * @param family the family budget, or null for a settlement's side, whose debt line is told by its account
         */
        private Line accountLine(Entry entry, LedgerScope family) {
            Line exchange = null;
            for (Line line : entry.lines()) {
                Account account = accounts.get(line.accountId());
                if (account.familyLedgerId() != null) {
                    continue;
                }
                if (FX_EXCHANGE.equals(account.code())) {
                    exchange = exchange == null ? line : exchange;
                    continue;
                }
                return line;
            }
            return exchange;
        }

        /**
         * The account a row names for his debt to the family budget: the workbook's FAMILY_DEBT of his ledger, which
         * the family budget stands for (D-82), or, without one, the budget's own debt account.
         */
        private Account familyDebt(LedgerScope family) {
            return accounts.values().stream().filter(a -> FAMILY_DEBT.equals(a.code())).findFirst()
                    .orElseGet(() -> accounts.values().stream()
                            .filter(a -> Objects.equals(a.familyLedgerId(), family.ledgerId())).findFirst()
                            .orElse(null));
        }

        // --- The summary -------------------------------------------------------------------------------------------

        private List<ExcelRow> sorted() {
            written.sort(Comparator.comparing((Written w) -> w.excelId() < 0)
                    .thenComparingLong(Written::excelId)
                    .thenComparing(Written::date)
                    .thenComparing(w -> w.row().id().startsWith(APP_FAMILY))
                    .thenComparingLong(Written::appId));
            return written.stream().map(Written::row).toList();
        }

        private ExportSummary summary(Map<Key, BigDecimal[]> parts) {
            List<ExcelRow> rows = written.stream().map(Written::row).toList();
            LocalDate first = rows.stream().map(ExcelRow::date).min(LocalDate::compareTo).orElse(null);
            LocalDate last = rows.stream().map(ExcelRow::date).max(LocalDate::compareTo).orElse(null);
            Map<Key, BigDecimal> opening = wholePeriod ? Map.of() : balances(personal, from.minusDays(1));
            Map<Key, BigDecimal> closing = balances(personal, to);
            Map<Key, BigDecimal[]> all = new TreeMap<>(Comparator.comparing((Key k) -> code(k.accountId()))
                    .thenComparing(Key::currency));
            all.putAll(parts);
            opening.keySet().forEach(k -> all.putIfAbsent(k, zeros()));
            closing.keySet().forEach(k -> all.putIfAbsent(k, zeros()));
            List<AccountCheck> checks = new ArrayList<>();
            boolean ok = true;
            for (Map.Entry<Key, BigDecimal[]> e : all.entrySet()) {
                Account account = accounts.get(e.getKey().accountId());
                BigDecimal open = displayed(account, opening.getOrDefault(e.getKey(), BigDecimal.ZERO));
                BigDecimal close = displayed(account, closing.getOrDefault(e.getKey(), BigDecimal.ZERO));
                BigDecimal[] sums = e.getValue();
                boolean equal = close.subtract(open).compareTo(sums[0].add(sums[1]).add(sums[2])) == 0;
                ok &= equal;
                if (open.signum() == 0 && close.signum() == 0 && sums[0].signum() == 0 && sums[1].signum() == 0
                        && sums[2].signum() == 0) {
                    continue;
                }
                checks.add(new AccountCheck(code(account.id()), account.name(), e.getKey().currency(),
                        strip(open), strip(close), strip(sums[0]), strip(sums[1]), strip(sums[2]), equal));
            }
            List<SkippedCount> skippedCounts = skipped.entrySet().stream()
                    .map(e -> new SkippedCount(e.getKey(), e.getValue())).toList();
            LocalDate nameFrom = wholePeriod ? first : from;
            LocalDate nameTo = wholePeriod ? last : to;
            String fileName = nameFrom == null ? "Transactions.csv"
                    : "Transactions_%s_%s.csv".formatted(nameFrom, nameTo);
            return new ExportSummary(wholePeriod ? null : from, wholePeriod ? null : to, first, last, rows.size(),
                    skippedCounts, fileName, new ExportSummary.Check(ok, checks));
        }

        private String code(long accountId) {
            return accounts.get(accountId).code();
        }

        private void skip(String kind) {
            skipped.merge(kind, 1, Integer::sum);
        }

        private BigDecimal shown(Line line) {
            return displayed(accounts.get(line.accountId()), line.amount());
        }
    }

    /** Rule 4: an asset's balance is the sum of its postings, any other account's minus that. */
    private static BigDecimal displayed(Account account, BigDecimal sum) {
        return account.type().equals("ASSET") ? sum : sum.negate();
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static BigDecimal strip(BigDecimal amount) {
        return amount.signum() == 0 ? BigDecimal.ZERO : amount.stripTrailingZeros();
    }

    /** The counterparty of the side on an account that requires one (rule 8), the debit's first; else null. */
    private static String counterparty(Account debitAccount, Line debit, Account creditAccount, Line credit) {
        if (debitAccount.requiresCounterparty() && debit.counterparty() != null) {
            return debit.counterparty();
        }
        return creditAccount.requiresCounterparty() ? credit.counterparty() : null;
    }

    // --- The forms of one row --------------------------------------------------------------------------------------

    /**
     * The forms a record's row may take, which the ledger can't tell apart (D-245), first the one written when no
     * stored hash decides: the sides in the order given, or the other way round with the amounts' signs turned; in two
     * currencies, Sum on the credit side with CUR_DEBIT and SUM_DEBIT, as the workbook mostly has it, or on the debit
     * side, or both sides named; the Family flag; Counteragent and Comment; LOAN_CA.
     */
    private static final class Shape {

        private final LocalDate date;
        private final List<Item> items;
        private final Side debit;
        private final BigDecimal debitAmount;
        private final String debitCurrency;
        private final Side credit;
        private final BigDecimal creditAmount;
        private final String creditCurrency;
        private final boolean mayTurn;
        private List<Boolean> families = List.of(false);
        private List<String> counteragents = List.of("");
        /** The comments after each counteragent; null for the counteragent itself. */
        private List<String> comments = List.of("");
        private List<String> loans = List.of("");

        Shape(LocalDate date, List<Item> items, Side debit, BigDecimal debitAmount, String debitCurrency, Side credit,
                BigDecimal creditAmount, String creditCurrency, boolean mayTurn) {
            this.date = date;
            this.items = items;
            this.debit = debit;
            this.debitAmount = debitAmount;
            this.debitCurrency = debitCurrency;
            this.credit = credit;
            this.creditAmount = creditAmount;
            this.creditCurrency = creditCurrency;
            this.mayTurn = mayTurn;
        }

        /**
         * Counteragent and Comment from the payee and the note. The import keeps the comment as the note where it
         * isn't the counteragent (EntryMapper), so without a note the comment was the counteragent, or empty. Where the
         * ledger keeps neither ({@code lost}: a settlement's, D-73's), empty, or the workbook's "-".
         */
        void counteragent(String payee, String memo, boolean lost) {
            if (lost) {
                counteragents = List.of("", EntryMapper.NO_CATEGORY);
                comments = Arrays.asList("", EntryMapper.NO_CATEGORY, null);
                return;
            }
            counteragents = List.of(payee == null ? "" : payee);
            comments = memo != null ? List.of(memo) : Arrays.asList(null, "");
        }

        /** LOAN_CA: the counterparty, where the import used "Unassigned" for an empty LOAN_CA that too. */
        void loan(String counterparty) {
            loans = counterparty == null ? List.of("")
                    : counterparty.equals(EntryMapper.UNASSIGNED) ? List.of(counterparty, "") : List.of(counterparty);
        }

        /** The first form that {@code matches}; the first of all if none does or there's nothing to check. */
        ExcelRow choose(String id, Predicate<ExcelRow> matches) {
            ExcelRow first = null;
            for (boolean turned : mayTurn ? List.of(false, true) : List.of(false)) {
                for (int pattern = 0; pattern < (sameCurrency() ? 1 : 4); pattern++) {
                    for (Item item : items) {
                        for (boolean family : families) {
                            for (String counteragent : counteragents) {
                                for (String comment : comments) {
                                    for (String loan : loans) {
                                        ExcelRow row = row(id, turned, pattern, item, family, counteragent,
                                                comment == null ? counteragent : comment, loan);
                                        if (matches == null) {
                                            return row;
                                        }
                                        if (matches.test(row)) {
                                            return row;
                                        }
                                        first = first == null ? row : first;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return first;
        }

        private boolean sameCurrency() {
            return debitCurrency.equals(creditCurrency);
        }

        /**
         * One form. Turned, the credit side is the debit side and the amounts change sign. In two currencies:
         * pattern 0 Sum on the credit side with CUR_DEBIT and SUM_DEBIT, 1 on the debit side with CUR_CREDIT and
         * SUM_CREDIT, 2 and 3 as 0 and 1 with both sides named.
         */
        private ExcelRow row(String id, boolean turned, int pattern, Item item, boolean family, String counteragent,
                String comment, String loan) {
            Side d = turned ? credit : debit;
            Side c = turned ? debit : credit;
            BigDecimal dAmount = turned ? creditAmount.negate() : debitAmount;
            BigDecimal cAmount = turned ? debitAmount.negate() : creditAmount;
            String dCurrency = turned ? creditCurrency : debitCurrency;
            String cCurrency = turned ? debitCurrency : creditCurrency;
            if (sameCurrency()) {
                return new ExcelRow(id, date, item, dAmount, dCurrency, counteragent, comment, d, c, family, null,
                        null, null, null, loan);
            }
            boolean onCredit = pattern % 2 == 0;
            boolean both = pattern >= 2;
            return new ExcelRow(id, date, item, onCredit ? cAmount : dAmount, onCredit ? cCurrency : dCurrency,
                    counteragent, comment, d, c, family, onCredit || both ? dCurrency : null,
                    onCredit || both ? dAmount : null, !onCredit || both ? cCurrency : null,
                    !onCredit || both ? cAmount : null, loan);
        }
    }
}
