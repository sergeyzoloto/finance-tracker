package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.FamilyImportSync;
import com.example.financetracker.ledger.family.ImportSync;
import com.example.financetracker.ledger.family.ImportSync.Known;
import com.example.financetracker.ledger.family.ImportSync.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The import's sync fields on family records (F8d; D-48, D-86; V13): the reference of a row of the source, its content
 * hash, the record's version as the import left it and the member who imported it. There is no endpoint (D3b's import
 * calls the service), so the tests call it as the import would, in a transaction, as a member, and the records are made
 * and changed through the API as a member makes and changes them.
 */
class FamilyImportSyncApiTests extends FamilyApiTest {

    @Autowired
    private FamilyImportSync sync;

    @Autowired
    private LedgerAccess access;

    @Autowired
    private TransactionTemplate transaction;

    private LedgerScope as(String user) {
        return access.member(user, family);
    }

    private void stamp(String user, long record, String reference, String hash) {
        transaction.executeWithoutResult(status -> sync.stamp(as(user), record, reference, hash));
    }

    private Known known(String user, String reference) {
        return sync.known(as(user), List.of(reference)).get(reference);
    }

    private long newRecord(String amount) throws IOException {
        return created(post(alice, uri + "/records", expense("2026-09-10", groceries, amount, kid, ""))).get("id")
                .asLong();
    }

    /** A row of the source is new, then unchanged, then edited and written over, as its ID never changes (D-86). */
    @Test
    void anEditedRowIsRecognisedByItsPermanentIdAndWrittenOverWhenNobodyChangedItInTheApp() throws IOException {
        String reference = "xls:export-2026-10-01:1042";
        String hash = ImportSync.contentHash("2026-09-10", "GROCERIES", "12.50", "EUR");
        assertThat(ImportSync.recognise(known(bob, reference), hash)).isEqualTo(Outcome.NEW);

        long record = newRecord("12.50");
        stamp(bob, record, reference, hash);
        Known imported = known(alice, reference);
        assertThat(imported.recordId()).isEqualTo(record);
        assertThat(imported.contentHash()).isEqualTo(hash);
        assertThat(imported.importedVersion()).isEqualTo(imported.version()).isZero();
        assertThat(ImportSync.recognise(imported, hash)).isEqualTo(Outcome.UNCHANGED);
        // Who imported it is on the record, for every member, and no reference, hash or version is.
        JsonNode view = ok(get(alice, uri + "/records/" + record));
        assertThat(view.get("importedBy").get("displayName").asText()).isEqualTo("Dad");
        assertThat(view.has("importedAt")).isTrue();
        assertThat(view.toString()).doesNotContain(reference).doesNotContain(hash).doesNotContain("importedVersion");

        // The Excel row is edited (its ID stays): recognised by the ID, and the app didn't touch the record.
        String edited = ImportSync.contentHash("2026-09-10", "GROCERIES", "13.00", "EUR");
        assertThat(ImportSync.recognise(imported, edited)).isEqualTo(Outcome.EDITED);
        ok(patch(alice, uri + "/records/%d?version=0".formatted(record), "{\"amount\": \"13.00\"}"));
        stamp(alice, record, reference, edited);
        Known again = known(bob, reference);
        assertThat(again.version()).isEqualTo(1);
        assertThat(again.importedVersion()).isEqualTo(1);
        assertThat(ImportSync.recognise(again, edited)).isEqualTo(Outcome.UNCHANGED);
        // The last importer is the one who wrote it last.
        assertThat(ok(get(alice, uri + "/records/" + record)).get("importedBy").get("displayName").asText())
                .isEqualTo("Mum");

        // A member edits the record in the app: a source row that changed too is a conflict, one that didn't is not.
        ok(patch(alice, uri + "/records/%d?version=1".formatted(record), "{\"comment\": \"Mum's\"}"));
        Known touched = known(alice, reference);
        assertThat(touched.version()).isEqualTo(2);
        assertThat(touched.importedVersion()).isEqualTo(1);
        String editedAgain = ImportSync.contentHash("2026-09-10", "GROCERIES", "14.00", "EUR");
        assertThat(ImportSync.recognise(touched, editedAgain)).isEqualTo(Outcome.CONFLICT);
        assertThat(ImportSync.recognise(touched, edited)).isEqualTo(Outcome.UNCHANGED);

        // Deleted in the app: it stays deleted, and the reference is still its.
        assertThat(delete(alice, uri + "/records/%d?version=2".formatted(record))).hasStatus(HttpStatus.NO_CONTENT);
        Known gone = known(alice, reference);
        assertThat(gone.deleted()).isTrue();
        assertThat(ImportSync.recognise(gone, editedAgain)).isEqualTo(Outcome.DELETED);
        assertThat(ok(get(alice, uri + "/records")).get("content")).isEmpty();
    }

    /** A reference names one record per family ledger, and only the ledger's own members' records. */
    @Test
    void aReferenceNamesOneRecordPerFamilyLedger() throws IOException {
        long one = newRecord("5");
        long two = newRecord("6");
        String hash = ImportSync.contentHash("a");
        stamp(alice, one, "xls:f:1", hash);
        assertThatThrownBy(() -> stamp(alice, two, "xls:f:1", hash)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("has the import reference xls:f:1 already");
        stamp(alice, one, "xls:f:1", ImportSync.contentHash("b"));
        stamp(alice, two, "xls:f:2", hash);
        Map<String, Known> both = sync.known(as(bob), List.of("xls:f:1", "xls:f:2", "xls:f:3"));
        assertThat(both).containsOnlyKeys("xls:f:1", "xls:f:2");
        assertThat(both.get("xls:f:2").recordId()).isEqualTo(two);
        // Another family ledger sees none of them, and a record of another ledger can't be stamped from this one.
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        long elsewhere = newFamily(carol, """
                {"name": "Elsewhere", "baseCurrency": "EUR", "displayName": "Carol"}""").get("id").asLong();
        assertThat(sync.known(access.member(carol, elsewhere), List.of("xls:f:1"))).isEmpty();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> sync.stamp(as(alice), 9_000_000_000L,
                "xls:f:9", hash))).hasMessageContaining("not found");
        // A record entered in the app has none.
        assertThat(ok(get(alice, uri + "/records/" + one)).has("importedBy")).isTrue();
        long plain = newRecord("7");
        assertThat(ok(get(alice, uri + "/records/" + plain)).has("importedBy")).isFalse();
    }
}
