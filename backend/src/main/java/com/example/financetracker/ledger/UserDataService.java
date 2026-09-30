package com.example.financetracker.ledger;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the app keeps about one user, as a whole: the person, not one of their ledgers. Callers pass the user id,
 * the Keycloak "sub" claim (rule 11). The login account itself belongs to the login service, auth.finance-nl.com, and
 * nothing here touches it.
 */
@Service
public class UserDataService {

    /** The user's personal ledger, by its member's sub. */
    private static final String PERSONAL_LEDGER = """
            SELECT ledger_id FROM ledger_member WHERE user_sub = :userId AND ledger_type = 'PERSONAL'""";

    private final JdbcClient jdbc;
    private final StarterLedger starterLedger;
    private final ApplicationEventPublisher events;

    UserDataService(JdbcClient jdbc, StarterLedger starterLedger, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.starterLedger = starterLedger;
        this.events = events;
    }

    /**
     * Provisions a user the app sees for the first time: a {@code users} row with their email and name, for display
     * and for finding a user's sub by email, and their settings, personal ledger and starter accounts and categories
     * ({@link StarterLedger}). Both steps are idempotent and safe when a user's first requests run in parallel.
     *
     * @param email as the login service has it; null if it has none
     * @param displayName as the login service has it, for display only; null if it has none
     */
    public void provision(String userId, String email, String displayName) {
        jdbc.sql("""
                INSERT INTO users (keycloak_id, email, display_name) VALUES (:userId, :email, :displayName)
                ON CONFLICT (keycloak_id) DO NOTHING""")
                .param("userId", userId)
                .param("email", email)
                .param("displayName", displayName)
                .update();
        starterLedger.seedIfNew(userId);
    }

    /**
     * Deletes every row the user owns, at once and for good: their personal ledger with its entries and their
     * postings, import batches, accounts, categories, counterparties and its member; their settings and manual
     * exchange rates; and the {@code users} row with the email address and name from the login. The ECB's rates,
     * which all users share, stay. All statements run in one transaction.
     * The entries go with those a family budget posted and the payments for its records (D-20): the database lets
     * them go while {@code app.writer} is {@code delete-all}, and their links stay as the family's history, without
     * the entry (V7).
     * <p>
     * Then, once no posting of the user's refers to a family category any more, the user's family memberships become
     * FORMER members without a sub, named "Former member" (D-20), by the database's {@code
     * release_family_memberships}, which the runbook's "Delete a user" runs too: their comments are erased and their
     * links detached. A family ledger without another ACTIVE member with an account goes with its records, categories
     * and members; otherwise the records and balances stay, frozen where they involve the user, and if the user was
     * its last owner, the ACTIVE member with an account who joined earliest becomes one.
     * <p>
     * After the commit, {@link UserDataDeleted} tells the app to treat the user as new: their next request provisions
     * them again, with the starter ledger, as on their first sign-in.
     *
     * @return how many rows were deleted
     */
    @Transactional
    public int deleteAll(String userId) {
        int deleted = 0;
        jdbc.sql("SELECT set_config('app.writer', 'delete-all', true)").query(String.class).single();
        // In the order of the foreign keys: the settings name an account, entries name counterparties and import
        // batches, postings (deleted with their entries) name accounts, categories and counterparties.
        deleted += jdbc.sql("DELETE FROM user_settings WHERE user_id = :userId").param("userId", userId).update();
        for (String table : new String[] {"journal_entry", "import_batch", "account", "category", "counterparty"}) {
            deleted += jdbc.sql("DELETE FROM " + table + " WHERE ledger_id IN (" + PERSONAL_LEDGER + ")")
                    .param("userId", userId).update();
        }
        deleted += jdbc.sql("DELETE FROM exchange_rate WHERE user_id = :userId").param("userId", userId).update();
        // V1's single-entry tables, unused since the ledger replaced them, are keyed by users.id.
        for (String table : new String[] {"transactions", "categories"}) {
            deleted += jdbc.sql("DELETE FROM " + table + " WHERE user_id IN (SELECT id FROM users WHERE keycloak_id = "
                    + ":userId)").param("userId", userId).update();
        }
        // After the entries: a family ledger that goes with the user takes its categories, which the user's own
        // postings could use (ADR 0003, topic J).
        jdbc.sql("SELECT release_family_memberships(:userId)").param("userId", userId).query(Integer.class).single();
        // The ledger's member goes with it (ON DELETE CASCADE). By the member's sub, also for a sub without a users
        // row, whose ledger the users row's trigger (V5) would leave.
        deleted += jdbc.sql("DELETE FROM ledger WHERE id IN (" + PERSONAL_LEDGER + ")").param("userId", userId)
                .update();
        deleted += jdbc.sql("DELETE FROM users WHERE keycloak_id = :userId").param("userId", userId).update();
        jdbc.sql("SELECT set_config('app.writer', '', true)").query(String.class).single();
        events.publishEvent(new UserDataDeleted(userId));
        return deleted;
    }
}
