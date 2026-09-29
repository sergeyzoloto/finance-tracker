package com.example.financetracker.ledger;

import java.math.BigDecimal;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's settings: the person's, 1:1 with their personal ledger (ADR 0003, topic A), so they are read by the user
 * id, the Keycloak "sub" claim (rule 11). A change is checked against the personal ledger, whose account it may name.
 */
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
     * Replaces the settings of the personal ledger's member.
     *
     * @param personalLedger the user's personal ledger, the settings' own
     * @param sharedAccountId null for the account FAMILY_DEBT
     * @throws RuleViolationException if the ledger has no account {@code sharedAccountId}
     */
    @Transactional
    public SettingsView update(LedgerScope personalLedger, String baseCurrency, Long sharedAccountId,
            BigDecimal defaultShareRatio) {
        if (sharedAccountId != null
                && accounts.find(personalLedger, sharedAccountId).isEmpty()) {
            throw new RuleViolationException("account %d does not exist".formatted(sharedAccountId));
        }
        UserSettings updated = new UserSettings(personalLedger.userId(), baseCurrency, sharedAccountId,
                defaultShareRatio);
        settings.save(updated);
        return view(updated);
    }

    private static SettingsView view(UserSettings settings) {
        return new SettingsView(settings.baseCurrency(), settings.sharedAccountId(),
                Money.normalize(settings.defaultShareRatio()));
    }
}
