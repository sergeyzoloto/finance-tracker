package com.example.financetracker.ledger;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Whom an entry is with (its payee), or whom a posting's balance is owed by or to (rule 8). */
@Table("counterparty")
public record Counterparty(@Id Long id, String userId, String name, Kind kind, Instant archivedAt) {

    /** Null until classified: the Excel ledger doesn't say. */
    public enum Kind { MERCHANT, PERSON, ORGANIZATION }
}
