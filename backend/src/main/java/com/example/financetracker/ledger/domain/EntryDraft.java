package com.example.financetracker.ledger.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** An entry as a command built it, before {@link LedgerValidator} checks it. */
public record EntryDraft(LocalDate entryDate, EntryKind kind, Long payeeId, String memo, List<PostingLine> postings) {

    public EntryDraft {
        postings = List.copyOf(postings);
    }

    public Set<Long> accountIds() {
        return ids(PostingLine::accountId);
    }

    public Set<Long> categoryIds() {
        return ids(PostingLine::categoryId);
    }

    /** Of the postings and the payee. */
    public Set<Long> counterpartyIds() {
        return Stream.concat(ids(PostingLine::counterpartyId).stream(), Stream.ofNullable(payeeId))
                .collect(Collectors.toSet());
    }

    private Set<Long> ids(Function<PostingLine, Long> id) {
        return postings.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
