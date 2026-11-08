package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.importer.ImportFixtures.bytes;
import static com.example.financetracker.ledger.importer.ImportFixtures.edited;
import static com.example.financetracker.ledger.importer.ImportFixtures.partnerRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.example.financetracker.Answers;
import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * D-337: the owner's own side of his family records (his payment of an expense, his receipt of an income, his side of
 * a settlement; the account, the paying currency and amount, the counterparty, the payee and the private note) is
 * compared with what his row maps to, as a member's is (D-181): equal is unchanged (and stamped, for a record without a
 * stored side), changed in the app with the row as last imported is left and explained, the row changed with the side
 * as the import left it is written over, and both changed is a conflict. And D-338: a family record changed in the app
 * with its row unchanged has its balance effect explained as APP_EDITED. On the synthetic file of
 * {@code src/test/resources/import/}: row 2 an expense from the euro account with a note, row 4 an income into it, row
 * 9 a settlement from it, and row 6 an expense from the bank's credit, an account that requires a counterparty.
 */
class OwnSideImportApiTests extends LedgerApiTest {

    /** The owner's sides in the synthetic file: 5 records he paid or received, 4 settlements, 2 conversions' legs. */
    private static final int SIDES = 13;

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

    private JsonNode run(byte[] transactions, String dryRun) throws IOException {
        return ok(importExport(owner, transactions, partnerRows(), family, seat, dryRun));
    }

    private JsonNode committed(byte[] transactions) throws IOException {
        JsonNode report = run(transactions, "false");
        assertThat(report.get("errors")).isEmpty();
        assertThat(report.get("outcome").asText()).isEqualTo("COMMITTED");
        return report;
    }

    private long recordOf(String excelId) {
        return jdbc.sql("SELECT id FROM family_record WHERE ledger_id = ? AND external_ref = ?")
                .params(family, "xls:" + ownerMember + ":" + excelId).query(Long.class).single();
    }

    private JsonNode recordView(long id) throws IOException {
        return ok(get(owner, "/api/family-ledgers/%d/records/%d".formatted(family, id)));
    }

    /** A change of the record in the app, as the owner makes it, at its current version. */
    private void change(String excelId, String body) throws IOException {
        long id = recordOf(excelId);
        ok(patch(owner, "/api/family-ledgers/%d/records/%d?version=%d".formatted(family, id,
                recordView(id).get("version").asInt()), body));
    }

    private long personalLedger(String user) {
        return jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(user).query(Long.class).single();
    }

    private long sides() {
        return jdbc.sql("SELECT count(*) FROM imported_family_side WHERE ledger_id = ?")
                .param(personalLedger(owner)).query(Long.class).single();
    }

    /** His side's account on the record, by its code. */
    private String sideAccount(String excelId) throws IOException {
        long account = recordView(recordOf(excelId)).get("yourPayment").get("accountId").asLong();
        return find(ok(get(owner, "/api/accounts")), "id", String.valueOf(account)).get("code").asText();
    }

    private static List<String> refs(JsonNode rows) {
        return StreamSupport.stream(rows.spliterator(), false)
                .map(r -> r.get("source").asText() + " " + r.get("excelId").asText() + ": " + r.get("reason").asText())
                .toList();
    }

    private static List<String> appEdits(JsonNode report) {
        return StreamSupport.stream(report.get("sync").get("appEdits").spliterator(), false)
                .map(e -> e.get("excelId").asText() + " " + e.get("account").asText() + " " + e.get("currency").asText()
                        + " " + e.get("amount").asText())
                .toList();
    }

    private static void passes(JsonNode report) {
        assertThat(report.get("reconciliation").get("failures")).isEmpty();
        assertThat(report.get("reconciliation").get("rows").get("failed").asInt()).isZero();
        assertThat(report.get("reconciliation").get("passed").asBoolean()).isTrue();
    }

    /** Row 2's, 4's and 9's own side moved from the euro account to the wallet, as Excel moves it. */
    private static byte[] onTheWallet(byte[] transactions) {
        byte[] t = edited(transactions, "2", Map.of(8, "Кошелёк", 18, "CASH", 20, "ASSET"));
        t = edited(t, "4", Map.of(7, "Кошелёк", 17, "CASH", 19, "ASSET"));
        return edited(t, "9", Map.of(8, "Кошелёк", 18, "CASH", 20, "ASSET"));
    }

    private static final String SIDE_APP_EDITED = "your side changed in the app, the file unchanged: left as it is";
    private static final String SIDE_CONFLICT = "your side changed in the app since its last import, and the row too";

    /**
     * The first import stores every side of his; a record whose side has no stored hash (every record before D4c) and
     * that the ledger holds as his row maps it is stamped, which a dry run counts and keeps none of; then nothing.
     */
    @Test
    void everySideIsStoredAndSidesWithoutAHashAreStamped() throws IOException {
        JsonNode first = committed(bytes("transactions.csv"));
        assertThat(first.get("sync").get("sidesStamped").asInt()).isZero();
        assertThat(sides()).isEqualTo(SIDES);
        // Each side names his row as a member's does (D-181): a conversion's two legs the same row.
        assertThat(jdbc.sql("SELECT count(DISTINCT external_ref) FROM imported_family_side WHERE ledger_id = ? "
                + "AND external_ref ~ '^xls:[0-9]+$'").param(personalLedger(owner)).query(Long.class).single())
                .isEqualTo(SIDES - 2);

        jdbc.sql("DELETE FROM imported_family_side WHERE ledger_id = ?").param(personalLedger(owner)).update();
        JsonNode dry = run(bytes("transactions.csv"), "true");
        assertThat(dry.get("sync").get("sidesStamped").asInt()).isEqualTo(SIDES);
        assertThat(dry.get("totals").get("unchanged").asInt()).isEqualTo(first.get("totals").get("created").asInt());
        assertThat(dry.get("sync").get("appEdited")).isEmpty();
        assertThat(sides()).isZero();

        var before = digestOf(owner);
        JsonNode stamped = committed(bytes("transactions.csv"));
        assertThat(stamped.get("sync").get("sidesStamped").asInt()).isEqualTo(SIDES);
        assertThat(stamped.get("totals").get("updated").asInt()).isZero();
        assertThat(sides()).isEqualTo(SIDES);
        // Only the stamps changed.
        var after = digestOf(owner);
        after.remove("imported_family_side");
        after.remove("import_batch");
        before.remove("imported_family_side");
        before.remove("import_batch");
        assertThat(after).isEqualTo(before);

        JsonNode again = committed(bytes("transactions.csv"));
        assertThat(again.get("sync").get("sidesStamped").asInt()).isZero();
        assertThat(again.get("totals").get("updated").asInt()).isZero();
    }

    /**
     * D-337's second case for an expense, an income, a settlement and an account that requires a counterparty: his side
     * changed in the app (the wallet in place of the euro account; another bank), his rows as last imported. Left
     * alone, listed by Excel ID, its effect explained as APP_EDITED, so the check passes and the commit saves.
     */
    @Test
    void aSideChangedInTheAppWithTheRowUnchangedIsLeftAndExplained() throws IOException {
        committed(bytes("transactions.csv"));
        long cash = accountId(owner, "CASH");
        change("2", "{\"paymentAccountId\": %d}".formatted(cash));
        change("4", "{\"paymentAccountId\": %d}".formatted(cash));
        change("9", "{\"paymentAccountId\": %d}".formatted(cash));
        change("6", "{\"paymentCounterpartyId\": %d}".formatted(newCounterparty(owner, "Another bank")));

        for (String dryRun : new String[] {"true", "false", "false"}) {
            JsonNode report = run(bytes("transactions.csv"), dryRun);
            assertThat(report.get("errors")).isEmpty();
            assertThat(report.get("conflicts")).isEmpty();
            assertThat(report.get("totals").get("updated").asInt()).isZero();
            assertThat(refs(report.get("sync").get("appEdited"))).containsExactlyInAnyOrder(
                    "OWNER_FAMILY 2: " + SIDE_APP_EDITED, "OWNER_FAMILY 4: " + SIDE_APP_EDITED,
                    "OWNER_SETTLEMENT 9: " + SIDE_APP_EDITED, "OWNER_FAMILY 6: " + SIDE_APP_EDITED);
            // The counterparty moves no balance; the accounts do.
            assertThat(appEdits(report)).containsExactlyInAnyOrder("2 CASH EUR -10.01", "2 EURO_ACCOUNT EUR 10.01",
                    "4 CASH EUR 100.00", "4 EURO_ACCOUNT EUR -100.00", "9 CASH EUR -30.00", "9 EURO_ACCOUNT EUR 30.00");
            passes(report);
            assertThat(report.get("outcome").asText()).isEqualTo(dryRun.equals("true") ? "DRY_RUN" : "COMMITTED");
        }
        assertThat(sideAccount("2")).isEqualTo("CASH");
        assertThat(sideAccount("4")).isEqualTo("CASH");
        assertThat(sideAccount("9")).isEqualTo("CASH");
    }

    /**
     * D-337's third case: his rows' sides changed (the wallet; row 6's bank), his sides as the import left them:
     * written over, without a new version (D-111), the check passing; then unchanged.
     */
    @Test
    void aRowsChangedSideIsWrittenOverASideAsTheImportLeftIt() throws IOException {
        committed(bytes("transactions.csv"));
        int version = recordView(recordOf("2")).get("version").asInt();
        byte[] moved = edited(onTheWallet(bytes("transactions.csv")), "6", 14, "Другой банк");

        JsonNode report = committed(moved);
        assertThat(report.get("totals").get("updated").asInt()).isEqualTo(4);
        assertThat(report.get("conflicts")).isEmpty();
        assertThat(report.get("sync").get("appEdited")).isEmpty();
        assertThat(report.get("sync").get("appEdits")).isEmpty();
        passes(report);
        assertThat(sideAccount("2")).isEqualTo("CASH");
        assertThat(sideAccount("4")).isEqualTo("CASH");
        assertThat(sideAccount("9")).isEqualTo("CASH");
        assertThat(recordView(recordOf("6")).get("yourPayment").get("counterpartyId").asLong())
                .isEqualTo(find(ok(get(owner, "/api/counterparties")), "name", "Другой банк").get("id").asLong());
        assertThat(recordView(recordOf("2")).get("version").asInt()).isEqualTo(version);

        JsonNode again = committed(moved);
        assertThat(again.get("totals").get("updated").asInt()).isZero();
        assertThat(again.get("totals").get("conflicts").asInt()).isZero();
    }

    /**
     * D-337's fourth case: his row's side changed and his side changed in the app too: a conflict, left alone (D-139);
     * the ledger then differs from the file, so the commit is refused (D-336). An edited record whose side changed in
     * the app is one too (D-339).
     */
    @Test
    void aSideChangedInTheAppAndInTheRowIsAConflict() throws IOException {
        committed(bytes("transactions.csv"));
        long cash = accountId(owner, "CASH");
        change("2", "{\"paymentAccountId\": %d}".formatted(cash));
        change("9", "{\"paymentAccountId\": %d}".formatted(cash));
        change("4", "{\"paymentAccountId\": %d}".formatted(cash));
        change("6", "{\"paymentCounterpartyId\": %d}".formatted(newCounterparty(owner, "Another bank")));
        // Row 2's note changed in the file; row 9's amount, an edit of the settlement as well; row 4's counteragent;
        // row 6's bank.
        byte[] t = edited(bytes("transactions.csv"), "2", 6, "на месяц");
        t = edited(t, "9", 3, "31");
        t = edited(t, "4", 5, "Кто-то");
        t = edited(t, "6", 14, "Другой банк");
        var before = digestOf(owner);

        JsonNode dry = run(t, "true");
        assertThat(refs(dry.get("conflicts"))).containsExactlyInAnyOrder("OWNER_FAMILY 2: " + SIDE_CONFLICT,
                "OWNER_SETTLEMENT 9: " + SIDE_CONFLICT, "OWNER_FAMILY 4: " + SIDE_CONFLICT,
                "OWNER_FAMILY 6: " + SIDE_CONFLICT);
        assertThat(dry.get("reconciliation").get("passed").asBoolean()).isFalse();
        JsonNode refused = run(t, "false");
        assertThat(refused.get("outcome").asText()).isEqualTo("ABORTED");
        assertThat(refused.get("refusedFor")).isEqualTo(Answers.json("[\"CHECK_FAILED\"]"));
        assertThat(digestOf(owner)).isEqualTo(before);
        assertThat(sideAccount("2")).isEqualTo("CASH");
        assertThat(recordView(recordOf("9")).get("amount").asText()).isEqualTo("30.00");
    }

    /**
     * D-337: a record without a stored side (every record before D4c) whose side isn't what the row maps to counts as
     * changed in the app and is never written over, even when the row is what changed.
     */
    @Test
    void aSideWithoutAStoredHashIsNeverWrittenOver() throws IOException {
        committed(bytes("transactions.csv"));
        jdbc.sql("DELETE FROM imported_family_side WHERE ledger_id = ?").param(personalLedger(owner)).update();

        JsonNode report = committed(onTheWallet(bytes("transactions.csv")));
        assertThat(report.get("totals").get("updated").asInt()).isZero();
        assertThat(refs(report.get("sync").get("appEdited"))).containsExactlyInAnyOrder(
                "OWNER_FAMILY 2: " + SIDE_APP_EDITED, "OWNER_FAMILY 4: " + SIDE_APP_EDITED,
                "OWNER_SETTLEMENT 9: " + SIDE_APP_EDITED);
        assertThat(appEdits(report)).containsExactlyInAnyOrder("2 CASH EUR 10.01", "2 EURO_ACCOUNT EUR -10.01",
                "4 CASH EUR -100.00", "4 EURO_ACCOUNT EUR 100.00", "9 CASH EUR 30.00", "9 EURO_ACCOUNT EUR -30.00");
        passes(report);
        assertThat(sideAccount("2")).isEqualTo("EURO_ACCOUNT");
        // The other sides are stamped, these three aren't.
        assertThat(report.get("sync").get("sidesStamped").asInt()).isEqualTo(SIDES - 3);
    }

    /**
     * D-338 for the owner: records changed in the app (row 2's amount, row 3's date, row 5's category, row 4's
     * receiver), their rows unchanged: listed, what each moves in his ledger (his share and his side) explained as
     * APP_EDITED on its own date, the check passing, the commit saving and leaving them as the app has them.
     */
    @Test
    void aRecordChangedInTheAppWithItsRowUnchangedIsExplained() throws IOException {
        committed(bytes("transactions.csv"));
        long other = body(post(owner, "/api/family-ledgers/" + family + "/categories", """
                {"code": "OTHER_FOOD", "name": "Other food", "type": "EXPENSE"}"""), HttpStatus.CREATED)
                .get("id").asLong();
        change("2", "{\"amount\": \"11.00\"}");
        change("3", "{\"date\": \"2024-02-04\"}");
        change("5", "{\"categoryId\": %d}".formatted(other));
        change("4", "{\"payerMemberId\": %d}".formatted(seat));

        for (String dryRun : new String[] {"true", "false", "false"}) {
            JsonNode report = run(bytes("transactions.csv"), dryRun);
            assertThat(report.get("errors")).isEmpty();
            assertThat(report.get("conflicts")).isEmpty();
            assertThat(report.get("totals").get("updated").asInt()).isZero();
            String appEdited = "changed in the app, the file unchanged: left as it is";
            assertThat(refs(report.get("sync").get("appEdited"))).containsExactlyInAnyOrder(
                    "OWNER_FAMILY 2: " + appEdited, "OWNER_FAMILY 3: " + appEdited, "OWNER_FAMILY 5: " + appEdited,
                    "OWNER_FAMILY 4: " + appEdited);
            // Row 3's refund moved to February nets to nothing at the cutoff; row 5's category moves nothing.
            assertThat(appEdits(report)).containsExactlyInAnyOrder("2 EQUITY+FAMILY_DEBT EUR -0.99",
                    "2 EURO_ACCOUNT EUR -0.99", "4 EQUITY+FAMILY_DEBT EUR -100.00", "4 EURO_ACCOUNT EUR -100.00");
            passes(report);
            assertThat(report.get("outcome").asText()).isEqualTo(dryRun.equals("true") ? "DRY_RUN" : "COMMITTED");
        }
        assertThat(recordView(recordOf("2")).get("amount").asText()).isEqualTo("11.00");
        assertThat(recordView(recordOf("3")).get("date").asText()).isEqualTo("2024-02-04");
    }
}
