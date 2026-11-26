package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V24 on a database of V23 (HIST-4 book snapshots part a, D-1040, D-1300, D-1304): additive, the image of V23 still
 * writes its snapshots and restore runs as before; a snapshot_ledger row can't be changed, goes with its snapshot under
 * the erasure's writer and with its family ledger by the cascade; a restore run needs a BEFORE_RESTORE snapshot or a
 * parent run.
 */
class BookSnapshotV24MigrationTests {

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final String DATABASE = "book_snapshot_v24_migration_tests";
    private static final String URL = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);

    @Test
    void snapshotLedgerRowsAreImmutableAndGoWithTheirSnapshotOrBudget() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            IntegrationTest.recreateDatabase(admin, DATABASE);
        }
        migrate("23");
        try (Connection db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Statement sql = db.createStatement();
            long personal = number(sql, "SELECT personal_ledger_id('v24-anna')");
            long before = number(sql, "INSERT INTO snapshot (ledger_id, revision, name, kind) VALUES (" + personal
                    + ", 0, 'Old', 'MANUAL') RETURNING id");

            migrate("24");

            // The image of V23: a snapshot and a restore run with its BEFORE_RESTORE snapshot, as before.
            long snapshot = number(sql, "INSERT INTO snapshot (ledger_id, revision, name, kind) VALUES (" + personal
                    + ", 0, 'One', 'MANUAL') RETURNING id");
            long run = number(sql, """
                    INSERT INTO restore_run (ledger_id, kind, snapshot_id, target_revision, revision, before_snapshot_id)
                    VALUES (%d, 'SNAPSHOT', %d, 0, 1, %d) RETURNING id""".formatted(personal, before, snapshot));
            assertThat(number(sql, "SELECT count(*) FROM snapshot_ledger WHERE snapshot_id = " + before)).isZero();

            long family = number(sql, """
                    INSERT INTO ledger (type, name, base_currency, split_rule, start_date)
                    VALUES ('SHARED', 'Home', 'EUR', 'EQUAL', DATE '2026-09-01') RETURNING id""");
            long other = number(sql, """
                    INSERT INTO ledger (type, name, base_currency, split_rule, start_date)
                    VALUES ('SHARED', 'Away', 'EUR', 'EQUAL', DATE '2026-09-01') RETURNING id""");
            sql.execute("INSERT INTO snapshot_ledger (snapshot_id, ledger_id, revision, member_id) VALUES (" + snapshot
                    + ", " + family + ", 3, 7), (" + snapshot + ", " + other + ", 0, 8)");
            assertThatThrownBy(() -> sql.execute("INSERT INTO snapshot_ledger (snapshot_id, ledger_id, revision, "
                    + "member_id) VALUES (" + before + ", " + family + ", -1, 7)")).hasMessageContaining("revision");
            assertThatThrownBy(() -> sql.execute("UPDATE snapshot_ledger SET revision = 4 WHERE ledger_id = " + family))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> sql.execute("DELETE FROM snapshot_ledger WHERE ledger_id = " + family))
                    .hasMessageContaining("immutable");

            // A family run: no BEFORE_RESTORE snapshot of its own, a parent run; neither is refused.
            sql.execute("INSERT INTO restore_run (ledger_id, kind, target_revision, revision, parent_run_id) VALUES ("
                    + family + ", 'BOOK', 3, 4, " + run + ")");
            assertThatThrownBy(() -> sql.execute("INSERT INTO restore_run (ledger_id, kind, target_revision, revision) "
                    + "VALUES (" + family + ", 'BOOK', 3, 4)")).hasMessageContaining("restore_run_before_or_parent");

            // A deleted budget takes its rows; the snapshot stays.
            sql.execute("DELETE FROM ledger WHERE id = " + other);
            assertThat(number(sql, "SELECT count(*) FROM snapshot_ledger WHERE ledger_id = " + other)).isZero();
            assertThat(number(sql, "SELECT count(*) FROM snapshot WHERE id = " + snapshot)).isEqualTo(1);

            // The erasure: its snapshots go with their family rows by the cascade.
            db.setAutoCommit(false);
            sql.execute("SELECT set_config('app.writer', 'delete-all', true)");
            sql.execute("DELETE FROM restore_run WHERE ledger_id IN (" + personal + ", " + family + ")");
            sql.execute("DELETE FROM snapshot WHERE ledger_id = " + personal);
            db.commit();
            db.setAutoCommit(true);
            assertThat(number(sql, "SELECT count(*) FROM snapshot_ledger WHERE snapshot_id = " + snapshot)).isZero();
        }
    }

    private static void migrate(String target) {
        Flyway.configure().dataSource(URL, POSTGRES.getUsername(), POSTGRES.getPassword()).schemas("app")
                .target(target).load().migrate();
    }

    private static long number(Statement sql, String query) throws SQLException {
        try (var rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
