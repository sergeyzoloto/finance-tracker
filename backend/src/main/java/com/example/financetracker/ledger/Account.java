package com.example.financetracker.ledger;

import java.time.Instant;

import com.example.financetracker.ledger.domain.AccountType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/** A ledger account (rule 4). Once postings refer to it, it is archived, never deleted (rule 12). */
@Table("account")
public record Account(@Id Long id, String userId, String code, String name, AccountType type, String defaultCurrency,
        boolean requiresCounterparty, boolean isSystem, Instant archivedAt, @ReadOnlyProperty Instant createdAt) {
}
