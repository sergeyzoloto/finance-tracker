package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.importer.ImportFixtures.seatRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * D3c's family (src/test/resources/import/d3c/, invented): the owner Alex creates "Home" (from 2024-01-01) with Sam, a
 * place without an account, and imports his export with Sam's family rows, as D3b-run did; then the partner takes Sam's
 * place by a claim and imports his own export (D-174 to D-191).
 */
abstract class MemberImportApiTest extends LedgerApiTest {

    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    protected final String owner = newUser();
    protected final String partner = newUser();
    protected long family;
    protected long ownerMember;
    protected long seat;

    @BeforeEach
    void createTheFamilyBudgetAndThePlace() throws IOException {
        ok(get(owner, "/api/accounts"));
        ok(get(partner, "/api/accounts"));
        JsonNode created = newFamily(owner, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Alex", "startDate": "2024-01-01",
                 "categoryIds": []}""");
        family = created.get("id").asLong();
        ownerMember = created.get("memberId").asLong();
        seat = body(post(owner, "/api/family-ledgers/" + family + "/members", """
                {"displayName": "Sam"}"""), HttpStatus.CREATED).get("id").asLong();
    }

    protected static byte[] d3c(String name) throws IOException {
        return fixture("d3c/" + name);
    }

    /** The owner's first import, as D3b-run: his export and Sam's family rows (D-151), committed. */
    protected JsonNode ownersFirstImport() throws IOException {
        return ownersFirstImport(seatRows(d3c("partner-transactions.csv")));
    }

    protected JsonNode ownersFirstImport(byte[] seatRows) throws IOException {
        JsonNode report = ok(ownerImport(seatRows, null, "false"));
        assertThat(report.get("errors")).isEmpty();
        assertThat(report.get("outcome").asText()).isEqualTo("COMMITTED");
        return report;
    }

    /** POST /api/import with the owner's transactions file, and Sam's rows where not null. */
    protected MvcTestResult ownerImport(byte[] seatRows, String upTo, String dryRun) throws IOException {
        return upload(owner, "owner", d3c("owner-transactions.csv"), seatRows, seat, upTo, dryRun);
    }

    /** POST /api/import with the partner's transactions file, as himself in his own place (D-175). */
    protected MvcTestResult memberImport(byte[] transactions, String upTo, String dryRun) throws IOException {
        return upload(partner, "partner", transactions, null, seat, upTo, dryRun);
    }

    protected MvcTestResult upload(String user, String prefix, byte[] transactions, byte[] seatRows, long seatId,
            String upTo, String dryRun) throws IOException {
        var request = mvc.post().uri("/api/import").multipart()
                .file(new MockMultipartFile("transactions", prefix + "-transactions.csv", "text/csv", transactions))
                .param("familyLedgerId", String.valueOf(family)).param("seatMemberId", String.valueOf(seatId))
                .with(member(user));
        if (seatRows != null) {
            request.file(new MockMultipartFile("partnerTransactions", "partner-family-rows.csv", "text/csv",
                    seatRows));
        }
        if (upTo != null) {
            request.param("upTo", upTo);
        }
        if (dryRun != null) {
            request.param("dryRun", dryRun);
        }
        return request.exchange();
    }

    /** The owner's invite to take Sam's place from the date: its token. */
    protected String claimInvite(String joinDate) throws IOException {
        String link = body(post(owner, "/api/family-ledgers/" + family + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "%s"}""".formatted(seat, joinDate)),
                HttpStatus.CREATED).get("link").asText();
        return link.substring(link.indexOf('#') + 1);
    }

    protected MvcTestResult inviteCall(String user, String action, String body) {
        String address = "203.0.113." + (ADDRESSES.incrementAndGet() % 250 + 1);
        return mvc.post().uri("/api/invites/" + action).with(member(user)).with(request -> {
            request.setRemoteAddr(address);
            return request;
        }).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    protected static String token(String token, String more) {
        return "{\"token\": \"%s\"%s}".formatted(token, more == null ? "" : ", " + more);
    }

    /** The partner takes Sam's place from D-174's date, which must work. */
    protected JsonNode claim() throws IOException {
        return ok(inviteCall(partner, "accept", token(claimInvite("2024-02-01"),
                "\"displayName\": \"Sam B\", \"categoryIds\": []")));
    }

    protected long record(String excelId) {
        return jdbc.sql("SELECT id FROM family_record WHERE ledger_id = ? AND external_ref = ?")
                .params(family, "xls:" + seat + ":" + excelId).query(Long.class).single();
    }

    protected long ownersRecord(String excelId) {
        return jdbc.sql("SELECT id FROM family_record WHERE ledger_id = ? AND external_ref = ?")
                .params(family, "xls:" + ownerMember + ":" + excelId).query(Long.class).single();
    }

    protected String records() {
        return "/api/family-ledgers/" + family + "/records/";
    }

    protected static String count(JsonNode report, String source, String kind) {
        for (JsonNode c : report.get("counts")) {
            if (c.get("source").asText().equals(source) && c.get("kind").asText().equals(kind)) {
                return "c%d u%d n%d x%d e%d s%d i%d".formatted(c.get("created").asInt(), c.get("updated").asInt(),
                        c.get("unchanged").asInt(), c.get("conflicts").asInt(), c.get("failed").asInt(),
                        c.get("skipped").asInt(), c.get("notImported").asInt());
            }
        }
        return "none";
    }

    /** The errors as "Excel ID: message". */
    protected static List<String> errors(JsonNode report) {
        return report.get("errors").findValues("message").stream().map(JsonNode::asText).toList().isEmpty()
                ? List.of()
                : java.util.stream.StreamSupport.stream(report.get("errors").spliterator(), false)
                        .map(e -> e.get("excelId").asText() + ": " + e.get("message").asText()).toList();
    }
}
