package com.example.financetracker.ledger;

import java.util.Optional;

/** Every lookup is scoped by ledger id; there is none by id alone ({@link LedgerScopedRepository}). */
public interface ImportBatchRepository extends LedgerScopedRepository<ImportBatch, Long> {

    Optional<ImportBatch> findByIdAndLedgerId(long id, long ledgerId);
}
