package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.example.financetracker.Answers;
import com.example.financetracker.ledger.history.HistoryJobs;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * HIST-2 step 1 (ADR 0006, section 13, D-1075, D-1080): the family journal is read from the records' versions, and
 * from {@code family_record_change} only for what happened before a record's versions began. Each change shows once,
 * newest first, in the same shape, without the payer's private side.
 */
class FamilyJournalFromVersionsApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    @Autowired
    private HistoryJobs jobs;

    private long newExpense(String date, String amount) throws IOException {
        return created(post(alice, uri + "/records", expense(date, groceries, amount, mum, LATER))).get("id").asLong();
    }

    private void edit(long id, int version, String body) throws IOException {
        ok(patch(alice, uri + "/records/" + id + "?version=" + version, body));
    }

    /** The state before HIST-1 for one record: its journal rows and no version (only an erasure may delete one). */
    private void forgetVersions(long id) throws Exception {
        try (Connection db = previousImageConnection()) {
            db.setAutoCommit(false);
            db.createStatement().execute("SELECT set_config('app.writer', 'delete-all', true)");
            try (PreparedStatement delete = db.prepareStatement(
                    "DELETE FROM record_version WHERE ledger_id = ? AND record_type = 'FAMILY_RECORD' AND record_id = ?")) {
                delete.setLong(1, family);
                delete.setLong(2, id);
                delete.executeUpdate();
            }
            db.commit();
        }
    }

    private JsonNode journal(String user, Long id) throws IOException {
        return ok(get(user, uri + "/journal?size=100" + (id == null ? "" : "&recordId=" + id)));
    }

    private static List<Long> changeIds(JsonNode journal) {
        List<Long> ids = new ArrayList<>();
        journal.get("content").forEach(c -> ids.add(c.get("id").asLong()));
        return ids;
    }

    @Test
    void aRecordsCreationTwoEditsAndDeletionReadFromItsVersions() throws IOException {
        long id = newExpense("2026-09-10", "30.00");
        edit(id, 0, """
                {"date": "2026-09-11", "comment": "first"}""");
        edit(id, 1, """
                {"categoryId": %d, "split": {"method": "ONE_MEMBER", "memberId": %d}}""".formatted(rent, kid));
        assertThat(delete(alice, uri + "/records/" + id + "?version=2")).hasStatus(HttpStatus.NO_CONTENT);

        JsonNode journal = journal(bob, id);
        assertThat(journal.get("totalElements").asInt()).isEqualTo(4);
        assertThat(changes(journal)).containsExactly(
                "DELETE by Mum: ",
                "UPDATE by Mum: category Groceries→Rent, splitMethod EQUAL→ONE_MEMBER, share of Mum 10.00→null, "
                        + "share of Dad 10.00→null, share of Kid 10.00→30.00",
                "UPDATE by Mum: date 2026-09-10→2026-09-11, comment null→first",
                "CREATE by Mum: date null→2026-09-10, category null→Groceries, amount null→30.00, payer null→Mum, "
                        + "splitMethod null→EQUAL, share of Mum null→10.00, share of Dad null→10.00, "
                        + "share of Kid null→10.00");
        // Every row is a version's (their ids negated), and the shape is the journal's.
        assertThat(changeIds(journal)).allMatch(i -> i < 0);
        JsonNode first = journal.get("content").get(0);
        assertThat(first.get("currency").asText()).isEqualTo("EUR");
        assertThat(first.get("record").get("deleted").asBoolean()).isTrue();
        assertThat(journal.get("content").get(3).get("changes").get(2).get("newCurrency").asText()).isEqualTo("EUR");
        // The payer's private side is in no version and in no row (D-16, D-1022).
        Answers.assertNoneMention(journal, "UNSPECIFIED", "account", "Account");
        Set<String> familyFields = Set.of("type", "date", "category", "payer", "payee", "amount", "currency",
                "splitMethod", "comment", "deleted", "externalRef", "shares");
        assertThat(jdbc.sql("""
                SELECT DISTINCT jsonb_object_keys(content) FROM record_version
                WHERE ledger_id = ? AND record_type = 'FAMILY_RECORD'""").param(family).query(String.class).list())
                .allMatch(familyFields::contains);

        // A member who deleted their data reads "Former member".
        long bobs = created(post(bob, uri + "/records", expense("2026-09-12", groceries, "9.00", dad, LATER)))
                .get("id").asLong();
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(changes(journal(alice, bobs)).getFirst()).startsWith("CREATE by Former member: ")
                .contains("payer null→Former member");
    }

    /** An update that only set a comment of the leaver's: erased (D-20), it still shows, null to null. */
    @Test
    void anUpdateOfOnlyALeaversErasedCommentStillShows() throws IOException {
        long id = created(post(bob, uri + "/records", expense("2026-09-10", groceries, "30.00", dad, LATER)))
                .get("id").asLong();
        ok(patch(bob, uri + "/records/" + id + "?version=0", """
                {"comment": "Dad's only change"}"""));
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode journal = journal(alice, id);
        assertThat(journal.get("totalElements").asInt()).isEqualTo(2);
        assertThat(changes(journal).getFirst()).isEqualTo("UPDATE by Former member: comment null→null");
    }

    @Test
    void aRecordWithJournalRowsOnlyReadsThemAll() throws Exception {
        long id = newExpense("2026-09-10", "30.00");
        edit(id, 0, """
                {"comment": "before versions"}""");
        forgetVersions(id);
        JsonNode journal = journal(alice, id);
        assertThat(changes(journal)).hasSize(2).first().asString()
                .isEqualTo("UPDATE by Mum: comment null→before versions");
        assertThat(changeIds(journal)).allMatch(i -> i > 0);
    }

    /** The 13 records with a second journal row: rows before the baseline, diffs after it, each once. */
    @Test
    void journalRowsBeforeTheBaselineAndVersionsAfterItShowEachChangeOnce() throws Exception {
        long id = newExpense("2026-09-10", "30.00");
        edit(id, 0, """
                {"comment": "old"}""");
        forgetVersions(id);
        jobs.baseline();
        assertThat(jdbc.sql("SELECT op FROM record_version WHERE ledger_id = ? AND record_type = 'FAMILY_RECORD' "
                + "AND record_id = ? ORDER BY seq").param(family).param(id).query(String.class).list())
                .containsExactly("BASELINE");
        edit(id, 1, """
                {"comment": "new"}""");
        edit(id, 2, """
                {"date": "2026-09-12"}""");
        JsonNode journal = journal(alice, id);
        assertThat(journal.get("totalElements").asInt()).isEqualTo(4);
        assertThat(changes(journal)).containsExactly(
                "UPDATE by Mum: date 2026-09-10→2026-09-12",
                "UPDATE by Mum: comment old→new",
                "UPDATE by Mum: comment null→old",
                "CREATE by Mum: date null→2026-09-10, category null→Groceries, amount null→30.00, payer null→Mum, "
                        + "splitMethod null→EQUAL, share of Mum null→10.00, share of Dad null→10.00, "
                        + "share of Kid null→10.00");
        List<Long> ids = changeIds(journal);
        assertThat(ids.subList(0, 2)).allMatch(i -> i < 0);
        assertThat(ids.subList(2, 4)).allMatch(i -> i > 0);
    }

    /**
     * The cut-over in one transaction: the first change after the journal rows writes the lazy baseline, the version and
     * a journal row at the same instant. The versions own that change, so it shows once.
     */
    @Test
    void theJournalRowOfTheBaselinesOwnTransactionIsShownOnceFromTheVersions() throws Exception {
        long id = newExpense("2026-09-10", "30.00");
        forgetVersions(id);
        edit(id, 0, """
                {"comment": "first change with versions"}""");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM record_version v JOIN family_record_change c
                    ON c.record_id = v.record_id AND c.changed_at = v.at AND c.action = 'UPDATE'
                WHERE v.ledger_id = ? AND v.record_type = 'FAMILY_RECORD' AND v.record_id = ? AND v.op = 'BASELINE'""")
                .param(family).param(id).query(Long.class).single()).isEqualTo(1);
        JsonNode journal = journal(alice, id);
        assertThat(changes(journal)).containsExactly("UPDATE by Mum: comment null→first change with versions",
                "CREATE by Mum: date null→2026-09-10, category null→Groceries, amount null→30.00, payer null→Mum, "
                        + "splitMethod null→EQUAL, share of Mum null→10.00, share of Dad null→10.00, "
                        + "share of Kid null→10.00");
        assertThat(changeIds(journal).get(0)).isNegative();
        assertThat(changeIds(journal).get(1)).isPositive();
    }

    @Test
    void pagesAcrossBothSourcesAreStableAndWhole() throws Exception {
        long legacy = newExpense("2026-09-05", "12.00");
        edit(legacy, 0, """
                {"comment": "legacy"}""");
        forgetVersions(legacy);
        long versioned = newExpense("2026-09-06", "15.00");
        edit(versioned, 0, """
                {"comment": "versioned"}""");
        edit(legacy, 1, """
                {"comment": "after the cut-over"}""");

        JsonNode all = journal(alice, null);
        assertThat(all.get("totalElements").asInt()).isEqualTo(5);
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode one = ok(get(alice, uri + "/journal?size=2&page=" + page));
            assertThat(one.get("totalElements").asInt()).isEqualTo(5);
            assertThat(one.get("totalPages").asInt()).isEqualTo(3);
            paged.addAll(changeIds(one));
        }
        assertThat(paged).isEqualTo(changeIds(all)).doesNotHaveDuplicates();
        assertThat(changes(all)).containsExactly(
                "UPDATE by Mum: comment legacy→after the cut-over",
                "UPDATE by Mum: comment null→versioned",
                changes(all).get(2),
                "UPDATE by Mum: comment null→legacy",
                changes(all).get(4));
        assertThat(changes(all).get(2)).startsWith("CREATE by Mum: date null→2026-09-06");
        assertThat(changes(all).get(4)).startsWith("CREATE by Mum: date null→2026-09-05");
    }

    @Test
    void oldRowsOfTheSameInstantKeepTheirIdOrder() throws Exception {
        long id = newExpense("2026-09-10", "30.00");
        forgetVersions(id);
        try (Connection db = previousImageConnection()) {
            for (String change : List.of("first", "second")) {
                try (PreparedStatement insert = db.prepareStatement("""
                        INSERT INTO family_record_change (ledger_id, record_id, changed_by_member_id, action, currency,
                            changed_at, changes)
                        VALUES (?, ?, ?, 'UPDATE', 'EUR', TIMESTAMPTZ '2026-09-20 12:00:00+00',
                            jsonb_build_array(jsonb_build_object('field', 'comment', 'old', NULL, 'new', ?::text)))""")) {
                    insert.setLong(1, family);
                    insert.setLong(2, id);
                    insert.setLong(3, mum);
                    insert.setString(4, change);
                    insert.executeUpdate();
                }
            }
        }
        List<String> changes = changes(journal(alice, id));
        assertThat(changes.indexOf("UPDATE by Mum: comment null→second"))
                .isEqualTo(changes.indexOf("UPDATE by Mum: comment null→first") - 1);
    }

    /** A missing previous version (a gap) makes that version no row, and never a failure. */
    @Test
    void aVersionWithoutItsPreviousOneIsSkippedNotAFailure() throws Exception {
        long id = newExpense("2026-09-10", "30.00");
        edit(id, 0, """
                {"comment": "one"}""");
        edit(id, 1, """
                {"comment": "two"}""");
        try (Connection db = previousImageConnection()) {
            db.setAutoCommit(false);
            db.createStatement().execute("SELECT set_config('app.writer', 'delete-all', true)");
            try (PreparedStatement delete = db.prepareStatement("DELETE FROM record_version WHERE ledger_id = ? "
                    + "AND record_type = 'FAMILY_RECORD' AND record_id = ? AND seq = 2")) {
                delete.setLong(1, family);
                delete.setLong(2, id);
                delete.executeUpdate();
            }
            db.commit();
        }
        JsonNode journal = journal(alice, id);
        assertThat(journal.get("totalElements").asInt()).isEqualTo(1);
        assertThat(changes(journal).getFirst()).startsWith("CREATE by Mum: ");
    }

    @Test
    void someoneWhoIsNoMemberGetsWhatAMissingFamilyBudgetGets() throws IOException {
        long id = newExpense("2026-09-10", "30.00");
        String outsider = newUser();
        ok(get(outsider, "/api/accounts"));
        String missing = "/api/family-ledgers/9000000000";
        assertThat(detail(get(outsider, uri + "/journal?recordId=" + id), HttpStatus.NOT_FOUND)
                .replace(String.valueOf(family), "N"))
                .isEqualTo(detail(get(outsider, missing + "/journal?recordId=" + id), HttpStatus.NOT_FOUND)
                        .replace("9000000000", "N"));
    }
}
