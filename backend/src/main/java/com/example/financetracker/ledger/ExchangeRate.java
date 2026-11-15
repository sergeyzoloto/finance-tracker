package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.data.relational.core.mapping.Table;

/**
 * Units of the quote currency for one unit of the base currency on a day. The base currency is always EUR: a cross
 * rate is computed through the euro. Its key is the date, the two currencies, the owner and the source together, so it has no
 * single id.
 *
 * @param userId null for a rate of the ECB or the CBR, which all users share; the Keycloak "sub" of the user who entered a
 *        manual rate, which only that user's reports use (rule 11)
 * @param source ECB, CBR or MANUAL
 */
@Table("exchange_rate")
public record ExchangeRate(LocalDate rateDate, String baseCurrency, String quoteCurrency, BigDecimal rate,
        String source, String userId) {
}
