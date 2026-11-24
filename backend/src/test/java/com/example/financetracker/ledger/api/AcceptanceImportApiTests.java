package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The author rule in an Excel import's commit (HIST-3 part 2, D-1164), with acceptance on: a commit by an uploader with
 * pending changes is refused with 409 before anything is written.
 */
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
// A context of its own: closed after the class, so that its connection pools don't add to the cached contexts'.
@DirtiesContext
class AcceptanceImportApiTests extends MemberImportApiTest {

    private final String carol = newUser();

    /** Everybody accepts (in place of part 3's accept), so that no reconcile elsewhere finds this family lagging. */
    @AfterEach
    void everybodyAccepts() {
        FamilyInvariants.checkAcceptingCounters(jdbc, family);
        jdbc.sql("""
                UPDATE ledger_member m SET accepted_revision = l.revision FROM ledger l
                WHERE l.id = m.ledger_id AND m.ledger_id = ? AND m.accepted_revision IS NOT NULL""")
                .param(family).update();
    }

    @Test
    void aLaggingOwnersCommitIsRefusedBeforeAnythingIsWritten() throws IOException {
        ok(get(carol, "/api/accounts"));
        String link = body(post(owner, "/api/family-ledgers/" + family + "/invites", """
                {"kind": "NEW_MEMBER"}"""), HttpStatus.CREATED).get("link").asText();
        ok(inviteCall(carol, "accept", token(link.substring(link.indexOf('#') + 1),
                "\"displayName\": \"Carol\", \"categoryIds\": []")));
        long carolMember = jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub = ?")
                .params(family, carol).query(Long.class).single();
        // Carol's settlement with the owner: pending for him.
        body(post(carol, "/api/family-ledgers/" + family + "/settlements", """
                {"date": "%s", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(utcToday(), carolMember, ownerMember)), HttpStatus.CREATED);

        long categoriesBefore = familyCount("category");
        long recordsBefore = familyCount("family_record");
        JsonNode problem = body(upload(owner, "owner", d3c("owner-transactions.csv"),
                com.example.financetracker.ledger.importer.ImportFixtures.seatRows(d3c("partner-transactions.csv")),
                seat, null, "false"), HttpStatus.CONFLICT);
        assertThat(problem.get("code").asText()).isEqualTo("FAMILY_PENDING_CHANGES");
        assertThat(problem.get("pendingChanges").asInt()).isEqualTo(1);
        // Refused before the first row: no family category and no family record was written.
        assertThat(familyCount("category")).isEqualTo(categoriesBefore);
        assertThat(familyCount("family_record")).isEqualTo(recordsBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM family_record WHERE ledger_id = ? AND external_ref IS NOT NULL")
                .param(family).query(Long.class).single()).isZero();
    }

    private long familyCount(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE ledger_id = ?").param(family)
                .query(Long.class).single();
    }
}
