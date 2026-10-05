package com.example.financetracker.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Each ledger entity round-trips through its repository, lookups by ledger see only that ledger's rows, and the
 * settings and manual rates, which are the person's, are looked up by user.
 */
class LedgerRepositoryTests extends IntegrationTest {

    @Autowired
    private AccountRepository accounts;
    @Autowired
    private LedgerCategoryRepository categories;
    @Autowired
    private CounterpartyRepository counterparties;
    @Autowired
    private ImportBatchRepository importBatches;
    @Autowired
    private UserSettingsRepository settings;
    @Autowired
    private ExchangeRateRepository exchangeRates;
    @Autowired
    private LedgerAccess ledgers;

    private final String user = UUID.randomUUID().toString();
    private final String other = UUID.randomUUID().toString();
    private LedgerScope scope;
    private LedgerScope othersScope;
    private long ledger;

    /** The days of the ECB's rates (every user's) that a test wrote, removed after it (IntegrationTest). */
    private final List<LocalDate> sharedRates = new ArrayList<>();

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void removeSharedRates() {
        for (LocalDate day : sharedRates) {
            jdbc.sql("DELETE FROM exchange_rate WHERE user_id IS NULL AND rate_date = ? AND quote_currency = 'RUB'")
                    .param(day).update();
        }
    }

    @BeforeEach
    void createLedgers() {
        scope = ledgers.provisionPersonal(user);
        othersScope = ledgers.provisionPersonal(other);
        ledger = scope.ledgerId();
    }

    @Test
    void accountRoundTripsAndIsScopedByLedger() {
        Instant archivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Account saved = accounts.save(new Account(null, user, ledger, "FX_EXCHANGE", "Exchange", AccountType.EQUITY,
                "EUR", false, true, archivedAt, null));

        Account read = accounts.find(scope, saved.id()).orElseThrow();

        assertThat(read).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(saved);
        assertThat(read.createdAt()).isNotNull();
        assertThat(accounts.find(othersScope, saved.id())).isEmpty();
        assertThat(accounts.lockAll(othersScope, List.of(saved.id()))).isEmpty();
    }

    @Test
    void categoryAndCounterpartyRoundTripAndAreScopedByLedger() {
        LedgerCategory category = categories.save(
                new LedgerCategory(null, user, ledger, "REST", "Rest", CategoryType.EXPENSE, null));
        Counterparty unclassified = counterparties.save(new Counterparty(null, user, ledger, "Friend A", null, null));

        assertThat(categories.find(scope, category.id())).contains(category);
        assertThat(counterparties.find(scope, unclassified.id())).contains(unclassified);
        assertThat(categories.find(othersScope, category.id())).isEmpty();
        assertThat(counterparties.lockAll(othersScope, List.of(unclassified.id()))).isEmpty();
    }

    @Test
    void importBatchKeepsItsJsonReport() throws Exception {
        ImportBatch saved = importBatches.save(new ImportBatch(null, user, ledger, "journal.csv", "a".repeat(64), true,
                null, null, new Json("""
                        {"rows": 299, "skipped": ["zero amount"]}""")));

        ImportBatch read = importBatches.find(scope, saved.id()).orElseThrow();

        assertThat(read.startedAt()).isNotNull();
        assertThat(read.dryRun()).isTrue();
        // JSONB normalizes the text, so compare the parsed values.
        assertThat(json.readTree(read.report().value()))
                .isEqualTo(json.readTree("{\"skipped\": [\"zero amount\"], \"rows\": 299}"));
        assertThat(importBatches.find(othersScope, saved.id())).isEmpty();
    }

    @Test
    void userSettingsAreInsertedThenReplaced() {
        long shared = accounts.save(new Account(null, user, ledger, "PARTNER_DEBT", "Partner", AccountType.LIABILITY,
                null, false, false, null, null)).id();

        settings.save(new UserSettings(user, "EUR", null, new BigDecimal("0.5000")));
        settings.save(new UserSettings(user, "RUB", shared, new BigDecimal("0.4000")));

        assertThat(settings.findById(user)).contains(new UserSettings(user, "RUB", shared, new BigDecimal("0.4000")));
        assertThat(settings.findById(other)).isEmpty();
    }

    @Test
    void exchangeRateIsInsertedThenReplaced() {
        LocalDate day = LocalDate.of(2026, 9, 25);
        sharedRates.add(day);

        exchangeRates.save(new ExchangeRate(day, "EUR", "RUB", new BigDecimal("75.00000000"), "ECB", null));
        exchangeRates.save(new ExchangeRate(day, "EUR", "RUB", new BigDecimal("75.50000000"), "ECB", null));
        // The user's own rate for the same day is a row of its own.
        exchangeRates.save(new ExchangeRate(day, "EUR", "RUB", new BigDecimal("80.00000000"), "MANUAL", user));

        assertThat(exchangeRates.find(day, "EUR", "RUB", null))
                .contains(new ExchangeRate(day, "EUR", "RUB", new BigDecimal("75.50000000"), "ECB", null));
        assertThat(exchangeRates.find(day, "EUR", "RUB", user))
                .contains(new ExchangeRate(day, "EUR", "RUB", new BigDecimal("80.00000000"), "MANUAL", user));
        assertThat(exchangeRates.find(day, "EUR", "RUB", other)).isEmpty();
        assertThat(exchangeRates.findManual(user)).hasSize(1);
        assertThat(exchangeRates.findManual(other)).isEmpty();
    }
}
