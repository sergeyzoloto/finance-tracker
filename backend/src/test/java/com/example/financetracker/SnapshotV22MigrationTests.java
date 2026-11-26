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
 * V22 on a ledger of V21 (HIST-4 part 1, D-1040, D-1194): additive; a snapshot can't be changed, and goes only with the
 * user's data (app.writer 'delete-all') or by the cascade with its ledger, as the image of V21 deletes one; the guard of
 * record_version stays as it was.
 */
class SnapshotV22MigrationTests {

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final String DATABASE = "snapshot_v22_migration_tests";
    private static final String URL = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);

    @Test
    void snapshotsAreImmutableAndGoWithTheirLedger() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            IntegrationTest.recreateDatabase(admin, DATABASE);
        }
        migrate("21");
        try (Connection db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            Statement sql = db.createStatement();
            long ledger = number(sql, "SELECT personal_ledger_id('v22-anna')");
            long other = number(sql, "SELECT personal_ledger_id('v22-ben')");
            sql.execute("UPDATE ledger SET revision = 4 WHERE id = " + ledger);

            migrate("22");

            // The image of V21 still writes its ledger as before.
            sql.execute("UPDATE ledger SET revision = 5 WHERE id = " + ledger);
            long snapshot = number(sql, "INSERT INTO snapshot (ledger_id, revision, name, kind) VALUES (" + ledger
                    + ", 5, 'One', 'MANUAL') RETURNING id");
            number(sql, "INSERT INTO snapshot (ledger_id, revision, name, kind) VALUES (" + other
                    + ", 0, 'Two', 'BEFORE_IMPORT') RETURNING id");
            assertThat(number(sql, "SELECT revision FROM ledger WHERE id = " + ledger)).isEqualTo(5);

            assertThatThrownBy(() -> sql.execute("UPDATE snapshot SET name = 'x' WHERE id = " + snapshot))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> sql.execute("DELETE FROM snapshot WHERE id = " + snapshot))
                    .hasMessageContaining("immutable");
            // With history on, the same: the guard isn't gated.
            db.setAutoCommit(false);
            sql.execute("SELECT set_config('app.history', 'on', true)");
            assertThatThrownBy(() -> sql.execute("DELETE FROM snapshot WHERE id = " + snapshot))
                    .hasMessageContaining("immutable");
            db.rollback();
            // record_version's guard is as V19 left it.
            long version = number(sql, """
                    INSERT INTO record_version (ledger_id, record_type, record_id, seq, revision, op, content,
                                                content_hash, content_ver, actor_kind, cause)
                    VALUES (%d, 'COUNTERPARTY', 1, 1, 5, 'CREATE', '{}'::jsonb, sha256('x'::bytea), 1, 'SYSTEM', 'EDIT')
                    RETURNING id""".formatted(ledger));
            db.commit();
            assertThatThrownBy(() -> sql.execute("UPDATE record_version SET seq = 2 WHERE id = " + version))
                    .hasMessageContaining("immutable");
            db.rollback();
            sql.execute("SELECT set_config('app.writer', 'delete-all', true)");
            assertThat(sql.executeUpdate("DELETE FROM snapshot WHERE id = " + snapshot)).isEqualTo(1);
            db.commit();
            db.setAutoCommit(true);

            // The ledger's deletion takes its snapshots by the cascade.
            sql.execute("DELETE FROM ledger WHERE id = " + other);
            assertThat(number(sql, "SELECT count(*) FROM snapshot WHERE ledger_id = " + other)).isZero();
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
