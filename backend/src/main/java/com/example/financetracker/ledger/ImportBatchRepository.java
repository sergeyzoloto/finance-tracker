package com.example.financetracker.ledger;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/** Every lookup is scoped by user id; callers never load a batch by id alone. */
public interface ImportBatchRepository extends CrudRepository<ImportBatch, Long> {

    Optional<ImportBatch> findByIdAndUserId(long id, String userId);
}
