package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.family.FamilySwitch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V11 on the records of before it (F8a; ADR 0004, "Existing records"). A database at V10 holds a family ledger with
 * records as V10's code writes them, with their shares, posted entries and links: an expense in the base currency, one
 * paid in dollars and converted at the ECB's rate, one with its euros entered and paid by a member without an account,
 * and a settlement whose other side waits on "Specify later". V10's own statements give the balances and the integrity
 * check's family figures; then the application starts on that database, Flyway applies V11, and the application's
 * answers give the same numbers, with every posted row unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = FamilySwitch.PROPERTY + "=true")
@Import(IntegrationTest.Clocks.class)
class FamilyRecordsV11MigrationTests {

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final String DATABASE = "family_v11_migration_tests";
    private static final String URL = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(),
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), DATABASE);

    private static final String ALICE = "a1111111-1111-1111-1111-111111111111";
    private static final String BOB = "b2222222-2222-2222-2222-222222222222";

    /** What V10's statements gave, before V11: the family ledger, every member's balance, every posted row. */
    private static final long FAMILY;
    private static final Map<String, BigDecimal> BALANCES_BEFORE;
    private static final Map<String, BigDecimal> DEBT_BEFORE;
    private static final String POSTED_BEFORE;

    static {
        try {
            try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword())) {
                admin.createStatement().execute("CREATE DATABASE " + DATABASE);
            }
            Flyway.configure().dataSource(URL, POSTGRES.getUsername(), POSTGRES.getPassword()).schemas("app")
                    .target("10").load().migrate();
            try (Connection db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(),
                    POSTGRES.getPassword())) {
                db.setAutoCommit(false);
                FAMILY = new V10Rows(db).write();
                db.commit();
                BALANCES_BEFORE = v10Balances(db, FAMILY);
                DEBT_BEFORE = v10DebtAccounts(db, FAMILY);
                POSTED_BEFORE = posted(db);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.keycloak.issuer-url", IntegrationTest.KEYCLOAK::issuer);
        registry.add("app.keycloak.client-id", () -> FakeKeycloak.CLIENT_ID);
        registry.add("app.keycloak.client-secret", () -> FakeKeycloak.CLIENT_SECRET);
        registry.add("app.rates.ecb.enabled", () -> "false");
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void theRecordsOfBeforeV11KeepTheirBalancesPostingsAndIntegrity() throws Exception {
        try (Connection db = DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            assertThat(strings(db, "SELECT version FROM flyway_schema_history WHERE success "
                    + "ORDER BY installed_rank DESC LIMIT 1")).containsExactly("11");
            // Every record has its ledger's base currency, which its amount and shares were in.
            assertThat(strings(db, "SELECT r.currency || ' ' || l.base_currency FROM family_record r "
                    + "JOIN ledger l ON l.id = r.ledger_id ORDER BY r.id"))
                    .containsExactly("EUR EUR", "EUR EUR", "EUR EUR", "EUR EUR");
            assertThat(posted(db)).isEqualTo(POSTED_BEFORE);
        }
        // The family balances, as V10 computed them.
        JsonNode balances = get("/api/family-ledgers/" + FAMILY + "/balances", ALICE);
        Map<String, BigDecimal> after = new LinkedHashMap<>();
        balances.path("members").forEach(m -> after.put(m.path("displayName").asText(),
                new BigDecimal(m.path("balance").asText())));
        assertThat(after).isEqualTo(BALANCES_BEFORE);
        assertThat(BALANCES_BEFORE).containsEntry("Alice", new BigDecimal("22.54"))
                .containsEntry("Bob", new BigDecimal("-7.47")).containsEntry("Sam", new BigDecimal("-15.07"));
        assertThat(balances.path("currency").asText()).isEqualTo("EUR");
        // The integrity check: each debt account shows its member's family balance, so no row for the family budget, as
        // before; and each personal ledger adds up.
        for (String sub : List.of(ALICE, BOB)) {
            assertThat(get("/api/reports/integrity", sub)).as(sub).isEmpty();
        }
        assertThat(DEBT_BEFORE).containsEntry("Alice", BALANCES_BEFORE.get("Alice"))
                .containsEntry("Bob", BALANCES_BEFORE.get("Bob"));
        // The dollar expense reads as it did: 50.00 EUR, paid with 54.00 USD at the ECB's rate.
        JsonNode records = get("/api/family-ledgers/" + FAMILY + "/records", BOB).path("content");
        JsonNode dollars = null;
        for (JsonNode record : records) {
            if (record.path("originalCurrency").asText().equals("USD") && record.has("rate")) {
                dollars = record;
            }
        }
        assertThat(dollars).isNotNull();
        assertThat(dollars.path("amount").asText()).isEqualTo("50.00");
        assertThat(dollars.path("currency").asText()).isEqualTo("EUR");
        assertThat(dollars.path("originalAmount").asText()).isEqualTo("54.00");
        assertThat(dollars.path("rateSource").asText()).isEqualTo("ECB");
        assertThat(dollars.path("yourPayment").path("amount").asText()).isEqualTo("54.00");
        assertThat(dollars.path("yourPayment").path("currency").asText()).isEqualTo("USD");
    }

    private JsonNode get(String uri, String sub) throws Exception {
        var result = mvc.get().uri(uri).with(IntegrationTest.member(sub)).exchange();
        assertThat(result.getResponse().getStatus()).as(uri).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Every member's family balance as V10's FamilyRecordService.balances computed it, in the base currency (at
     * {@code 6fdc989}).
     */
    private static Map<String, BigDecimal> v10Balances(Connection db, long family) throws SQLException {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        try (PreparedStatement statement = db.prepareStatement("""
                SELECT m.display_name,
                       coalesce((SELECT sum(CASE r.type WHEN 'EXPENSE' THEN s.amount ELSE -s.amount END)
                                 FROM family_share s JOIN family_record r ON r.id = s.record_id
                                 WHERE s.member_id = m.id AND r.ledger_id = ? AND r.deleted_at IS NULL), 0)
                       - coalesce((SELECT sum(CASE r.type WHEN 'INCOME' THEN -r.base_amount ELSE r.base_amount END)
                                   FROM family_record r
                                   WHERE r.payer_member_id = m.id AND r.ledger_id = ?
                                     AND r.deleted_at IS NULL), 0)
                       + coalesce((SELECT sum(r.base_amount) FROM family_record r
                                   WHERE r.payee_member_id = m.id AND r.ledger_id = ?
                                     AND r.deleted_at IS NULL), 0) AS balance
                FROM ledger_member m
                WHERE m.ledger_id = ?
                ORDER BY m.join_date, m.id""")) {
            for (int i = 1; i <= 4; i++) {
                statement.setLong(i, family);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    balances.put(rows.getString(1), rows.getBigDecimal(2).setScale(2));
                }
            }
        }
        return balances;
    }

    /**
     * What each member's debt account shows, in the base currency, as V10's integrity check reads it
     * (ReportService.familyDifference at {@code 6fdc989}); none of its postings is in another currency.
     */
    private static Map<String, BigDecimal> v10DebtAccounts(Connection db, long family) throws SQLException {
        Map<String, BigDecimal> debts = new LinkedHashMap<>();
        try (PreparedStatement statement = db.prepareStatement("""
                SELECT m.display_name, coalesce(-sum(p.amount) FILTER (WHERE p.currency = l.base_currency), 0),
                       count(p.id) FILTER (WHERE p.currency <> l.base_currency)
                FROM ledger_member m
                JOIN ledger l ON l.id = m.ledger_id
                JOIN ledger_member own ON own.user_sub = m.user_sub AND own.ledger_type = 'PERSONAL'
                JOIN account a ON a.ledger_id = own.ledger_id AND a.family_ledger_id = l.id
                LEFT JOIN posting p ON p.account_id = a.id
                WHERE m.ledger_id = ?
                GROUP BY m.id, m.display_name
                ORDER BY m.id""")) {
            statement.setLong(1, family);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    assertThat(rows.getLong(3)).isZero();
                    debts.put(rows.getString(1), rows.getBigDecimal(2).setScale(2));
                }
            }
        }
        return debts;
    }

    /** Every entry, posting and link of the database, as text. */
    private static String posted(Connection db) throws SQLException {
        return strings(db, """
                SELECT (SELECT string_agg(e::text, '|' ORDER BY e.id) FROM journal_entry e)
                    || (SELECT string_agg(p::text, '|' ORDER BY p.id) FROM posting p)
                    || (SELECT string_agg(l::text, '|' ORDER BY l.id) FROM family_entry_link l)
                    || (SELECT string_agg(s::text, '|' ORDER BY s.record_id, s.member_id) FROM family_share s)
                    || (SELECT string_agg(a::text, '|' ORDER BY a.id) FROM account a)""").getFirst();
    }

    private static List<String> strings(Connection db, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = db.createStatement().executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    /**
     * The rows as V10's code writes them: two users with their personal ledgers, a family ledger "Home" in euros with
     * Alice, Bob and Sam (no account), and four records with what the posting service posted for them.
     */
    private static final class V10Rows {

        private final Connection db;
        private int lineNo;

        V10Rows(Connection db) {
            this.db = db;
        }

        long write() throws SQLException {
            Map<String, Map<String, Long>> accounts = new LinkedHashMap<>();
            Map<String, Long> personal = new LinkedHashMap<>();
            for (String sub : List.of(ALICE, BOB)) {
                String name = sub.equals(ALICE) ? "Alice" : "Bob";
                update("INSERT INTO users (keycloak_id, email, display_name) VALUES (?, ?, ?)", sub,
                        name.toLowerCase() + "@example.com", name);
                update("INSERT INTO user_settings (user_id) VALUES (?)", sub);
                personal.put(sub, number("SELECT personal_ledger_id(?)", sub));
                Map<String, Long> own = new LinkedHashMap<>();
                own.put("CASH", account(sub, "CASH", "ASSET", "EUR", false));
                own.put("DOLLARS", account(sub, "DOLLARS", "ASSET", "USD", false));
                own.put("UNALLOCATED", account(sub, "UNALLOCATED", "EQUITY", null, false));
                own.put("OPENING_BALANCE", account(sub, "OPENING_BALANCE", "EQUITY", null, true));
                own.put("FX_EXCHANGE", account(sub, "FX_EXCHANGE", "EQUITY", null, true));
                accounts.put(sub, own);
            }
            long family = number("INSERT INTO ledger (type, name, base_currency, split_rule, start_date) "
                    + "VALUES ('SHARED', 'Home', 'EUR', 'EQUAL', DATE '2026-09-01') RETURNING id");
            long alice = member(family, ALICE, "Alice", "OWNER");
            long bob = member(family, BOB, "Bob", "MEMBER");
            long sam = member(family, null, "Sam", "MEMBER");
            long groceries = number("INSERT INTO category (ledger_id, code, name, type) "
                    + "VALUES (?, 'GROCERIES', 'Groceries', 'EXPENSE') RETURNING id", family);

            // What the posting service writes, with app.writer set.
            number("SELECT length(set_config('app.writer', 'family-posting', true))");
            Map<String, Long> debt = new LinkedHashMap<>();
            for (String sub : List.of(ALICE, BOB)) {
                debt.put(sub, number("""
                        INSERT INTO account (user_id, ledger_id, code, name, type, default_currency, is_system,
                                             family_ledger_id)
                        VALUES (?, ?, ?, 'Debt to family budget: Home', 'LIABILITY', 'EUR', TRUE, ?) RETURNING id""",
                        sub, personal.get(sub), "FAMILY_DEBT_" + family, family));
            }
            long bobsPlaceholder = number("""
                    INSERT INTO account (user_id, ledger_id, code, name, type, is_system)
                    VALUES (?, ?, 'UNSPECIFIED_PAYMENTS', 'Payments without a specified account', 'ASSET', TRUE)
                    RETURNING id""", BOB, personal.get(BOB));

            // 1. Groceries for 10.01 EUR, paid by Alice with cash, split equally among the three (D-12: the payer
            // takes the remainder).
            long one = record(family, "EXPENSE", "2026-09-05", groceries, alice, null, "10.01", "EUR", "10.01", null,
                    null, null, "EQUAL", alice);
            share(family, one, alice, "3.35", alice);
            share(family, one, bob, "3.33", alice);
            share(family, one, sam, "3.33", alice);
            shareEntry(ALICE, accounts, debt, family, one, alice, "2026-09-05", "3.35", groceries);
            shareEntry(BOB, accounts, debt, family, one, bob, "2026-09-05", "3.33", groceries);
            ownLedger(personal.get(ALICE));
            long payment = entry(ALICE, "FAMILY_PAYMENT", "2026-09-05");
            post(payment, accounts.get(ALICE).get("CASH"), "EUR", "-10.01", null);
            post(payment, debt.get(ALICE), "EUR", "10.01", null);
            link(payment, family, alice, one, "PAYMENT", false);

            // 2. Groceries for 54.00 USD, paid by Bob from his dollars, 50.00 EUR at the ECB's rate (F4e), split by
            // amounts between Alice and Bob; his payment through his FX_EXCHANGE.
            long two = record(family, "EXPENSE", "2026-09-10", groceries, bob, null, "54.00", "USD", "50.00",
                    "0.925925925926", "ECB", "2026-09-10", "AMOUNT", bob);
            share(family, two, alice, "25.00", bob);
            share(family, two, bob, "25.00", bob);
            shareEntry(ALICE, accounts, debt, family, two, alice, "2026-09-10", "25.00", groceries);
            shareEntry(BOB, accounts, debt, family, two, bob, "2026-09-10", "25.00", groceries);
            ownLedger(personal.get(BOB));
            long dollars = entry(BOB, "FAMILY_PAYMENT", "2026-09-10");
            post(dollars, accounts.get(BOB).get("DOLLARS"), "USD", "-54.00", null);
            post(dollars, accounts.get(BOB).get("FX_EXCHANGE"), "USD", "54.00", null);
            post(dollars, accounts.get(BOB).get("FX_EXCHANGE"), "EUR", "-50.00", null);
            post(dollars, debt.get(BOB), "EUR", "50.00", null);
            link(dollars, family, bob, two, "PAYMENT", false);

            // 3. 20.00 USD paid by Sam, who has no account, with its 18.40 EUR entered: no payment entry.
            long three = record(family, "EXPENSE", "2026-09-12", groceries, sam, null, "20.00", "USD", "18.40", null,
                    "ENTERED", null, "AMOUNT", alice);
            share(family, three, alice, "9.20", alice);
            share(family, three, bob, "9.20", alice);
            shareEntry(ALICE, accounts, debt, family, three, alice, "2026-09-12", "9.20", groceries);
            shareEntry(BOB, accounts, debt, family, three, bob, "2026-09-12", "9.20", groceries);

            // 4. Alice pays Bob 5.00 EUR from her cash; his side waits on "Specify later" (D-24).
            long four = record(family, "SETTLEMENT", "2026-09-15", null, alice, bob, "5.00", "EUR", "5.00", null,
                    null, null, null, alice);
            ownLedger(personal.get(ALICE));
            long paid = entry(ALICE, "FAMILY_SETTLEMENT", "2026-09-15");
            post(paid, accounts.get(ALICE).get("CASH"), "EUR", "-5.00", null);
            post(paid, debt.get(ALICE), "EUR", "5.00", null);
            link(paid, family, alice, four, "SETTLEMENT", false);
            long received = entry(BOB, "FAMILY_SETTLEMENT", "2026-09-15");
            post(received, bobsPlaceholder, "EUR", "5.00", null);
            post(received, debt.get(BOB), "EUR", "-5.00", null);
            link(received, family, bob, four, "SETTLEMENT", true);
            number("SELECT length(set_config('app.writer', '', true))");
            return family;
        }

        private long account(String sub, String code, String type, String currency, boolean system)
                throws SQLException {
            return number("INSERT INTO account (user_id, code, name, type, default_currency, is_system) "
                    + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id", sub, code, code, type, currency, system);
        }

        private long member(long family, String sub, String name, String role) throws SQLException {
            return number("""
                    INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date)
                    VALUES (?, 'SHARED', ?, ?, ?, 'ACTIVE', DATE '2026-09-01') RETURNING id""", family, sub, name, role);
        }

        private long record(long family, String type, String date, Long category, long payer, Long payee,
                String original, String originalCurrency, String base, String rate, String source, String rateDate,
                String split, long author) throws SQLException {
            return number("""
                    INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id,
                        payee_member_id, original_amount, original_currency, base_amount, base_rate, base_rate_source,
                        base_rate_date, split_method, author_member_id, updated_by_member_id)
                    VALUES (?, ?, CAST(? AS date), ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS date), ?, ?, ?) RETURNING id""",
                    family, type, date, category, payer, payee, new BigDecimal(original), originalCurrency,
                    new BigDecimal(base), rate == null ? null : new BigDecimal(rate), source, rateDate, split, author,
                    author);
        }

        private void share(long family, long record, long member, String amount, long by) throws SQLException {
            update("INSERT INTO family_share (ledger_id, record_id, member_id, amount, updated_by_member_id) "
                    + "VALUES (?, ?, ?, ?, ?)", family, record, member, new BigDecimal(amount), by);
        }

        private void shareEntry(String sub, Map<String, Map<String, Long>> accounts, Map<String, Long> debt,
                long family, long record, long member, String date, String amount, long category) throws SQLException {
            long entry = entry(sub, "FAMILY_SHARE", date);
            post(entry, accounts.get(sub).get("UNALLOCATED"), "EUR", amount, category);
            post(entry, debt.get(sub), "EUR", new BigDecimal(amount).negate().toPlainString(), null);
            link(entry, family, member, record, "SHARE", true);
        }

        /** The ledger the writer names as the acting member's own, for their own account (V7, V8). */
        private void ownLedger(long ledger) throws SQLException {
            number("SELECT length(set_config('app.own_ledger', ?, true))", String.valueOf(ledger));
        }

        private long entry(String sub, String kind, String date) throws SQLException {
            lineNo = 0;
            return number("INSERT INTO journal_entry (user_id, entry_date, kind) VALUES (?, CAST(? AS date), ?) "
                    + "RETURNING id", sub, date, kind);
        }

        private void post(long entry, long account, String currency, String amount, Long category)
                throws SQLException {
            update("INSERT INTO posting (entry_id, line_no, account_id, currency, amount, category_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", entry, lineNo++, account, currency, new BigDecimal(amount), category);
        }

        private void link(long entry, long family, long member, long record, String type, boolean systemOwned)
                throws SQLException {
            update("INSERT INTO family_entry_link (entry_id, family_ledger_id, member_id, record_id, link_type, "
                    + "system_owned) VALUES (?, ?, ?, ?, ?, ?)", entry, family, member, record, type, systemOwned);
        }

        private void update(String sql, Object... params) throws SQLException {
            try (PreparedStatement statement = prepare(sql, params)) {
                statement.executeUpdate();
            }
        }

        private long number(String sql, Object... params) throws SQLException {
            try (PreparedStatement statement = prepare(sql, params); ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
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
}
