package com.example.financetracker.ledger.family;

import java.math.BigDecimal;

/** An amount in a currency, as one of a list per currency (D-45, ADR 0004). */
public record CurrencyAmount(String currency, BigDecimal amount) {
}
