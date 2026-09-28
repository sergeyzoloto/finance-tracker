package com.example.financetracker.ledger;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the app keeps about one user, as a whole. Callers pass the user id, the Keycloak "sub" claim (rule 11).
 * The login account itself belongs to the login service, auth.finance-nl.com, and nothing here touches it.
 */
@Service
public class UserDataService {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    UserDataService(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /**
     * Deletes every row the user owns, at once and for good: entries with their postings, import batches, accounts,
     * categories, counterparties, settings, the user's manual exchange rates, and the {@code users} row with the email
     * address and name from the login. The ECB's rates, which all users share, stay. Every statement is filtered by
     * the user, and all of them run in one transaction.
     * <p>
     * After the commit, {@link UserDataDeleted} tells the app to treat the user as new: their next request provisions
     * them again, with the starter ledger, as on their first sign-in.
     *
     * @return how many rows were deleted
     */
    @Transactional
    public int deleteAll(String userId) {
        int deleted = 0;
        // In the order of the foreign keys: the settings name an account, entries name counterparties and import
        // batches, postings (deleted with their entries) name accounts, categories and counterparties.
        for (String table : new String[] {"user_settings", "journal_entry", "import_batch", "account", "category",
                "counterparty", "exchange_rate"}) {
            deleted += jdbc.sql("DELETE FROM " + table + " WHERE user_id = :userId").param("userId", userId).update();
        }
        // V1's single-entry tables, unused since the ledger replaced them, are keyed by users.id.
        for (String table : new String[] {"transactions", "categories"}) {
            deleted += jdbc.sql("DELETE FROM " + table + " WHERE user_id IN (SELECT id FROM users WHERE keycloak_id = "
                    + ":userId)").param("userId", userId).update();
        }
        deleted += jdbc.sql("DELETE FROM users WHERE keycloak_id = :userId").param("userId", userId).update();
        events.publishEvent(new UserDataDeleted(userId));
        return deleted;
    }
}
