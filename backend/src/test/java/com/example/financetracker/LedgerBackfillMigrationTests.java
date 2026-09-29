package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V5's backfill, on rows as the code before it writes them: a database migrated to V4 gets the rows of several users,
 * each known to other tables, and then V5. Every sub gets one personal ledger with itself as its one owner, and every
 * row its user's ledger, whether or not the sub has a users or user_settings row.
 */
class LedgerBackfillMigrationTests {

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final String URL = "jdbc:postgresql://%s:%d/ledger_backfill_tests"
            .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));

    /** A whole ledger: users and settings rows, accounts, categories, a counterparty, an import and an entry. */
    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    /** Only a user_settings row, as after a first request whose seeding failed halfway. */
    private static final String SETTINGS_ONLY = "22222222-2222-2222-2222-222222222222";
    /** Only a manual exchange rate. */
    private static final String RATES_ONLY = "33333333-3333-3333-3333-333333333333";
    /** Only a users row. */
    private static final String USERS_ONLY = "44444444-4444-4444-4444-444444444444";
    /** Accounts, a category and an entry, but no users row, as the command-line importer writes them. */
    private static final String WITHOUT_USERS_ROW = "55555555-5555-5555-5555-555555555555";

    private static final List<String> LEDGER_SCOPED = List.of("account", "category", "counterparty",
            "journal_entry", "import_batch");

    private Connection db;

    @Test
    void everySubGetsOnePersonalLedgerAndEveryRowItsUsersLedger() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE ledger_backfill_tests");
        }
        assertThat(flyway("4").migrate().targetSchemaVersion).isEqualTo("4");
        db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(), POSTGRES.getPassword());
        try {
            // In one transaction: an entry balances at commit.
            db.setAutoCommit(false);
            writeRowsAsBeforeV5();
            db.commit();
            db.setAutoCommit(true);
            Map<String, Long> rowsBefore = rows();
            String postingsBefore = strings("SELECT string_agg(p::text, '|' ORDER BY p.id) FROM posting p").getFirst();

            MigrateResult v5 = flyway("5").migrate();

            assertThat(v5.success).isTrue();
            assertThat(v5.initialSchemaVersion).isEqualTo("4");
            assertThat(v5.targetSchemaVersion).isEqualTo("5");
            // One personal ledger per sub, with the sub as its one member and owner, named as in users if it can be.
            assertThat(strings("""
                    SELECT concat_ws(' ', m.user_sub, l.type, m.ledger_type, m.role, m.status,
                        '"' || m.display_name || '"')
                    FROM ledger l JOIN ledger_member m ON m.ledger_id = l.id ORDER BY m.user_sub""")).containsExactly(
                    OWNER + " PERSONAL PERSONAL OWNER ACTIVE \"Olive Owner\"",
                    SETTINGS_ONLY + " PERSONAL PERSONAL OWNER ACTIVE \"\"",
                    RATES_ONLY + " PERSONAL PERSONAL OWNER ACTIVE \"\"",
                    USERS_ONLY + " PERSONAL PERSONAL OWNER ACTIVE \"Una Users\"",
                    WITHOUT_USERS_ROW + " PERSONAL PERSONAL OWNER ACTIVE \"\"");
            assertThat(number("SELECT count(*) FROM ledger")).isEqualTo(5);
            // Every row is in its user's ledger, and nothing else changed.
            for (String table : LEDGER_SCOPED) {
                assertThat(number("""
                        SELECT count(*) FROM %s t WHERE t.ledger_id IS DISTINCT FROM
                            (SELECT ledger_id FROM ledger_member m WHERE m.user_sub = t.user_id)""".formatted(table)))
                        .as(table).isZero();
            }
            assertThat(rows()).isEqualTo(rowsBefore);
            assertThat(strings("SELECT string_agg(p::text, '|' ORDER BY p.id) FROM posting p").getFirst())
                    .isEqualTo(postingsBefore);
            assertThat(strings("SELECT DISTINCT user_id || ' ' || ledger_id FROM account ORDER BY 1")).containsExactly(
                    OWNER + " " + ledger(OWNER), WITHOUT_USERS_ROW + " " + ledger(WITHOUT_USERS_ROW));

            // V6 (F3a) on the backfilled rows, as in production: additive, it changes none of them, and a personal
            // ledger gets no split rule and its member no share.
            String ledgers = """
                    SELECT string_agg(concat_ws(' ', l.id, l.type, l.name, l.base_currency, m.id, m.user_sub,
                        m.display_name, m.role, m.status, m.join_date), '|' ORDER BY l.id)
                    FROM ledger l JOIN ledger_member m ON m.ledger_id = l.id""";
            String ledgersBefore = strings(ledgers).getFirst();
            MigrateResult v6 = flyway(null).migrate();
            assertThat(v6.success).isTrue();
            assertThat(v6.targetSchemaVersion).isEqualTo("6");
            assertThat(rows()).isEqualTo(rowsBefore);
            assertThat(strings("SELECT string_agg(p::text, '|' ORDER BY p.id) FROM posting p").getFirst())
                    .isEqualTo(postingsBefore);
            assertThat(strings(ledgers).getFirst()).isEqualTo(ledgersBefore);
            assertThat(number("SELECT count(*) FROM ledger WHERE split_rule IS NOT NULL")).isZero();
            assertThat(number("SELECT count(*) FROM ledger_member WHERE share_bp IS NOT NULL")).isZero();
        } finally {
            db.close();
        }
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(URL, POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("app");
        return (target == null ? configuration : configuration.target(target)).load();
    }

    /** The rows of the five users, and an ECB rate, which belongs to nobody. */
    private void writeRowsAsBeforeV5() throws SQLException {
        update("INSERT INTO users (keycloak_id, email, display_name) VALUES (?, 'owner@example.com', 'Olive Owner')",
                OWNER);
        update("INSERT INTO user_settings (user_id) VALUES (?)", OWNER);
        long cash = insert("INSERT INTO account (user_id, code, name, type) VALUES (?, 'CASH', 'Cash', 'ASSET')", OWNER);
        long unallocated = insert("INSERT INTO account (user_id, code, name, type) "
                + "VALUES (?, 'UNALLOCATED', 'Unallocated', 'EQUITY')", OWNER);
        long familyDebt = insert("INSERT INTO account (user_id, code, name, type) "
                + "VALUES (?, 'FAMILY_DEBT', 'Family budget', 'LIABILITY')", OWNER);
        update("UPDATE user_settings SET shared_account_id = ? WHERE user_id = ?", familyDebt, OWNER);
        long groceries = insert("INSERT INTO category (user_id, code, name, type) "
                + "VALUES (?, 'GROCERIES', 'Groceries', 'EXPENSE')", OWNER);
        long shop = insert("INSERT INTO counterparty (user_id, name, kind) VALUES (?, 'Shop', 'MERCHANT')", OWNER);
        long batch = insert("INSERT INTO import_batch (user_id, file_name, file_sha256, dry_run) "
                + "VALUES (?, 'transactions.csv', repeat('a', 64), FALSE)", OWNER);
        long entry = insert("INSERT INTO journal_entry (user_id, entry_date, kind, payee_id, import_batch_id, "
                + "external_ref) VALUES (?, DATE '2026-09-01', 'SHARED_EXPENSE', ?, ?, 'xls:1:0')", OWNER, shop, batch);
        post(entry, 0, cash, "-10.01", null);
        post(entry, 1, unallocated, "5.00", groceries);
        post(entry, 2, familyDebt, "5.01", null);
        rate(OWNER, "MANUAL");

        update("INSERT INTO user_settings (user_id, base_currency) VALUES (?, 'USD')", SETTINGS_ONLY);
        rate(RATES_ONLY, "MANUAL");
        update("INSERT INTO users (keycloak_id, display_name) VALUES (?, 'Una Users')", USERS_ONLY);

        long bank = insert("INSERT INTO account (user_id, code, name, type) VALUES (?, 'BANK', 'Bank', 'ASSET')",
                WITHOUT_USERS_ROW);
        long equity = insert("INSERT INTO account (user_id, code, name, type) "
                + "VALUES (?, 'UNALLOCATED', 'Unallocated', 'EQUITY')", WITHOUT_USERS_ROW);
        long salary = insert("INSERT INTO category (user_id, code, name, type) VALUES (?, 'SALARY', 'Salary', 'INCOME')",
                WITHOUT_USERS_ROW);
        long income = insert("INSERT INTO journal_entry (user_id, entry_date, kind) VALUES (?, DATE '2026-09-02', "
                + "'INCOME')", WITHOUT_USERS_ROW);
        post(income, 0, bank, "100", null);
        post(income, 1, equity, "-100", salary);

        rate(null, "ECB");
    }

    private void post(long entry, int lineNo, long account, String amount, Long category) throws SQLException {
        update("INSERT INTO posting (entry_id, line_no, account_id, currency, amount, category_id) "
                + "VALUES (?, ?, ?, 'EUR', ?, ?)", entry, lineNo, account, new BigDecimal(amount), category);
    }

    private void rate(String user, String source) throws SQLException {
        update("INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source, user_id) "
                + "VALUES (DATE '2026-09-01', 'EUR', 'USD', 1.1, ?, ?)", source, user);
    }

    /** The number of rows in each table of the ledger. */
    private Map<String, Long> rows() throws SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : List.of("users", "user_settings", "account", "category", "counterparty", "journal_entry",
                "posting", "import_batch", "exchange_rate")) {
            rows.put(table, number("SELECT count(*) FROM " + table));
        }
        return rows;
    }

    private long ledger(String sub) throws SQLException {
        return number("SELECT ledger_id FROM ledger_member WHERE user_sub = ?", sub);
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
