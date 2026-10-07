package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NOT_NULL_VIOLATION = "23502";

    private static final PostgreSQLContainer<?> POSTGRES = IntegrationTest.POSTGRES;
    private static final String URL = "jdbc:postgresql://%s:%d/ledger_schema_tests"
            .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));

    private static MigrateResult migration;

    private final String user = UUID.randomUUID().toString();
    private Connection db;
    private long cash, card, loans, unallocated, familyDebt, fxExchange, groceries, borrower;
    /** Unique across all entries, so that a posting moved to another entry keeps a free line number. */
    private int lineNo;
    /** The members {@link #member} named so far. */
    private int members;

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
        db = connect();
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

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL + "?currentSchema=app", POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void migrationsApplyToAnEmptyDatabase() throws SQLException {
        assertThat(migration.success).isTrue();
        assertThat(migration.initialSchemaVersion).isNull();
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = 'app'"))
                .containsExactlyInAnyOrder("flyway_schema_history",
                        "users", "categories", "transactions", // V1
                        "account", "category", "counterparty", "journal_entry", "posting", "exchange_rate",
                        "import_batch", "user_settings", // V2 to V4
                        "ledger", "ledger_member", // V5
                        "family_record", "family_share", "family_entry_link", "family_record_change", // V7
                        "ledger_invite"); // V9
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

    /** Two postings of one entry at the same position would make the order of its postings ambiguous (V3). */
    @Test
    void postingsOfAnEntryHaveDistinctLineNumbers() throws SQLException {
        long entry = expense("7.50");

        assertFails(() -> insert("""
                INSERT INTO posting (entry_id, line_no, account_id, currency, amount)
                SELECT entry_id, line_no, account_id, currency, amount
                FROM posting WHERE entry_id = ? AND account_id = ?""",
                entry, cash), UNIQUE_VIOLATION, "posting_entry_id_line_no_key");
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

    /**
     * Rates are units of a currency for one euro. The ECB's are shared, one per day and currency; a manual rate
     * belongs to a user, who can have one of their own for the same day.
     */
    @Test
    void exchangeRatesAreAgainstTheEuroAndManualOnesBelongToAUser() throws SQLException {
        String rate = "INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source, user_id) "
                + "VALUES (DATE '1999-01-04', ?, ?, 1.5, ?, ?)";
        update(rate, "EUR", "XAU", "ECB", null);
        update(rate, "EUR", "XAU", "MANUAL", user);
        update(rate, "EUR", "XAU", "MANUAL", UUID.randomUUID().toString());
        db.commit();

        assertFails(() -> update(rate, "EUR", "XAU", "ECB", null), UNIQUE_VIOLATION, "exchange_rate_key");
        db.rollback();
        assertFails(() -> update(rate, "EUR", "XAU", "MANUAL", user), UNIQUE_VIOLATION, "exchange_rate_key");
        db.rollback();
        assertFails(() -> update(rate, "EUR", "XAG", "MANUAL", null), CHECK_VIOLATION, "exchange_rate_owner_check");
        db.rollback();
        assertFails(() -> update(rate, "EUR", "XAG", "ECB", user), CHECK_VIOLATION, "exchange_rate_owner_check");
        db.rollback();
        assertFails(() -> update(rate, "XAG", "EUR", "ECB", null), CHECK_VIOLATION, "exchange_rate_euro_check");
        db.rollback();
        assertFails(() -> update(rate, "EUR", "XAG", "BANK", null), CHECK_VIOLATION, "exchange_rate_source_check");
        db.rollback();
        update("DELETE FROM exchange_rate WHERE quote_currency = 'XAU'");
        db.commit();
    }

    @Test
    void rowsNeverChangeOwner() {
        assertFails(() -> update("UPDATE account SET user_id = ? WHERE id = ?", UUID.randomUUID().toString(), cash),
                CHECK_VIOLATION, "account %d: user_id cannot change".formatted(cash));
    }

    /** Rows written without a ledger, as the code before V5 writes them, go to their user's personal ledger (V5). */
    @Test
    void rowsWrittenWithoutALedgerGoToTheirUsersPersonalLedger() throws SQLException {
        long entry = expense("1.00");
        long batch = importBatch(user);
        db.commit();

        long ledger = personalLedger(user);
        Map<String, Long> rows = Map.of("account", cash, "category", groceries, "counterparty", borrower,
                "journal_entry", entry, "import_batch", batch);
        for (var row : rows.entrySet()) {
            assertThat(number("SELECT ledger_id FROM " + row.getKey() + " WHERE id = ?", row.getValue()))
                    .as(row.getKey()).isEqualTo(ledger);
        }
        assertThat(strings("SELECT type FROM ledger WHERE id = ?", ledger)).containsExactly("PERSONAL");
        // Its one member is the user, who owns it. The user has no users row here, so no name.
        assertThat(strings("""
                SELECT concat_ws(' ', user_sub, role, status, '"' || display_name || '"') FROM ledger_member
                WHERE ledger_id = ?""", ledger)).containsExactly(user + " OWNER ACTIVE \"\"");
        assertThat(number("SELECT count(*) FROM ledger_member WHERE user_sub = ?", user)).isOne();
    }

    @Test
    void aNewUsersPersonalLedgerIsNamedAfterThemAndGoesWithTheirUsersRow() throws SQLException {
        String carol = UUID.randomUUID().toString();
        update("INSERT INTO users (keycloak_id, display_name) VALUES (?, 'Carol')", carol);
        long carolsCash = account(carol, "CASH", "ASSET", false);
        db.commit();
        long ledger = personalLedger(carol);
        assertThat(strings("SELECT display_name FROM ledger_member WHERE ledger_id = ?", ledger))
                .containsExactly("Carol");

        // While the ledger has rows, the users row can't go: "Delete all my data" deletes it last.
        assertFails(() -> update("DELETE FROM users WHERE keycloak_id = ?", carol), FOREIGN_KEY_VIOLATION,
                "account_ledger_id_fkey");
        db.rollback();
        update("DELETE FROM account WHERE id = ?", carolsCash);
        update("DELETE FROM users WHERE keycloak_id = ?", carol);
        db.commit();

        assertThat(number("SELECT count(*) FROM ledger WHERE id = ?", ledger)).isZero();
        assertThat(number("SELECT count(*) FROM ledger_member WHERE user_sub = ?", carol)).isZero();
        assertThat(number("SELECT count(*) FROM ledger_member WHERE user_sub = ?", user)).isOne();
    }

    /** D-4: each user has one personal ledger, and a personal ledger has one member, its owner. */
    @Test
    void eachUserHasOnePersonalLedgerWithOneMember() throws SQLException {
        long ledger = personalLedger(user);

        long second = insert("INSERT INTO ledger (type) VALUES ('PERSONAL')");
        assertFails(() -> member(second, "PERSONAL", user, "OWNER"), UNIQUE_VIOLATION,
                "ledger_member_personal_sub_key");
        db.rollback();
        assertFails(() -> member(ledger, "PERSONAL", UUID.randomUUID().toString(), "OWNER"), UNIQUE_VIOLATION,
                "ledger_member_personal_ledger_key");
        db.rollback();
        long withoutOwner = insert("INSERT INTO ledger (type) VALUES ('PERSONAL')");
        assertFails(() -> member(withoutOwner, "PERSONAL", UUID.randomUUID().toString(), "MEMBER"), CHECK_VIOLATION,
                "ledger_member_personal_owner_check");
        db.rollback();
        // Without a member, a personal ledger doesn't commit, and its member can't leave it.
        long empty = insert("INSERT INTO ledger (type) VALUES ('PERSONAL')");
        assertFails(db::commit, CHECK_VIOLATION, "personal ledger %d has no member".formatted(empty));
        update("DELETE FROM ledger_member WHERE ledger_id = ?", ledger);
        assertFails(db::commit, CHECK_VIOLATION, "personal ledger %d has no member".formatted(ledger));
        // Nor can it move to another ledger.
        long family = sharedLedger();
        assertFails(() -> update("UPDATE ledger_member SET ledger_id = ?, ledger_type = 'SHARED' WHERE ledger_id = ?",
                family, ledger), CHECK_VIOLATION, "the ledger cannot change");
    }

    /** D-4: a sub is at most once in a ledger. Members without an account are members of their own. */
    @Test
    void aSubIsInALedgerAtMostOnce() throws SQLException {
        long family = sharedLedger();
        member(family, "SHARED", user, "OWNER");
        seat(family);
        seat(family);
        db.commit();

        assertFails(() -> member(family, "SHARED", user, "MEMBER"), UNIQUE_VIOLATION,
                "ledger_member_ledger_id_user_sub_key");
    }

    /** D-22: a personal ledger never becomes shared, nor the other way round. */
    @Test
    void aLedgersTypeNeverChanges() throws SQLException {
        long family = sharedLedger();
        db.commit();
        long ledger = personalLedger(user);

        assertFails(() -> update("UPDATE ledger SET type = 'PERSONAL' WHERE id = ?", family), CHECK_VIOLATION,
                "ledger %d: type cannot change".formatted(family));
        db.rollback();
        assertFails(() -> update("UPDATE ledger SET type = 'SHARED', name = 'Family', base_currency = 'EUR' "
                + "WHERE id = ?", ledger), CHECK_VIOLATION, "ledger %d: type cannot change".formatted(ledger));
        db.rollback();
        assertFails(() -> update("UPDATE ledger_member SET ledger_type = 'SHARED' WHERE ledger_id = ?", ledger),
                CHECK_VIOLATION, "the ledger cannot change");
    }

    /**
     * A seat gets its sub, and may get an earlier join date, when it is claimed (D-18); after that the sub and the
     * join date stay, but for a member who left and returns (D-26). A former member stays as they are (D-20).
     */
    @Test
    void aMembershipKeepsItsUserAndItsJoinDate() throws SQLException {
        long family = sharedLedger();
        long seat = seat(family);
        db.commit();

        update("UPDATE ledger_member SET user_sub = ?, join_date = DATE '2025-12-01' WHERE id = ?", user, seat);
        db.commit();
        assertFails(() -> update("UPDATE ledger_member SET user_sub = ? WHERE id = ?", UUID.randomUUID().toString(),
                seat), CHECK_VIOLATION, "ledger member %d: user_sub cannot pass to another user".formatted(seat));
        db.rollback();
        assertFails(() -> update("UPDATE ledger_member SET join_date = DATE '2026-02-01' WHERE id = ?", seat),
                CHECK_VIOLATION, "ledger member %d: join_date cannot change once the member has joined"
                        .formatted(seat));
        db.rollback();

        update("UPDATE ledger_member SET status = 'LEFT', left_date = DATE '2026-03-01' WHERE id = ?", seat);
        db.commit();
        update("UPDATE ledger_member SET status = 'ACTIVE', left_date = NULL, join_date = DATE '2026-04-01' "
                + "WHERE id = ?", seat);
        db.commit();
        update("UPDATE ledger_member SET status = 'FORMER', user_sub = NULL, display_name = 'Former member', "
                + "left_date = DATE '2026-05-01' WHERE id = ?", seat);
        db.commit();
        assertFails(() -> update("UPDATE ledger_member SET status = 'ACTIVE', left_date = NULL WHERE id = ?", seat),
                CHECK_VIOLATION, "ledger member %d: a former member stays as they are".formatted(seat));
    }

    /**
     * A row with a user is in that user's personal ledger, and in no other: not another user's, and not a family
     * ledger, which holds only family categories (V6, familyLedgerHoldsOnlyCategoriesWithoutAUser).
     */
    @Test
    void aRowIsInThePersonalLedgerOfItsUser() throws SQLException {
        String other = UUID.randomUUID().toString();
        account(other, "CASH", "ASSET", false);
        long family = sharedLedger();
        long batch = importBatch(user);
        db.commit();

        long othersLedger = personalLedger(other);
        assertFails(() -> insert("INSERT INTO account (user_id, ledger_id, code, name, type) "
                + "VALUES (?, ?, 'BANK', 'Bank', 'ASSET')", user, othersLedger), FOREIGN_KEY_VIOLATION,
                "ledger %d is not the personal ledger of its user".formatted(othersLedger));
        db.rollback();
        assertFails(() -> insert("INSERT INTO account (user_id, ledger_id, code, name, type) "
                + "VALUES (?, ?, 'BANK', 'Bank', 'ASSET')", user, family), FOREIGN_KEY_VIOLATION,
                "ledger %d is a family ledger, which holds only categories without user_id".formatted(family));
        db.rollback();
        // An import batch's user_id was never fixed, but it can't take the batch into another user's ledger.
        long ledger = personalLedger(user);
        assertFails(() -> update("UPDATE import_batch SET user_id = ? WHERE id = ?", other, batch),
                FOREIGN_KEY_VIOLATION, "import_batch %d: ledger %d is not the personal ledger of its user"
                        .formatted(batch, ledger));
        db.rollback();
        insert("INSERT INTO account (user_id, ledger_id, code, name, type) VALUES (?, ?, 'BANK', 'Bank', 'ASSET')",
                user, ledger);
        db.commit();
    }

    @Test
    void rowsNeverChangeLedger() throws SQLException {
        long entry = expense("1.00");
        long batch = importBatch(user);
        long elsewhere = number("SELECT personal_ledger_id(?)", UUID.randomUUID().toString());
        db.commit();

        Map<String, Long> rows = Map.of("account", cash, "category", groceries, "counterparty", borrower,
                "journal_entry", entry, "import_batch", batch);
        for (var row : rows.entrySet()) {
            assertFails(() -> update("UPDATE " + row.getKey() + " SET ledger_id = ? WHERE id = ?", elsewhere,
                    row.getValue()), CHECK_VIOLATION, "%s %d: ledger_id cannot change".formatted(row.getKey(),
                    row.getValue()));
            db.rollback();
        }
    }

    /**
     * A family ledger holds family categories, rows without a user, and nothing else yet (V6; ADR 0003, topics A and
     * F). A category without a user is in a family ledger, never in a personal one.
     */
    @Test
    void familyLedgerHoldsOnlyCategoriesWithoutAUser() throws SQLException {
        long family = sharedLedger();
        db.commit();
        // The same code as the user's own GROCERIES: codes are unique per ledger.
        long familyGroceries = insert("INSERT INTO category (ledger_id, code, name, type) "
                + "VALUES (?, 'GROCERIES', 'Groceries', 'EXPENSE')", family);
        db.commit();

        String refused = "ledger %d is a family ledger, which holds only categories without user_id".formatted(family);
        for (String insert : List.of(
                "INSERT INTO category (user_id, ledger_id, code, name, type) VALUES (?, ?, 'RENT', 'Rent', 'EXPENSE')",
                "INSERT INTO account (user_id, ledger_id, code, name, type) VALUES (?, ?, 'BANK', 'Bank', 'ASSET')",
                "INSERT INTO counterparty (user_id, ledger_id, name) VALUES (?, ?, 'Shop')",
                "INSERT INTO journal_entry (user_id, ledger_id, entry_date) VALUES (?, ?, DATE '2026-09-25')",
                "INSERT INTO import_batch (user_id, ledger_id, file_name, file_sha256, dry_run) "
                        + "VALUES (?, ?, 'transactions.csv', repeat('0', 64), TRUE)")) {
            assertFails(() -> insert(insert, user, family), FOREIGN_KEY_VIOLATION, refused);
            db.rollback();
        }
        assertFails(() -> update("UPDATE category SET user_id = ? WHERE id = ?", user, familyGroceries),
                FOREIGN_KEY_VIOLATION, refused);
        db.rollback();
        assertFails(() -> insert("INSERT INTO category (ledger_id, code, name, type) "
                + "VALUES (?, 'GROCERIES', 'Food', 'EXPENSE')", family), UNIQUE_VIOLATION, "category_ledger_id_code_key");
        db.rollback();

        long ledger = personalLedger(user);
        assertFails(() -> insert("INSERT INTO category (ledger_id, code, name, type) "
                + "VALUES (?, 'RENT', 'Rent', 'EXPENSE')", ledger), FOREIGN_KEY_VIOLATION,
                "ledger %d is not the personal ledger of its user".formatted(ledger));
        db.rollback();
        assertFails(() -> update("UPDATE category SET user_id = NULL WHERE id = ?", groceries), FOREIGN_KEY_VIOLATION,
                "ledger %d is not the personal ledger of its user".formatted(ledger));
        db.rollback();
        assertFails(() -> insert("INSERT INTO category (code, name, type) VALUES ('RENT', 'Rent', 'EXPENSE')"),
                NOT_NULL_VIOLATION, "a personal ledger needs a user");
    }

    /**
     * A personal entry may use a category of a family ledger in which its user is an ACTIVE member (V7; D-11, ADR 0003
     * topic C), and no other ledger's: not one they left, and not one they were never in.
     */
    @Test
    void aFamilyCategoryIsUsedOnlyByItsActiveMembers() throws SQLException {
        long family = sharedLedger();
        long membership = member(family, "SHARED", user, "OWNER");
        long familyGroceries = familyCategory(family, "GROCERIES", "EXPENSE");
        long elsewhere = sharedLedger();
        member(elsewhere, "SHARED", UUID.randomUUID().toString(), "OWNER");
        long notMine = familyCategory(elsewhere, "GROCERIES", "EXPENSE");
        db.commit();

        long entry = entry();
        post(entry, cash, "EUR", "-5.00");
        post(entry, unallocated, "EUR", "5.00", familyGroceries, null);
        db.commit();

        assertFails(() -> post(entry(), unallocated, "EUR", "5.00", notMine, null), FOREIGN_KEY_VIOLATION,
                "category %d belongs to another ledger".formatted(notMine));
        db.rollback();
        update("UPDATE ledger_member SET status = 'LEFT', role = 'MEMBER', left_date = DATE '2026-09-01' WHERE id = ?",
                membership);
        member(family, "SHARED", null, "MEMBER");
        db.commit();
        assertFails(() -> post(entry(), unallocated, "EUR", "5.00", familyGroceries, null), FOREIGN_KEY_VIOLATION,
                "category %d belongs to another ledger".formatted(familyGroceries));
    }

    /**
     * A family ledger's start date (D-27): set for a family ledger only, today for code that leaves it out, and fixed
     * once set. A record is dated on or after it, its category has its type, and at commit its shares add up to its
     * amount; a share goes to an ACTIVE member (V7; topic D). The base currency, fixed by the first record until V11, is
     * the main currency since then and changes with records (D-45), which keep their own currency.
     */
    @Test
    void familyRecordsFitTheirLedgerAndTheirShares() throws SQLException {
        long family = sharedLedger();
        assertThat(number("SELECT count(*) FROM ledger WHERE id = ? AND start_date = current_date", family)).isOne();
        assertFails(() -> insert("INSERT INTO ledger (type, start_date) VALUES ('PERSONAL', current_date)"),
                CHECK_VIOLATION, "ledger_start_date_check");
        db.rollback();
        family = sharedLedger();
        long anna = member(family, "SHARED", user, "OWNER", "Anna", null);
        long boris = seat(family);
        long groceriesOfFamily = familyCategory(family, "GROCERIES", "EXPENSE");
        long salary = familyCategory(family, "SALARY", "INCOME");
        db.commit();
        long ledger = family;
        assertFails(() -> update("UPDATE ledger SET start_date = start_date - 1 WHERE id = ?", ledger),
                CHECK_VIOLATION, "ledger %d: the start date cannot change".formatted(ledger));
        db.rollback();

        assertFails(() -> record(ledger, "current_date - 1", groceriesOfFamily, anna, "10.00"), CHECK_VIOLATION,
                "before its family ledger %d starts on".formatted(ledger));
        db.rollback();
        assertFails(() -> record(ledger, "current_date", salary, anna, "10.00"), CHECK_VIOLATION,
                "category %d is INCOME, and a record of type EXPENSE needs one of its type".formatted(salary));
        db.rollback();
        long record = record(ledger, "current_date", groceriesOfFamily, anna, "10.01");
        share(ledger, record, anna, "5.01", anna);
        share(ledger, record, boris, "5.01", anna);
        assertFails(db::commit, CHECK_VIOLATION,
                "family record %d: the shares sum to 10.0200, not to the amount 10.0100".formatted(record));
        record = record(ledger, "current_date", groceriesOfFamily, anna, "10.01");
        share(ledger, record, anna, "5.01", anna);
        share(ledger, record, boris, "5.00", anna);
        db.commit();

        long mine = record;
        update("UPDATE ledger SET base_currency = 'USD' WHERE id = ?", ledger);
        db.commit();
        assertThat(number("SELECT count(*) FROM family_record WHERE ledger_id = ? AND currency = 'EUR'", ledger))
                .isOne();
        update("UPDATE ledger SET base_currency = 'EUR', name = 'Renamed' WHERE id = ?", ledger);
        db.commit();
        update("UPDATE ledger_member SET status = 'LEFT', left_date = current_date WHERE id = ?", boris);
        db.commit();
        assertFails(() -> update("UPDATE family_share SET amount = 5.00 WHERE record_id = ? AND member_id = ?", mine,
                boris), CHECK_VIOLATION, "member %d is not an active member and gets no share".formatted(boris));
        db.rollback();
        // A deleted record keeps its shares, and they need not add up any more.
        update("UPDATE family_record SET deleted_at = now(), deleted_by_member_id = ? WHERE id = ?", anna, mine);
        update("DELETE FROM family_share WHERE record_id = ? AND member_id = ?", mine, anna);
        db.commit();
    }

    /**
     * Records in other currencies (V8, V11; D-13, F4e, D-45, D-87). A record has its own currency, its ledger's base
     * currency when an insert leaves it out (an image before V11). A paying side in the record's currency is the
     * record's amount, without a rate; one in another currency says where the record's amount came from: a rate of the
     * ECB or of the member, with the rate's day (records converted before V11), or entered, without a rate. The writer
     * posts a side in another currency through the member's FX_EXCHANGE, only in the ledger it names as the acting
     * member's own and only for a payment or a settlement side.
     */
    @Test
    void recordsInOtherCurrenciesAndTheirExchange() throws SQLException {
        long family = sharedLedger();
        long anna = member(family, "SHARED", user, "OWNER", "Anna", null);
        long groceriesOfFamily = familyCategory(family, "GROCERIES", "EXPENSE");
        update("UPDATE account SET is_system = TRUE WHERE id = ?", fxExchange);
        long ledger = personalLedger(user);
        db.commit();
        String insert = """
                INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id, original_amount,
                    original_currency, base_amount, base_rate, base_rate_source, base_rate_date, split_method,
                    author_member_id, updated_by_member_id)
                VALUES (?, 'EXPENSE', current_date, ?, ?, ?, ?, ?, ?, ?, ?, 'EQUAL', ?, ?)""";
        BigDecimal ten = new BigDecimal("10.00");
        BigDecimal nine = new BigDecimal("9.20");
        BigDecimal rate = new BigDecimal("0.92");
        Date day = Date.valueOf(LocalDate.of(2026, 9, 25));
        assertFails(() -> insert(insert, family, groceriesOfFamily, anna, ten, "EUR", nine, null, null, null, anna,
                anna), CHECK_VIOLATION, "paid in its own currency EUR, the paying side is the amount, without a rate");
        db.rollback();
        assertFails(() -> insert(insert, family, groceriesOfFamily, anna, ten, "EUR", ten, null, "ENTERED", null, anna,
                anna), CHECK_VIOLATION, "paid in its own currency EUR, the paying side is the amount, without a rate");
        db.rollback();
        assertFails(() -> insert(insert, family, groceriesOfFamily, anna, ten, "USD", nine, null, null, null, anna,
                anna), CHECK_VIOLATION, "a paying side in USD for an amount in EUR needs the amount's source");
        db.rollback();
        assertFails(() -> insert(insert, family, groceriesOfFamily, anna, ten, "USD", nine, null, "ECB", null, anna,
                anna), CHECK_VIOLATION, "family_record_rate_source_check");
        db.rollback();
        assertFails(() -> insert(insert, family, groceriesOfFamily, anna, ten, "USD", nine, rate, "ENTERED", day, anna,
                anna), CHECK_VIOLATION, "family_record_rate_source_check");
        db.rollback();
        assertFails(() -> insert(insert, family, groceriesOfFamily, anna, ten, "USD", nine, rate, "MANUAL", null, anna,
                anna), CHECK_VIOLATION, "family_record_rate_source_check");
        db.rollback();
        long converted = insert(insert, family, groceriesOfFamily, anna, ten, "USD", nine, rate, "ECB", day, anna, anna);
        share(family, converted, anna, "9.20", anna);
        assertThat(number("SELECT count(*) FROM family_record WHERE id = ? AND currency = 'EUR'", converted)).isOne();
        db.commit();
        // A record in its own currency (V11, D-45): paid in it, the paying side is its amount; paid from an account in
        // another currency, with the amount entered (D-87).
        String own = """
                INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id, original_amount,
                    original_currency, base_amount, currency, base_rate_source, split_method, author_member_id,
                    updated_by_member_id)
                VALUES (?, 'EXPENSE', current_date, ?, ?, ?, ?, ?, ?, ?, 'EQUAL', ?, ?)""";
        long inDollars = insert(own, family, groceriesOfFamily, anna, ten, "USD", ten, "USD", null, anna, anna);
        share(family, inDollars, anna, "10.00", anna);
        db.commit();
        assertFails(() -> insert(own, family, groceriesOfFamily, anna, nine, "EUR", ten, "USD", null, anna, anna),
                CHECK_VIOLATION, "a paying side in EUR for an amount in USD needs the amount's source");
        db.rollback();
        assertFails(() -> insert(own, family, groceriesOfFamily, anna, ten, "USD", ten, "usd", "ENTERED", anna, anna),
                CHECK_VIOLATION, "family_record_currency_check");
        db.rollback();
        long fromEuros = insert(own, family, groceriesOfFamily, anna, nine, "EUR", ten, "USD", "ENTERED", anna, anna);
        share(family, fromEuros, anna, "10.00", anna);
        long entered = insert(insert, family, groceriesOfFamily, anna, ten, "USD", nine, null, "ENTERED", null, anna,
                anna);
        share(family, entered, anna, "9.20", anna);
        db.commit();
        // A change back to the base currency drops the rate with it.
        assertFails(() -> update("UPDATE family_record SET original_currency = 'EUR', original_amount = 9.20 "
                + "WHERE id = ?", converted), CHECK_VIOLATION, "paid in its own currency EUR, the paying side is the amount");
        db.rollback();
        update("UPDATE family_record SET original_currency = 'EUR', original_amount = 9.20, base_rate = NULL, "
                + "base_rate_source = NULL, base_rate_date = NULL WHERE id = ?", converted);
        db.commit();

        String debtAccount = "INSERT INTO account (user_id, ledger_id, code, name, type, is_system, family_ledger_id) "
                + "VALUES (?, ?, ?, 'Debt to family budget: Family', 'LIABILITY', TRUE, ?)";
        // FX_EXCHANGE only in the acting payer's own ledger, for a payment: not without it, and not in a share.
        writer("family-posting");
        long debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        long payment = familyEntry("FAMILY_PAYMENT");
        assertFails(() -> post(payment, fxExchange, "USD", "10.00"), CHECK_VIOLATION,
                "the family posting may not post FAMILY_PAYMENT to account FX_EXCHANGE");
        db.rollback();
        writer("family-posting");
        number("SELECT length(set_config('app.own_ledger', ?, true))", String.valueOf(ledger));
        debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        long share = familyEntry("FAMILY_SHARE");
        assertFails(() -> post(share, fxExchange, "USD", "10.00"), CHECK_VIOLATION,
                "the family posting may not post FAMILY_SHARE to account FX_EXCHANGE");
        db.rollback();
        writer("family-posting");
        number("SELECT length(set_config('app.own_ledger', ?, true))", String.valueOf(ledger));
        debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        long ownPayment = familyEntry("FAMILY_PAYMENT");
        post(ownPayment, cash, "USD", "-10.00");
        post(ownPayment, fxExchange, "USD", "10.00");
        post(ownPayment, fxExchange, "EUR", "-9.20");
        post(ownPayment, debt, "EUR", "9.20");
        insert("INSERT INTO family_entry_link (entry_id, family_ledger_id, member_id, record_id, link_type, "
                + "system_owned) VALUES (?, ?, ?, ?, 'PAYMENT', FALSE)", ownPayment, family, anna, entered);
        writer("");
        db.commit();
        assertThat(strings("SELECT currency || ' ' || sum(amount) FROM posting WHERE entry_id = ? GROUP BY currency "
                + "ORDER BY currency", ownPayment)).containsExactly("EUR 0.0000", "USD 0.0000");
    }

    /**
     * Only the posting service's writer writes posted rows, and only the kinds and accounts D-8 lists (V7; topic E):
     * an entry of a family kind, a debt account, a posting to one, and a link. A posted entry changes only through the
     * writer and is deleted only through it or with all of its user's data; a link loses its entry then.
     */
    @Test
    void onlyTheFamilyPostingWritesPostedRows() throws SQLException {
        long family = sharedLedger();
        long anna = member(family, "SHARED", user, "OWNER", "Anna", null);
        long groceriesOfFamily = familyCategory(family, "GROCERIES", "EXPENSE");
        long record = record(family, "current_date", groceriesOfFamily, anna, "10.00");
        share(family, record, anna, "10.00", anna);
        long ledger = personalLedger(user);
        db.commit();

        String debtAccount = "INSERT INTO account (user_id, ledger_id, code, name, type, is_system, family_ledger_id) "
                + "VALUES (?, ?, ?, 'Debt to family budget: Family', 'LIABILITY', TRUE, ?)";
        assertFails(() -> insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family), CHECK_VIOLATION,
                "only the family posting creates a family budget's debt account");
        db.rollback();
        assertFails(() -> familyEntry("FAMILY_SHARE"), CHECK_VIOLATION,
                "posted from a family budget, it changes only through its family record");
        db.rollback();

        writer("family-posting");
        long debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        String stranger = UUID.randomUUID().toString();
        long strangersLedger = number("SELECT personal_ledger_id(?)", stranger);
        assertFails(() -> insert(debtAccount, stranger, strangersLedger, "FAMILY_DEBT_" + family, family),
                FOREIGN_KEY_VIOLATION, "is not the personal ledger of an active member of family ledger " + family);
        db.rollback();
        writer("family-posting");
        debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        assertFails(() -> insert("INSERT INTO journal_entry (user_id, entry_date, kind) "
                + "VALUES (?, current_date, 'EXPENSE')", user), CHECK_VIOLATION,
                "the family posting writes only family kinds, not EXPENSE");
        db.rollback();

        writer("family-posting");
        debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        long share = familyEntry("FAMILY_SHARE");
        long debtAccountId = debt;
        // Not the user's cash, and not UNALLOCATED without a family category.
        assertFails(() -> post(share, cash, "EUR", "10.00"), CHECK_VIOLATION,
                "the family posting may not post FAMILY_SHARE to account CASH");
        db.rollback();
        writer("family-posting");
        debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        long shareEntry = familyEntry("FAMILY_SHARE");
        assertFails(() -> post(shareEntry, unallocated, "EUR", "10.00", groceries, null), CHECK_VIOLATION,
                "the family posting may not post FAMILY_SHARE to account UNALLOCATED");
        db.rollback();

        // A share and a payment with the payer's own cash, which only the ledger the writer names as the caller's gets.
        writer("family-posting");
        debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        long posted = familyEntry("FAMILY_SHARE");
        post(posted, unallocated, "EUR", "10.00", groceriesOfFamily, null);
        post(posted, debt, "EUR", "-10.00");
        insert("INSERT INTO family_entry_link (entry_id, family_ledger_id, member_id, record_id, link_type, "
                + "system_owned) VALUES (?, ?, ?, ?, 'SHARE', TRUE)", posted, family, anna, record);
        long payment = familyEntry("FAMILY_PAYMENT");
        long paymentDebt = debt;
        assertFails(() -> post(payment, cash, "EUR", "-10.00"), CHECK_VIOLATION,
                "the family posting may not post FAMILY_PAYMENT to account CASH");
        db.rollback();
        writer("family-posting");
        debt = insert(debtAccount, user, ledger, "FAMILY_DEBT_" + family, family);
        posted = familyEntry("FAMILY_SHARE");
        post(posted, unallocated, "EUR", "10.00", groceriesOfFamily, null);
        post(posted, debt, "EUR", "-10.00");
        insert("INSERT INTO family_entry_link (entry_id, family_ledger_id, member_id, record_id, link_type, "
                + "system_owned) VALUES (?, ?, ?, ?, 'SHARE', TRUE)", posted, family, anna, record);
        number("SELECT length(set_config('app.own_ledger', ?, true))", String.valueOf(ledger));
        long ownPayment = familyEntry("FAMILY_PAYMENT");
        post(ownPayment, cash, "EUR", "-10.00");
        post(ownPayment, debt, "EUR", "10.00");
        insert("INSERT INTO family_entry_link (entry_id, family_ledger_id, member_id, record_id, link_type, "
                + "system_owned) VALUES (?, ?, ?, ?, 'PAYMENT', FALSE)", ownPayment, family, anna, record);
        writer("");
        db.commit();

        // Past the writer: no posting to the debt account, no change of the posted entry, no link.
        long postedEntry = posted;
        long debtId = debt;
        long ordinary = expense("1.00");
        db.commit();
        assertFails(() -> post(ordinary, debtId, "EUR", "1.00"), CHECK_VIOLATION,
                "is the debt account of a family budget, which only it posts to");
        db.rollback();
        String onlyThroughTheRecord = "posted from a family budget, it changes only through its family record";
        assertFails(() -> update("UPDATE journal_entry SET memo = 'mine now' WHERE id = ?", postedEntry),
                CHECK_VIOLATION, onlyThroughTheRecord);
        db.rollback();
        assertFails(() -> update("UPDATE posting SET amount = 11 WHERE entry_id = ? AND amount > 0", postedEntry),
                CHECK_VIOLATION, onlyThroughTheRecord);
        db.rollback();
        assertFails(() -> update("DELETE FROM journal_entry WHERE id = ?", postedEntry), CHECK_VIOLATION,
                onlyThroughTheRecord);
        db.rollback();
        assertFails(() -> update("UPDATE family_entry_link SET system_owned = FALSE WHERE entry_id = ?", postedEntry),
                CHECK_VIOLATION, "only the family posting writes links");
        db.rollback();
        assertFails(() -> update("UPDATE journal_entry SET kind = 'FAMILY_SHARE' WHERE id = ?", ordinary),
                CHECK_VIOLATION, onlyThroughTheRecord);
        db.rollback();
        // The payer's own payment is theirs at the database's level (the service keeps it read-only until F4c).
        update("UPDATE journal_entry SET memo = 'my card' WHERE id = ?", ownPayment);
        db.commit();

        // With all of the user's data, a posted entry goes, and its link stays without it.
        writer("delete-all");
        update("DELETE FROM journal_entry WHERE id IN (?, ?)", postedEntry, ownPayment);
        db.commit();
        assertThat(strings("SELECT link_type || ' ' || (entry_id IS NULL) FROM family_entry_link "
                + "WHERE family_ledger_id = ? ORDER BY id", family)).containsExactly("SHARE true", "PAYMENT true");
        assertThat(paymentDebt).isPositive();
        assertThat(debtAccountId).isPositive();
    }

    /** UNALLOCATED takes every share of a family budget, so it can be renamed but not archived (V7; D-8). */
    @Test
    void unallocatedIsRenamedButNeverArchived() throws SQLException {
        update("UPDATE account SET name = 'Free money' WHERE id = ?", unallocated);
        db.commit();
        assertFails(() -> update("UPDATE account SET archived_at = now() WHERE id = ?", unallocated), CHECK_VIOLATION,
                "account UNALLOCATED: every share of a family budget is posted there, so it cannot be archived");
        db.rollback();
        update("UPDATE account SET archived_at = now() WHERE id = ?", cash);
        db.commit();
    }

    /**
     * "Delete all my data" in a family ledger (V7's release_family_memberships; D-20, topic H): the member's comments
     * go from the records and from the journal, their links are detached, and a custom rule's fall back to EQUAL is
     * journaled as a system change about them. A family ledger without another ACTIVE member with an account goes
     * with its records and journal.
     */
    @Test
    void releasingAMembershipErasesCommentsAndJournalsTheRuleReset() throws SQLException {
        String bob = UUID.randomUUID().toString();
        long family = sharedLedger("CUSTOM");
        long anna = member(family, "SHARED", user, "OWNER", "Anna", 6000);
        long bobs = member(family, "SHARED", bob, "MEMBER", "Bob", 4000);
        long groceriesOfFamily = familyCategory(family, "GROCERIES", "EXPENSE");
        long annasRecord = record(family, "current_date", groceriesOfFamily, anna, "10.00");
        share(family, annasRecord, anna, "10.00", anna);
        update("UPDATE family_record SET comment = 'Bob changed it' WHERE id = ?", annasRecord);
        long bobsRecord = record(family, "current_date", groceriesOfFamily, bobs, "4.00");
        share(family, bobsRecord, bobs, "4.00", bobs);
        update("UPDATE family_record SET comment = 'Bob''s own' WHERE id = ?", bobsRecord);
        journal(family, annasRecord, anna, "CREATE", "[{\"field\": \"comment\", \"old\": null, \"new\": \"Anna's\"}]");
        journal(family, annasRecord, bobs, "UPDATE", "[{\"field\": \"comment\", \"old\": \"Anna's\", \"new\": \"Bob changed it\"}]");
        journal(family, bobsRecord, bobs, "CREATE", "[{\"field\": \"amount\", \"old\": null, \"new\": \"4.00\"}, "
                + "{\"field\": \"comment\", \"old\": null, \"new\": \"Bob's own\"}]");
        long alone = sharedLedger();
        member(alone, "SHARED", bob, "OWNER", "Bob", null);
        member(alone, "SHARED", null, "MEMBER", "Kid", null);
        long alonesCategory = familyCategory(alone, "TOYS", "EXPENSE");
        long alonesRecord = record(alone, "current_date", alonesCategory, number(
                "SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub = ?", alone, bob), "3.00");
        share(alone, alonesRecord, number("SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub IS NULL",
                alone), "3.00", number("SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub = ?", alone, bob));
        db.commit();

        assertThat(number("SELECT release_family_memberships(?)", bob)).isEqualTo(2);
        assertThat(strings("SELECT coalesce(current_setting('app.writer', true), '')")).containsExactly("");
        db.commit();

        assertThat(strings("SELECT coalesce(comment, '-') FROM family_record WHERE id IN (?, ?) ORDER BY id",
                annasRecord, bobsRecord)).containsExactly("-", "-");
        assertThat(strings("SELECT changes::text FROM family_record_change WHERE record_id IS NOT NULL ORDER BY id"))
                .containsExactly("[{\"new\": \"Anna's\", \"old\": null, \"field\": \"comment\"}]",
                        "[{\"new\": null, \"old\": \"Anna's\", \"field\": \"comment\"}]",
                        "[{\"new\": \"4.00\", \"old\": null, \"field\": \"amount\"}, "
                                + "{\"new\": null, \"old\": null, \"field\": \"comment\"}]");
        assertThat(strings("SELECT concat_ws(' ', action, about_member_id, changed_by_member_id IS NULL, changes::text) "
                + "FROM family_record_change WHERE ledger_id = ? AND record_id IS NULL", family)).containsExactly(
                "SPLIT_RULE_RESET %d t [{\"new\": \"EQUAL\", \"old\": \"CUSTOM\", \"field\": \"splitRule\"}]"
                        .formatted(bobs));
        assertThat(strings("SELECT split_rule FROM ledger WHERE id = ?", family)).containsExactly("EQUAL");
        assertThat(number("SELECT count(*) FROM ledger WHERE id = ?", alone)).isZero();
        assertThat(number("SELECT count(*) FROM family_record WHERE ledger_id = ?", alone)).isZero();
    }

    /**
     * In a family ledger, a display name is never blank, and belongs to one member who isn't FORMER, whatever its case
     * (D-3). FORMER members are all "Former member" (D-20).
     */
    @Test
    void displayNamesAreUniquePerFamilyLedgerWhateverTheirCase() throws SQLException {
        long family = sharedLedger();
        member(family, "SHARED", user, "OWNER", "Anna", null);
        long boris = member(family, "SHARED", null, "MEMBER", "Boris", null);
        long clara = member(family, "SHARED", null, "MEMBER", "Clara", null);
        db.commit();

        assertFails(() -> member(family, "SHARED", null, "MEMBER", "ANNA", null), UNIQUE_VIOLATION,
                "ledger_member_display_name_key");
        db.rollback();
        assertFails(() -> update("UPDATE ledger_member SET display_name = 'anna' WHERE id = ?", boris),
                UNIQUE_VIOLATION, "ledger_member_display_name_key");
        db.rollback();
        assertFails(() -> member(family, "SHARED", null, "MEMBER", " ", null), CHECK_VIOLATION,
                "ledger_member_display_name_check");
        db.rollback();
        // Another family ledger has its own names.
        member(sharedLedger(), "SHARED", null, "MEMBER", "Anna", null);
        db.commit();

        // A member who left keeps the name; former members share theirs, and free the one they had.
        update("UPDATE ledger_member SET status = 'LEFT', left_date = DATE '2026-05-01' WHERE id = ?", clara);
        db.commit();
        assertFails(() -> member(family, "SHARED", null, "MEMBER", "clara", null), UNIQUE_VIOLATION,
                "ledger_member_display_name_key");
        db.rollback();
        update("UPDATE ledger_member SET status = 'FORMER', role = 'MEMBER', user_sub = NULL, "
                + "display_name = 'Former member', left_date = DATE '2026-05-01' WHERE ledger_id = ? AND user_sub = ?",
                family, user);
        update("UPDATE ledger_member SET status = 'FORMER', display_name = 'Former member', "
                + "left_date = DATE '2026-05-01' WHERE id = ?", boris);
        member(family, "SHARED", null, "MEMBER", "Anna", null);
        db.commit();
    }

    /**
     * A family ledger has a split rule and a personal one none. Under CUSTOM every ACTIVE member has a share out of
     * 10000, and they sum to 10000 at commit; under EQUAL nobody has one (D-12, ADR 0003 topic B).
     */
    @Test
    void aFamilyLedgersSplitRuleFitsItsMembers() throws SQLException {
        assertFails(() -> insert("INSERT INTO ledger (type, name, base_currency) VALUES ('SHARED', 'Family', 'EUR')"),
                CHECK_VIOLATION, "ledger_shared_split_rule_check");
        db.rollback();
        assertFails(() -> insert("INSERT INTO ledger (type, split_rule) VALUES ('PERSONAL', 'EQUAL')"),
                CHECK_VIOLATION, "ledger_shared_split_rule_check");
        db.rollback();
        assertFails(() -> insert("INSERT INTO ledger (type, name, base_currency, split_rule) "
                + "VALUES ('SHARED', ' ', 'EUR', 'EQUAL')"), CHECK_VIOLATION, "ledger_name_check");
        db.rollback();

        long family = sharedLedger("CUSTOM");
        long anna = member(family, "SHARED", user, "OWNER", "Anna", 6000);
        member(family, "SHARED", null, "MEMBER", "Boris", 4000);
        db.commit();
        update("UPDATE ledger_member SET share_bp = 5000 WHERE id = ?", anna);
        assertFails(db::commit, CHECK_VIOLATION,
                "family ledger %d: the custom shares sum to 9000, not 10000".formatted(family));
        member(family, "SHARED", null, "MEMBER", "Clara", null);
        assertFails(db::commit, CHECK_VIOLATION,
                "family ledger %d: a custom split needs a share for each of its 1 members without one"
                        .formatted(family));
        long clara = member(family, "SHARED", null, "MEMBER", "Clara", 0);
        db.commit();

        // Refused at once: a share out of range, one of a member who isn't ACTIVE, one in a personal ledger.
        assertFails(() -> update("UPDATE ledger_member SET share_bp = 10001 WHERE id = ?", anna), CHECK_VIOLATION,
                "ledger_member_share_bp_check");
        db.rollback();
        assertFails(() -> update("UPDATE ledger_member SET status = 'LEFT', left_date = DATE '2026-05-01' "
                + "WHERE id = ?", clara), CHECK_VIOLATION, "ledger_member_share_check");
        db.rollback();
        assertFails(() -> update("UPDATE ledger_member SET share_bp = 0 WHERE user_sub = ? AND ledger_type = "
                + "'PERSONAL'", user), CHECK_VIOLATION, "ledger_member_share_check");
        db.rollback();

        update("UPDATE ledger SET split_rule = 'EQUAL' WHERE id = ?", family);
        assertFails(db::commit, CHECK_VIOLATION,
                "family ledger %d: an equal split has no custom shares".formatted(family));
        update("UPDATE ledger SET split_rule = 'EQUAL' WHERE id = ?", family);
        update("UPDATE ledger_member SET share_bp = NULL WHERE ledger_id = ?", family);
        db.commit();
    }

    /**
     * A posting's account, category and counterparty are in its entry's ledger. While each ledger is its user's, the
     * check of the users fails first (postingThatReferencesAnotherUsersRowFails), so rows written past the triggers
     * stand in for rows of another ledger.
     */
    @Test
    void postingThatReferencesAnotherLedgersRowFails() throws SQLException {
        long elsewhere = number("SELECT personal_ledger_id(?)", UUID.randomUUID().toString());
        db.commit();
        update("SET LOCAL session_replication_role = replica");
        long strayAccount = insert("INSERT INTO account (user_id, ledger_id, code, name, type) "
                + "VALUES (?, ?, 'STRAY', 'Stray', 'ASSET')", user, elsewhere);
        long strayCategory = insert("INSERT INTO category (user_id, ledger_id, code, name, type) "
                + "VALUES (?, ?, 'STRAY', 'Stray', 'EXPENSE')", user, elsewhere);
        long strayCounterparty = insert("INSERT INTO counterparty (user_id, ledger_id, name) VALUES (?, ?, 'Stray')",
                user, elsewhere);
        db.commit();

        assertFails(() -> post(entry(), strayAccount, "EUR", "5.00"), FOREIGN_KEY_VIOLATION,
                "account %d belongs to another ledger".formatted(strayAccount));
        db.rollback();
        assertFails(() -> post(entry(), unallocated, "EUR", "5.00", strayCategory, null), FOREIGN_KEY_VIOLATION,
                "category %d belongs to another ledger".formatted(strayCategory));
        db.rollback();
        assertFails(() -> post(entry(), loans, "EUR", "5.00", null, strayCounterparty), FOREIGN_KEY_VIOLATION,
                "counterparty %d belongs to another ledger".formatted(strayCounterparty));
    }

    /**
     * A new user's first requests may all ask for the personal ledger at once. The second call waits on the unique
     * index for the first transaction's member, finds it when that commits, and leaves no ledger of its own behind.
     */
    @Test
    void concurrentCallsGetOnePersonalLedger() throws Exception {
        String dave = UUID.randomUUID().toString();
        long ledgersBefore = number("SELECT count(*) FROM ledger");
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try (Connection second = connect()) {
            second.setAutoCommit(false);
            long first = number("SELECT personal_ledger_id(?)", dave);
            Future<Long> secondCall = thread.submit(() -> {
                long id = number(second, "SELECT personal_ledger_id(?)", dave);
                second.commit();
                return id;
            });
            assertThatThrownBy(() -> secondCall.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            db.commit();

            assertThat(secondCall.get(10, TimeUnit.SECONDS)).isEqualTo(first);
        } finally {
            thread.shutdownNow();
        }
        assertThat(number("SELECT count(*) FROM ledger_member WHERE user_sub = ?", dave)).isOne();
        assertThat(number("SELECT count(*) FROM ledger")).isEqualTo(ledgersBefore + 1);
    }

    /**
     * V12 (F8c, D-101): a claim's join date is checked against the latest date anywhere, the one at UTC+14, in place
     * of the session's {@code current_date}: its owner's zone, which the api decides it in, may be a day ahead of the
     * database session's (UTC in production). The session is set to UTC here, whatever zone the test JVM runs in.
     * The user's time zone column starts null and is limited to 64 characters.
     */
    @Test
    void aClaimsJoinDateMayBeTodayInAnyZone() throws SQLException {
        db.createStatement().execute("SET TIME ZONE 'UTC'");
        long family = sharedLedger();
        long owner = member(family, "SHARED", user, "OWNER");
        long seat = seat(family);
        db.commit();
        String insert = """
                INSERT INTO ledger_invite (ledger_id, token_hash, seat_member_id, join_date, created_by_member_id,
                                           expires_at)
                VALUES (?, ?, ?, ?, ?, now() + interval '72 hours')""";
        LocalDate utc = LocalDate.parse(strings("SELECT (now() AT TIME ZONE 'UTC')::date::text").getFirst());
        LocalDate ahead = LocalDate.parse(strings(
                "SELECT (now() AT TIME ZONE 'Pacific/Kiritimati')::date::text").getFirst());
        assertThat(ahead).isIn(utc, utc.plusDays(1));
        // Hashes of their own: the class's tests share one database, whose token hashes are unique.
        insert(insert, family, hash(201), seat, utc, owner);
        // The date at UTC+14, a day ahead of UTC's for fourteen hours of each day, is today there, so it is accepted.
        insert(insert, family, hash(202), seat, ahead, owner);
        db.commit();
        // Nowhere on earth is it already the day after.
        assertFails(() -> insert(insert, family, hash(203), seat, ahead.plusDays(1), owner), CHECK_VIOLATION,
                "is not between the start of family ledger");
        db.rollback();

        update("INSERT INTO user_settings (user_id, base_currency) VALUES (?, 'EUR')", user);
        assertThat(strings("SELECT time_zone FROM user_settings WHERE user_id = ?", user)).containsExactly((String) null);
        update("UPDATE user_settings SET time_zone = 'Europe/Amsterdam' WHERE user_id = ?", user);
        db.commit();
        assertFails(() -> update("UPDATE user_settings SET time_zone = '' WHERE user_id = ?", user), CHECK_VIOLATION,
                "user_settings_time_zone_length");
        db.rollback();
        assertFails(() -> update("UPDATE user_settings SET time_zone = repeat('x', 65) WHERE user_id = ?", user),
                CHECK_VIOLATION, "user_settings_time_zone_length");
        db.rollback();
    }

    /**
     * V9's invites (F5; D-17, D-18, D-20): a token's hash of 32 bytes, once; created by an ACTIVE owner with an account
     * of a family ledger, never of a personal one; a claim's seat an ACTIVE member without an account, with a join
     * date from the start date to today; at most seven days; revoked, used or declined once, for good, and nothing else
     * of it ever changes. Releasing the creator's memberships revokes their pending invites.
     */
    @Test
    void invitesFitTheirFamilyLedger() throws SQLException {
        long family = sharedLedger();
        long owner = member(family, "SHARED", user, "OWNER");
        long other = member(family, "SHARED", UUID.randomUUID().toString(), "OWNER");
        long member = member(family, "SHARED", UUID.randomUUID().toString(), "MEMBER");
        long seat = seat(family);
        db.commit();
        String insert = """
                INSERT INTO ledger_invite (ledger_id, token_hash, seat_member_id, join_date, created_by_member_id,
                                           expires_at)
                VALUES (?, ?, ?, ?, ?, now() + interval '72 hours')""";
        LocalDate today = LocalDate.parse(strings("SELECT current_date::text").getFirst());
        long invite = insert(insert, family, hash(1), null, null, owner);
        long claim = insert(insert, family, hash(2), seat, today, owner);
        db.commit();

        assertFails(() -> insert(insert, family, new byte[16], null, null, owner), CHECK_VIOLATION,
                "ledger_invite_token_hash_check");
        db.rollback();
        assertFails(() -> insert(insert, family, hash(1), null, null, owner), UNIQUE_VIOLATION,
                "ledger_invite_token_hash_key");
        db.rollback();
        assertFails(() -> insert(insert, family, hash(3), null, null, member), CHECK_VIOLATION,
                "is not an active owner");
        db.rollback();
        assertFails(() -> insert(insert, family, hash(3), member, today, owner), CHECK_VIOLATION,
                "is not a seat without an account");
        db.rollback();
        assertFails(() -> insert(insert, family, hash(3), seat, null, owner), CHECK_VIOLATION, "ledger_invite_check");
        db.rollback();
        for (LocalDate outside : List.of(today.minusDays(1), today.plusDays(1))) {
            assertFails(() -> insert(insert, family, hash(3), seat, outside, owner), CHECK_VIOLATION,
                    "is not between the start of family ledger");
            db.rollback();
        }
        assertFails(() -> insert(insert.replace("72 hours", "169 hours"), family, hash(3), null, null, owner),
                CHECK_VIOLATION, "ledger_invite_check");
        db.rollback();
        long personal = personalLedger(user);
        long personalOwner = number("SELECT id FROM ledger_member WHERE ledger_id = ?", personal);
        assertFails(() -> insert(insert, personal, hash(3), null, null, personalOwner), FOREIGN_KEY_VIOLATION,
                "ledger_invite_ledger_id_ledger_type_fkey");
        db.rollback();

        update("UPDATE ledger_invite SET revoked_at = now() WHERE id = ?", invite);
        db.commit();
        assertFails(() -> update("UPDATE ledger_invite SET revoked_at = NULL WHERE id = ?", invite), CHECK_VIOLATION,
                "only a pending invite changes");
        db.rollback();
        assertFails(() -> update("UPDATE ledger_invite SET declined_at = now(), revoked_at = NULL WHERE id = ?",
                invite), CHECK_VIOLATION, "only a pending invite changes");
        db.rollback();
        assertFails(() -> update("UPDATE ledger_invite SET expires_at = expires_at + interval '1 hour' WHERE id = ?",
                claim), CHECK_VIOLATION, "only a pending invite changes");
        db.rollback();
        assertFails(() -> update("UPDATE ledger_invite SET used_at = now() WHERE id = ?", claim), CHECK_VIOLATION,
                "ledger_invite_check");
        db.rollback();

        // The owner's data goes: their pending claim is revoked; the revoked one keeps its time.
        String revokedAt = strings("SELECT revoked_at::text FROM ledger_invite WHERE id = ?", invite).getFirst();
        writer("delete-all");
        number("SELECT release_family_memberships(?)", user);
        db.commit();
        assertThat(strings("SELECT revoked_at::text FROM ledger_invite WHERE id = ?", invite)).containsExactly(revokedAt);
        assertThat(number("SELECT count(*) FROM ledger_invite WHERE id = ? AND revoked_at IS NOT NULL", claim)).isOne();
        assertThat(number("SELECT count(*) FROM ledger_member WHERE id = ? AND role = 'OWNER'", other)).isOne();
    }

    /**
     * V10 (F6b). Whether a member took a seat (D-35) is the database's to keep, whichever code writes the membership:
     * never a new member, set when a seat gets its sub (a claim), cleared when a member who left comes back (D-39), and
     * nothing else changes it. {@code delete_family_ledger} (D-36) refuses a family ledger that still has an ACTIVE
     * member with an account, and deletes one without, with its members, categories, records, shares, journal and
     * invites; it refuses a personal ledger too.
     */
    @Test
    void claimedSeatsAndTheDeletionOfAFamilyLedger() throws SQLException {
        long family = sharedLedger();
        long owner = member(family, "SHARED", user, "OWNER");
        long seat = seat(family);
        long told = insert("""
                INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date,
                                           claimed_seat)
                VALUES (?, 'SHARED', ?, 'Told so', 'MEMBER', 'ACTIVE', DATE '2026-01-01', TRUE)""", family,
                UUID.randomUUID().toString());
        db.commit();
        String claimed = "SELECT claimed_seat::text FROM ledger_member WHERE id = ?";
        assertThat(strings(claimed, told)).containsExactly("false");
        update("UPDATE ledger_member SET claimed_seat = TRUE WHERE id = ?", owner);
        assertThat(strings(claimed, owner)).containsExactly("false");

        update("UPDATE ledger_member SET user_sub = ?, display_name = 'Carol', join_date = DATE '2026-02-01' "
                + "WHERE id = ?", UUID.randomUUID().toString(), seat);
        assertThat(strings(claimed, seat)).containsExactly("true");
        update("UPDATE ledger_member SET claimed_seat = FALSE WHERE id = ?", seat);
        assertThat(strings(claimed, seat)).containsExactly("true");
        update("UPDATE ledger_member SET status = 'LEFT', left_date = DATE '2026-03-01' WHERE id = ?", seat);
        assertThat(strings(claimed, seat)).containsExactly("true");
        update("UPDATE ledger_member SET status = 'ACTIVE', left_date = NULL, join_date = DATE '2026-04-01' "
                + "WHERE id = ?", seat);
        assertThat(strings(claimed, seat)).containsExactly("false");
        long personalMember = number("SELECT id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'",
                user);
        update("UPDATE ledger_member SET claimed_seat = TRUE WHERE id = ?", personalMember);
        assertThat(strings(claimed, personalMember)).containsExactly("false");
        db.commit();

        long category = familyCategory(family, "RENT", "EXPENSE");
        long rent = record(family, "current_date", category, owner, "10.00");
        share(family, rent, owner, "10.00", owner);
        journal(family, rent, owner, "CREATE", "[]");
        insert("""
                INSERT INTO ledger_invite (ledger_id, token_hash, created_by_member_id, expires_at)
                VALUES (?, ?, ?, now() + interval '1 hour')""", family, hash(9), owner);
        db.commit();
        String delete = "SELECT 'deleted' FROM (SELECT delete_family_ledger(?)) AS d";
        assertFails(() -> strings(delete, family), CHECK_VIOLATION, "has an active member with an account");
        db.rollback();
        assertFails(() -> strings(delete, personalLedger(user)), CHECK_VIOLATION, "is no family ledger");
        db.rollback();

        update("UPDATE ledger_member SET status = 'LEFT', role = 'MEMBER', left_date = current_date "
                + "WHERE ledger_id = ? AND user_sub IS NOT NULL", family);
        assertThat(strings(delete, family)).containsExactly("deleted");
        db.commit();
        for (String table : List.of("ledger WHERE id", "ledger_member WHERE ledger_id", "category WHERE ledger_id",
                "family_record WHERE ledger_id", "family_share WHERE ledger_id", "family_record_change WHERE ledger_id",
                "ledger_invite WHERE ledger_id")) {
            assertThat(number("SELECT count(*) FROM " + table + " = ?", family)).as(table).isZero();
        }
        assertThat(strings("SELECT current_setting('app.writer', true)")).containsExactly("");
    }

    /** A token's hash as the test makes it up: 32 bytes of n. */
    private static byte[] hash(int n) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, (byte) n);
        return hash;
    }

    private static void assertFails(ThrowingCallable call, String sqlState, String message) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(sqlState))
                .hasMessageContaining(message);
    }

    private long familyCategory(long family, String code, String type) throws SQLException {
        return insert("INSERT INTO category (ledger_id, code, name, type) VALUES (?, ?, ?, ?)", family, code, code, type);
    }

    /** An expense of the family ledger, EQUAL, dated by the SQL expression, which its payer wrote. */
    private long record(long family, String date, long category, long payer, String amount) throws SQLException {
        return insert("""
                INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id, original_amount,
                    original_currency, base_amount, split_method, author_member_id, updated_by_member_id)
                VALUES (?, 'EXPENSE', %s, ?, ?, ?, 'EUR', ?, 'EQUAL', ?, ?)""".formatted(date), family, category, payer,
                new BigDecimal(amount), new BigDecimal(amount), payer, payer);
    }

    private void share(long family, long record, long member, String amount, long by) throws SQLException {
        update("INSERT INTO family_share (ledger_id, record_id, member_id, amount, updated_by_member_id) "
                + "VALUES (?, ?, ?, ?, ?)", family, record, member, new BigDecimal(amount), by);
    }

    private void journal(long family, long record, long by, String action, String changes) throws SQLException {
        update("INSERT INTO family_record_change (ledger_id, record_id, changed_by_member_id, action, changes) "
                + "VALUES (?, ?, ?, ?, ?::jsonb)", family, record, by, action, changes);
    }

    /** An entry of a family kind in the user's ledger, today. */
    private long familyEntry(String kind) throws SQLException {
        return insert("INSERT INTO journal_entry (user_id, entry_date, kind) VALUES (?, current_date, ?)", user, kind);
    }

    /** Which code writes, for V7's triggers, until the transaction ends. */
    private void writer(String writer) throws SQLException {
        number("SELECT length(set_config('app.writer', ?, true))", writer);
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

    private long importBatch(String owner) throws SQLException {
        return insert("INSERT INTO import_batch (user_id, file_name, file_sha256, dry_run) "
                + "VALUES (?, 'transactions.csv', repeat('0', 64), TRUE)", owner);
    }

    private long personalLedger(String owner) throws SQLException {
        return number("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'", owner);
    }

    private long sharedLedger() throws SQLException {
        return sharedLedger("EQUAL");
    }

    private long sharedLedger(String splitRule) throws SQLException {
        return insert("INSERT INTO ledger (type, name, base_currency, split_rule) VALUES ('SHARED', 'Family', 'EUR', ?)",
                splitRule);
    }

    /** A member named "Member n", a name no other member of the test has. */
    private long member(long ledger, String ledgerType, String sub, String role) throws SQLException {
        return member(ledger, ledgerType, sub, role, "Member " + ++members, null);
    }

    private long member(long ledger, String ledgerType, String sub, String role, String name, Integer share)
            throws SQLException {
        return insert("""
                INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date,
                                           share_bp)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', DATE '2026-01-01', ?)""", ledger, ledgerType, sub, name, role, share);
    }

    /** A member without an account. */
    private long seat(long family) throws SQLException {
        return member(family, "SHARED", null, "MEMBER");
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
                INSERT INTO posting (entry_id, line_no, account_id, currency, amount, category_id, counterparty_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", entry, lineNo++, account, currency, new BigDecimal(amount), category,
                counterparty);
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
        return number(db, sql, params);
    }

    private static long number(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, params); var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private List<String> strings(String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(sql, params); var rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        return prepare(db, sql, params);
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}
