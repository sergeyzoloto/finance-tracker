package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
    private final Today today;

    SettingsService(UserSettingsRepository settings, AccountRepository accounts, Today today) {
        this.settings = settings;
        this.accounts = accounts;
        this.today = today;
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

    /** The user's time zone as {@code /api/me} reports it, with today's date in it (D-100, D-101). */
    public record TimeZoneView(String timeZone, LocalDate today) {
    }

    /**
     * Every id {@link #setTimeZone} accepts (D-103): exactly {@code ZoneId.getAvailableZoneIds()}, case-sensitive, so
     * "UTC", "Etc/UTC", "Etc/GMT+5" and legacy links such as "Asia/Calcutta" are in, and an offset, an abbreviation or
     * other case are not. The frontend offers a zone only if it is in this list.
     */
    public List<String> timeZones() {
        return ZoneId.getAvailableZoneIds().stream().sorted().toList();
    }

    /**
     * Sets the user's time zone (D-100, D-101): the date there is D-53's today for everything they do from now on.
     *
     * @param userId a user the request provisioned, who therefore has a settings row
     * @param timeZone an IANA zone id such as "Europe/Amsterdam" or "UTC"; an offset or an abbreviation is no id
     * @throws RuleViolationException (422) if the id isn't a time zone the api knows
     */
    @Transactional
    public TimeZoneView setTimeZone(String userId, String timeZone) {
        if (timeZone == null || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw new RuleViolationException("'%s' is not a time zone: use an IANA name such as Europe/Amsterdam"
                    .formatted(timeZone));
        }
        if (!settings.updateTimeZone(userId, timeZone)) {
            throw new NotFoundException("Settings not found");
        }
        return new TimeZoneView(timeZone, today.date(ZoneId.of(timeZone)));
    }

    private static SettingsView view(UserSettings settings) {
        return new SettingsView(settings.baseCurrency(), settings.sharedAccountId(),
                Money.normalize(settings.defaultShareRatio()));
    }
}
