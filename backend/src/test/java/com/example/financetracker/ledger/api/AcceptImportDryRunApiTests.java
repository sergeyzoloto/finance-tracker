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
 * D-1173 (HIST-3 part 4): a lagging uploader's dry run lists one note and no row error for the family rows the author
 * rule would refuse; they are counted as not checked until the uploader accepts. It writes nothing, and the same
 * uploader's commit is still refused with 409 (D-1164).
 */
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
@DirtiesContext
class AcceptImportDryRunApiTests extends MemberImportApiTest {

    private final String carol = newUser();

    @AfterEach
    void everybodyAccepts() throws IOException {
        AcceptingMode.everybodyAccepts(this, family);
    }

    @Test
    void aLaggingDryRunCountsTheFamilyRowsAsNotCheckedAndWritesNothing() throws IOException {
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
        String before = state();

        JsonNode dry = ok(ownerImport(ImportFixtures.seatRows(d3c("partner-transactions.csv")), null, "true"));
        assertThat(dry.get("outcome").asText()).isEqualTo("DRY_RUN");
        List<String> warnings = new ArrayList<>();
        dry.get("warnings").forEach(warning -> warnings.add(warning.get("message").asText()));
        // The one note of D-1173, beside the fixture's own warning of an Excel ID gap, which every run of it has.
        assertThat(warnings).containsExactlyInAnyOrder(
                "1 family changes are waiting for you; accept them before committing",
                "the file's Excel IDs have 1 gap(s) between its lowest and highest ID: listed in the report");
        List<String> errors = new ArrayList<>();
        dry.get("errors").forEach(error -> errors.add(error.get("message").asText()));
        assertThat(errors).noneMatch(m -> m.contains("pending change"));
        // D-1180: every family row of the fixture, each row of a grouped settlement included: the rows a caught-up
        // dry run checks (below).
        assertThat(dry.get("totals").get("notCheckedUntilAccepted").asInt()).isEqualTo(16);
        assertThat(state()).isEqualTo(before);

        JsonNode problem = body(ownerImport(ImportFixtures.seatRows(d3c("partner-transactions.csv")), null, "false"),
                HttpStatus.CONFLICT);
        assertThat(problem.get("code").asText()).isEqualTo("FAMILY_PENDING_CHANGES");
        assertThat(state()).isEqualTo(before);

        ok(post(owner, "/api/family-ledgers/" + family + "/accept", "{}"));
        JsonNode caughtUp = ok(ownerImport(ImportFixtures.seatRows(d3c("partner-transactions.csv")), null, "true"));
        int familyRows = 0;
        for (JsonNode c : caughtUp.get("counts")) {
            if (!c.get("source").asText().equals("OWNER_PERSONAL")) {
                familyRows += c.get("created").asInt() + c.get("updated").asInt() + c.get("unchanged").asInt()
                        + c.get("conflicts").asInt() + c.get("failed").asInt();
            }
        }
        assertThat(familyRows).isEqualTo(16);
    }

    @Test
    void aCaughtUpDryRunChecksEveryFamilyRow() throws IOException {
        JsonNode dry = ok(ownerImport(ImportFixtures.seatRows(d3c("partner-transactions.csv")), null, "true"));
        assertThat(dry.get("totals").get("notCheckedUntilAccepted").asInt()).isZero();
        dry.get("warnings").forEach(warning -> assertThat(warning.get("message").asText())
                .doesNotContain("family changes are waiting for you"));
    }

    /** The family ledger's rows and counters, as one string to compare before and after. */
    private String state() {
        return jdbc.sql("""
                SELECT concat_ws(' ',
                  (SELECT revision FROM ledger WHERE id = :id),
                  (SELECT string_agg(id || ':' || coalesce(accepted_revision::text, '-'), ',' ORDER BY id)
                     FROM ledger_member WHERE ledger_id = :id),
                  (SELECT count(*) FROM family_record WHERE ledger_id = :id),
                  (SELECT string_agg(id || ':' || last_revision, ',' ORDER BY id) FROM family_record
                     WHERE ledger_id = :id),
                  (SELECT count(*) FROM category WHERE ledger_id = :id),
                  (SELECT count(*) FROM journal_entry WHERE ledger_id IN
                     (SELECT ledger_id FROM ledger_member WHERE user_sub = :owner)))""")
                .param("id", family).param("owner", owner).query(String.class).single();
    }
}
