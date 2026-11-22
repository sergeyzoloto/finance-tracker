package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.importer.ImportFixtures.seatRows;
import static com.example.financetracker.ledger.importer.ImportFixtures.withoutRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * HIST-2 step 4 (D-1077, D-1105) and 1b step 2 (D-1115): a family import that took a revision of the family budget tells
 * the other ACTIVE members once, not once per row, with that revision; one that changed only the importer's side of
 * the records, or nothing, tells no one; a seat's claim tells the others; a dry run or a refused import tells no one.
 */
class ImportNotificationApiTests extends MemberImportApiTest {

    private List<String> rows(long member) {
        return jdbc.sql("""
                SELECT kind || ' ' || coalesce(author_member_id::text, '-') FROM notification
                WHERE recipient_member_id = ? ORDER BY id""").param(member).query(String.class).list();
    }

    private List<String> imported(long member) {
        return jdbc.sql("""
                SELECT author_member_id || ' ' || coalesce(from_revision::text, '-') || ' '
                       || coalesce(to_revision::text, '-') FROM notification
                WHERE recipient_member_id = ? AND kind = 'FAMILY_IMPORTED' ORDER BY id""")
                .param(member).query(String.class).list();
    }

    private long revision() {
        return jdbc.sql("SELECT revision FROM ledger WHERE id = ?").param(family).query(Long.class).single();
    }

    @Test
    void anImportThatChangesFamilyRecordsTellsTheOthersWithItsRevision() throws IOException {
        byte[] ownersFile = d3c("owner-transactions.csv");
        // The first import leaves out the owner's family row 2; the partner claims Sam's place and gets an account.
        assertThat(ok(upload(owner, "owner", withoutRows(ownersFile, Set.of("2")),
                seatRows(d3c("partner-transactions.csv")), seat, null, "false")).get("outcome").asText())
                .isEqualTo("COMMITTED");
        claim();
        long before = revision();
        assertThat(ok(upload(owner, "owner", ownersFile, null, seat, null, "false")).get("outcome").asText())
                .isEqualTo("COMMITTED");
        long after = revision();
        assertThat(after).isGreaterThan(before);
        assertThat(imported(seat)).containsExactly(ownerMember + " " + after + " " + after);
        assertThat(imported(ownerMember)).isEmpty();
        assertThat(ok(get(partner, "/api/notifications")).get("content").get(0).get("text").asText())
                .isEqualTo("Alex imported records into the family budget");

        // The same file again changes nothing: no one is told.
        assertThat(ok(upload(owner, "owner", ownersFile, null, seat, null, "false")).get("outcome").asText())
                .isEqualTo("COMMITTED");
        assertThat(revision()).isEqualTo(after);
        assertThat(imported(seat)).hasSize(1);
    }

    @Test
    void anImportOfOnlyTheImportersSideTellsNoOne() throws IOException {
        // Alone with an account, the owner's import tells no one (Sam's place has no account).
        ownersFirstImport();
        assertThat(rows(ownerMember)).isEmpty();
        assertThat(rows(seat)).isEmpty();

        claim();
        assertThat(rows(ownerMember)).containsExactly("SEAT_CLAIMED " + seat);

        byte[] his = withoutRows(d3c("partner-transactions.csv"), Set.of("111", "112"));
        assertThat(ok(memberImport(his, null, "true")).get("outcome").asText()).isNotEqualTo("COMMITTED");
        assertThat(rows(ownerMember)).hasSize(1);
        // Refused rows abort the import: no one is told.
        assertThat(ok(memberImport(d3c("partner-transactions.csv"), null, "false")).get("outcome").asText())
                .isNotEqualTo("COMMITTED");
        assertThat(rows(ownerMember)).hasSize(1);
        assertThat(ok(memberImport(his, null, "false")).get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(rows(ownerMember)).containsExactly("SEAT_CLAIMED " + seat);
        assertThat(rows(seat)).isEmpty();
    }
}
