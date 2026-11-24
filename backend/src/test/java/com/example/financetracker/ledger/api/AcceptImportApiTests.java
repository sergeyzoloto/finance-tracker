package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import com.example.financetracker.ledger.importer.ImportFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * An Excel import with acceptance on (HIST-3 part 3, D-1172): a lagging uploader's dry run names their pending changes
 * without failing, and a caught-up uploader's family import is pending for the other members.
 */
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
// A context of its own: closed after the class, so that its connection pools don't add to the cached contexts'.
@DirtiesContext
class AcceptImportApiTests extends MemberImportApiTest {

    private final String carol = newUser();

    @AfterEach
    void everybodyAccepts() throws IOException {
        AcceptingMode.everybodyAccepts(this, family);
    }

    @Test
    void aLaggingDryRunNamesThePendingChangesAndACaughtUpImportIsPendingForTheOthers() throws IOException {
        ok(get(carol, "/api/accounts"));
        String link = body(post(owner, "/api/family-ledgers/" + family + "/invites", """
                {"kind": "NEW_MEMBER"}"""), HttpStatus.CREATED).get("link").asText();
        ok(inviteCall(carol, "accept", token(link.substring(link.indexOf('#') + 1),
                "\"displayName\": \"Carol\", \"categoryIds\": []")));
        long carolMember = jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub = ?")
                .params(family, carol).query(Long.class).single();
        body(post(carol, "/api/family-ledgers/" + family + "/settlements", """
                {"date": "%s", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(utcToday(), carolMember, ownerMember)), HttpStatus.CREATED);

        JsonNode dry = ok(ownerImport(ImportFixtures.seatRows(d3c("partner-transactions.csv")), null, "true"));
        assertThat(dry.get("outcome").asText()).isEqualTo("DRY_RUN");
        List<String> warnings = new ArrayList<>();
        dry.get("warnings").forEach(warning -> warnings.add(warning.get("message").asText()));
        assertThat(warnings).contains("1 family changes are waiting for you; accept them before committing");
        // Each family row of the dry run meets the author rule of part 2 as a row error, as before this note.
        dry.get("errors").forEach(error -> assertThat(error.get("message").asText())
                .isEqualTo("the family budget refuses it: Accept the 1 pending change first"));

        ok(post(owner, "/api/family-ledgers/" + family + "/accept", "{}"));
        ownersFirstImport();
        JsonNode pending = ok(get(carol, "/api/family-ledgers/" + family + "/pending"));
        assertThat(pending.get("count").asInt()).isPositive();
        long imported = pending.get("recordIds").get(0).asLong();
        assertThat(ok(get(carol, "/api/family-ledgers/" + family + "/records/" + imported)).get("pendingForMe")
                .asBoolean()).isTrue();
        assertThat(ok(get(owner, "/api/family-ledgers/" + family + "/pending")).get("count").asInt()).isZero();
    }
}
