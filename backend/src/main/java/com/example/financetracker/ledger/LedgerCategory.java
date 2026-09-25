package com.example.financetracker.ledger;

import java.time.Instant;

import com.example.financetracker.ledger.domain.CategoryType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A category of the ledger (rule 5), set on postings to EQUITY accounts. Not to be confused with V1's table
 * {@code categories}, which nothing uses any more.
 */
@Table("category")
public record LedgerCategory(@Id Long id, String userId, String code, String name, CategoryType type,
        Instant archivedAt) {
}
