package com.example.financetracker.ledger;

import java.math.BigDecimal;

import com.example.financetracker.ledger.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The user's settings. Callers pass the user id, the Keycloak "sub" claim (rule 11). */
@Service
public class SettingsService {

    private final UserSettingsRepository settings;
    private final AccountRepository accounts;

    SettingsService(UserSettingsRepository settings, AccountRepository accounts) {
        this.settings = settings;
        this.accounts = accounts;
    }

    /** The user's settings, or the defaults for a user who has none. */
    @Transactional(readOnly = true)
    public SettingsView get(String userId) {
        return settings.findById(userId)
                .map(SettingsService::view)
                .orElseGet(() -> new SettingsView(StarterLedger.BASE_CURRENCY, null,
                        Money.normalize(EntryService.DEFAULT_SHARE_RATIO)));
    }

    /**
     * Replaces the user's settings.
     *
     * @param sharedAccountId null for the account FAMILY_DEBT
     * @throws RuleViolationException if the user has no account {@code sharedAccountId}
     */
    @Transactional
    public SettingsView update(String userId, String baseCurrency, Long sharedAccountId,
            BigDecimal defaultShareRatio) {
        if (sharedAccountId != null && accounts.findByIdAndUserId(sharedAccountId, userId).isEmpty()) {
            throw new RuleViolationException("account %d does not exist".formatted(sharedAccountId));
        }
        UserSettings updated = new UserSettings(userId, baseCurrency, sharedAccountId, defaultShareRatio);
        settings.save(updated);
        return view(updated);
    }

    private static SettingsView view(UserSettings settings) {
        return new SettingsView(settings.baseCurrency(), settings.sharedAccountId(),
                Money.normalize(settings.defaultShareRatio()));
    }
}
