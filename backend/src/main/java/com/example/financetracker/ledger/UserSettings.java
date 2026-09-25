package com.example.financetracker.ledger;

import java.math.BigDecimal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * @param sharedAccountId receives the other part of a shared expense (rule 7); null for the account FAMILY_DEBT
 * @param defaultShareRatio the other part's share when a shared expense doesn't set one
 */
@Table("user_settings")
public record UserSettings(@Id String userId, String baseCurrency, Long sharedAccountId,
        BigDecimal defaultShareRatio) {
}
