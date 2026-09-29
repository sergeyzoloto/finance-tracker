package com.example.financetracker.ledger;

import java.time.Instant;

import com.example.financetracker.ledger.domain.CategoryType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A category of the ledger (rule 5), set on postings to EQUITY accounts. Not to be confused with V1's table
 * {@code categories}, which nothing uses any more.
 *
 * @param userId the sub of the personal ledger's member, until the cleanup migration (ADR 0003, topic A)
 */
@Table("category")
public record LedgerCategory(@Id Long id, String userId, Long ledgerId, String code, String name, CategoryType type,
        Instant archivedAt) {
}
