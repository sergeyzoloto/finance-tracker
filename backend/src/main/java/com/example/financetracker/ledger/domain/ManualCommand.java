package com.example.financetracker.ledger.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Raw postings, for corrections. They are saved as given, and only {@link LedgerValidator} stands between them and
 * the ledger.
 */
public record ManualCommand(LocalDate entryDate, Long payeeId, String memo, List<PostingLine> postings)
        implements EntryCommand {

    public ManualCommand {
        Problems.ofEntry(entryDate)
                .present(postings, "list of postings")
                .check(postings == null || postings.stream().allMatch(Objects::nonNull), "a posting is missing")
                .throwIfAny();
        postings = List.copyOf(postings);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.MANUAL;
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        return postings;
    }
}
