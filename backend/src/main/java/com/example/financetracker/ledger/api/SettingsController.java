package com.example.financetracker.ledger.api;

import java.math.BigDecimal;
import java.util.List;

import com.example.financetracker.api.CurrencyCode;
import com.example.financetracker.ledger.SettingsService;
import com.example.financetracker.ledger.SettingsService.TimeZoneView;
import com.example.financetracker.ledger.SettingsView;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
class SettingsController {

    /**
     * All of the user's settings.
     *
     * @param sharedAccountId the account that receives the other part of a shared expense (rule 7); null for
     *        FAMILY_DEBT
     * @param defaultShareRatio the other part's share when a shared expense doesn't set one, such as "0.50"
     */
    record SettingsRequest(@NotNull @CurrencyCode String baseCurrency, Long sharedAccountId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
            @Digits(integer = 1, fraction = 4) BigDecimal defaultShareRatio) {
    }

    /** @param timeZone an IANA zone id; an unknown one is 422 */
    record TimeZoneRequest(@NotBlank @Size(max = 64) String timeZone) {
    }

    private final SettingsService settings;

    SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    SettingsView get(CurrentUser user) {
        return settings.get(user.id());
    }

    /** Every time zone id the PUT below accepts (D-103), so that the frontend offers only those. */
    @GetMapping("/time-zones")
    List<String> timeZones() {
        return settings.timeZones();
    }

    /**
     * Sets the user's time zone (D-100, D-101), from the browser's on the first load and from the Settings page after
     * that; the answer holds the zone and today's date in it. The one thing that PUT of the settings leaves alone.
     */
    @PutMapping("/time-zone")
    TimeZoneView setTimeZone(CurrentUser user, @Valid @RequestBody TimeZoneRequest request) {
        return settings.setTimeZone(user.id(), request.timeZone());
    }

    /** Replaces the settings. A shared account the user doesn't have is refused with 422. */
    @PutMapping
    SettingsView update(LedgerScope ledger, @Valid @RequestBody SettingsRequest request) {
        return settings.update(ledger, request.baseCurrency(), request.sharedAccountId(),
                request.defaultShareRatio());
    }
}
