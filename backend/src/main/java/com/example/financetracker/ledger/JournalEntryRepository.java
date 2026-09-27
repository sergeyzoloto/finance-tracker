package com.example.financetracker.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Query;

/** Every lookup is scoped by user id; there is none by id alone ({@link OwnedRepository}). */
public interface JournalEntryRepository extends OwnedRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByIdAndUserId(long id, String userId);

    /** The identities in their files of all the user's imported entries. */
    @Query("SELECT external_ref FROM journal_entry WHERE user_id = :userId AND external_ref IS NOT NULL")
    List<String> findExternalRefsByUserId(String userId);

    /** Deletes the entry with its postings, if it is still at the version it was loaded with. */
    void delete(JournalEntry entry);
}
