package com.example.financetracker.ledger.demo;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.EntryService;
import com.example.financetracker.ledger.StarterLedger;
import com.example.financetracker.ledger.demo.DemoLedger.DemoAccount;
import com.example.financetracker.ledger.demo.DemoLedger.DemoCategory;
import com.example.financetracker.ledger.demo.DemoLedger.DemoCounterparty;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.EntryKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills a user's empty ledger with the {@link DemoLedger}. Callers pass the user id, the Keycloak "sub" claim (rule
 * 11). Everything happens in one transaction, so a user gets the whole demo or nothing of it.
 */
@Service
public class DemoLedgerService {

    private final JdbcClient jdbc;
    private final StarterLedger starterLedger;
    private final EntryService entries;

    DemoLedgerService(JdbcClient jdbc, StarterLedger starterLedger, EntryService entries) {
        this.jdbc = jdbc;
        this.starterLedger = starterLedger;
        this.entries = entries;
    }

    /**
     * Loads the demo ledger, whose last entries are dated {@code today}. The starter accounts and categories are set
     * back to the starter ledger's, the demo's own are added, and the settings get base currency EUR, the account
     * FAMILY_DEBT for shared expenses and a share of 0.50. Every entry goes through {@link EntryService}, so the
     * ledger's rules hold for it as for any other.
     *
     * @throws ConflictException if the user has any entries or counterparties, or accounts or categories other than
     *         the starter ledger's
     */
    @Transactional
    public DemoLedgerView load(String userId, LocalDate today) {
        // A user whose data was just deleted may have no settings yet. The settings row then serializes this with a
        // concurrent load or deletion of the same user's data.
        starterLedger.seedIfNew(userId);
        jdbc.sql("SELECT user_id FROM user_settings WHERE user_id = :userId FOR UPDATE").param("userId", userId)
                .query(String.class).optional();
        if (hasLedgerOfOwn(userId)) {
            throw new ConflictException("The demo data can only go into an empty ledger, and yours has entries, "
                    + "counterparties, or accounts or categories of your own. Delete all your data in Settings "
                    + "first to load it");
        }

        starterLedger.restore(userId);
        for (DemoAccount account : DemoLedger.ACCOUNTS) {
            jdbc.sql("""
                    INSERT INTO account (user_id, code, name, type, default_currency)
                    VALUES (:userId, :code, :name, :type, :defaultCurrency)""")
                    .param("userId", userId)
                    .param("code", account.code())
                    .param("name", account.name())
                    .param("type", account.type().name())
                    .param("defaultCurrency", account.defaultCurrency())
                    .update();
        }
        for (DemoCategory category : DemoLedger.CATEGORIES) {
            jdbc.sql("INSERT INTO category (user_id, code, name, type) VALUES (:userId, :code, :name, :type)")
                    .param("userId", userId)
                    .param("code", category.code())
                    .param("name", category.name())
                    .param("type", category.type().name())
                    .update();
        }
        for (DemoCounterparty counterparty : DemoLedger.COUNTERPARTIES) {
            jdbc.sql("INSERT INTO counterparty (user_id, name, kind) VALUES (:userId, :name, :kind)")
                    .param("userId", userId)
                    .param("name", counterparty.name())
                    .param("kind", counterparty.kind().name())
                    .update();
        }
        jdbc.sql("""
                UPDATE user_settings SET base_currency = :baseCurrency, shared_account_id = NULL,
                    default_share_ratio = 0.50
                WHERE user_id = :userId""")
                .param("userId", userId)
                .param("baseCurrency", DemoLedger.BASE_CURRENCY)
                .update();

        List<EntryCommand> commands = DemoLedger.entries(today, ids(userId));
        Map<EntryKind, Integer> byKind = new EnumMap<>(EntryKind.class);
        for (EntryCommand command : commands) {
            entries.create(userId, command);
            byKind.merge(command.kind(), 1, Integer::sum);
        }
        return new DemoLedgerView(byKind, DemoLedger.ACCOUNTS.size(), DemoLedger.CATEGORIES.size(),
                DemoLedger.COUNTERPARTIES.size(), commands.getFirst().entryDate(), commands.getLast().entryDate());
    }

    /** Whether the user has anything in the ledger besides the starter accounts and categories. */
    private boolean hasLedgerOfOwn(String userId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT FROM journal_entry WHERE user_id = :userId)
                    OR EXISTS (SELECT FROM counterparty WHERE user_id = :userId)
                    OR EXISTS (SELECT FROM account WHERE user_id = :userId AND code NOT IN (:accountCodes))
                    OR EXISTS (SELECT FROM category WHERE user_id = :userId AND code NOT IN (:categoryCodes))""")
                .param("userId", userId)
                .param("accountCodes", starterLedger.accountCodes())
                .param("categoryCodes", starterLedger.categoryCodes())
                .query(Boolean.class)
                .single();
    }

    /** The ids of the user's accounts and categories by code, and of their counterparties by name. */
    private DemoLedger.Ids ids(String userId) {
        Map<String, Long> accounts = idsBy("SELECT code AS key, id FROM account WHERE user_id = :userId", userId);
        Map<String, Long> categories = idsBy("SELECT code AS key, id FROM category WHERE user_id = :userId", userId);
        Map<String, Long> counterparties = idsBy("SELECT name AS key, id FROM counterparty WHERE user_id = :userId",
                userId);
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

    private Map<String, Long> idsBy(String sql, String userId) {
        Map<String, Long> ids = new HashMap<>();
        jdbc.sql(sql).param("userId", userId).query(row -> {
            ids.put(row.getString("key"), row.getLong("id"));
        });
        return ids;
    }
}
