package com.example.financetracker.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.domain.EntryDraft;
import com.example.financetracker.ledger.domain.EntryKind;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A journal entry and its postings: one aggregate, which Spring Data JDBC saves and deletes as a whole. Saving an
 * existing entry deletes all its postings and inserts the current ones. The save updates the entry only while
 * {@code version} is still what was read, and otherwise fails with an OptimisticLockingFailureException.
 */
@Table("journal_entry")
public record JournalEntry(@Id Long id, String userId, LocalDate entryDate, EntryKind kind, Long payeeId, String memo,
        Long importBatchId, String externalRef, @Version Integer version, @ReadOnlyProperty Instant createdAt,
        Instant updatedAt, @MappedCollection(idColumn = "entry_id", keyColumn = "line_no") List<Posting> postings) {

    static JournalEntry create(String userId, EntryDraft draft, Instant now) {
        return new JournalEntry(null, userId, draft.entryDate(), draft.kind(), draft.payeeId(), draft.memo(), null,
                null, null, null, now, postings(draft));
    }

    /** This entry with the draft's fields and postings. Its id, owner, import origin and version stay. */
    JournalEntry replacedBy(EntryDraft draft, Instant now) {
        return new JournalEntry(id, userId, draft.entryDate(), draft.kind(), draft.payeeId(), draft.memo(),
                importBatchId, externalRef, version, createdAt, now, postings(draft));
    }

    private static List<Posting> postings(EntryDraft draft) {
        return draft.postings().stream()
                .map(p -> new Posting(p.accountId(), p.currency(), p.amount(), p.categoryId(), p.counterpartyId()))
                .toList();
    }
}
