package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.data.relational.core.mapping.Table;

/**
 * Units of the quote currency for one unit of the base currency on a day. Shared by all users; the ledger itself never
 * converts between currencies. Its key is the date and the two currencies together, so it has no single id.
 */
@Table("exchange_rate")
public record ExchangeRate(LocalDate rateDate, String baseCurrency, String quoteCurrency, BigDecimal rate,
        String source) {
}
