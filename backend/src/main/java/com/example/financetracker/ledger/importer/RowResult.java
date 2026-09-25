package com.example.financetracker.ledger.importer;

import java.util.List;

/** What {@link EntryMapper} made of one row of the journal. Warnings come with every outcome. */
sealed interface RowResult {

    List<String> warnings();

    record Entry(EntryPlan plan, List<String> warnings) implements RowResult {
    }

    /** @param zeroFxGain whether the row is an FX gain with Sum 0 */
    record Skipped(String reason, boolean zeroFxGain, List<String> warnings) implements RowResult {
    }

    record Failed(List<String> errors, List<String> warnings) implements RowResult {
    }
}
