package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * At most one row per user, keyed by the user id itself. A user without a row has the defaults. Not a CrudRepository:
 * its save would take a row with an id for an existing one and only ever update.
 */
public interface UserSettingsRepository extends Repository<UserSettings, String> {

    Optional<UserSettings> findById(String userId);

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
