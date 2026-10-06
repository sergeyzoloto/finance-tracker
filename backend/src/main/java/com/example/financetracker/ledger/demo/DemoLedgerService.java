package com.example.financetracker.ledger.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.StarterLedger;
import com.example.financetracker.ledger.UserSettings;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.demo.DemoLedger.DemoAccount;
import com.example.financetracker.ledger.demo.DemoLedger.DemoCategory;
import com.example.financetracker.ledger.demo.DemoLedger.DemoCounterparty;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.family.FamilyLedgerService;
import com.example.financetracker.ledger.family.FamilyLedgerView;
import com.example.financetracker.ledger.family.FamilyRecordService;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.family.NewFamilyRecord;
import com.example.financetracker.ledger.family.NewSettlement;
import com.example.financetracker.ledger.family.RecordSplit;
import com.example.financetracker.ledger.family.SplitRule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills a user's empty personal ledger with the {@link DemoLedger} and, while the family budget is switched on (D-25),
 * creates the {@link DemoFamily} (H1). Callers pass the {@link LedgerScope} that LedgerAccess resolved (rule 11).
 * Everything happens in one transaction, so a user gets the whole demo or nothing of it.
 */
@Service
public class DemoLedgerService {

    /** The partner's part of a shared expense (rule 7). */
    private static final BigDecimal SHARE_RATIO = new BigDecimal("0.50");

    private final JdbcClient jdbc;
    private final StarterLedger starterLedger;
    private final EntryService entries;
    private final UserSettingsRepository settings;
    private final FamilySwitch familySwitch;
    private final LedgerAccess access;
    private final FamilyLedgerService families;
    private final FamilyRecordService familyRecords;

    DemoLedgerService(JdbcClient jdbc, StarterLedger starterLedger, EntryService entries,
            UserSettingsRepository settings, FamilySwitch familySwitch, LedgerAccess access,
            FamilyLedgerService families, FamilyRecordService familyRecords) {
        this.jdbc = jdbc;
        this.starterLedger = starterLedger;
        this.entries = entries;
        this.settings = settings;
        this.familySwitch = familySwitch;
        this.access = access;
        this.families = families;
        this.familyRecords = familyRecords;
    }

    /**
     * Loads the demo ledger, whose last entries are dated {@code today}. The starter accounts and categories are set
     * back to the starter ledger's, the demo's own are added, and the settings get base currency EUR, the account
     * FAMILY_DEBT for shared expenses and a share of 0.50. Every entry goes through {@link EntryService}, so the
     * ledger's rules hold for it as for any other.
     * <p>
     * For a member of family budgets (F4a) the demo touches none of their family data: their family budgets' entries
     * in the ledger stay. A starter category that became a family category when a family budget was created (D-11's
     * merge) is created again in the personal ledger, beside the family one with the same code. While the family
     * budget is switched off, it stays so, and no demo entry uses a family category.
     * <p>
     * While it is switched on, the demo then creates its family budget (H1), through the family budget's own services:
     * {@link DemoFamily#NAME} in euros, from the demo's first day, with the user as its owner and the invented partner
     * {@link DemoFamily#PARTNER} without an account. It brings the demo's categories of {@link DemoFamily#CATEGORIES}
     * and every personal category with the code and type of a family category of the user's other family budgets, and
     * D-11's merge moves their postings to the family's categories and deletes the personal ones, so that no personal
     * twin of a family category remains. Its records and settlements are posted as for any family budget.
     *
     * @param personalLedger the user's personal ledger, whose member the settings belong to
     * @param displayName the user's name in the demo family budget; null or blank for {@link DemoFamily#YOU}
     * @throws ConflictException if the ledger has any entries or counterparties, or accounts or categories other than
     *         the starter ledger's
     * @throws NotFoundException if the ledger was deleted meanwhile, with all the user's data
     */
    @Transactional
    public DemoLedgerView load(LedgerScope personalLedger, LocalDate today, String displayName) {
        // The settings row serializes this with a concurrent load into the same ledger, and with the deletion of all
        // the user's data, which deletes that row first. After such a deletion the ledger is gone.
        jdbc.sql("SELECT user_id FROM user_settings WHERE user_id = :userId FOR UPDATE")
                .param("userId", personalLedger.userId()).query(String.class).optional();
        if (!jdbc.sql("SELECT EXISTS (SELECT FROM ledger WHERE id = :ledgerId)")
                .param("ledgerId", personalLedger.ledgerId()).query(Boolean.class).single()) {
            throw new NotFoundException("Ledger " + personalLedger.ledgerId() + " not found");
        }
        if (hasLedgerOfOwn(personalLedger)) {
            throw new ConflictException("The demo data can only go into an empty ledger, and yours has entries, "
                    + "counterparties, or accounts or categories of your own. Delete all your data in Settings "
                    + "first to load it");
        }

        starterLedger.restore(personalLedger);
        for (DemoAccount account : DemoLedger.ACCOUNTS) {
            jdbc.sql("""
                    INSERT INTO account (user_id, ledger_id, code, name, type, default_currency)
                    VALUES (:userId, :ledgerId, :code, :name, :type, :defaultCurrency)""")
                    .param("userId", personalLedger.userId())
                    .param("ledgerId", personalLedger.ledgerId())
                    .param("code", account.code())
                    .param("name", account.name())
                    .param("type", account.type().name())
                    .param("defaultCurrency", account.defaultCurrency())
                    .update();
        }
        for (DemoCategory category : DemoLedger.CATEGORIES) {
            jdbc.sql("""
                    INSERT INTO category (user_id, ledger_id, code, name, type)
                    VALUES (:userId, :ledgerId, :code, :name, :type)""")
                    .param("userId", personalLedger.userId())
                    .param("ledgerId", personalLedger.ledgerId())
                    .param("code", category.code())
                    .param("name", category.name())
                    .param("type", category.type().name())
                    .update();
        }
        for (DemoCounterparty counterparty : DemoLedger.COUNTERPARTIES) {
            jdbc.sql("""
                    INSERT INTO counterparty (user_id, ledger_id, name, kind)
                    VALUES (:userId, :ledgerId, :name, :kind)""")
                    .param("userId", personalLedger.userId())
                    .param("ledgerId", personalLedger.ledgerId())
                    .param("name", counterparty.name())
                    .param("kind", counterparty.kind().name())
                    .update();
        }
        settings.save(new UserSettings(personalLedger.userId(), DemoLedger.BASE_CURRENCY, null, SHARE_RATIO));

        List<EntryCommand> commands = DemoLedger.entries(today, ids(personalLedger));
        Map<EntryKind, Integer> byKind = new EnumMap<>(EntryKind.class);
        for (EntryCommand command : commands) {
            entries.create(personalLedger, command);
            byKind.merge(command.kind(), 1, Integer::sum);
        }
        LocalDate from = commands.getFirst().entryDate();
        Long familyLedgerId = familySwitch.enabled() ? family(personalLedger, from, today, displayName) : null;
        return new DemoLedgerView(byKind, DemoLedger.ACCOUNTS.size(), DemoLedger.CATEGORIES.size(),
                DemoLedger.COUNTERPARTIES.size(), from, commands.getLast().entryDate(), familyLedgerId);
    }

    /** Creates the demo's family budget (H1) and records {@link DemoFamily#plan}; returns its id. */
    private long family(LedgerScope personalLedger, LocalDate start, LocalDate today, String displayName) {
        Map<String, Long> personalCategories = idsBy(personalLedger,
                "SELECT code AS key, id FROM category WHERE ledger_id = :ledgerId");
        LinkedHashSet<Long> brought = new LinkedHashSet<>();
        DemoFamily.CATEGORIES.forEach(code -> brought.add(Objects.requireNonNull(personalCategories.get(code),
                () -> "No category " + code)));
        brought.addAll(twins(personalLedger));
        String you = displayName == null || displayName.isBlank() ? DemoFamily.YOU : displayName.strip();
        FamilyLedgerView created = families.create(personalLedger, DemoFamily.NAME, DemoLedger.BASE_CURRENCY, start,
                you, SplitRule.EQUAL, new ArrayList<>(brought));
        LedgerScope owner = access.owner(personalLedger.userId(), created.id());
        long partner = families.addMember(owner, you.equalsIgnoreCase(DemoFamily.PARTNER)
                ? DemoFamily.PARTNER + " (partner)" : DemoFamily.PARTNER).id();
        long me = created.memberId();
        Map<String, Long> familyCategories = idsBy(owner,
                "SELECT code AS key, id FROM category WHERE ledger_id = :ledgerId");
        Map<String, Long> accounts = idsBy(personalLedger,
                "SELECT code AS key, id FROM account WHERE ledger_id = :ledgerId");

        DemoFamily.Plan plan = DemoFamily.plan(start, today);
        for (DemoFamily.Record record : plan.records()) {
            boolean mine = record.payer() == DemoFamily.Who.YOU;
            RecordSplit split = record.yourBasisPoints() == null ? RecordSplit.rule()
                    : new RecordSplit(RecordSplit.Method.PERCENT, List.of(
                            new RecordSplit.ShareInput(me, record.yourBasisPoints(), null),
                            new RecordSplit.ShareInput(partner, 10_000 - record.yourBasisPoints(), null)), null);
            // In its own currency (D-45), paid from an account in it: no amount of the account's to name (D-87).
            familyRecords.create(owner, personalLedger, new NewFamilyRecord(record.type(), record.date(),
                    familyCategories.get(record.category()), record.amountValue(), record.comment(),
                    mine ? me : partner, mine ? accounts.get(record.account()) : null, false, split, null,
                    record.currency(), null, null));
        }
        for (DemoFamily.Settlement settlement : plan.settlements()) {
            familyRecords.settle(owner, personalLedger, new NewSettlement(settlement.date(), settlement.amountValue(),
                    partner, me, settlement.comment(), accounts.get(settlement.account()), false, null, null, null));
        }
        return created.id();
    }

    /**
     * The user's personal categories with the code and type of a family category of a family budget they are an
     * ACTIVE member of: the starter categories that {@link StarterLedger#restore} brought back beside a merged one.
     */
    private List<Long> twins(LedgerScope personalLedger) {
        List<Long> families = access.families(personalLedger.userId()).stream().map(LedgerScope::ledgerId).toList();
        if (families.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT p.id FROM category p
                WHERE p.ledger_id = :ledgerId
                  AND EXISTS (SELECT FROM category f WHERE f.ledger_id IN (:families) AND f.code = p.code
                              AND f.type = p.type)
                ORDER BY p.code""")
                .param("ledgerId", personalLedger.ledgerId())
                .param("families", families)
                .query(Long.class)
                .list();
    }

    /**
     * Whether the ledger has anything of the user's own besides the starter accounts and categories. What a family
     * budget posted there doesn't count (F4a): its entries, the debt accounts and "Payments without a specified
     * account", which the demo leaves as they are.
     */
    private boolean hasLedgerOfOwn(LedgerScope ledger) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT FROM journal_entry e WHERE e.ledger_id = :ledgerId
                               AND NOT EXISTS (SELECT FROM family_entry_link l
                                               WHERE l.entry_id = e.id AND l.detached_at IS NULL))
                    OR EXISTS (SELECT FROM counterparty WHERE ledger_id = :ledgerId)
                    OR EXISTS (SELECT FROM account WHERE ledger_id = :ledgerId AND code NOT IN (:accountCodes)
                               AND family_ledger_id IS NULL AND NOT (is_system AND code = 'UNSPECIFIED_PAYMENTS'))
                    OR EXISTS (SELECT FROM category WHERE ledger_id = :ledgerId AND code NOT IN (:categoryCodes))""")
                .param("ledgerId", ledger.ledgerId())
                .param("accountCodes", starterLedger.accountCodes())
                .param("categoryCodes", starterLedger.categoryCodes())
                .query(Boolean.class)
                .single();
    }

    /** The ids of the ledger's accounts and categories by code, and of its counterparties by name. */
    private DemoLedger.Ids ids(LedgerScope ledger) {
        Map<String, Long> accounts = idsBy(ledger, "SELECT code AS key, id FROM account WHERE ledger_id = :ledgerId");
        Map<String, Long> categories = idsBy(ledger,
                "SELECT code AS key, id FROM category WHERE ledger_id = :ledgerId");
        Map<String, Long> counterparties = idsBy(ledger,
                "SELECT name AS key, id FROM counterparty WHERE ledger_id = :ledgerId");
        return new DemoLedger.Ids() {
            @Override
            public long account(String code) {
                return Objects.requireNonNull(accounts.get(code), () -> "No account " + code);
            }

            @Override
            public long category(String code) {
                return Objects.requireNonNull(categories.get(code), () -> "No category " + code);
            }

            @Override
            public long counterparty(String name) {
                return Objects.requireNonNull(counterparties.get(name), () -> "No counterparty " + name);
            }
        };
    }

    private Map<String, Long> idsBy(LedgerScope ledger, String sql) {
        Map<String, Long> ids = new HashMap<>();
        jdbc.sql(sql).param("ledgerId", ledger.ledgerId()).query(row -> {
            ids.put(row.getString("key"), row.getLong("id"));
        });
        return ids;
    }
}
