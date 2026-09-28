package com.example.financetracker.ledger.demo;

import java.time.LocalDate;
import java.util.Map;

import com.example.financetracker.ledger.domain.EntryKind;

/**
 * What loading the demo created.
 *
 * @param entriesByKind the entries, counted by kind
 * @param accounts the accounts the demo added to the starter ledger's
 * @param categories the categories the demo added to the starter ledger's
 * @param counterparties the counterparties it created
 * @param from the first entry's date: the opening balances, on the first day of the ledger's first month
 * @param to the last entry's date: the day it was loaded
 */
public record DemoLedgerView(Map<EntryKind, Integer> entriesByKind, int accounts, int categories,
        int counterparties, LocalDate from, LocalDate to) {
}
