package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * An amount in some currency as a family ledger would take it on a day (F4e; D-13): its base amount in the ledger's base
 * currency, and how it was found, for the forms to show before they save. The rate is the one a record of the member
 * who asks would use: the ECB's, or where the ECB has none their own manual rate, never anyone else's.
 *
 * @param baseAmount null if no rate converts it: the record then needs its base amount entered ({@code RATE_MISSING})
 * @param rate units of the base currency for one of {@code currency}; left out in the base currency or without a rate
 * @param rateSource ECB or MANUAL; left out with {@code rate}
 * @param rateDate the day of the rate, on or before the date asked about; left out with {@code rate}
 */
public record FamilyConversion(BigDecimal amount, String currency, BigDecimal baseAmount, String baseCurrency,
        @JsonInclude(Include.NON_NULL) BigDecimal rate, @JsonInclude(Include.NON_NULL) String rateSource,
        @JsonInclude(Include.NON_NULL) LocalDate rateDate) {
}
