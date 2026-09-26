package com.example.financetracker.ledger.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.api.CurrencyCode;
import com.example.financetracker.ledger.rates.ManualRate;
import com.example.financetracker.ledger.rates.RateService;
import com.example.financetracker.ledger.rates.RateService.ManualRateView;
import com.example.financetracker.ledger.rates.RateService.RatesView;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Exchange rates for the reports in the base currency: the ECB's, shared by all users, and the user's own manual
 * rates, for currencies the ECB doesn't publish, such as RUB since March 2022. Rates are units of a currency for one
 * euro; rates between other currencies are computed through the euro.
 */
@RestController
@RequestMapping("/api/rates")
class RateController {

    /**
     * A manual rate: units of {@code quote} for one unit of {@code base}, one of which is EUR, such as
     * {@code {"date": "2026-09-01", "base": "EUR", "quote": "RUB", "rate": "95.50"}}. It replaces the user's rate for
     * the same day and currency. Given as euros for one unit of another currency, it is stored inverted.
     */
    record ManualRateRequest(@NotNull LocalDate date, @NotNull @CurrencyCode String base,
            @NotNull @CurrencyCode String quote,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 8) BigDecimal rate) {
    }

    /** @param saved how many rates the file had, all saved */
    record CsvResult(int saved) {
    }

    private final RateService rates;

    RateController(RateService rates) {
        this.rates = rates;
    }

    /**
     * The latest rate of every currency, and the days on which the user's postings can't be converted to the base
     * currency for want of a rate.
     */
    @GetMapping
    RatesView overview(CurrentUser user) {
        return rates.overview(user.id());
    }

    /** The user's manual rates, newest first. */
    @GetMapping("/manual")
    List<ManualRateView> manualRates(CurrentUser user) {
        return rates.manualRates(user.id());
    }

    /** Saves a manual rate. A rate that isn't against EUR is refused with 422. */
    @PostMapping("/manual")
    ManualRateView saveManual(CurrentUser user, @Valid @RequestBody ManualRateRequest request) {
        return rates.saveManual(user.id(),
                new ManualRate(request.date(), request.base(), request.quote(), request.rate()));
    }

    /**
     * Saves every rate of a CSV file with the columns date, base, quote and rate, such as
     * {@code 2026-09-01,EUR,RUB,95.50}, or none: a file with invalid rows is refused with 422, which lists them.
     */
    @PostMapping(path = "/manual/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    CsvResult saveManualCsv(CurrentUser user, @RequestPart MultipartFile file) throws IOException {
        return new CsvResult(rates.saveManualCsv(user.id(), file.getBytes()));
    }

    /** Deletes the user's manual rate for the currency on that day; 404 if there is none. */
    @DeleteMapping("/manual")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteManual(CurrentUser user, @RequestParam LocalDate date,
            @RequestParam @CurrencyCode String currency) {
        rates.deleteManual(user.id(), date, currency);
    }
}
