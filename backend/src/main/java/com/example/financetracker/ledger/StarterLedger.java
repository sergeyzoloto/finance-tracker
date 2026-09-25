package com.example.financetracker.ledger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a new user starts with: settings with base currency EUR, and the generic accounts and categories of
 * {@code seed/starter-ledger.json}. The seed is generic on purpose and holds nothing from the owner's own ledger in
 * data/private. It has an account for every {@link com.example.financetracker.ledger.domain.AccountRole}, so every
 * kind of entry works from the start, and OPENING_BALANCE and FX_EXCHANGE are system accounts (rule 4). The Excel
 * importer later creates or updates accounts and categories by code on top of it.
 */
@Service
public class StarterLedger {

    static final String SEED = "seed/starter-ledger.json";
    static final String BASE_CURRENCY = "EUR";

    private final JdbcClient jdbc;
    private final Seed seed;

    StarterLedger(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.seed = read(json);
    }

    /**
     * Gives the user settings and the starter accounts and categories, unless the user has settings already. The
     * settings row decides: of concurrent first requests, one inserts it and seeds, and the others wait on its key
     * until that transaction commits, then find the row and leave. Accounts and categories whose code the user has
     * already, say from an import, are kept as they are.
     *
     * @return whether the user was new and has been seeded
     */
    @Transactional
    public boolean seedIfNew(String userId) {
        int inserted = jdbc.sql("""
                INSERT INTO user_settings (user_id, base_currency) VALUES (:userId, :baseCurrency)
                ON CONFLICT (user_id) DO NOTHING""")
                .param("userId", userId)
                .param("baseCurrency", BASE_CURRENCY)
                .update();
        if (inserted == 0) {
            return false;
        }
        for (SeedAccount account : seed.accounts()) {
            jdbc.sql("""
                    INSERT INTO account (user_id, code, name, type, default_currency, requires_counterparty, is_system)
                    VALUES (:userId, :code, :name, :type, :defaultCurrency, :requiresCounterparty, :system)
                    ON CONFLICT (user_id, code) DO NOTHING""")
                    .param("userId", userId)
                    .param("code", account.code())
                    .param("name", account.name())
                    .param("type", account.type().name())
                    .param("defaultCurrency", account.defaultCurrency())
                    .param("requiresCounterparty", account.requiresCounterparty())
                    .param("system", account.system())
                    .update();
        }
        for (SeedCategory category : seed.categories()) {
            jdbc.sql("""
                    INSERT INTO category (user_id, code, name, type) VALUES (:userId, :code, :name, :type)
                    ON CONFLICT (user_id, code) DO NOTHING""")
                    .param("userId", userId)
                    .param("code", category.code())
                    .param("name", category.name())
                    .param("type", category.type().name())
                    .update();
        }
        return true;
    }

    Seed seed() {
        return seed;
    }

    private static Seed read(ObjectMapper json) {
        try (InputStream in = new ClassPathResource(SEED).getInputStream()) {
            return json.readerFor(Seed.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + SEED, e);
        }
    }

    record Seed(List<SeedAccount> accounts, List<SeedCategory> categories) {
    }

    /** @param defaultCurrency null for none */
    record SeedAccount(String code, String name, AccountType type, String defaultCurrency,
            boolean requiresCounterparty, boolean system) {
    }

    record SeedCategory(String code, String name, CategoryType type) {
    }
}
