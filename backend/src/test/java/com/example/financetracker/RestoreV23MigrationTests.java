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
 * V23 on a ledger of V22 (HIST-4 part 2a, D-22, D-1040, D-1224): restore_run and restore_item are additive and
 * immutable as snapshots are (an item only with its run, or the erasure); they go with the user's data and with their
 * ledger; and the import tombstone skips the writer 'restore-archive' (an archive is no deletion, D-1038) while every
 * other writer still records it.
 */
class RestoreV23MigrationTests {

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final String DATABASE = "restore_v23_migration_tests";
    private static final String URL = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);

    @Test
    void restoreRunsAreImmutableGoWithTheirLedgerAndArchivesSkipTheImportTombstone() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            IntegrationTest.recreateDatabase(admin, DATABASE);
        }
        migrate("22");
        try (Connection db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Statement sql = db.createStatement();
            long ledger = number(sql, "SELECT personal_ledger_id('v23-anna')");
            long other = number(sql, "SELECT personal_ledger_id('v23-ben')");
            long snapshot = number(sql, "INSERT INTO snapshot (ledger_id, revision, name, kind) VALUES (" + ledger
                    + ", 0, 'Before', 'MANUAL') RETURNING id");
            long snapshotOther = number(sql, "INSERT INTO snapshot (ledger_id, revision, name, kind) VALUES (" + other
                    + ", 0, 'Other', 'MANUAL') RETURNING id");

            migrate("23");

            // The image of V22 still writes its ledger and snapshots as before.
            sql.execute("UPDATE ledger SET revision = 1 WHERE id = " + ledger);
            long run = number(sql, """
                    INSERT INTO restore_run (ledger_id, kind, snapshot_id, target_revision, revision, before_snapshot_id)
                    VALUES (%d, 'SNAPSHOT', %d, 0, 1, %d) RETURNING id""".formatted(ledger, snapshot, snapshot));
            sql.execute("INSERT INTO restore_item (run_id, record_type, record_id, outcome) VALUES (" + run
                    + ", 'ACCOUNT', 1, 'DELETED')");
            long otherRun = number(sql, """
                    INSERT INTO restore_run (ledger_id, kind, snapshot_id, target_revision, revision, before_snapshot_id)
                    VALUES (%d, 'SNAPSHOT', %d, 0, 1, %d) RETURNING id""".formatted(other, snapshotOther,
                    snapshotOther));

            assertThatThrownBy(() -> sql.execute("UPDATE restore_run SET kind = 'VERSION' WHERE id = " + run))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> sql.execute("DELETE FROM restore_run WHERE id = " + run))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> sql.execute("UPDATE restore_item SET outcome = 'RESTORED' WHERE run_id = " + run))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> sql.execute("DELETE FROM restore_item WHERE run_id = " + run))
                    .hasMessageContaining("immutable");
            // The same with history on: the guards aren't gated by app.history (as snapshot's aren't).
            db.setAutoCommit(false);
            sql.execute("SELECT set_config('app.history', 'on', true)");
            assertThatThrownBy(() -> sql.execute("DELETE FROM restore_run WHERE id = " + run))
                    .hasMessageContaining("immutable");
            db.rollback();
            db.setAutoCommit(true);

            // The erasure of the user's data (app.writer delete-all) may delete them, items first.
            db.setAutoCommit(false);
            sql.execute("SELECT set_config('app.writer', 'delete-all', true)");
            assertThat(sql.executeUpdate("DELETE FROM restore_run WHERE id = " + otherRun)).isEqualTo(1);
            db.commit();
            db.setAutoCommit(true);
            assertThat(number(sql, "SELECT count(*) FROM restore_run WHERE id = " + otherRun)).isZero();

            // Precondition: the ledger holds a snapshot, a run that names it and an item of that run.
            assertThat(number(sql, "SELECT count(*) FROM snapshot WHERE ledger_id = " + ledger)).isPositive();
            assertThat(number(sql, "SELECT count(*) FROM restore_run WHERE id = " + run + " AND ledger_id = " + ledger
                    + " AND (snapshot_id = " + snapshot + " OR before_snapshot_id = " + snapshot + ")")).isEqualTo(1);
            assertThat(number(sql, "SELECT count(*) FROM restore_item WHERE run_id = " + run)).isPositive();
            // No writer is set: app.writer was local to the committed transaction.
            try (var rs = sql.executeQuery("SELECT coalesce(current_setting('app.writer', true), '')")) {
                rs.next();
                assertThat(rs.getString(1)).isEmpty();
            }

            // The plain statement of the previous image and the runbook: the ledger's deletion takes its snapshots,
            // its runs and their items by the cascade (the runs' NO ACTION foreign keys to snapshot are checked at
            // the statement's end, when both are gone).
            sql.execute("DELETE FROM ledger WHERE id = " + ledger);
            assertThat(number(sql, "SELECT count(*) FROM snapshot WHERE ledger_id = " + ledger)).isZero();
            assertThat(number(sql, "SELECT count(*) FROM restore_run WHERE ledger_id = " + ledger)).isZero();
            assertThat(number(sql, "SELECT count(*) FROM restore_item WHERE run_id = " + run)).isZero();

            tombstoneSkipsRestoreArchive(db, sql);
        }
    }

    /** An imported entry deleted by the restore's archive writes no tombstone; any other delete writes one. */
    private static void tombstoneSkipsRestoreArchive(Connection db, Statement sql) throws SQLException {
        long ledger = number(sql, "SELECT personal_ledger_id('v23-cara')");
        String hash = "a".repeat(64);
        long cash = number(sql, "INSERT INTO account (user_id, ledger_id, code, name, type, default_currency) "
                + "VALUES ('v23-cara', " + ledger + ", 'CASH', 'Cash', 'ASSET', 'EUR') RETURNING id");
        long equity = number(sql, "INSERT INTO account (user_id, ledger_id, code, name, type, default_currency) "
                + "VALUES ('v23-cara', " + ledger + ", 'OPENING', 'Opening', 'EQUITY', 'EUR') RETURNING id");
        long first = importedEntry(db, sql, ledger, cash, equity, hash, "ref-1");
        long second = importedEntry(db, sql, ledger, cash, equity, hash, "ref-2");

        db.setAutoCommit(false);
        sql.execute("SELECT set_config('app.writer', 'restore-archive', true)");
        sql.execute("DELETE FROM journal_entry WHERE id = " + first);
        db.commit();
        db.setAutoCommit(true);
        assertThat(number(sql, "SELECT count(*) FROM imported_entry_deleted WHERE ledger_id = " + ledger
                + " AND external_ref = 'ref-1'")).isZero();

        db.setAutoCommit(false);
        sql.execute("SELECT set_config('app.writer', '', true)");
        sql.execute("DELETE FROM journal_entry WHERE id = " + second);
        db.commit();
        db.setAutoCommit(true);
        assertThat(number(sql, "SELECT count(*) FROM imported_entry_deleted WHERE ledger_id = " + ledger
                + " AND external_ref = 'ref-2'")).isEqualTo(1);
    }

    private static long importedEntry(Connection db, Statement sql, long ledger, long cash, long equity, String hash,
            String ref) throws SQLException {
        db.setAutoCommit(false);
        long entry = number(sql, """
                INSERT INTO journal_entry (user_id, ledger_id, entry_date, kind, import_hash, import_version,
                                           external_ref)
                VALUES ('v23-cara', %d, DATE '2026-09-10', 'TRANSFER', '%s', 1, '%s')
                RETURNING id""".formatted(ledger, hash, ref));
        sql.execute("INSERT INTO posting (entry_id, line_no, account_id, currency, amount) VALUES (" + entry + ", 1, " + cash
                + ", 'EUR', 5)");
        sql.execute("INSERT INTO posting (entry_id, line_no, account_id, currency, amount) VALUES (" + entry + ", 2, " + equity
                + ", 'EUR', -5)");
        db.commit();
        db.setAutoCommit(true);
        return entry;
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
