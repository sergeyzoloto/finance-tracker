package com.example.financetracker.ledger;

/**
 * A partial update of a counterparty: fields left null stay as they are.
 *
 * @param archived true archives the counterparty, false restores it
 * @param changesKind whether to set the kind to {@code kind}, which may be null for unclassified
 */
public record CounterpartyChanges(String name, Boolean archived, boolean changesKind, Counterparty.Kind kind) {
}
