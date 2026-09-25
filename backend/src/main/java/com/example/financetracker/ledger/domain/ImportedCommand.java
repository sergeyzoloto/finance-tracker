package com.example.financetracker.ledger.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * An entry read from a file, with the postings and the kind an importer worked out for it. As with a
 * {@link ManualCommand}, the postings are saved as given, and only {@link LedgerValidator} stands between them and
 * the ledger. Unlike a manual entry, it keeps the kind the importer inferred, as a hint for the UI (rule 6).
 */
public record ImportedCommand(LocalDate entryDate, Long payeeId, String memo, EntryKind kind,
        List<PostingLine> postings) implements EntryCommand {

    public ImportedCommand {
        Problems.ofEntry(entryDate)
                .present(kind, "kind")
                .present(postings, "list of postings")
                .check(postings == null || postings.stream().allMatch(Objects::nonNull), "a posting is missing")
                .throwIfAny();
        postings = List.copyOf(postings);
    }

    @Override
    public List<PostingLine> postings(LedgerContext context) {
        return postings;
    }
}
