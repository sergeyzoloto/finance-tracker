package com.example.financetracker.ledger;

import java.util.Optional;

/** Every lookup is scoped by user id; there is none by id alone ({@link OwnedRepository}). */
public interface ImportBatchRepository extends OwnedRepository<ImportBatch, Long> {

    Optional<ImportBatch> findByIdAndUserId(long id, String userId);
}
