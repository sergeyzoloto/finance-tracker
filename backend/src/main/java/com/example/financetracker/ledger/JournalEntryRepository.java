package com.example.financetracker.ledger;

import java.util.List;
import java.util.Optional;

import com.example.financetracker.ledger.access.LedgerScope;
import org.springframework.data.jdbc.repository.query.Query;

/**
 * Every lookup is scoped by ledger id; there is none by id alone ({@link LedgerScopedRepository}). Callers use the
 * default methods, which take a {@link LedgerScope}; the derived queries with a raw ledger id are called only from
 * them (ADR 0003, topic C; {@code ArchitectureTests}).
 */
public interface JournalEntryRepository extends LedgerScopedRepository<JournalEntry, Long> {

    default Optional<JournalEntry> find(LedgerScope ledger, long id) {
        return findByIdAndLedgerId(id, ledger.ledgerId());
    }

    Optional<JournalEntry> findByIdAndLedgerId(long id, long ledgerId);

    /** The identities in their files of all the ledger's imported entries. */
    @Query("""
            SELECT external_ref FROM journal_entry
            WHERE ledger_id = :#{#ledger.ledgerId()} AND external_ref IS NOT NULL""")
    List<String> findExternalRefs(LedgerScope ledger);

    /** Deletes the entry with its postings, if it is still at the version it was loaded with. */
    void delete(JournalEntry entry);
}
