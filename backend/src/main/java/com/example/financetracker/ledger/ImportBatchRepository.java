package com.example.financetracker.ledger;

import java.util.Optional;

import com.example.financetracker.ledger.access.LedgerScope;

/**
 * Every lookup is scoped by ledger id; there is none by id alone ({@link LedgerScopedRepository}). Callers use the
 * default methods, which take a {@link LedgerScope}; the derived queries with a raw ledger id are called only from
 * them (ADR 0003, topic C; {@code ArchitectureTests}).
 */
public interface ImportBatchRepository extends LedgerScopedRepository<ImportBatch, Long> {

    default Optional<ImportBatch> find(LedgerScope ledger, long id) {
        return findByIdAndLedgerId(id, ledger.ledgerId());
    }

    Optional<ImportBatch> findByIdAndLedgerId(long id, long ledgerId);
}
