package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.domain.EntryKind;

/**
 * An entry the importer is about to write. Counterparties are named rather than referred to by id, since the import
 * creates those it doesn't find.
 *
 * @param fxGain whether the entry books an FX gain as income, which rule 9 would count twice
 */
record EntryPlan(String externalRef, LocalDate date, EntryKind kind, Party payee, String memo, List<Line> postings,
        boolean fxGain) {

    EntryPlan {
        postings = List.copyOf(postings);
    }

    /** A posting, signed by rule 2. */
    record Line(long accountId, String currency, BigDecimal amount, Long categoryId, Party counterparty) {
    }

    /**
     * A counterparty to find by name, case-insensitively, or to create.
     *
     * @param kind for a new counterparty; null leaves it unclassified
     */
    record Party(String name, Counterparty.Kind kind) {
    }
}
