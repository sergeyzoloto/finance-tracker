package com.example.financetracker.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Each ledger entity round-trips through its repository, and lookups by user see only that user's rows. */
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

    private final String user = UUID.randomUUID().toString();
    private final String other = UUID.randomUUID().toString();

    @Test
    void accountRoundTripsAndIsScopedByUser() {
        Instant archivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Account saved = accounts.save(new Account(null, user, "FX_EXCHANGE", "Exchange", AccountType.EQUITY, "EUR",
                false, true, archivedAt, null));

        Account read = accounts.findByIdAndUserId(saved.id(), user).orElseThrow();

        assertThat(read).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(saved);
        assertThat(read.createdAt()).isNotNull();
        assertThat(accounts.findByIdAndUserId(saved.id(), other)).isEmpty();
        assertThat(accounts.findAllByUserIdAndIdIn(other, List.of(saved.id()))).isEmpty();
    }

    @Test
    void categoryAndCounterpartyRoundTripAndAreScopedByUser() {
        LedgerCategory category = categories.save(
                new LedgerCategory(null, user, "REST", "Rest", CategoryType.EXPENSE, null));
        Counterparty unclassified = counterparties.save(new Counterparty(null, user, "Friend A", null, null));

        assertThat(categories.findByIdAndUserId(category.id(), user)).contains(category);
        assertThat(counterparties.findByIdAndUserId(unclassified.id(), user)).contains(unclassified);
        assertThat(categories.findByIdAndUserId(category.id(), other)).isEmpty();
        assertThat(counterparties.findAllByUserIdAndIdIn(other, List.of(unclassified.id()))).isEmpty();
    }

    @Test
    void importBatchKeepsItsJsonReport() throws Exception {
        ImportBatch saved = importBatches.save(new ImportBatch(null, user, "journal.csv", "a".repeat(64), true, null,
                null, new Json("""
                        {"rows": 299, "skipped": ["zero amount"]}""")));

        ImportBatch read = importBatches.findByIdAndUserId(saved.id(), user).orElseThrow();

        assertThat(read.startedAt()).isNotNull();
        assertThat(read.dryRun()).isTrue();
        // JSONB normalizes the text, so compare the parsed values.
        assertThat(json.readTree(read.report().value()))
                .isEqualTo(json.readTree("{\"skipped\": [\"zero amount\"], \"rows\": 299}"));
        assertThat(importBatches.findByIdAndUserId(saved.id(), other)).isEmpty();
    }

    @Test
    void userSettingsAreInsertedThenReplaced() {
        long shared = accounts.save(new Account(null, user, "PARTNER_DEBT", "Partner", AccountType.LIABILITY, null,
                false, false, null, null)).id();

        settings.save(new UserSettings(user, "EUR", null, new BigDecimal("0.5000")));
        settings.save(new UserSettings(user, "RUB", shared, new BigDecimal("0.4000")));

        assertThat(settings.findById(user)).contains(new UserSettings(user, "RUB", shared, new BigDecimal("0.4000")));
        assertThat(settings.findById(other)).isEmpty();
    }

    @Test
    void exchangeRateIsInsertedThenReplaced() {
        LocalDate day = LocalDate.of(2026, 9, 25);

        exchangeRates.save(new ExchangeRate(day, "EUR", "RUB", new BigDecimal("75.00000000"), "manual"));
        exchangeRates.save(new ExchangeRate(day, "EUR", "RUB", new BigDecimal("75.50000000"), "ecb"));

        assertThat(exchangeRates.find(day, "EUR", "RUB"))
                .contains(new ExchangeRate(day, "EUR", "RUB", new BigDecimal("75.50000000"), "ecb"));
        assertThat(exchangeRates.find(day, "RUB", "EUR")).isEmpty();
    }
}
