package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V21 on a family ledger of V20 (HIST-3 part 1; D-1155, D-1157): ACTIVE members with and without an account, a LEFT
 * and a FORMER one, a record with versions and one without, attached and detached links. The backfill leaves nothing
 * pending: each ACTIVE member with an account has accepted the ledger's revision, nobody else has a counter, a record's
 * last revision is its latest version's (0 without one), and each link is posted at its record's. Then the image of
 * V20 writes as before: a member leaving loses the counter, and a change of the counter alone writes no version.
 */
class FamilyV21MigrationTests {

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final String DATABASE = "family_v21_migration_tests";
    private static final String URL = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);

    @Test
    void theBackfillLeavesNothingPending() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            IntegrationTest.recreateDatabase(admin, DATABASE);
        }
        migrate("20");
        try (Connection db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            db.setAutoCommit(false);
            for (String sub : new String[] {"v21-anna", "v21-ben", "v21-leo"}) {
                number(db, "SELECT personal_ledger_id(?)", sub);
            }
            long family = number(db, "INSERT INTO ledger (type, name, base_currency, split_rule, revision) "
                    + "VALUES ('SHARED', 'Family', 'EUR', 'EQUAL', 7) RETURNING id");
            long anna = member(db, family, "'v21-anna'", "'ACTIVE'", "NULL");
            long ben = member(db, family, "'v21-ben'", "'ACTIVE'", "NULL");
            long kid = member(db, family, "NULL", "'ACTIVE'", "NULL");
            long leo = member(db, family, "'v21-leo'", "'LEFT'", "current_date");
            long former = member(db, family, "NULL", "'FORMER'", "current_date");
            long category = number(db, "INSERT INTO category (ledger_id, code, name, type) "
                    + "VALUES (?, 'GROCERIES', 'Groceries', 'EXPENSE') RETURNING id", family);
            long versioned = record(db, family, category, anna);
            long unversioned = record(db, family, category, anna);
            version(db, family, versioned, 1, 3);
            version(db, family, versioned, 2, 5);
            db.createStatement().execute("SELECT set_config('app.writer', 'family-posting', true)");
            link(db, family, anna, versioned, "NULL");
            link(db, family, ben, versioned, "NULL");
            link(db, family, leo, versioned, "now()");
            link(db, family, anna, unversioned, "NULL");
            db.commit();

            migrate("21");

            assertThat(accepted(db, anna)).isEqualTo(7L);
            assertThat(accepted(db, ben)).isEqualTo(7L);
            assertThat(accepted(db, kid)).isNull();
            assertThat(accepted(db, leo)).isNull();
            assertThat(accepted(db, former)).isNull();
            assertThat(number(db, "SELECT last_revision FROM family_record WHERE id = ?", versioned)).isEqualTo(5);
            assertThat(number(db, "SELECT last_revision FROM family_record WHERE id = ?", unversioned)).isZero();
            assertThat(number(db, "SELECT count(*) FROM family_entry_link l JOIN family_record r ON r.id = l.record_id "
                    + "WHERE l.family_ledger_id = ? AND l.posted_revision <> r.last_revision", family)).isZero();
            assertThat(number(db, "SELECT count(*) FROM family_entry_link WHERE family_ledger_id = ? "
                    + "AND posted_revision = 5", family)).isEqualTo(3);
            db.commit();

            // The image of V20 on V21: a member leaving loses the counter; one coming back gets the head.
            db.createStatement().execute("UPDATE ledger_member SET status = 'LEFT', left_date = current_date "
                    + "WHERE id = " + ben);
            assertThat(accepted(db, ben)).isNull();
            db.createStatement().execute("UPDATE ledger_member SET status = 'ACTIVE', left_date = NULL "
                    + "WHERE id = " + leo);
            assertThat(accepted(db, leo)).isEqualTo(7L);
            db.commit();

            // A counter alone is no content change: with history on, it marks nothing, and the commit passes...
            db.createStatement().execute("SELECT set_config('app.history', 'on', true)");
            db.createStatement().execute("UPDATE family_record SET last_revision = 9 WHERE id = " + unversioned);
            db.commit();
            assertThat(number(db, "SELECT count(*) FROM record_version WHERE record_id = ?", unversioned)).isZero();
            // ...while a content change without its version fails, so the check was live.
            db.createStatement().execute("SELECT set_config('app.history', 'on', true)");
            db.createStatement().execute("UPDATE family_record SET comment = 'x' WHERE id = " + unversioned);
            assertThatThrownBy(db::commit).isInstanceOf(SQLException.class);
            db.rollback();
        }
    }

    private static void migrate(String target) {
        Flyway.configure().dataSource(URL, POSTGRES.getUsername(), POSTGRES.getPassword()).schemas("app")
                .target(target).load().migrate();
    }

    private static long member(Connection db, long family, String sub, String status, String leftDate)
            throws SQLException {
        return number(db, """
                INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date,
                                           left_date)
                VALUES (?, 'SHARED', %s, ?, 'MEMBER', %s, current_date - 30, %s) RETURNING id"""
                .formatted(sub, status, leftDate), family, "M" + NAMES.incrementAndGet());
    }

    private static long record(Connection db, long family, long category, long payer) throws SQLException {
        long record = number(db, """
                INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id, original_amount,
                    original_currency, base_amount, currency, split_method, author_member_id, updated_by_member_id)
                VALUES (?, 'EXPENSE', current_date, ?, ?, 10, 'EUR', 10, 'EUR', 'EQUAL', ?, ?) RETURNING id""",
                family, category, payer, payer, payer);
        update(db, "INSERT INTO family_share (ledger_id, record_id, member_id, amount, updated_by_member_id) "
                + "VALUES (?, ?, ?, ?, ?)", family, record, payer, new BigDecimal("10"), payer);
        return record;
    }

    private static void version(Connection db, long family, long record, int seq, long revision)
            throws SQLException {
        update(db, """
                INSERT INTO record_version (ledger_id, record_type, record_id, seq, revision, op, content, content_hash,
                                            content_ver, actor_kind, cause)
                VALUES (?, 'FAMILY_RECORD', ?, ?, ?, 'UPDATE', '{}'::jsonb, sha256('x'::bytea), 1, 'SYSTEM', 'EDIT')""",
                family, record, seq, revision);
    }

    private static void link(Connection db, long family, long member, long record, String detachedAt)
            throws SQLException {
        update(db, """
                INSERT INTO family_entry_link (family_ledger_id, member_id, record_id, link_type, system_owned,
                                               detached_at)
                VALUES (?, ?, ?, 'SHARE', TRUE, %s)""".formatted(detachedAt), family, member, record);
    }

    private static Long accepted(Connection db, long member) throws SQLException {
        try (PreparedStatement statement = prepare(db, "SELECT accepted_revision FROM ledger_member WHERE id = ?",
                member); var rows = statement.executeQuery()) {
            rows.next();
            long value = rows.getLong(1);
            return rows.wasNull() ? null : value;
        }
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

    private static PreparedStatement prepare(Connection db, String sql, Object... params) throws SQLException {
        PreparedStatement statement = db.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}
