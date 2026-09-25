package com.example.financetracker.ledger;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

/** Every lookup is scoped by user id; callers never load an entry by id alone. */
public interface JournalEntryRepository extends CrudRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByIdAndUserId(long id, String userId);
}
