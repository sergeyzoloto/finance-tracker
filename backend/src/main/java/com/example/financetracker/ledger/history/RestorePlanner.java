package com.example.financetracker.ledger.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.InvalidRequestException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.EntryDraft;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.LedgerReferences;
import com.example.financetracker.ledger.domain.LedgerReferences.AccountInfo;
import com.example.financetracker.ledger.domain.LedgerReferences.CategoryInfo;
import com.example.financetracker.ledger.domain.LedgerValidator;
import com.example.financetracker.ledger.domain.PostingLine;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only plan of a restore (ADR 0006, 6.3, HIST-4 part 2a, D-1220 to D-1225): what a restore of a snapshot, or of
 * one record's earlier version, would do, and what would stop it. Nothing is written; the plan is computed in one
 * REPEATABLE READ transaction, with a fixed number of statements (the live state is read set-based, and the
 * references are counted in memory).
 * <p>
 * For every record with a version after the target revision N (or the one record of a version restore), the target is
 * its latest version at or before N. The outcomes are RESTORED, RETURNED, DELETED, ARCHIVED, UNCHANGED, and KEPT for a
 * removal that a posting still needs (rule 12, D-1221).
 */
@Service
public class RestorePlanner {

    /** Items listed per outcome; the counts are always complete. */
    public static final int ITEM_LIMIT = 200;

    public enum Outcome { RESTORED, RETURNED, DELETED, ARCHIVED, UNCHANGED, KEPT }

    public static final String INVALID_TARGET = "INVALID_TARGET";
    public static final String REFERENCE_MISSING = "REFERENCE_MISSING";
    public static final String KEY_COLLISION = "KEY_COLLISION";
    public static final String DERIVED_RECORD = "DERIVED_RECORD";

    private static final LedgerValidator VALIDATOR = new LedgerValidator();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One record in a preview; {@code referenceCount} is set for KEPT only: the entries that still use it. */
    public record Item(String recordType, long recordId, @JsonInclude(JsonInclude.Include.NON_NULL) Integer referenceCount) {
    }

    /** A reason the plan is refused. The record is null for a blocker about the whole ledger. */
    public record Blocker(String recordType, Long recordId, String reason, String message) {
    }

    /** The JSON answer of a preview. */
    public record Preview(String kind, long atRevision, long targetRevision, boolean restorable,
            Map<String, Integer> counts, Map<String, List<Item>> items, List<Blocker> blockers) {
    }

    /**
     * The plan with what the tests need beyond the answer: the live state after the plan, per type and id, as canonical
     * text, and the KEPT records (whose live state stays and differs from the state at N, by design).
     */
    public record Plan(Preview preview, Map<RecordType, Map<Long, String>> afterState,
            Map<RecordType, Set<Long>> kept) {
    }

    private record Key(RecordType type, long id) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byType = type.compareTo(other.type);
            return byType != 0 ? byType : Long.compare(id, other.id);
        }
    }

    /** A version: its operation, its content (null for a removal) and the stored hash of its canonical content. */
    private record Target(String op, Map<String, Object> content, byte[] hash) {
    }

    private final JdbcClient jdbc;
    private final LedgerState state;
    private final HistoryJobs jobs;
    private final RecordContents contents;
    private final LedgerAccess access;

    RestorePlanner(JdbcClient jdbc, LedgerState state, HistoryJobs jobs, RecordContents contents,
            LedgerAccess access) {
        this.jdbc = jdbc;
        this.state = state;
        this.jobs = jobs;
        this.contents = contents;
        this.access = access;
    }

    /** The plan of restoring a snapshot of the personal ledger (target revision: the snapshot's). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Plan ofSnapshot(LedgerScope scope, long snapshotId) {
        personalOnly(scope);
        long target = jdbc.sql("SELECT revision FROM snapshot WHERE id = :id AND ledger_id = :l")
                .param("id", snapshotId).param("l", scope.ledgerId()).query(Long.class).optional()
                .orElseThrow(() -> new NotFoundException("Snapshot " + snapshotId + " not found"));
        Map<Key, Target> latest = latestAt(scope, target);
        Map<Key, Target> targets = new TreeMap<>();
        for (Key key : changedSince(scope, target)) {
            targets.put(key, latest.get(key));
        }
        return compute(scope, "SNAPSHOT", target, targets, false);
    }

    /** The plan of restoring one record to its version {@code seq} (target revision: that version's). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Plan ofVersion(LedgerScope scope, RecordType type, long recordId, int seq) {
        personalOnly(scope);
        if (!LedgerState.PERSONAL.contains(type)) {
            throw new InvalidRequestException("Only entries, accounts, categories and counterparties can be restored "
                    + "from here", "RECORD_TYPE_UNSUPPORTED");
        }
        var row = jdbc.sql("""
                SELECT revision, op, content::text AS content, content_hash FROM record_version
                WHERE ledger_id = :l AND record_type = :t AND record_id = :id AND seq = :seq""")
                .param("l", scope.ledgerId()).param("t", type.name()).param("id", recordId).param("seq", seq)
                .query((r, n) -> new Object[] {r.getLong("revision"), r.getString("op"), r.getString("content"),
                        r.getBytes("content_hash")})
                .optional().orElseThrow(() -> new NotFoundException("Version " + seq + " of " + type + " " + recordId
                        + " not found"));
        long revision = (long) row[0];
        Target target = target((String) row[1], (String) row[2], (byte[]) row[3]);
        Map<Key, Target> targets = new TreeMap<>();
        targets.put(new Key(type, recordId), target);
        return compute(scope, "VERSION", revision, targets, true);
    }

    /** Whether a version's operation removes its record (DELETE or ARCHIVE). */
    private static boolean removal(String op) {
        return "DELETE".equals(op) || "ARCHIVE".equals(op);
    }

    private Target target(String op, String content, byte[] hash) {
        return new Target(op, removal(op) ? null : read(content), hash);
    }

    private static Map<String, Object> read(String json) {
        try {
            return JSON.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored version isn't JSON", e);
        }
    }

    private Plan compute(LedgerScope scope, String kind, long target, Map<Key, Target> targets, boolean single) {
        long ledger = scope.ledgerId();
        List<Blocker> blockers = new ArrayList<>();
        long atRevision = jdbc.sql("SELECT revision FROM ledger WHERE id = :l").param("l", ledger)
                .query(Long.class).single();

        // The state the BEFORE_RESTORE snapshot would hold must be consistent, and there must be room for it (D-1222).
        Map<RecordType, Long> counts = state.counts(scope, atRevision);
        for (RecordType type : LedgerState.PERSONAL) {
            if (jobs.liveIds(type, ledger).size() != counts.get(type)) {
                blockers.add(new Blocker(null, null, SnapshotService.HISTORY_INCONSISTENT,
                        "The history of your data doesn't match it yet; try again later"));
                break;
            }
        }
        long snapshots = jdbc.sql("SELECT count(*) FROM snapshot WHERE ledger_id = :l").param("l", ledger)
                .query(Long.class).single();
        if (snapshots >= SnapshotService.CAP) {
            blockers.add(new Blocker(null, null, SnapshotService.LIMIT_REACHED, "The ledger has "
                    + SnapshotService.CAP + " snapshots, so there is no room for the snapshot taken before a restore"));
        }

        // The live state, set-based, and the references the derived rows (not records) hold.
        EnumMap<RecordType, Map<Long, Map<String, Object>>> live = new EnumMap<>(RecordType.class);
        for (RecordType type : LedgerState.PERSONAL) {
            live.put(type, contents.loadAll(type, ledger));
        }
        Map<Long, AccountInfo> derivedAccounts = new HashMap<>();
        Map<Long, String> derivedCodes = new HashMap<>();
        jdbc.sql("""
                SELECT id, code, type, requires_counterparty FROM account
                WHERE ledger_id = :l AND (family_ledger_id IS NOT NULL OR code = 'UNSPECIFIED_PAYMENTS')""")
                .param("l", ledger)
                .query((r, n) -> {
                    derivedAccounts.put(r.getLong("id"), new AccountInfo(r.getString("code"),
                            AccountType.valueOf(r.getString("type")), r.getBoolean("requires_counterparty")));
                    derivedCodes.put(r.getLong("id"), r.getString("code"));
                    return null;
                }).list();
        Map<Long, Set<Key>> derivedRefs = derivedRefs(scope);
        Map<Long, Map<String, Object>> categories = new HashMap<>(live.get(RecordType.CATEGORY));
        categories.putAll(externalCategories(scope));

        // The state after the plan: the live records, with the changed ones replaced by their targets.
        EnumMap<RecordType, Map<Long, Map<String, Object>>> after = new EnumMap<>(RecordType.class);
        for (RecordType type : LedgerState.PERSONAL) {
            after.put(type, new LinkedHashMap<>(live.get(type)));
        }
        Map<Key, Outcome> outcomes = new TreeMap<>();
        Map<Key, Integer> referenceCounts = new HashMap<>();
        for (Map.Entry<Key, Target> change : targets.entrySet()) {
            Key key = change.getKey();
            Target version = change.getValue();
            Map<String, Object> now = live.get(key.type()).get(key.id());
            if (key.type() == RecordType.ACCOUNT && derivedAccounts.containsKey(key.id())) {
                blockers.add(new Blocker(key.type().name(), key.id(), DERIVED_RECORD,
                        "The account " + key.id() + " belongs to a family budget and can't be restored"));
                continue;
            }
            if (version != null && !removal(version.op())) {
                if (now == null) {
                    after.get(key.type()).put(key.id(), version.content());
                    outcomes.put(key, Outcome.RETURNED);
                } else if (Arrays.equals(version.hash(), Canonical.hash(now))) {
                    outcomes.put(key, Outcome.UNCHANGED);
                } else {
                    after.get(key.type()).put(key.id(), version.content());
                    outcomes.put(key, Outcome.RESTORED);
                }
            } else if (now == null) {
                outcomes.put(key, Outcome.UNCHANGED);
            } else {
                after.get(key.type()).remove(key.id());
                outcomes.put(key, version == null ? Outcome.ARCHIVED : Outcome.DELETED);
            }
        }

        // A removed account, category or counterparty stays (KEPT, rule 12, D-1221) while a posting after the plan uses it.
        Map<Key, Integer> uses = referencedBy(after.get(RecordType.ENTRY), derivedRefs);
        for (Map.Entry<Key, Outcome> change : outcomes.entrySet()) {
            Key key = change.getKey();
            boolean removed = change.getValue() == Outcome.DELETED || change.getValue() == Outcome.ARCHIVED;
            int users = uses.getOrDefault(key, 0);
            if (removed && key.type() != RecordType.ENTRY && users > 0) {
                change.setValue(Outcome.KEPT);
                referenceCounts.put(key, users);
                after.get(key.type()).put(key.id(), live.get(key.type()).get(key.id()));
            }
        }

        // The restored entries must pass the rules a save passes, against the state after the plan (D-1225 aside).
        for (Map.Entry<Key, Outcome> change : outcomes.entrySet()) {
            Key key = change.getKey();
            if (key.type() != RecordType.ENTRY || (change.getValue() != Outcome.RESTORED
                    && change.getValue() != Outcome.RETURNED)) {
                continue;
            }
            Map<String, Object> content = after.get(RecordType.ENTRY).get(key.id());
            EntryDraft draft = draft(content);
            LedgerReferences refs = references(after, categories, derivedAccounts);
            List<Blocker> missing = new ArrayList<>();
            // A single record's restore doesn't bring back a reference it needs (D-1223): a missing or archived one
            // is a blocker. Derived accounts and family categories are no records of this ledger, so only their
            // existence counts.
            for (long id : draft.accountIds()) {
                if (!refs.accounts().containsKey(id)
                        || single && !derivedAccounts.containsKey(id) && archived(after.get(RecordType.ACCOUNT).get(id))) {
                    missing.add(reference(key, RecordType.ACCOUNT, id));
                }
            }
            for (long id : draft.categoryIds()) {
                if (!categories.containsKey(id) || single && archived(categories.get(id))) {
                    missing.add(reference(key, RecordType.CATEGORY, id));
                }
            }
            for (long id : draft.counterpartyIds()) {
                if (!refs.counterparties().contains(id)
                        || single && archived(after.get(RecordType.COUNTERPARTY).get(id))) {
                    missing.add(reference(key, RecordType.COUNTERPARTY, id));
                }
            }
            if (!missing.isEmpty()) {
                blockers.addAll(missing);
                continue;
            }
            for (String violation : VALIDATOR.violations(draft, refs)) {
                blockers.add(new Blocker(RecordType.ENTRY.name(), key.id(), INVALID_TARGET, violation));
            }
        }

        // Unique keys (D-1225): two rows of the state after the plan with one key are refused, whatever the order.
        Map<String, List<Key>> byKey = new TreeMap<>();
        after.get(RecordType.ACCOUNT).forEach((id, c) -> byKey.computeIfAbsent("account code "
                + c.get("code"), k -> new ArrayList<>()).add(new Key(RecordType.ACCOUNT, id)));
        derivedCodes.forEach((id, code) -> byKey.computeIfAbsent("account code " + code, k -> new ArrayList<>())
                .add(new Key(RecordType.ACCOUNT, id)));
        after.get(RecordType.CATEGORY).forEach((id, c) -> byKey.computeIfAbsent("category code " + c.get("code"),
                k -> new ArrayList<>()).add(new Key(RecordType.CATEGORY, id)));
        after.get(RecordType.COUNTERPARTY).forEach((id, c) -> byKey.computeIfAbsent("counterparty name "
                + String.valueOf(c.get("name")).toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(new Key(RecordType.COUNTERPARTY, id)));
        after.get(RecordType.ENTRY).forEach((id, c) -> {
            if (c.get("externalRef") != null) {
                byKey.computeIfAbsent("entry external ref " + c.get("externalRef"), k -> new ArrayList<>())
                        .add(new Key(RecordType.ENTRY, id));
            }
        });
        for (List<Key> same : byKey.values()) {
            if (same.size() < 2) {
                continue;
            }
            for (Key key : same) {
                Outcome outcome = outcomes.get(key);
                if (outcome == Outcome.RESTORED || outcome == Outcome.RETURNED) {
                    Key other = same.stream().filter(k -> !k.equals(key)).findFirst().orElseThrow();
                    blockers.add(new Blocker(key.type().name(), key.id(), KEY_COLLISION, key.type().name().toLowerCase(Locale.ROOT)
                            + " " + key.id() + " has the same unique key as " + other.type().name().toLowerCase(Locale.ROOT)
                            + " " + other.id()));
                }
            }
        }

        return new Plan(preview(kind, atRevision, target, outcomes, referenceCounts, blockers), toCanonical(after),
                kept(outcomes));
    }

    private static Blocker reference(Key entry, RecordType type, long id) {
        return new Blocker(type.name(), id, REFERENCE_MISSING, "entry " + entry.id() + " uses " + type.name()
                .toLowerCase(Locale.ROOT) + " " + id + ", which is missing or archived; restore that record first");
    }

    private static boolean archived(Map<String, Object> row) {
        return row == null || Boolean.TRUE.equals(row.get("archived"));
    }

    private Preview preview(String kind, long atRevision, long target, Map<Key, Outcome> outcomes,
            Map<Key, Integer> referenceCounts, List<Blocker> blockers) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<Item>> items = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome.name(), 0);
            items.put(outcome.name(), new ArrayList<>());
        }
        for (Map.Entry<Key, Outcome> change : outcomes.entrySet()) {
            Key key = change.getKey();
            String name = change.getValue().name();
            counts.merge(name, 1, Integer::sum);
            List<Item> list = items.get(name);
            if (list.size() < ITEM_LIMIT) {
                list.add(new Item(key.type().name(), key.id(), referenceCounts.get(key)));
            }
        }
        return new Preview(kind, atRevision, target, blockers.isEmpty(), counts, items, List.copyOf(blockers));
    }

    private static Map<RecordType, Set<Long>> kept(Map<Key, Outcome> outcomes) {
        Map<RecordType, Set<Long>> kept = new EnumMap<>(RecordType.class);
        LedgerState.PERSONAL.forEach(t -> kept.put(t, new HashSet<>()));
        outcomes.forEach((key, outcome) -> {
            if (outcome == Outcome.KEPT) {
                kept.get(key.type()).add(key.id());
            }
        });
        return kept;
    }

    private static Map<RecordType, Map<Long, String>> toCanonical(EnumMap<RecordType, Map<Long, Map<String, Object>>> after) {
        Map<RecordType, Map<Long, String>> text = new EnumMap<>(RecordType.class);
        after.forEach((type, rows) -> {
            Map<Long, String> canonical = new TreeMap<>();
            rows.forEach((id, content) -> canonical.put(id, Canonical.text(content)));
            text.put(type, canonical);
        });
        return text;
    }

    /** How many entries of the state after the plan (and the derived ones, which stay) use each reference row. */
    private static Map<Key, Integer> referencedBy(Map<Long, Map<String, Object>> entries,
            Map<Long, Set<Key>> derived) {
        Map<Key, Integer> uses = new HashMap<>();
        entries.forEach((id, content) -> refsOf(content).forEach(key -> uses.merge(key, 1, Integer::sum)));
        derived.forEach((id, keys) -> keys.forEach(key -> uses.merge(key, 1, Integer::sum)));
        return uses;
    }

    private static Set<Key> refsOf(Map<String, Object> entry) {
        Set<Key> refs = new HashSet<>();
        Long payee = asLong(entry.get("payee"));
        if (payee != null) {
            refs.add(new Key(RecordType.COUNTERPARTY, payee));
        }
        for (Object posting : postings(entry)) {
            Map<?, ?> p = (Map<?, ?>) posting;
            addKey(refs, RecordType.ACCOUNT, asLong(p.get("account")));
            addKey(refs, RecordType.CATEGORY, asLong(p.get("category")));
            addKey(refs, RecordType.COUNTERPARTY, asLong(p.get("counterparty")));
        }
        return refs;
    }

    private static void addKey(Set<Key> refs, RecordType type, Long id) {
        if (id != null) {
            refs.add(new Key(type, id));
        }
    }

    private Map<Long, Set<Key>> derivedRefs(LedgerScope scope) {
        long ledger = scope.ledgerId();
        Map<Long, Set<Key>> refs = new HashMap<>();
        jdbc.sql("""
                SELECT e.id, e.payee_id, p.account_id, p.category_id, p.counterparty_id
                FROM journal_entry e LEFT JOIN posting p ON p.entry_id = e.id
                WHERE e.ledger_id = :l AND e.kind LIKE 'FAMILY\\_%'""")
                .param("l", ledger)
                .query((r, n) -> {
                    Set<Key> set = refs.computeIfAbsent(r.getLong("id"), k -> new HashSet<>());
                    addKey(set, RecordType.COUNTERPARTY, r.getObject("payee_id", Long.class));
                    addKey(set, RecordType.ACCOUNT, r.getObject("account_id", Long.class));
                    addKey(set, RecordType.CATEGORY, r.getObject("category_id", Long.class));
                    addKey(set, RecordType.COUNTERPARTY, r.getObject("counterparty_id", Long.class));
                    return null;
                }).list();
        return refs;
    }

    /** The categories of the family budgets the user is an ACTIVE member of: a personal entry may use one (D-11). */
    private Map<Long, Map<String, Object>> externalCategories(LedgerScope scope) {
        List<Long> families = access.families(scope.userId()).stream().map(LedgerScope::ledgerId).toList();
        Map<Long, Map<String, Object>> rows = new HashMap<>();
        if (!families.isEmpty()) {
            jdbc.sql("SELECT id, code, name, type, archived_at IS NOT NULL AS archived FROM category "
                            + "WHERE ledger_id IN (:ls)")
                    .param("ls", families)
                    .query((r, n) -> rows.put(r.getLong("id"), RecordContents.categoryContent(r))).list();
        }
        return rows;
    }

    private static LedgerReferences references(EnumMap<RecordType, Map<Long, Map<String, Object>>> after,
            Map<Long, Map<String, Object>> categories, Map<Long, AccountInfo> derivedAccounts) {
        Map<Long, AccountInfo> accounts = new HashMap<>(derivedAccounts);
        after.get(RecordType.ACCOUNT).forEach((id, c) -> accounts.put(id, new AccountInfo((String) c.get("code"),
                AccountType.valueOf((String) c.get("type")), Boolean.TRUE.equals(c.get("requiresCounterparty")))));
        Map<Long, CategoryInfo> infos = categories.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> new CategoryInfo((String) e.getValue().get("code"),
                        CategoryType.valueOf((String) e.getValue().get("type")))));
        return new LedgerReferences(accounts, infos, after.get(RecordType.COUNTERPARTY).keySet());
    }

    /** The entry's content as a draft for {@link LedgerValidator}, from a stored version or a live record. */
    private static EntryDraft draft(Map<String, Object> content) {
        List<PostingLine> lines = new ArrayList<>();
        for (Object posting : postings(content)) {
            Map<?, ?> p = (Map<?, ?>) posting;
            lines.add(new PostingLine(asLong(p.get("account")), (String) p.get("currency"), asDecimal(p.get("amount")),
                    asLong(p.get("category")), asLong(p.get("counterparty"))));
        }
        return new EntryDraft(asDate(content.get("date")), EntryKind.valueOf((String) content.get("kind")),
                asLong(content.get("payee")), (String) content.get("memo"), lines);
    }

    private static List<?> postings(Map<String, Object> entry) {
        return entry.get("postings") instanceof List<?> list ? list : List.of();
    }

    private static Long asLong(Object value) {
        return switch (value) {
            case null -> null;
            case Number n -> n.longValue();
            default -> Long.parseLong(value.toString());
        };
    }

    private static BigDecimal asDecimal(Object value) {
        return value == null ? null : value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    private static LocalDate asDate(Object value) {
        return value == null ? null : value instanceof LocalDate d ? d : LocalDate.parse(value.toString());
    }

    /** The versions of the ledger after N: one (type, id) each. */
    private Set<Key> changedSince(LedgerScope scope, long revision) {
        long ledger = scope.ledgerId();
        Set<Key> keys = new HashSet<>();
        jdbc.sql("""
                SELECT DISTINCT record_type, record_id FROM record_version
                WHERE ledger_id = :l AND revision > :n AND record_type IN (:types)""")
                .param("l", ledger).param("n", revision).param("types", names())
                .query((r, n) -> keys.add(new Key(RecordType.valueOf(r.getString("record_type")), r.getLong("record_id"))))
                .list();
        return keys;
    }

    /** The latest version of every record at or before N, as {@link LedgerState#LATEST} picks it, with its hash. */
    private Map<Key, Target> latestAt(LedgerScope scope, long revision) {
        long ledger = scope.ledgerId();
        Map<Key, Target> latest = new HashMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (record_type, record_id) record_type, record_id, op, content::text AS content,
                       content_hash
                FROM record_version
                WHERE ledger_id = :l AND revision <= :n AND record_type IN (:types)
                ORDER BY record_type, record_id, revision DESC, seq DESC""")
                .param("l", ledger).param("n", revision).param("types", names())
                .query((r, n) -> latest.put(new Key(RecordType.valueOf(r.getString("record_type")), r.getLong("record_id")),
                        target(r.getString("op"), r.getString("content"), r.getBytes("content_hash"))))
                .list();
        return latest;
    }

    private static List<String> names() {
        return LedgerState.PERSONAL.stream().map(Enum::name).toList();
    }

    private static void personalOnly(LedgerScope scope) {
        if (scope.type() != LedgerType.PERSONAL) {
            throw new ConflictException("A family budget has no restores yet", SnapshotService.FAMILY_UNSUPPORTED);
        }
    }
}
