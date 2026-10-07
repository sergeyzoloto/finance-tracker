package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The family of the family records' tests, and what they read: Alice ("Mum") created the family ledger "Home", which
 * starts on 2026-09-01; Bob ("Dad") is a member with an account since then, written with plain SQL as no code adds one
 * before F5; Kid has no account. After every test, the family ledger's invariants hold ({@link FamilyInvariants}).
 */
abstract class FamilyApiTest extends LedgerApiTest {

    /** The last octet of the next client address of {@link #inviteCall}. */
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    protected final String alice = newUser();
    protected final String bob = newUser();

    protected long family;
    protected String uri;
    protected long mum;
    protected long dad;
    protected long kid;
    protected long groceries;
    protected long salary;
    protected long rent;

    @BeforeEach
    void createTheFamily() throws IOException {
        ok(get(bob, "/api/accounts"));
        JsonNode created = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-09-01",
                 "categoryIds": [%d, %d]}""".formatted(categoryId(alice, "GROCERIES"), categoryId(alice, "SALARY")));
        family = created.get("id").asLong();
        uri = "/api/family-ledgers/" + family;
        mum = created.get("memberId").asLong();
        dad = join(family, bob, "Dad", "MEMBER", LocalDate.of(2026, 9, 1));
        kid = body(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED).get("id").asLong();
        JsonNode categories = ok(get(alice, uri + "/categories"));
        groceries = find(categories, "code", "GROCERIES").get("id").asLong();
        salary = find(categories, "code", "SALARY").get("id").asLong();
        rent = body(post(alice, uri + "/categories", """
                {"code": "RENT", "name": "Rent", "type": "EXPENSE"}"""), HttpStatus.CREATED).get("id").asLong();
    }

    @AfterEach
    void theInvariantsHold() {
        FamilyInvariants.check(jdbc, family);
    }

    /** A new record of the rule in the base currency, and the rest of the request. */
    protected static String expense(String date, long category, String amount, long payer, String payment) {
        return expense(date, category, amount, payer, payment, null);
    }

    protected static String expense(String date, long category, String amount, long payer, String payment,
            String split) {
        return """
                {"date": "%s", "categoryId": %d, "amount": "%s", %s "payerMemberId": %d%s}""".formatted(date, category,
                amount, payment, payer, split == null ? "" : ", \"split\": " + split);
    }

    protected JsonNode created(MvcTestResult result) throws IOException {
        return body(result, HttpStatus.CREATED);
    }

    protected String detail(MvcTestResult result, HttpStatus status) throws IOException {
        return body(result, status).get("detail").asText();
    }

    protected List<String> violationsOf(MvcTestResult result) throws IOException {
        List<String> violations = new ArrayList<>();
        body(result, HttpStatus.UNPROCESSABLE_ENTITY).get("violations").forEach(v -> violations.add(v.asText()));
        return violations;
    }

    /** The 422's violations as "CODE memberId message". */
    protected List<String> details(MvcTestResult result) throws IOException {
        List<String> details = new ArrayList<>();
        body(result, HttpStatus.UNPROCESSABLE_ENTITY).get("violationDetails").forEach(v -> details.add(
                v.get("code").asText() + " " + v.get("memberId").asText() + " " + v.get("message").asText()));
        return details;
    }

    /** The record's shares as "name amount basisPoints". */
    protected static List<String> shares(JsonNode record) {
        return StreamSupport.stream(record.get("shares").spliterator(), false)
                .map(s -> s.get("member").get("displayName").asText() + " " + s.get("amount").asText() + " "
                        + s.get("basisPoints").asText())
                .toList();
    }

    /** The balances in the main currency, EUR, as the user reads them, as "name balance" and " you" for their own. */
    protected List<String> balances(String user) throws IOException {
        JsonNode balances = ok(get(user, uri + "/balances")).get("byCurrency").get(0);
        assertThat(balances.get("currency").asText()).isEqualTo("EUR");
        return StreamSupport.stream(balances.get("members").spliterator(), false)
                .map(b -> b.get("displayName").asText() + " " + b.get("balance").asText()
                        + (b.get("you").asBoolean() ? " you" : ""))
                .toList();
    }

    /** The user's entries of the family, as "kind link account:amount:category …", oldest first. */
    protected List<String> postedEntries(String user) throws IOException {
        List<String> entries = new ArrayList<>();
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            if (!entry.get("family").isNull()) {
                StringBuilder text = new StringBuilder(entry.get("kind").asText() + " "
                        + entry.get("family").get("link").asText());
                entry.get("postings").forEach(p -> text.append(" ").append(p.get("accountId").asLong()).append(":")
                        .append(p.get("amount").asText()).append(":").append(p.get("categoryId").asText()));
                entries.add(0, text.toString());
            }
        }
        return entries;
    }

    protected List<String> entryVersions(String user) throws IOException {
        return ok(get(user, "/api/entries?size=200")).get("content").findValuesAsText("version");
    }

    /** The journal as "ACTION by author: field old→new, …". */
    protected static List<String> changes(JsonNode journal) {
        return StreamSupport.stream(journal.get("content").spliterator(), false).map(change -> {
            List<String> fields = new ArrayList<>();
            change.get("changes").forEach(c -> fields.add(c.get("field").asText()
                    + (c.get("member").isNull() ? "" : " of " + c.get("member").get("displayName").asText()) + " "
                    + c.get("old").asText() + "→" + c.get("new").asText()));
            return change.get("action").asText() + " by " + change.get("author").get("displayName").asText() + ": "
                    + String.join(", ", fields);
        }).toList();
    }

    /** Each journal entry's record as "date category amount live|deleted". */
    protected static List<String> summaries(JsonNode journal) {
        return StreamSupport.stream(journal.get("content").spliterator(), false).map(change -> change.get("record"))
                .map(record -> record.get("date").asText() + " " + record.get("category").asText() + " "
                        + record.get("amount").asText() + (record.get("deleted").asBoolean() ? " deleted" : " live"))
                .toList();
    }

    /**
     * A new invite of the owner's to the family ledger, which must be valid: the token from its link's fragment.
     *
     * @param request the body of POST /invites
     */
    protected String newInvite(String owner, String request) throws IOException {
        String link = created(post(owner, uri + "/invites", request)).get("link").asText();
        assertThat(link).startsWith("https://app.finance-nl.com/invite#");
        return link.substring(link.indexOf('#') + 1);
    }

    /** An invite of Alice's to take Kid's place from the date: its token. */
    protected String kidsPlace(String joinDate) throws IOException {
        return newInvite(alice, """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "%s"}""".formatted(kid, joinDate));
    }

    /**
     * POST /api/invites/{action} as the user, from an address of its own, so that the limit per client address
     * doesn't add up across tests (InviteRateLimit).
     */
    protected MvcTestResult inviteCall(String user, String action, String body) {
        return inviteCall(user, action, body, "198.51.100." + (ADDRESSES.incrementAndGet() % 250 + 1));
    }

    protected MvcTestResult inviteCall(String user, String action, String body, String address) {
        return mvc.post().uri("/api/invites/" + action).with(member(user)).with(request -> {
            request.setRemoteAddr(address);
            return request;
        }).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** The body {"token": …}, with more fields if not null. */
    protected static String token(String token, String more) {
        return "{\"token\": \"%s\"%s}".formatted(token, more == null ? "" : ", " + more);
    }

    /** Accepts the invite with the display name and the categories to bring, which must work. */
    protected JsonNode accept(String user, String token, String displayName, Long... categoryIds) throws IOException {
        return ok(inviteCall(user, "accept", token(token, "\"displayName\": \"%s\", \"categoryIds\": %s"
                .formatted(displayName, List.of(categoryIds)))));
    }

    /** The api's today for these users, who set no time zone: the UTC date (D-101). */
    protected LocalDate today() {
        return utcToday();
    }

    protected static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(element -> element.get("id").asLong()).toList();
    }

    protected static JsonNode[] elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toArray(JsonNode[]::new);
    }
}
