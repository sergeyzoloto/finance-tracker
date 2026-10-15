package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.importer.ImportFixtures.seatRows;
import static com.example.financetracker.ledger.importer.ImportFixtures.withField;
import static com.example.financetracker.ledger.importer.ImportFixtures.withoutRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.example.financetracker.Answers;
import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The partner's claim from a past join date and his own import (D-174 to D-191), over D3c's synthetic export.
 */
class MemberImportApiTests extends MemberImportApiTest {

    /** His rows refused by D-178 (a settlement without a counterpart) and D-179 (an expense from FAMILY_DEBT). */
    private static final Set<String> REFUSED = Set.of("111", "112");

    @AfterEach
    void theInvariantsHold() {
        if (jdbc.sql("SELECT count(*) FROM ledger WHERE id = ?").param(family).query(Long.class).single() > 0) {
            FamilyInvariants.check(jdbc, family);
        }
    }

    private byte[] his() throws IOException {
        return d3c("partner-transactions.csv");
    }

    private byte[] hisWithoutTheRefused() throws IOException {
        return withoutRows(his(), REFUSED);
    }

    private long entriesOf(String user, String kind) {
        return jdbc.sql("""
                SELECT count(*) FROM journal_entry e JOIN ledger_member m ON m.ledger_id = e.ledger_id
                WHERE m.user_sub = ? AND m.ledger_type = 'PERSONAL' AND e.kind = ?""")
                .params(user, kind).query(Long.class).single();
    }

    private int version(long recordId) {
        return jdbc.sql("SELECT version FROM family_record WHERE id = ?").param(recordId).query(Integer.class).single();
    }

    private long author(long recordId) {
        return jdbc.sql("SELECT author_member_id FROM family_record WHERE id = ?").param(recordId)
                .query(Long.class).single();
    }

    private String ownersLinks() {
        return jdbc.sql("""
                SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) FROM family_entry_link t
                WHERE t.family_ledger_id = ? AND t.member_id = ?""").params(family, ownerMember)
                .query(String.class).single();
    }

    private Map<String, Object> liveRecords() {
        return jdbc.sql("""
                SELECT count(*) AS records, coalesce(sum(base_amount), 0) AS amounts, coalesce(sum(version), 0) AS versions
                FROM family_record WHERE ledger_id = ? AND deleted_at IS NULL""")
                .param(family).query().singleRow();
    }

    /** D-177: the owner's upload of a place's family rows is the same from either layout of the partner's file. */
    @Test
    void theOwnersUploadIsTheSameFromEitherLayoutOfThePartnersFile() throws IOException {
        assertThat(seatRows(his())).isEqualTo(seatRows(d3c("partner-transactions-old.csv")));
    }

    /**
     * D-174, D-183: a claim from a past join date posts the opening balance in each currency and the shares from that
     * date only, and every record the place paid from it stays open for his account (D-32). The records of his file
     * become his (D-176).
     */
    @Test
    void aClaimFromAPastJoinDatePostsTheOpeningAndTheRecordsFromIt() throws IOException {
        ownersFirstImport();
        String token = claimInvite("2024-02-01");
        JsonNode lookup = ok(inviteCall(partner, "lookup", token(token, null)));
        assertThat(lookup.get("joinDate").asText()).isEqualTo("2024-02-01");
        // Sam's own four records, the owner's two family records of February, and the nine settlements and conversion
        // legs between them from February on.
        assertThat(lookup.get("recordsAttached").asInt()).isEqualTo(15);
        // Sam's half of the owner's January expense of 20.01, as D-12 cut it: what he owes the family.
        assertThat(lookup.get("openingBalances")).isEqualTo(Answers.json("""
                [{"currency": "EUR", "amount": "10.00"}]"""));

        ok(inviteCall(partner, "accept", token(token, "\"displayName\": \"Sam B\", \"categoryIds\": []")));

        assertThat(entriesOf(partner, "FAMILY_OPENING")).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT min(e.entry_date) FROM journal_entry e JOIN ledger_member m ON m.ledger_id = e.ledger_id
                WHERE m.user_sub = ? AND m.ledger_type = 'PERSONAL' AND e.kind LIKE 'FAMILY_%'""")
                .param(partner).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2024, 2, 1));
        // His shares of Sam's four records and of the owner's two, and his payments of Sam's four, on "Specify later".
        assertThat(entriesOf(partner, "FAMILY_SHARE")).isEqualTo(6);
        assertThat(entriesOf(partner, "FAMILY_PAYMENT")).isEqualTo(4);
        assertThat(entriesOf(partner, "FAMILY_SETTLEMENT")).isEqualTo(9);

        // D-176: Sam's records are his now; the owner's stay the owner's.
        for (String id : List.of("101", "102", "103", "113")) {
            assertThat(author(record(id))).isEqualTo(seat);
        }
        assertThat(author(ownersRecord("3"))).isEqualTo(ownerMember);
        // D-32: the payment of a record of February takes his account.
        ok(patch(partner, records() + record("101") + "?version=" + version(record("101")), """
                {"paymentAccountId": %d}""".formatted(accountId(partner, "CASH"))));
    }

    /** D-176: who changes and deletes what after the claim (D-14). */
    @Test
    void afterTheClaimHisRecordsAreHis() throws IOException {
        ownersFirstImport();
        claim();
        long his = record("101");
        long owners = ownersRecord("3");

        ok(patch(partner, records() + his + "?version=" + version(his), """
                {"comment": "ours"}"""));
        assertThat(body(patch(partner, records() + owners + "?version=" + version(owners), """
                {"comment": "ours"}"""), HttpStatus.CONFLICT).get("detail").asText())
                .contains("Only the expense's author or an owner");
        // The owner is an owner: the family fields of any record are his to change too (D-14).
        ok(patch(owner, records() + his + "?version=" + version(his), """
                {"comment": "ours, both"}"""));
        // The payment and deleting are the payer's, who has an account now.
        assertThat(body(delete(owner, records() + his + "?version=" + version(his)), HttpStatus.CONFLICT)
                .get("detail").asText())
                .contains("who paid it");
        assertThat(delete(partner, records() + record("113") + "?version=" + version(record("113"))))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    /**
     * D-186: deleting all of one's data or leaving never deletes or changes a record another active member's ledger
     * posts, and leaves that member's ledger alone.
     */
    @Test
    void deletingOnesDataOrLeavingKeepsTheOtherMembersRecords() throws IOException {
        ownersFirstImport();
        claim();
        Map<String, Object> records = liveRecords();
        Map<String, String> owners = digestOf(owner);
        String links = ownersLinks();

        assertThat(delete(partner, "/api/family-ledgers/" + family + "/members/me"))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(liveRecords()).isEqualTo(records);
        Map<String, String> ownersAfter = digestOf(owner);
        // Only the membership of the one who left, his links (detached) and the invites of his place changed in the
        // family's rows; the owner's own links are as they were.
        Set<String> his = Set.of("family ledger_member", "family ledger_invite", "family family_entry_link");
        owners.keySet().removeAll(his);
        ownersAfter.keySet().removeAll(his);
        assertThat(ownersAfter).isEqualTo(owners);
        assertThat(ownersLinks()).isEqualTo(links);
    }

    @Test
    void theOwnersDeletionOfHisDataKeepsThePartnersRecords() throws IOException {
        ownersFirstImport();
        claim();
        Map<String, Object> records = liveRecords();
        long partnersEntries = jdbc.sql("""
                SELECT count(*) FROM journal_entry e JOIN ledger_member m ON m.ledger_id = e.ledger_id
                WHERE m.user_sub = ? AND m.ledger_type = 'PERSONAL'""").param(partner).query(Long.class).single();

        assertThat(delete(owner, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(liveRecords()).isEqualTo(records);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM journal_entry e JOIN ledger_member m ON m.ledger_id = e.ledger_id
                WHERE m.user_sub = ? AND m.ledger_type = 'PERSONAL'""").param(partner).query(Long.class).single())
                .isEqualTo(partnersEntries);
    }

    /** D-175: once Sam's place has an account, the owner's import takes no partner's file and isn't refused. */
    @Test
    void theOwnersImportAfterTheClaim() throws IOException {
        JsonNode first = ownersFirstImport();
        assertThat(first.get("reconciliation").get("failures")).isEmpty();
        assertThat(first.get("reconciliation").get("passed").asBoolean()).isTrue();
        claim();
        assertThat(body(ownerImport(seatRows(his()), null, null), HttpStatus.CONFLICT).get("detail").asText())
                .contains("has an account now and imports their own file (D-175)");

        JsonNode report = ok(ownerImport(null, null, null));
        assertThat(report.get("errors")).isEmpty();
        assertThat(report.get("family").get("mode").asText()).isEqualTo("OWNER_AFTER_CLAIM");
        assertThat(report.get("totals").get("created").asInt()).isZero();
        assertThat(report.get("totals").get("updated").asInt()).isZero();
        assertThat(report.get("totals").get("missing").asInt()).isZero();
        assertThat(report.get("reconciliation").get("failures")).isEmpty();
        assertThat(report.get("reconciliation").get("passed").asBoolean()).isTrue();
    }

    /**
     * D-175 to D-181, D-188: his dry run. His four family records are recognised as the owner's import left them, with
     * his payment side attached; his settlement rows match by each tier, his conversion by both legs, his benefit row
     * is checked; the row without a counterpart and the expense from FAMILY_DEBT are refused.
     */
    @Test
    void hisDryRun() throws IOException {
        ownersFirstImport();
        claim();
        JsonNode report = ok(memberImport(his(), null, null));

        assertThat(report.get("family").get("mode").asText()).isEqualTo("MEMBER");
        assertThat(errors(report)).containsExactlyInAnyOrder(
                "111: no counterpart: no settlement of 2024-02-25 in EUR of 77 matches it (D-178)",
                "112: an expense paid straight from FAMILY_DEBT: split it in Excel into a settlement into an account "
                        + "of yours and the expense from that account (D-179)");
        JsonNode member = report.get("member");
        assertThat(member.get("familyRows").asInt()).isEqualTo(4);
        assertThat(member.get("recordsUnchanged").asInt()).isEqualTo(4);
        assertThat(member.get("recordsEdited")).isEmpty();
        assertThat(member.get("recordsCreated").asInt()).isZero();
        assertThat(member.get("sidesAttached").asInt()).isEqualTo(4);
        assertThat(member.get("tier1").asInt()).isEqualTo(3);
        assertThat(member.get("tier2").asInt()).isEqualTo(1);
        assertThat(member.get("tier3").asInt()).isEqualTo(2);
        assertThat(member.get("conversions").asInt()).isEqualTo(1);
        assertThat(member.get("conversionsMatched").asInt()).isEqualTo(1);
        assertThat(member.get("unmatched").asInt()).isEqualTo(1);
        assertThat(member.get("ownerSettlementsWithoutSide").asInt()).isZero();
        assertThat(member.get("benefitRows").asInt()).isEqualTo(1);
        assertThat(member.get("benefitMonths")).isEqualTo(Answers.json("""
                [{"month": "2024-02-01", "currency": "EUR", "rows": 1, "benefitRows": "50.00", "shares": "50.00",
                  "differs": false}]"""));
        // D-188: row 109 of 2 March matched the owner's settlement of 28 February.
        assertThat(member.get("straddling")).isEqualTo(Answers.json("""
                [{"excelId": "109", "rowDate": "2024-03-02", "settlementDate": "2024-02-28", "currency": "EUR",
                  "amount": "12"}]"""));
        assertThat(count(report, "MEMBER_FAMILY", "EXPENSE")).isEqualTo("c0 u0 n3 x0 e0 s0 i0");
        assertThat(count(report, "MEMBER_FAMILY", "PAYMENT_SIDE")).isEqualTo("c0 u4 n0 x0 e0 s0 i0");
        assertThat(count(report, "MEMBER_BENEFIT", "INCOME")).isEqualTo("c0 u0 n0 x0 e0 s0 i1");
        assertThat(report.get("outcome").asText()).isEqualTo("DRY_RUN");
        assertThat(rowsOf(partner).get("imported_family_side")).isZero();
        assertThat(rowsOf(partner).get("family family_import_cutoff")).isEqualTo(2);
    }

    /**
     * D-189 in small: his file without the refused rows commits and reconciles, the same file again changes nothing,
     * and the owner's import then changes nothing either, his family debt compared up to the partner's cutoff.
     */
    @Test
    void hisCommitThenTheSameAgainThenTheOwners() throws IOException {
        ownersFirstImport();
        claim();
        JsonNode committed = ok(memberImport(hisWithoutTheRefused(), null, "false"));
        assertThat(committed.get("errors")).isEmpty();
        assertThat(committed.get("outcome").asText()).isEqualTo("COMMITTED");
        JsonNode reconciliation = committed.get("reconciliation");
        assertThat(reconciliation.get("failures")).isEmpty();
        assertThat(reconciliation.get("passed").asBoolean()).isTrue();
        assertThat(reconciliation.get("explained").findValuesAsText("reason")).contains("CLAIM_OPENING",
                "SETTLEMENT_DATE", "SHARES");
        // D-180: his first family debt (10.01, two decimals) against the claim's opening (10.00, D-12's cut of 10.005),
        // once: what D-12 doesn't explain of it.
        JsonNode opening = StreamSupport.stream(reconciliation.get("explained").spliterator(), false)
                .filter(e -> e.get("reason").asText().equals("CLAIM_OPENING")).findFirst().orElseThrow();
        assertThat(opening.get("account").asText() + " " + opening.get("currency").asText() + " "
                + opening.get("amount").asText()).isEqualTo("FAMILY_DEBT EUR -0.005");
        assertThat(committed.get("integrityViolations")).isEmpty();
        assertThat(jdbc.sql("SELECT cutoff FROM family_import_cutoff WHERE ledger_id = ? AND member_id = ?")
                .params(family, seat).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2024, 3, 8));

        JsonNode again = ok(memberImport(hisWithoutTheRefused(), null, null));
        assertThat(again.get("errors")).isEmpty();
        assertThat(again.get("totals").get("created").asInt()).isZero();
        assertThat(again.get("totals").get("updated").asInt()).isZero();
        assertThat(again.get("totals").get("conflicts").asInt()).isZero();
        assertThat(again.get("member").get("sidesUnchanged").asInt()).isEqualTo(4);
        assertThat(again.get("member").get("settlementsKnown").asInt()).isEqualTo(7);
        assertThat(again.get("reconciliation").get("passed").asBoolean()).isTrue();

        JsonNode owners = ok(ownerImport(null, null, null));
        assertThat(owners.get("errors")).isEmpty();
        assertThat(owners.get("totals").get("created").asInt()).isZero();
        assertThat(owners.get("totals").get("updated").asInt()).isZero();
        assertThat(owners.get("totals").get("conflicts").asInt()).isZero();
        assertThat(owners.get("reconciliation").get("passed").asBoolean()).isTrue();
        assertThat(owners.get("reconciliation").get("familyDebtUpTo").asText()).isEqualTo("2024-03-08");
    }

    /** D-190: a row whose uploaded columns changed only in precision is an ordinary edit, listed. */
    @Test
    void aPrecisionEditIsAnOrdinaryEdit() throws IOException {
        // The owner uploaded Sam's row 113 rounded to cents, as export-2026-10-13 had it.
        ownersFirstImport(seatRows(withField(his(), "113", 3, "8.00")));
        claim();
        JsonNode report = ok(memberImport(hisWithoutTheRefused(), null, null));
        assertThat(report.get("errors")).isEmpty();
        assertThat(report.get("member").get("recordsEdited")).isEqualTo(Answers.json("[\"113\"]"));
        assertThat(report.get("member").get("recordsUnchanged").asInt()).isEqualTo(3);
        assertThat(report.get("member").get("sidesAttached").asInt()).isEqualTo(4);
    }

    /** D-179: a benefit month that differs from his shares of the incomes the owner received is a warning. */
    @Test
    void aBenefitMonthThatDiffersIsAWarning() throws IOException {
        ownersFirstImport();
        claim();
        JsonNode report = ok(memberImport(withField(hisWithoutTheRefused(), "105", 3, "49"), null, null));
        assertThat(report.get("warnings").findValuesAsText("message")).contains(
                "benefit rows of 2024-02 in EUR: 49.00, your shares of the family incomes the other members received: "
                        + "50.00 (D-179)");
        assertThat(report.get("member").get("benefitMonths").get(0).get("differs").asBoolean()).isTrue();
    }

    /** D-178: an owner's settlement that none of his rows matches is a warning. */
    @Test
    void anOwnersSettlementWithoutHisSideIsAWarning() throws IOException {
        ownersFirstImport();
        claim();
        JsonNode report = ok(memberImport(withoutRows(hisWithoutTheRefused(), Set.of("106")), null, null));
        assertThat(report.get("errors")).isEmpty();
        assertThat(report.get("member").get("ownerSettlementsWithoutSide").asInt()).isEqualTo(1);
        assertThat(report.get("warnings").findValuesAsText("message")).contains(
                "the settlement of 2024-02-10 in EUR of 40 has no side of yours: none of your rows matched it (D-178)");
    }

    /** D-177: his CUR_FAMILY must be the record's currency; a file without the owner's columns is refused. */
    @Test
    void hisFileIsCheckedAsTheOwnersIs() throws IOException {
        ownersFirstImport();
        claim();
        JsonNode report = ok(memberImport(withField(hisWithoutTheRefused(), "101", 15, "USD"), null, null));
        assertThat(errors(report)).singleElement().asString().startsWith("101: ").contains("CUR_FAMILY");

        String withoutColumn = Arrays.stream(new String(hisWithoutTheRefused(), StandardCharsets.UTF_8).split("\r\n"))
                .map(line -> {
                    List<String> fields = new java.util.ArrayList<>(List.of(line.split(";", -1)));
                    fields.remove(15);
                    return String.join(";", fields);
                }).collect(Collectors.joining("\r\n", "", "\r\n"));
        JsonNode refused = ok(memberImport(withoutColumn.getBytes(StandardCharsets.UTF_8), null, null));
        assertThat(refused.get("errors").findValuesAsText("message")).contains("missing columns: CUR_FAMILY");
    }

    /** D-175: the family debt waits for the other member's import beyond his latest committed cutoff. */
    @Test
    void theFamilyDebtWaitsForTheOtherMembersImport() throws IOException {
        ownersFirstImport();
        claim();
        ok(memberImport(hisWithoutTheRefused(), "2024-02-20", "false"));
        JsonNode report = ok(ownerImport(null, null, null));
        JsonNode reconciliation = report.get("reconciliation");
        assertThat(reconciliation.get("familyDebtUpTo").asText()).isEqualTo("2024-02-20");
        JsonNode debt = StreamSupport.stream(reconciliation.get("lines").spliterator(), false)
                .filter(line -> line.get("account").asText().equals("FAMILY_DEBT")
                        && line.get("currency").asText().equals("EUR"))
                .findFirst().orElseThrow();
        assertThat(debt.get("waiting").asInt()).isEqualTo(1);
        assertThat(debt.get("failed").asInt()).isZero();
    }

    /** Another user's budget answers like a missing one; a member who isn't an owner imports only his own place. */
    @Test
    void onlyItsMembersImportIntoTheBudgetAndAMemberOnlyHisOwnPlace() throws IOException {
        ownersFirstImport();
        claim();
        String stranger = newUser();
        ok(get(stranger, "/api/accounts"));
        assertThat(upload(stranger, "partner", hisWithoutTheRefused(), null, seat, null, null))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(body(upload(partner, "partner", hisWithoutTheRefused(), null, ownerMember, null, null),
                HttpStatus.CONFLICT).get("detail").asText()).contains("Only an owner of the family budget imports");
        // V15: a member's imported side lives in a personal ledger only.
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_trigger WHERE tgname = 'imported_family_side_check'""")
                .query(Long.class).single()).isEqualTo(1);
    }
}
