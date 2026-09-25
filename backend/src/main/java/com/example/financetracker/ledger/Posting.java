package com.example.financetracker.ledger;

import java.math.BigDecimal;

import org.springframework.data.relational.core.mapping.Table;

/**
 * One line of a {@link JournalEntry}, stored and replaced only with it. Its row's {@code entry_id} and {@code line_no}
 * are its entry and its index in {@link JournalEntry#postings()}. It has no user id: it belongs to its entry's user.
 */
@Table("posting")
public record Posting(Long accountId, String currency, BigDecimal amount, Long categoryId, Long counterpartyId) {
}
