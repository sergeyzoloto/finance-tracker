package com.example.financetracker.ledger.api;

import java.math.BigDecimal;

import com.example.financetracker.api.CurrencyCode;
import com.example.financetracker.ledger.SettingsService;
import com.example.financetracker.ledger.SettingsView;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
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

    private final SettingsService settings;

    SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    SettingsView get(CurrentUser user) {
        return settings.get(user.id());
    }

    /** Replaces the settings. A shared account the user doesn't have is refused with 422. */
    @PutMapping
    SettingsView update(CurrentUser user, @Valid @RequestBody SettingsRequest request) {
        return settings.update(user.id(), request.baseCurrency(), request.sharedAccountId(),
                request.defaultShareRatio());
    }
}
