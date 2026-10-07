package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V13 on the journal of before it (F8d; D-93, ADR 0004, "F8d"). A database at V12 holds a family ledger whose records
 * have journal rows as V12's code writes them, with no currency: a record in the main currency, one created in dollars,
 * one whose currency changed from euros to dollars between two of its rows, a deleted one, and a system change. V13
 * gives each row the currency its amounts were in, which only the rows' own "currency" changes and the record's
 * current currency tell, and nothing else of the ledger changes. Then a row written as V12's code writes one, with no
 * currency, gets the record's by trigger, and a row of a system change keeps none.
 */
class FamilyV13MigrationTests {

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final String DATABASE = "family_v13_migration_tests";
    private static final String URL = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);

    @Test
    void everyJournalRowGetsTheCurrencyItsAmountsWereIn() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE " + DATABASE);
        }
        Flyway.configure().dataSource(URL, POSTGRES.getUsername(), POSTGRES.getPassword()).schemas("app")
                .target("12").load().migrate();
        try (Connection db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            db.setAutoCommit(false);
            long family = number(db, "INSERT INTO ledger (type, name, base_currency, split_rule) "
                    + "VALUES ('SHARED', 'Family', 'EUR', 'EQUAL') RETURNING id");
            long anna = number(db, """
                    INSERT INTO ledger_member (ledger_id, ledger_type, display_name, role, status, join_date)
                    VALUES (?, 'SHARED', 'Anna', 'MEMBER', 'ACTIVE', current_date) RETURNING id""", family);
            long category = number(db, "INSERT INTO category (ledger_id, code, name, type) "
                    + "VALUES (?, 'GROCERIES', 'Groceries', 'EXPENSE') RETURNING id", family);
            long inEuros = record(db, family, category, anna, "EUR", "10.00");
            long inDollars = record(db, family, category, anna, "USD", "20.00");
            long changed = record(db, family, category, anna, "USD", "30.00");
            long deleted = record(db, family, category, anna, "EUR", "40.00");
            db.commit();

            // The rows, in the order the code wrote them.
            journal(db, family, inEuros, "CREATE", "[{\"field\":\"amount\",\"old\":null,\"new\":\"9.00\"}]");
            journal(db, family, inEuros, "UPDATE", "[{\"field\":\"amount\",\"old\":\"9.00\",\"new\":\"10.00\"}]");
            journal(db, family, inDollars, "CREATE", "[{\"field\":\"amount\",\"old\":null,\"new\":\"20.00\"},"
                    + "{\"field\":\"currency\",\"old\":null,\"new\":\"USD\"}]");
            // Created in euros (no currency in the row: the ledger's main one), then changed to dollars, then edited.
            journal(db, family, changed, "CREATE", "[{\"field\":\"amount\",\"old\":null,\"new\":\"25.00\"}]");
            journal(db, family, changed, "UPDATE", "[{\"field\":\"amount\",\"old\":\"25.00\",\"new\":\"30.00\"},"
                    + "{\"field\":\"currency\",\"old\":\"EUR\",\"new\":\"USD\"}]");
            journal(db, family, changed, "UPDATE", "[{\"field\":\"amount\",\"old\":\"30.00\",\"new\":\"31.00\"}]");
            journal(db, family, deleted, "CREATE", "[{\"field\":\"amount\",\"old\":null,\"new\":\"40.00\"}]");
            journal(db, family, deleted, "DELETE", "[]");
            db.createStatement().execute("INSERT INTO family_record_change (ledger_id, about_member_id, action, "
                    + "changes) VALUES (" + family + ", " + anna + ", 'SPLIT_RULE_RESET', '[]'::jsonb)");
            db.commit();
            String records = "SELECT string_agg(concat_ws(',', r.id, r.type, r.record_date, r.base_amount, "
                    + "r.original_amount, r.original_currency, r.currency, r.category_id, r.payer_member_id, "
                    + "r.version), '|' ORDER BY r.id) FROM family_record r";
            String recordsBefore = strings(db, records).getFirst();
            String sharesBefore = strings(db, "SELECT string_agg(s::text, '|' ORDER BY s.record_id) FROM family_share s")
                    .getFirst();

            // This connection's own transaction holds read locks that the migration's ALTER TABLE would wait for.
            db.commit();
            Flyway.configure().dataSource(URL, POSTGRES.getUsername(), POSTGRES.getPassword()).schemas("app")
                    .target("13").load().migrate();

            assertThat(strings(db, """
                    SELECT c.action || ' ' || coalesce(c.currency, 'none') FROM family_record_change c
                    ORDER BY c.id""")).containsExactly(
                    "CREATE EUR", "UPDATE EUR",
                    "CREATE USD",
                    "CREATE EUR", "UPDATE USD", "UPDATE USD",
                    "CREATE EUR", "DELETE EUR",
                    "SPLIT_RULE_RESET none");
            // Nothing else changed.
            assertThat(strings(db, records).getFirst()).isEqualTo(recordsBefore);
            assertThat(strings(db, "SELECT string_agg(s::text, '|' ORDER BY s.record_id) FROM family_share s")
                    .getFirst()).isEqualTo(sharesBefore);

            // An image of V12 writes a row with no currency: the record's own, right after its change.
            db.createStatement().execute("INSERT INTO family_record_change (ledger_id, record_id, "
                    + "changed_by_member_id, action, changes) VALUES (" + family + ", " + inDollars + ", " + anna
                    + ", 'UPDATE', '[{\"field\":\"comment\",\"old\":null,\"new\":\"x\"}]'::jsonb)");
            db.commit();
            assertThat(strings(db, "SELECT currency FROM family_record_change ORDER BY id DESC LIMIT 1"))
                    .containsExactly("USD");
            // A system change has none, and a record's row can't lack it.
            db.createStatement().execute("INSERT INTO family_record_change (ledger_id, about_member_id, action, "
                    + "changes) VALUES (" + family + ", " + anna + ", 'SPLIT_RULE_RESET', '[]'::jsonb)");
            db.commit();
            assertThat(strings(db, "SELECT coalesce(currency, 'none') FROM family_record_change ORDER BY id DESC "
                    + "LIMIT 1")).containsExactly("none");
        }
    }

    private static long record(Connection db, long family, long category, long payer, String currency, String amount)
            throws SQLException {
        long record = number(db, """
                INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id, original_amount,
                    original_currency, base_amount, currency, split_method, author_member_id, updated_by_member_id)
                VALUES (?, 'EXPENSE', current_date, ?, ?, ?, ?, ?, ?, 'EQUAL', ?, ?) RETURNING id""", family, category,
                payer, new BigDecimal(amount), currency, new BigDecimal(amount), currency, payer, payer);
        update(db, "INSERT INTO family_share (ledger_id, record_id, member_id, amount, updated_by_member_id) "
                + "VALUES (?, ?, ?, ?, ?)", family, record, payer, new BigDecimal(amount), payer);
        return record;
    }

    private static void journal(Connection db, long family, long record, String action, String changes)
            throws SQLException {
        update(db, "INSERT INTO family_record_change (ledger_id, record_id, changed_by_member_id, action, changes) "
                + "SELECT ?, ?, author_member_id, ?, ?::jsonb FROM family_record WHERE id = ?", family, record, action,
                changes, record);
    }

    private static void update(Connection db, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, params)) {
            statement.executeUpdate();
        }
    }

    private static long number(Connection db, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, params); var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static List<String> strings(Connection db, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, params); var rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static PreparedStatement prepare(Connection db, String sql, Object... params) throws SQLException {
        PreparedStatement statement = db.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}
