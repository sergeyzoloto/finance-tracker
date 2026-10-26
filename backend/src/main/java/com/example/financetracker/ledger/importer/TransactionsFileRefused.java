package com.example.financetracker.ledger.importer;

import java.util.List;

import com.example.financetracker.ledger.RuleViolationException;

/**
 * A transactions file the import refuses as a whole, before it writes anything (D4b): a header other than D-235's, or
 * accounts and categories that don't fit together, within the file or with the app's (D-229, D-262). The reasons name
 * columns, codes and IDs, never a name or an amount.
 */
public class TransactionsFileRefused extends RuleViolationException {

    TransactionsFileRefused(List<String> reasons) {
        super(reasons);
    }
}
