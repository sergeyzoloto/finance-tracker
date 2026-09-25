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
 */
public record EntryView(long id, int version, LocalDate entryDate, EntryKind kind, Long payeeId, String memo,
        List<PostingLine> postings) {

    static EntryView of(JournalEntry entry) {
        return new EntryView(entry.id(), entry.version(), entry.entryDate(), entry.kind(), entry.payeeId(),
                entry.memo(), entry.postings().stream()
                        .map(p -> new PostingLine(p.accountId(), p.currency(), Money.normalize(p.amount()),
                                p.categoryId(), p.counterpartyId()))
                        .toList());
    }
}
