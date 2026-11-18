package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PERF-1 (D-973): {@code RecordPayees} leaves out four join conditions because composite foreign keys imply them. If one
 * of the constraints is dropped or changes its columns, the rule would silently change what it finds, so each is
 * checked here through {@code pg_constraint}: its table, its columns and the referenced table and columns.
 */
class RecordPayeesConstraintsTests {

    /** Constraint name, referencing table, its columns, referenced table, its columns. */
    private record Expected(String name, String table, String columns, String referenced, String referencedColumns) { }

    private static final List<Expected> RELIED_ON = List.of(
            // rr.ledger_id = rl.family_ledger_id and pl.family_ledger_id = rr.ledger_id
            new Expected("family_entry_link_family_ledger_id_record_id_fkey", "family_entry_link",
                    "family_ledger_id,record_id", "family_record", "ledger_id,id"),
            // payer.ledger_id = rr.ledger_id
            new Expected("family_record_ledger_id_payer_member_id_fkey", "family_record",
                    "ledger_id,payer_member_id", "ledger_member", "ledger_id,id"),
            // pc.ledger_id = pe.ledger_id
            new Expected("journal_entry_ledger_id_payee_id_fkey", "journal_entry",
                    "ledger_id,payee_id", "counterparty", "ledger_id,id"));

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;

    private static String url(String database) {
        return "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), database);
    }

    @BeforeAll
    static void migrate() throws SQLException {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE record_payees_constraints");
            admin.createStatement().execute("CREATE DATABASE record_payees_constraints_broken");
        }
        for (String database : List.of("record_payees_constraints", "record_payees_constraints_broken")) {
            Flyway.configure().dataSource(url(database), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas("app").load().migrate();
        }
    }

    /** What is wrong with the constraints RecordPayees relies on; empty when all four conditions are implied. */
    private static List<String> problems(String database) throws SQLException {
        List<String> problems = new ArrayList<>();
        try (Connection db = DriverManager.getConnection(url(database), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            for (Expected e : RELIED_ON) {
                try (var statement = db.prepareStatement("""
                        SELECT c.conrelid::regclass::text AS tbl, c.confrelid::regclass::text AS ref,
                               (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                                FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS cols,
                               (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                                FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ord)
                                JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum) AS refcols
                        FROM pg_constraint c
                        WHERE c.contype = 'f' AND c.conname = ? AND c.connamespace = 'app'::regnamespace""")) {
                    statement.setString(1, e.name());
                    try (ResultSet rs = statement.executeQuery()) {
                        if (!rs.next()) {
                            problems.add("RecordPayees relies on the foreign key " + e.name() + ", which is missing");
                            continue;
                        }
                        String actual = rs.getString("tbl") + "(" + rs.getString("cols") + ") -> " + rs.getString("ref")
                                + "(" + rs.getString("refcols") + ")";
                        String expected = "app." + e.table() + "(" + e.columns() + ") -> app." + e.referenced() + "("
                                + e.referencedColumns() + ")";
                        if (!actual.equals(expected)) {
                            problems.add("RecordPayees relies on the foreign key " + e.name() + " being " + expected
                                    + ", but it is " + actual);
                        }
                    }
                }
            }
        }
        return problems;
    }

    @Test
    void theForeignKeysThatRecordPayeesRelyOnExist() throws SQLException {
        assertThat(problems("record_payees_constraints"))
                .as("RecordPayees (D-973) leaves out join conditions that these foreign keys imply")
                .isEmpty();
    }

    @Test
    void theCheckNoticesADroppedOrChangedConstraint() throws SQLException {
        try (Connection db = DriverManager.getConnection(url("record_payees_constraints_broken"),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            db.createStatement().execute("ALTER TABLE app.journal_entry DROP CONSTRAINT journal_entry_ledger_id_payee_id_fkey");
            db.createStatement().execute("ALTER TABLE app.family_record DROP CONSTRAINT family_record_ledger_id_payer_member_id_fkey");
            db.createStatement().execute("""
                    ALTER TABLE app.family_record ADD CONSTRAINT family_record_ledger_id_payer_member_id_fkey
                    FOREIGN KEY (payer_member_id) REFERENCES app.ledger_member (id)""");
        }
        assertThat(problems("record_payees_constraints_broken")).hasSize(2)
                .allSatisfy(p -> assertThat(p).contains("RecordPayees"))
                .anySatisfy(p -> assertThat(p).contains("journal_entry_ledger_id_payee_id_fkey").contains("missing"))
                .anySatisfy(p -> assertThat(p).contains("family_record_ledger_id_payer_member_id_fkey")
                        .contains("but it is"));
    }
}
