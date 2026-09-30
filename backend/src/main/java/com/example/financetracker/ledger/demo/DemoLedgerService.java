package com.example.financetracker.ledger.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.StarterLedger;
import com.example.financetracker.ledger.UserSettings;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.demo.DemoLedger.DemoAccount;
import com.example.financetracker.ledger.demo.DemoLedger.DemoCategory;
import com.example.financetracker.ledger.demo.DemoLedger.DemoCounterparty;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.EntryKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills a user's empty personal ledger with the {@link DemoLedger}. Callers pass the {@link LedgerScope} that
 * LedgerAccess resolved (rule 11). Everything happens in one transaction, so a user gets the whole demo or nothing of
 * it.
 */
@Service
public class DemoLedgerService {

    /** The partner's part of a shared expense (rule 7). */
    private static final BigDecimal SHARE_RATIO = new BigDecimal("0.50");

    private final JdbcClient jdbc;
    private final StarterLedger starterLedger;
    private final EntryService entries;
    private final UserSettingsRepository settings;

    DemoLedgerService(JdbcClient jdbc, StarterLedger starterLedger, EntryService entries,
            UserSettingsRepository settings) {
        this.jdbc = jdbc;
        this.starterLedger = starterLedger;
        this.entries = entries;
        this.settings = settings;
    }

    /**
     * Loads the demo ledger, whose last entries are dated {@code today}. The starter accounts and categories are set
     * back to the starter ledger's, the demo's own are added, and the settings get base currency EUR, the account
     * FAMILY_DEBT for shared expenses and a share of 0.50. Every entry goes through {@link EntryService}, so the
     * ledger's rules hold for it as for any other.
     * <p>
     * For a member of family budgets (F4a) the demo touches no family data: the family budget's entries in the ledger
     * stay, and the demo's categories are always personal ones. A starter category that became a family category
     * when a family budget was created (D-11's merge) is created again in the personal ledger, beside the family one
     * with the same code, which the category list marks with its family budget; no demo entry uses a family category.
     *
     * @param personalLedger the user's personal ledger, whose member the settings belong to
     * @throws ConflictException if the ledger has any entries or counterparties, or accounts or categories other than
     *         the starter ledger's
     * @throws NotFoundException if the ledger was deleted meanwhile, with all the user's data
     */
    @Transactional
    public DemoLedgerView load(LedgerScope personalLedger, LocalDate today) {
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
        return new DemoLedgerView(byKind, DemoLedger.ACCOUNTS.size(), DemoLedger.CATEGORIES.size(),
                DemoLedger.COUNTERPARTIES.size(), commands.getFirst().entryDate(), commands.getLast().entryDate());
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
