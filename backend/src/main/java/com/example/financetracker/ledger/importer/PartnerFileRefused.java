package com.example.financetracker.ledger.importer;

import java.util.List;

import com.example.financetracker.ledger.RuleViolationException;

/**
 * The partner's file holds a column or a row that the import page's filter never lets through (D-140): the whole
 * request is refused (422), and nothing of it is read or written. The reasons name columns and row numbers only.
 */
public class PartnerFileRefused extends RuleViolationException {

    PartnerFileRefused(List<String> reasons) {
        super(reasons);
    }
}
