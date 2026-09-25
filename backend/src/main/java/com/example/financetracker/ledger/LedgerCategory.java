package com.example.financetracker.ledger;

import java.time.Instant;

import com.example.financetracker.ledger.domain.CategoryType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A category of the ledger (rule 5), set on postings to EQUITY accounts. Not to be confused with V1's
 * {@link com.example.financetracker.category.Category}, which the current API still uses.
 */
@Table("category")
public record LedgerCategory(@Id Long id, String userId, String code, String name, CategoryType type,
        Instant archivedAt) {
}
