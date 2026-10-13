package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.importer.ImportFixtures.bytes;
import static com.example.financetracker.ledger.importer.ImportFixtures.partnerRows;
import static com.example.financetracker.ledger.importer.ImportFixtures.withField;
import static com.example.financetracker.ledger.importer.ImportFixtures.withLine;
import static com.example.financetracker.ledger.importer.ImportFixtures.withoutRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.example.financetracker.Answers;
import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * POST /api/import over the synthetic export of {@code src/test/resources/import/} (D3b): the owner's personal and
 * family rows and the partner's family rows into a family budget and the partner's seat (D-82, D-141), the
 * reconciliation with the daily balance file, and the sync by permanent IDs (D-138, D-139, D-142, D-150).
 */
class ImportApiTests extends LedgerApiTest {

    /** What the export holds, by source and kind: created on a first import. */
    private static final int CREATED = 33;

    private final String owner = newUser();
    private long family;
    private long ownerMember;
    private long seat;

    @BeforeEach
    void createTheFamilyBudgetAndTheSeat() throws IOException {
        ok(get(owner, "/api/accounts"));
        JsonNode created = newFamily(owner, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Owner", "startDate": "2024-01-01",
                 "categoryIds": []}""");
        family = created.get("id").asLong();
        ownerMember = created.get("memberId").asLong();
        seat = body(post(owner, "/api/family-ledgers/" + family + "/members", """
                {"displayName": "Sam"}"""), HttpStatus.CREATED).get("id").asLong();
    }

    @AfterEach
    void theInvariantsHold() {
        FamilyInvariants.check(jdbc, family);
    }

    private JsonNode run(byte[] transactions, byte[] partner, String dryRun) throws IOException {
        return ok(importExport(owner, transactions, partner, family, seat, dryRun));
    }

    private JsonNode run(String dryRun) throws IOException {
        return run(bytes("transactions.csv"), partnerRows(), dryRun);
    }

    private static String count(JsonNode report, String source, String kind) {
        for (JsonNode c : report.get("counts")) {
            if (c.get("source").asText().equals(source) && c.get("kind").asText().equals(kind)) {
                return "c%d u%d n%d x%d e%d s%d i%d".formatted(c.get("created").asInt(), c.get("updated").asInt(),
                        c.get("unchanged").asInt(), c.get("conflicts").asInt(), c.get("failed").asInt(),
                        c.get("skipped").asInt(), c.get("notImported").asInt());
            }
        }
        return "none";
    }

    private static List<String> refs(JsonNode rows) {
        return StreamSupport.stream(rows.spliterator(), false)
                .map(r -> r.get("source").asText() + " " + r.get("excelId").asText()).toList();
    }

    private long entries() throws IOException {
        return ok(get(owner, "/api/entries?size=1")).get("totalElements").asLong();
    }

    private List<String> externalRefs() {
        return jdbc.sql("SELECT external_ref FROM family_record WHERE ledger_id = ? ORDER BY external_ref")
                .param(family).query(String.class).list();
    }

    /** A dry run writes everything, reconciles, reports and keeps nothing; it is the default. */
    @Test
    void aDryRunImportsTheWholeExportReconcilesItAndSavesNothing() throws IOException {
        JsonNode report = run(null);

        assertThat(report.get("outcome").asText()).isEqualTo("DRY_RUN");
        assertThat(report.get("errors")).isEmpty();
        assertThat(report.get("totals")).isEqualTo(Answers.json("""
                {"created": %d, "updated": 0, "unchanged": 0, "missing": 0, "conflicts": 0, "failed": 0, "skipped": 3,
                 "notImported": 1}""".formatted(CREATED)));
        assertThat(count(report, "OWNER_PERSONAL", "OPENING_BALANCE")).isEqualTo("c1 u0 n0 x0 e0 s0 i0");
        assertThat(count(report, "OWNER_FAMILY", "EXPENSE")).isEqualTo("c3 u0 n0 x0 e0 s0 i0");
        assertThat(count(report, "OWNER_FAMILY", "REFUND")).isEqualTo("c1 u0 n0 x0 e0 s0 i0");
        assertThat(count(report, "OWNER_SETTLEMENT", "SETTLEMENT")).isEqualTo("c4 u0 n0 x0 e0 s0 i0");
        assertThat(count(report, "OWNER_SETTLEMENT", "CONVERSION")).isEqualTo("c4 u0 n0 x0 e0 s0 i0");
        assertThat(count(report, "OWNER_SEAT", "INCOME")).isEqualTo("c1 u0 n0 x0 e0 s0 i1");
        assertThat(count(report, "OWNER_LUMP", "EXPENSE")).isEqualTo("c2 u0 n0 x0 e0 s0 i0");
        assertThat(count(report, "PARTNER", "EXPENSE")).isEqualTo("c4 u0 n0 x0 e0 s0 i0");
        assertThat(count(report, "PARTNER", "ZERO")).isEqualTo("c0 u0 n0 x0 e0 s1 i0");
        assertThat(report.get("period")).isEqualTo(Answers.json("""
                {"from": "2024-01-02", "to": "2024-03-08"}"""));
        assertThat(StreamSupport.stream(report.get("currencies").spliterator(), false).map(JsonNode::asText))
                .containsExactly("EUR", "RUB", "USD");
        assertThat(report.get("family").get("seatName").asText()).isEqualTo("Sam");
        // The partner's file goes by a name of the server's, never his file's (D-143).
        assertThat(report.get("files").findValuesAsText("name")).contains("partner-family-rows.csv")
                .noneMatch(name -> name.contains("someone"));

        JsonNode reconciliation = report.get("reconciliation");
        assertThat(reconciliation.get("failures")).isEmpty();
        assertThat(reconciliation.get("passed").asBoolean()).isTrue();
        assertThat(reconciliation.get("cutoff").asText()).isEqualTo("2024-03-08");
        assertThat(reconciliation.get("tailFrom").asText()).isEqualTo("2024-03-01");
        assertThat(reconciliation.get("lastCompared").asText()).isEqualTo("2024-02-29");
        assertThat(reconciliation.get("withoutFamilyRows").findValuesAsText("currency")).containsExactly("USD");
        assertThat(reconciliation.get("withoutFamilyRows").get(0).get("ok").asBoolean()).isTrue();
        assertThat(reconciliation.get("explained").findValuesAsText("reason")).contains("SHARES", "MINOR_UNIT",
                "FOUR_DECIMALS");
        assertThat(report.get("integrityViolations")).isEmpty();

        assertThat(entries()).isZero();
        assertThat(externalRefs()).isEmpty();
    }

    /** D-144 in small: a commit, then the same export again creates, updates and conflicts with nothing. */
    @Test
    void aCommitSavesItAndTheSameExportAgainChangesNothing() throws IOException {
        JsonNode committed = run("false");
        assertThat(committed.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("totals").get("created").asInt()).isEqualTo(CREATED);
        assertThat(committed.get("reconciliation").get("passed").asBoolean()).isTrue();

        // D-142: each family row is keyed by the seat whose file it comes from, and its Excel ID.
        String o = "xls:" + ownerMember + ":";
        String p = "xls:" + seat + ":";
        assertThat(externalRefs()).containsExactlyInAnyOrder(o + "2", o + "3", o + "4", o + "5", o + "6", o + "9",
                o + "10", o + "11", o + "12", o + "13", o + "14:EUR", o + "14:RUB", o + "15:EUR", o + "15:USD", o + "16",
                o + "PL:2024-01:EUR", o + "PL:2024-01:RUB", p + "101", p + "102", p + "103", p + "104", p + "108",
                p + "109");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM journal_entry e JOIN ledger_member m ON m.ledger_id = e.ledger_id
                WHERE m.user_sub = ? AND e.external_ref ~ '^xls:[0-9]+$' AND e.import_hash IS NOT NULL""")
                .param(owner).query(Long.class).single()).isEqualTo(9);
        // The partner's records are paid by his seat, imported by the owner (D-48).
        JsonNode records = ok(get(owner, "/api/family-ledgers/" + family + "/records?size=100")).get("content");
        assertThat(records).hasSize(23);
        assertThat(records).allSatisfy(r -> assertThat(r.get("importedBy").get("memberId").asLong())
                .isEqualTo(ownerMember));

        JsonNode again = run("false");
        assertThat(again.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(again.get("totals")).isEqualTo(Answers.json("""
                {"created": 0, "updated": 0, "unchanged": %d, "missing": 0, "conflicts": 0, "failed": 0, "skipped": 3,
                 "notImported": 1}""".formatted(CREATED)));
        assertThat(again.get("reconciliation").get("passed").asBoolean()).isTrue();
        assertThat(externalRefs()).hasSize(23);
    }

    private JsonNode runUpTo(byte[] transactions, String upTo) throws IOException {
        return ok(importExport(owner, transactions, partnerRows(), family, seat, upTo, "false"));
    }

    /** The owner's imported entries and the family budget's live records dated after the day. */
    private long importedAfter(String day) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM journal_entry e JOIN ledger_member m ON m.ledger_id = e.ledger_id
                        WHERE m.user_sub = :owner AND e.external_ref LIKE 'xls:%' AND e.entry_date > :day::date)
                     + (SELECT count(*) FROM family_record WHERE ledger_id = :family AND external_ref IS NOT NULL
                        AND deleted_at IS NULL AND record_date > :day::date)""")
                .param("owner", owner).param("family", family).param("day", day).query(Long.class).single();
    }

    private static String afterCutoff(JsonNode report) {
        return StreamSupport.stream(report.get("afterCutoff").spliterator(), false)
                .map(c -> c.get("source").asText() + " " + c.get("afterCutoff").asInt() + "/" + c.get("rows").asInt())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * D-165: rows of either file dated after "Import up to" are neither imported nor missing, the reconciliation stops
     * at it, and an import up to a later date adds exactly the newer rows.
     */
    @Test
    void importUpToADateAndLaterUpToALaterOne() throws IOException {
        JsonNode january = runUpTo(bytes("transactions.csv"), "2024-01-31");
        assertThat(january.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(january.get("errors")).isEmpty();
        assertThat(january.get("upTo").asText()).isEqualTo("2024-01-31");
        // The owner's 3 rows of February and March, and all 7 of the partner's rows that D-140's filter uploads.
        assertThat(afterCutoff(january)).isEqualTo("OWNER 3/25, PARTNER 7/7");
        assertThat(january.get("totals").get("missing").asInt()).isZero();
        // The rows imported end before the date: the last of them is of 2024-01-24.
        assertThat(january.get("period").get("to").asText()).isEqualTo("2024-01-24");
        JsonNode reconciliation = january.get("reconciliation");
        // Every month-end up to the date agrees; but the dollar family debt is still open on 2024-01-31 (row 16 settles
        // it on 2024-02-05), so D-147's check at the last compared point (D-157) says so, as it would in Excel's month.
        assertThat(reconciliation.get("failures")).isEmpty();
        assertThat(reconciliation.get("withoutFamilyRows")).isEqualTo(Answers.json("""
                [{"currency": "USD", "appFamilyDebt": "10.00", "excelFamilyDebt": "22.35", "ok": false}]"""));
        assertThat(reconciliation.get("passed").asBoolean()).isFalse();
        assertThat(reconciliation.get("cutoff").asText()).isEqualTo("2024-01-31");
        assertThat(reconciliation.get("lastCompared").asText()).isEqualTo("2024-01-31");
        assertThat(reconciliation.get("pointsCompared").asInt()).isEqualTo(2 * reconciliation.get("lines").size());
        assertThat(importedAfter("2024-01-31")).isZero();
        int createdInJanuary = january.get("totals").get("created").asInt();

        // The same date again: nothing new, and the rows after it aren't missing.
        JsonNode again = runUpTo(bytes("transactions.csv"), "2024-01-31");
        assertThat(again.get("totals")).isEqualTo(Answers.json("""
                {"created": 0, "updated": 0, "unchanged": %d, "missing": 0, "conflicts": 0, "failed": 0, "skipped": 2,
                 "notImported": 0}""".formatted(createdInJanuary)));

        // A later date adds exactly the rows up to it.
        JsonNode february = runUpTo(bytes("transactions.csv"), "2024-02-29");
        assertThat(afterCutoff(february)).isEqualTo("OWNER 1/25, PARTNER 1/7");
        assertThat(february.get("totals").get("updated").asInt()).isZero();
        assertThat(february.get("totals").get("unchanged").asInt()).isEqualTo(createdInJanuary);
        assertThat(february.get("totals").get("missing").asInt()).isZero();
        assertThat(february.get("reconciliation").get("lastCompared").asText()).isEqualTo("2024-02-29");
        assertThat(february.get("reconciliation").get("passed").asBoolean()).isTrue();
        assertThat(importedAfter("2024-02-29")).isZero();
        assertThat(importedAfter("2024-01-31")).isEqualTo(february.get("totals").get("created").asLong());

        // Empty: everything, as before D-165; the whole export is then imported once.
        JsonNode all = run("false");
        assertThat(all.get("upTo").isNull()).isTrue();
        assertThat(all.get("afterCutoff")).isEmpty();
        assertThat(all.get("totals").get("updated").asInt()).isZero();
        assertThat(all.get("totals").get("missing").asInt()).isZero();
        assertThat(createdInJanuary + february.get("totals").get("created").asInt()
                + all.get("totals").get("created").asInt()).isEqualTo(CREATED);
        assertThat(all.get("totals").get("unchanged").asInt()).isEqualTo(CREATED - all.get("totals").get("created")
                .asInt());
        assertThat(externalRefs()).hasSize(23);
        assertThat(all.get("reconciliation").get("passed").asBoolean()).isTrue();

        // An earlier date after that leaves the newer rows alone: neither missing nor changed, even one gone from the
        // file whose stored date is after it.
        JsonNode earlier = runUpTo(withoutRows(bytes("transactions.csv"), Set.of("25")), "2024-01-31");
        assertThat(earlier.get("totals")).isEqualTo(Answers.json("""
                {"created": 0, "updated": 0, "unchanged": %d, "missing": 0, "conflicts": 0, "failed": 0, "skipped": 2,
                 "notImported": 0}""".formatted(createdInJanuary)));
        assertThat(externalRefs()).hasSize(23);
        assertThat(entryOf("xls:25")).isNotNull();
        // Without the date, the row gone from the file is missing again (D-138).
        assertThat(refs(run(withoutRows(bytes("transactions.csv"), Set.of("25")), partnerRows(), "true")
                .get("missing"))).containsExactly("OWNER_PERSONAL 25");
    }

    /**
     * D-139: an edited row is written over the way the app changes it, unless it changed in the app since its last
     * import; that is a conflict, left alone, and the commit still runs.
     */
    @Test
    void anEditedRowIsWrittenOverUnlessItChangedInTheApp() throws IOException {
        run("false");
        byte[] transactions = withField(bytes("transactions.csv"), "2", 3, "12.00");
        transactions = withField(transactions, "1", 3, "1001");
        byte[] partner = withField(partnerRows(), "101", 6, "41.00");
        JsonNode edited = run(transactions, partner, "false");
        assertThat(edited.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(edited.get("totals").get("updated").asInt()).isEqualTo(3);
        assertThat(count(edited, "OWNER_FAMILY", "EXPENSE")).isEqualTo("c0 u1 n2 x0 e0 s0 i0");
        long recordOf2 = recordOf("xls:" + ownerMember + ":2");
        JsonNode record = ok(get(owner, "/api/family-ledgers/" + family + "/records/" + recordOf2));
        assertThat(record.get("amount").asText()).isEqualTo("12.00");
        assertThat(record.get("version").asInt()).isEqualTo(1);

        // Changed in the app: the owner's comment on the record. The next edit of its row is a conflict.
        ok(patch(owner, "/api/family-ledgers/%d/records/%d?version=1".formatted(family, recordOf2), """
                {"comment": "checked"}"""));
        JsonNode conflict = run(withField(transactions, "2", 3, "13.00"), partner, "false");
        assertThat(conflict.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(refs(conflict.get("conflicts"))).containsExactly("OWNER_FAMILY 2");
        assertThat(conflict.get("conflicts").get(0).get("reason").asText())
                .isEqualTo("changed in the app since its last import (D-139)");
        assertThat(ok(get(owner, "/api/family-ledgers/" + family + "/records/" + recordOf2)).get("amount").asText())
                .isEqualTo("12.00");

        // A personal entry changed in the app is a conflict too.
        JsonNode entry = entryOf("xls:21");
        ok(put(owner, "/api/entries/%d?version=%d".formatted(entry.get("id").asLong(), entry.get("version").asInt()),
                """
                {"kind": "MANUAL", "entryDate": "2024-01-22", "memo": "mine now", "postings": [
                  {"accountId": %d, "currency": "EUR", "amount": "-1.50"},
                  {"accountId": %d, "currency": "EUR", "amount": "1.50"}]}"""
                        .formatted(accountId(owner, "EURO_ACCOUNT"), accountId(owner, "CASH"))));
        JsonNode personal = run(withField(transactions, "21", 3, "1.75"), partner, "false");
        assertThat(refs(personal.get("conflicts"))).contains("OWNER_PERSONAL 21");
        assertThat(entryOf("xls:21").get("memo").asText()).isEqualTo("mine now");
    }

    /** D-138: a row gone from the export is reported, by its Excel ID, and nothing is deleted. */
    @Test
    void aRowGoneFromTheExportIsReportedAndNothingIsDeleted() throws IOException {
        run("false");
        long before = entries();
        JsonNode report = run(withoutRows(bytes("transactions.csv"), Set.of("1", "2")),
                withoutRows(partnerRows(), Set.of("101")), "false");
        assertThat(report.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(refs(report.get("missing"))).containsExactlyInAnyOrder("OWNER_PERSONAL 1", "OWNER 2", "PARTNER 101");
        assertThat(report.get("totals").get("missing").asInt()).isEqualTo(3);
        assertThat(entries()).isEqualTo(before);
        assertThat(externalRefs()).hasSize(23);
    }

    /** D-150 and D-113: a row deleted in the app stays deleted and is a conflict. */
    @Test
    void aRowDeletedInTheAppStaysDeleted() throws IOException {
        run("false");
        JsonNode entry = entryOf("xls:21");
        assertThat(delete(owner, "/api/entries/%d?version=%d".formatted(entry.get("id").asLong(),
                entry.get("version").asInt()))).hasStatus(HttpStatus.NO_CONTENT);
        long refund = recordOf("xls:" + seat + ":102");
        assertThat(delete(owner, "/api/family-ledgers/%d/records/%d?version=0".formatted(family, refund)))
                .hasStatus(HttpStatus.NO_CONTENT);
        long before = entries();

        JsonNode report = run("false");
        assertThat(report.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(refs(report.get("conflicts"))).containsExactlyInAnyOrder("OWNER_PERSONAL 21", "PARTNER 102");
        assertThat(report.get("conflicts").findValuesAsText("reason")).containsExactlyInAnyOrder(
                "deleted in the app after its import (D-150)", "deleted in the app after its import (D-113)");
        assertThat(report.get("totals").get("created").asInt()).isZero();
        assertThat(entries()).isEqualTo(before);
    }

    /** A row that changed its kind can't be changed in place: an error, and the commit saves nothing. */
    @Test
    void aRowThatChangedItsKindIsAnError() throws IOException {
        run("false");
        JsonNode report = run(withField(bytes("transactions.csv"), "2", 3, "-10.01"), partnerRows(), "false");
        assertThat(report.get("outcome").asText()).isEqualTo("ABORTED");
        assertThat(report.get("errors")).singleElement().satisfies(e -> {
            assertThat(e.get("excelId").asText()).isEqualTo("2");
            assertThat(e.get("message").asText()).contains("changed its kind since its last import");
        });
    }

    /** A negative Sum with swapped sides is a valid transfer (D-145); an exchange within one account too. */
    @Test
    void aNegativeSumTransferAndAnExchangeWithinOneAccount() throws IOException {
        run("false");
        JsonNode transfer = entryOf("xls:17");
        assertThat(transfer.get("kind").asText()).isEqualTo("TRANSFER");
        assertThat(lines(transfer)).containsExactlyInAnyOrder("EURO_ACCOUNT EUR 20.00", "CASH EUR -20.00");
        JsonNode exchange = entryOf("xls:24");
        assertThat(exchange.get("kind").asText()).isEqualTo("CURRENCY_EXCHANGE");
        assertThat(lines(exchange)).containsExactlyInAnyOrder("EURO_ACCOUNT EUR -10.00", "FX_EXCHANGE EUR 10.00",
                "FX_EXCHANGE RUB -900.00", "EURO_ACCOUNT RUB 900.00");
        // A personal row in a family category posts to the family budget's category (D-11).
        JsonNode food = entryOf("xls:19");
        long familyFood = find(ok(get(owner, "/api/family-ledgers/" + family + "/categories")), "code", "FOOD")
                .get("id").asLong();
        assertThat(food.get("postings").findValuesAsText("categoryId")).contains(String.valueOf(familyFood));
    }

    /** D-140: the partner's file is refused whole (422) for a row or a column the page's filter never lets through. */
    @Test
    void thePartnersFileIsRefusedWholeIfItBreaksTheFilter() throws IOException {
        byte[] personalRow = withLine(partnerRows(), "200;09.02.2024;Кафе-секрет;;Debit;EUR;9");
        Answers.assertNoneMention(body(importExport(owner, bytes("transactions.csv"), personalRow, family, seat,
                "false"), HttpStatus.UNPROCESSABLE_ENTITY), "Кафе", "секрет");
        assertThat(violations(importExport(owner, bytes("transactions.csv"), personalRow, family, seat, "false")))
                .containsExactly("row 9 of the partner's file isn't a family row (Family = \"да\")");
        byte[] noItem = withLine(partnerRows(), "201;09.02.2024;-;да;Debit;EUR;9");
        assertThat(violations(importExport(owner, bytes("transactions.csv"), noItem, family, seat, "false")))
                .containsExactly("row 9 of the partner's file has no item (\"-\")");
        String raw = new String(bytes("partner-transactions.csv"), StandardCharsets.UTF_8);
        assertThat(violations(importExport(owner, bytes("transactions.csv"), raw.getBytes(StandardCharsets.UTF_8),
                family, seat, "false"))).first().asString().startsWith("the partner's file has a column");
        assertThat(entries()).isZero();
        assertThat(externalRefs()).isEmpty();
    }

    /** D-141: the family budget and the seat are the owner's to name: another user's answer as missing ones. */
    @Test
    void theFamilyBudgetAndTheSeatAreOnesTheOwnerMayImportInto() throws IOException {
        String bob = newUser();
        ok(get(bob, "/api/accounts"));
        long bobs = newFamily(bob, """
                {"name": "Bob's", "baseCurrency": "EUR", "displayName": "Bob", "startDate": "2024-01-01"}""")
                .get("id").asLong();
        long bobsSeat = body(post(bob, "/api/family-ledgers/" + bobs + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED).get("id").asLong();
        byte[] t = bytes("transactions.csv");
        assertThat(importExport(owner, t, partnerRows(), bobs, bobsSeat, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(importExport(owner, t, partnerRows(), family, bobsSeat, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(importExport(bob, t, partnerRows(), family, seat, null)).hasStatus(HttpStatus.NOT_FOUND);
        // The owner's own place has an account: not the partner's seat.
        assertThat(importExport(owner, t, partnerRows(), family, ownerMember, null)).hasStatus(HttpStatus.CONFLICT);
        // A budget without a seat, or the partner's rows without a budget, is a bad request.
        assertThat(mvc.post().uri("/api/import").multipart()
                .file(new org.springframework.mock.web.MockMultipartFile("accounts", "a.csv", "text/csv", t))
                .file(new org.springframework.mock.web.MockMultipartFile("categories", "c.csv", "text/csv", t))
                .file(new org.springframework.mock.web.MockMultipartFile("transactions", "t.csv", "text/csv", t))
                .file(new org.springframework.mock.web.MockMultipartFile("dailyBalance", "b.csv", "text/csv", t))
                .file(new org.springframework.mock.web.MockMultipartFile("dailyChange", "d.csv", "text/csv", t))
                .param("familyLedgerId", String.valueOf(family)).with(member(owner)).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(importExport(owner, t, partnerRows(), null, null, null)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(entries()).isZero();
    }

    /** Family rows without a family budget are errors; the personal rows alone import as before. */
    @Test
    void familyRowsNeedTheFamilyBudget() throws IOException {
        JsonNode report = ok(importWorkbook(owner, bytes("transactions.csv"), "false"));
        assertThat(report.get("outcome").asText()).isEqualTo("ABORTED");
        assertThat(report.get("errorSummary").findValuesAsText("message"))
                .contains("a family row needs the family budget and the partner's seat: choose them (D-141)");
        JsonNode personal = ok(importWorkbook(owner, personalRows(), "false"));
        assertThat(personal.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(personal.get("family").isNull()).isTrue();
        assertThat(personal.get("totals").get("created").asInt()).isEqualTo(10);
    }

    private List<String> violations(org.springframework.test.web.servlet.assertj.MvcTestResult result)
            throws IOException {
        return StreamSupport.stream(body(result, HttpStatus.UNPROCESSABLE_ENTITY).get("violations").spliterator(),
                false).map(JsonNode::asText).toList();
    }

    private long recordOf(String ref) {
        return jdbc.sql("SELECT id FROM family_record WHERE ledger_id = ? AND external_ref = ?").params(family, ref)
                .query(Long.class).single();
    }

    private JsonNode entryOf(String ref) throws IOException {
        long id = jdbc.sql("""
                SELECT e.id FROM journal_entry e JOIN ledger_member m ON m.ledger_id = e.ledger_id
                WHERE m.user_sub = ? AND m.ledger_type = 'PERSONAL' AND e.external_ref = ?""")
                .params(owner, ref).query(Long.class).single();
        return ok(get(owner, "/api/entries/" + id));
    }

    private List<String> lines(JsonNode entry) throws IOException {
        JsonNode accounts = ok(get(owner, "/api/accounts"));
        return StreamSupport.stream(entry.get("postings").spliterator(), false)
                .map(p -> find(accounts, "id", p.get("accountId").asText()).get("code").asText() + " "
                        + p.get("currency").asText() + " " + p.get("amount").asText())
                .toList();
    }
}
