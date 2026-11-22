package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.importer.ImportFixtures.withoutRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * HIST-2 step 4 (D-1077, D-1105): a family import's commit tells the other ACTIVE members once, not once per row, even
 * when it changed only the importer's side of the records (no revision of the family budget); a seat's claim tells
 * the others too; a dry run tells no one.
 */
class ImportNotificationApiTests extends MemberImportApiTest {

    private List<String> rows(long member) {
        return jdbc.sql("""
                SELECT kind || ' ' || coalesce(author_member_id::text, '-') FROM notification
                WHERE recipient_member_id = ? ORDER BY id""").param(member).query(String.class).list();
    }

    @Test
    void aCommittedImportIsOneNotificationToTheOthers() throws IOException {
        // Alone with an account, the owner's import tells no one (Sam's place has no account).
        ownersFirstImport();
        assertThat(rows(ownerMember)).isEmpty();
        assertThat(rows(seat)).isEmpty();

        claim();
        assertThat(rows(ownerMember)).containsExactly("SEAT_CLAIMED " + seat);

        byte[] his = withoutRows(d3c("partner-transactions.csv"), Set.of("111", "112"));
        assertThat(ok(memberImport(his, null, "true")).get("outcome").asText()).isNotEqualTo("COMMITTED");
        assertThat(rows(ownerMember)).hasSize(1);
        assertThat(ok(memberImport(his, null, "false")).get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(rows(ownerMember)).containsExactly("SEAT_CLAIMED " + seat, "FAMILY_IMPORTED " + seat);
        assertThat(rows(seat)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE recipient_member_id = ? AND from_revision IS NULL")
                .param(ownerMember).query(Long.class).single()).isEqualTo(2);
        assertThat(ok(get(owner, "/api/notifications")).get("content").get(0).get("text").asText())
                .isEqualTo("Sam B imported records into the family budget");
    }
}
