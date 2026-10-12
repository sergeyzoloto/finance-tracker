package com.example.financetracker.ledger.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.domain.EntryKind;

/**
 * A personal entry the importer is about to write. Counterparties are named rather than referred to by id, since the
 * import creates those it doesn't find.
 *
 * @param externalRef the entry's reference in its ledger: {@code xls:<ID>} (D-142), or the opening balances'
 * @param contentHash what the row held, for the next import to tell an edit (D-139)
 * @param fxGain whether the entry books an FX gain as income, which rule 9 would count twice
 * @param drift what its rounding makes the balances differ from Excel's (D-68)
 */
record EntryPlan(String externalRef, String contentHash, LocalDate date, EntryKind kind, Party payee, String memo,
        List<Line> postings, boolean fxGain, List<Drift> drift) {

    EntryPlan {
        postings = List.copyOf(postings);
        drift = List.copyOf(drift);
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
