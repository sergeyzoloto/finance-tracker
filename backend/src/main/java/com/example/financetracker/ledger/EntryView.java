package com.example.financetracker.ledger;

import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.domain.PostingLine;

/**
 * A journal entry as {@link EntryService} hands it out; entities stay inside the service. Postings keep their order,
 * and amounts read the same whether just saved or read back: {@link Money#normalize}d.
 *
 * @param version what to pass back to update or delete the entry
 * @param family what a family budget has to do with the entry, or null for an entry of the user's own (additive, F4a)
 */
public record EntryView(long id, int version, LocalDate entryDate, EntryKind kind, Long payeeId, String memo,
        List<PostingLine> postings, EntryFamily family) {

    static EntryView of(JournalEntry entry) {
        return of(entry, null);
    }

    static EntryView of(JournalEntry entry, EntryFamily family) {
        return new EntryView(entry.id(), entry.version(), entry.entryDate(), entry.kind(), entry.payeeId(),
                entry.memo(), entry.postings().stream()
                        .map(p -> new PostingLine(p.accountId(), p.currency(), Money.normalize(p.amount()),
                                p.categoryId(), p.counterpartyId()))
                        .toList(), family);
    }

    EntryView withFamily(EntryFamily family) {
        return new EntryView(id, version, entryDate, kind, payeeId, memo, postings, family);
    }
}
