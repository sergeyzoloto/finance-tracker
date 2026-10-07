package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * At most one row per user, keyed by the user id itself. A user without a row has the defaults. The settings are the
 * person's, 1:1 with their personal ledger (ADR 0003, topic A), so this is not a {@link LedgerScopedRepository}. Not a
 * CrudRepository either: its save would take a row with an id for an existing one and only ever update.
 */
public interface UserSettingsRepository extends Repository<UserSettings, String> {

    Optional<UserSettings> findById(String userId);

    /**
     * Inserts the user's row with the base currency, unless the user has one. Of concurrent calls for one user, one
     * inserts, and the others wait on the row's key until that transaction ends.
     *
     * @return whether it inserted the row
     */
    @Modifying
    @Query("INSERT INTO user_settings (user_id, base_currency) VALUES (:userId, :baseCurrency) "
            + "ON CONFLICT (user_id) DO NOTHING")
    boolean insertIfAbsent(String userId, String baseCurrency);

    /** The user's time zone, an IANA id (D-101); empty when they have no row or have set none. */
    @Query("SELECT time_zone FROM user_settings WHERE user_id = :userId")
    Optional<String> findTimeZone(String userId);

    /** Sets the zone of the user's row, which must exist; {@code upsert} leaves it alone. */
    @Modifying
    @Query("UPDATE user_settings SET time_zone = :timeZone WHERE user_id = :userId")
    boolean updateTimeZone(String userId, String timeZone);

    /** Inserts or replaces the user's row. */
    default void save(UserSettings settings) {
        upsert(settings.userId(), settings.baseCurrency(), settings.sharedAccountId(), settings.defaultShareRatio());
    }

    @Modifying
    @Query("""
            INSERT INTO user_settings (user_id, base_currency, shared_account_id, default_share_ratio)
            VALUES (:userId, :baseCurrency, :sharedAccountId, :defaultShareRatio)
            ON CONFLICT (user_id) DO UPDATE
            SET base_currency = EXCLUDED.base_currency, shared_account_id = EXCLUDED.shared_account_id,
                default_share_ratio = EXCLUDED.default_share_ratio""")
    void upsert(String userId, String baseCurrency, Long sharedAccountId, BigDecimal defaultShareRatio);
}
