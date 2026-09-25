package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The ledger's integrity rules as PostgreSQL enforces them on its own, without the application: plain JDBC against a
 * database that starts empty and is migrated by Flyway. Every test runs as a fresh user in one transaction, since
 * the balance is checked at commit.
 */
class LedgerSchemaTests {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final String URL = "jdbc:postgresql://%s:%d/ledger_schema_tests"
            .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));

    private static MigrateResult migration;

    private final String user = UUID.randomUUID().toString();
    private Connection db;
    private long cash, card, loans, unallocated, familyDebt, fxExchange, groceries, borrower;

    @BeforeAll
    static void migrateEmptyDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE ledger_schema_tests");
        }
        // As the application configures it (spring.flyway.schemas).
        migration = Flyway.configure().dataSource(URL, POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("app").load().migrate();
    }

    @BeforeEach
    void createAccounts() throws SQLException {
        db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(), POSTGRES.getPassword());
        cash = account(user, "CASH", "ASSET", false);
        card = account(user, "CARD", "ASSET", false);
        loans = account(user, "LOANS_ASSET", "ASSET", true);
        unallocated = account(user, "UNALLOCATED", "EQUITY", false);
        familyDebt = account(user, "FAMILY_DEBT", "LIABILITY", false);
        fxExchange = account(user, "FX_EXCHANGE", "EQUITY", false);
        groceries = category(user);
        borrower = counterparty(user);
        db.setAutoCommit(false);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void migrationsApplyToAnEmptyDatabase() throws SQLException {
        assertThat(migration.success).isTrue();
        assertThat(migration.initialSchemaVersion).isNull();
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = 'app'"))
                .containsExactlyInAnyOrder("flyway_schema_history",
                        "users", "categories", "transactions", // V1
                        "account", "category", "counterparty", "journal_entry", "posting", "exchange_rate",
                        "import_batch", "user_settings");
    }

    /** A shared expense (rule 7): 10.01 paid in cash, 5.00 of it the user's groceries and 5.01 the family's. */
    @Test
    void balancedEntryCommits() throws SQLException {
        long entry = entry();
        post(entry, cash, "EUR", "-10.01");
        post(entry, unallocated, "EUR", "5.00", groceries, null);
        post(entry, familyDebt, "EUR", "5.01");

        db.commit();

        assertThat(number("SELECT count(*) FROM posting WHERE entry_id = ?", entry)).isEqualTo(3);
    }

    @Test
    void unbalancedEntryFailsAtCommit() throws SQLException {
        long entry = entry();
        post(entry, cash, "EUR", "-10.00");
        post(entry, unallocated, "EUR", "9.99", groceries, null); // accepted until commit

        assertFails(db::commit, CHECK_VIOLATION,
                "journal entry %d does not balance in EUR: postings sum to -0.0100".formatted(entry));
        assertThat(number("SELECT count(*) FROM journal_entry WHERE id = ?", entry)).isZero();
    }

    /** An exchange (rule 9) of 1000 RUB for 10 EUR, with one cent too much arriving on the card. */
    @Test
    void entryBalancedInRubButNotInEurFailsAtCommit() throws SQLException {
        long entry = entry();
        post(entry, cash, "RUB", "-1000.00");
        post(entry, fxExchange, "RUB", "1000.00");
        post(entry, fxExchange, "EUR", "-10.00");
        post(entry, card, "EUR", "10.01");

        assertFails(db::commit, CHECK_VIOLATION,
                "journal entry %d does not balance in EUR: postings sum to 0.0100".formatted(entry));
    }

    @Test
    void entryWithoutPostingsFailsAtCommit() throws SQLException {
        long entry = entry();

        assertFails(db::commit, CHECK_VIOLATION, "journal entry %d needs at least 2 postings, has 0".formatted(entry));
    }

    @Test
    void deletingOnePostingOfAnEntryFailsAtCommit() throws SQLException {
        long entry = expense("7.50");
        db.commit();

        update("DELETE FROM posting WHERE entry_id = ? AND account_id = ?", entry, cash);

        assertFails(db::commit, CHECK_VIOLATION, "journal entry %d needs at least 2 postings, has 1".formatted(entry));
    }

    @Test
    void deletingAnEntryDeletesItsPostings() throws SQLException {
        long entry = expense("7.50");
        db.commit();

        update("DELETE FROM journal_entry WHERE id = ?", entry);
        db.commit();

        assertThat(number("SELECT count(*) FROM posting WHERE entry_id = ?", entry)).isZero();
    }

    @Test
    void movingAPostingToAnotherEntryChecksBothEntries() throws SQLException {
        long from = expense("1.00");
        long to = expense("2.00");
        db.commit();

        update("UPDATE posting SET entry_id = ? WHERE entry_id = ? AND account_id = ?", to, from, cash);

        assertFails(db::commit, CHECK_VIOLATION, "journal entry %d needs at least 2 postings, has 1".formatted(from));
    }

    @Test
    void categoryOnAssetPostingFails() throws SQLException {
        long entry = entry();

        assertFails(() -> post(entry, cash, "EUR", "-5.00", groceries, null), CHECK_VIOLATION,
                "account CASH is ASSET, and only postings to EQUITY accounts can have a category");
    }

    @Test
    void postingToLoansAssetWithoutCounterpartyFails() throws SQLException {
        long entry = entry();
        post(entry, loans, "EUR", "100.00", null, borrower);

        assertFails(() -> post(entry, loans, "EUR", "100.00"), CHECK_VIOLATION,
                "account LOANS_ASSET requires a counterparty");
    }

    @Test
    void postingThatReferencesAnotherUsersRowFails() throws SQLException {
        String other = UUID.randomUUID().toString();
        long othersAccount = account(other, "CASH", "ASSET", false);
        long othersCategory = category(other);
        long othersCounterparty = counterparty(other);
        db.commit();

        assertFails(() -> post(entry(), othersAccount, "EUR", "5.00"), FOREIGN_KEY_VIOLATION,
                "account %d belongs to another user".formatted(othersAccount));
        db.rollback();
        assertFails(() -> post(entry(), unallocated, "EUR", "5.00", othersCategory, null), FOREIGN_KEY_VIOLATION,
                "category %d belongs to another user".formatted(othersCategory));
        db.rollback();
        assertFails(() -> post(entry(), loans, "EUR", "5.00", null, othersCounterparty), FOREIGN_KEY_VIOLATION,
                "counterparty %d belongs to another user".formatted(othersCounterparty));
    }

    @Test
    void accountCannotChangeSoThatItsPostingsBreakTheRules() throws SQLException {
        expense("3.00");
        db.commit();

        assertFails(() -> update("UPDATE account SET type = 'LIABILITY' WHERE id = ?", unallocated), CHECK_VIOLATION,
                "account UNALLOCATED has postings with a category and must stay EQUITY");
        db.rollback();
        assertFails(() -> update("UPDATE account SET requires_counterparty = TRUE WHERE id = ?", cash),
                CHECK_VIOLATION, "account CASH has postings without a counterparty and cannot require one");
    }

    @Test
    void rowsNeverChangeOwner() {
        assertFails(() -> update("UPDATE account SET user_id = ? WHERE id = ?", UUID.randomUUID().toString(), cash),
                CHECK_VIOLATION, "account %d: user_id cannot change".formatted(cash));
    }

    private static void assertFails(ThrowingCallable call, String sqlState, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(sqlState))
                .hasMessageContaining(message);
    }

    private long account(String owner, String code, String type, boolean requiresCounterparty) throws SQLException {
        return insert("INSERT INTO account (user_id, code, name, type, requires_counterparty) VALUES (?, ?, ?, ?, ?)",
                owner, code, code, type, requiresCounterparty);
    }

    private long category(String owner) throws SQLException {
        return insert("INSERT INTO category (user_id, code, name, type) VALUES (?, 'GROCERIES', 'Groceries', 'EXPENSE')",
                owner);
    }

    private long counterparty(String owner) throws SQLException {
        return insert("INSERT INTO counterparty (user_id, name, kind) VALUES (?, 'Borrower', 'PERSON')", owner);
    }

    private long entry() throws SQLException {
        return insert("INSERT INTO journal_entry (user_id, entry_date) VALUES (?, DATE '2026-09-25')", user);
    }

    /** Groceries paid in cash. */
    private long expense(String amount) throws SQLException {
        long entry = entry();
        post(entry, cash, "EUR", "-" + amount);
        post(entry, unallocated, "EUR", amount, groceries, null);
        return entry;
    }

    private void post(long entry, long account, String currency, String amount) throws SQLException {
        post(entry, account, currency, amount, null, null);
    }

    private void post(long entry, long account, String currency, String amount, Long category, Long counterparty)
            throws SQLException {
        insert("""
                INSERT INTO posting (entry_id, account_id, currency, amount, category_id, counterparty_id)
                VALUES (?, ?, ?, ?, ?, ?)""", entry, account, currency, new BigDecimal(amount), category, counterparty);
    }

    private long insert(String sql, Object... params) throws SQLException {
        return number(sql + " RETURNING id", params);
    }

    private void update(String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(sql, params)) {
            statement.executeUpdate();
        }
    }

    private long number(String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(sql, params); var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private List<String> strings(String sql) throws SQLException {
        try (PreparedStatement statement = prepare(sql); var rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement statement = db.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}
