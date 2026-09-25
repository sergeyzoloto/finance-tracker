package com.example.financetracker.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Every lookup is scoped by user id; callers never load an entry by id alone. */
public interface JournalEntryRepository extends CrudRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByIdAndUserId(long id, String userId);

    /** The identities in their files of all the user's imported entries. */
    @Query("SELECT external_ref FROM journal_entry WHERE user_id = :userId AND external_ref IS NOT NULL")
    List<String> findExternalRefsByUserId(String userId);
}
