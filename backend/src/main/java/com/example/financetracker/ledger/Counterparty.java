package com.example.financetracker.ledger;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Whom an entry is with (its payee), or whom a posting's balance is owed by or to (rule 8).
 *
 * @param userId the sub of the personal ledger's member, until the cleanup migration (ADR 0003, topic A)
 */
@Table("counterparty")
public record Counterparty(@Id Long id, String userId, Long ledgerId, String name, Kind kind, Instant archivedAt) {

    /** Null until classified: the Excel ledger doesn't say. */
    public enum Kind { MERCHANT, PERSON, ORGANIZATION }
}
