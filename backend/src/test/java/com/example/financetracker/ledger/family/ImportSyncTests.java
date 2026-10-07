package com.example.financetracker.ledger.family;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.financetracker.ledger.family.ImportSync.Known;
import com.example.financetracker.ledger.family.ImportSync.Outcome;
import org.junit.jupiter.api.Test;

/** The rules by which an import recognises a row of its source it wrote before (D-48, D-86), in plain Java. */
class ImportSyncTests {

    private static final String HASH = ImportSync.contentHash("2026-09-10", "GROCERIES", "12.50", "EUR");

    private static Known known(String hash, int importedVersion, int version, boolean deleted) {
        return new Known(7, hash, importedVersion, version, deleted);
    }

    @Test
    void aRowWithNoRecordIsNew() {
        assertThat(ImportSync.recognise(null, HASH)).isEqualTo(Outcome.NEW);
    }

    @Test
    void aRowWithTheSameContentIsUnchangedWhateverWasDoneInTheApp() {
        assertThat(ImportSync.recognise(known(HASH, 0, 0, false), HASH)).isEqualTo(Outcome.UNCHANGED);
        // Changed in the app since (the version moved): the source didn't change, so nothing is written over it.
        assertThat(ImportSync.recognise(known(HASH, 0, 3, false), HASH)).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void anEditedRowOfARecordNobodyChangedInTheAppIsEdited() {
        String edited = ImportSync.contentHash("2026-09-10", "GROCERIES", "13.00", "EUR");
        assertThat(ImportSync.recognise(known(HASH, 2, 2, false), edited)).isEqualTo(Outcome.EDITED);
    }

    @Test
    void anEditedRowOfARecordAlsoChangedInTheAppIsAConflict() {
        String edited = ImportSync.contentHash("2026-09-10", "GROCERIES", "13.00", "EUR");
        assertThat(ImportSync.recognise(known(HASH, 2, 3, false), edited)).isEqualTo(Outcome.CONFLICT);
    }

    @Test
    void aDeletedRecordStaysDeletedWhateverTheRowSays() {
        String edited = ImportSync.contentHash("2026-09-10", "GROCERIES", "13.00", "EUR");
        assertThat(ImportSync.recognise(known(HASH, 0, 1, true), HASH)).isEqualTo(Outcome.DELETED);
        assertThat(ImportSync.recognise(known(HASH, 0, 1, true), edited)).isEqualTo(Outcome.DELETED);
    }

    @Test
    void theHashIsSha256OfTheFieldsAndNoTwoListsOfFieldsShareOne() {
        assertThat(HASH).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ImportSync.contentHash("2026-09-10", "GROCERIES", "12.50", "EUR")).isEqualTo(HASH);
        // Moving a character from one field to the next is another row; so is a missing field against an empty one.
        assertThat(ImportSync.contentHash("ab", "c")).isNotEqualTo(ImportSync.contentHash("a", "bc"));
        assertThat(ImportSync.contentHash("a", null)).isNotEqualTo(ImportSync.contentHash("a", ""));
        assertThat(ImportSync.contentHash("a;", "b")).isNotEqualTo(ImportSync.contentHash("a", ";b"));
        // A known value, so that the hash can never silently change (an import's stored hashes depend on it).
        assertThat(ImportSync.contentHash("a", "b")).isEqualTo("d3bfd0a218bb20b3c8a571e4fff25d38b41887aa708d3f87c0fa2ae170bed6d9");
    }
}
