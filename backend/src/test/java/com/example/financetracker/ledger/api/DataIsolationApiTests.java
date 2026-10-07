package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Strict isolation between users (rule 11), endpoint by endpoint. Anyone can sign up to the production realm, so no
 * user may see, change or use anything of another user's.
 * <p>
 * Alice's ledger has every kind of row a user owns: accounts, categories and counterparties of her own, entries of
 * every kind, settings, manual rates and an import, on the same days and in the same currencies as Bob's. Bob can't
 * list, read, change, delete or refer to any of it; for each of her objects he gets the answer he gets for an object
 * that doesn't exist; every read of his answers the same before and after she writes her ledger; and his demo data
 * and the deletion of all his data leave hers as they were.
 * <p>
 * Every request of Bob's after Alice wrote her ledger goes through {@link #bobsRequest}, which checks that her rows
 * are the same afterwards and records the endpoint that handled it, as the application's handler mapping resolved
 * it. {@link #everyEndpointIsCheckedHere} runs last and fails for every mapped endpoint that none of his requests
 * reached, unless {@link #NOT_USER_SCOPED} names it with the reason.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataIsolationApiTests extends LedgerApiTest {

    private static final String AUGUST = "?from=2026-08-01&to=2026-08-31";
    private static final String AS_OF = "?asOf=2026-08-31";
    /** An id that no row has. */
    private static final long MISSING = 9_000_000_000L;

    /** Every read of the API, with parameters under which Alice's ledger would show if it leaked. */
    private static final List<String> READS = List.of(
            "/api/me",
            "/api/accounts",
            "/api/categories",
            "/api/counterparties",
            "/api/entries?size=200",
            "/api/entries" + AUGUST + "&q=shop",
            "/api/reports/balances" + AS_OF,
            "/api/reports/balances" + AS_OF + "&currency=BASE",
            "/api/reports/cash-flow" + AUGUST,
            "/api/reports/cash-flow" + AUGUST + "&currency=BASE",
            "/api/reports/counterparty-balances" + AS_OF + "&accountCode=LOANS_ASSET",
            "/api/reports/net-worth" + AS_OF,
            "/api/reports/net-worth" + AS_OF + "&currency=BASE",
            "/api/reports/shared-settlement" + AS_OF,
            "/api/reports/integrity",
            "/api/rates",
            "/api/rates/manual",
            "/api/settings");

    /** The mapped endpoints that hold nothing of any user's, so that there is nothing to isolate, with the reason. */
    private static final Map<String, String> NOT_USER_SCOPED = Map.of(
            "GET /api/openapi", "The API's description, the same for every user.",
            "GET /api/openapi.yaml", "The same description as YAML.",
            "GET /actuator", "Actuator's links to its exposed endpoints: only health, open to everyone.",
            "GET /actuator/health", "UP or DOWN, open to everyone.",
            "GET /actuator/health/**", "The status of a health group or component, open to everyone.",
            "ANY /error", "Spring Boot's error answer, which says only the status of a failed request.");

    /** The endpoints that handled a request of Bob's against Alice's data, in every test of this class so far. */
    private static final Set<String> CHECKED = ConcurrentHashMap.newKeySet();

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private List<RequestMappingInfoHandlerMapping> handlerMappings;

    private final String alice = newUser();
    private final String bob = newUser();

    /** Bob's answer to every read, before Alice wrote anything. */
    private Map<String, JsonNode> bobsViewBefore;
    private long bobsCash;
    private long bobsGroceries;
    private long bobsFriend;
    private JsonNode bobsExpense;

    private long alicesBank;
    private long alicesLoans;
    private long alicesSharedAccount;
    private long alicesHobby;
    private long alicesGifts;
    private long alicesLandlord;
    private long alicesFriend;
    private JsonNode alicesImport;

    @BeforeEach
    void writeBothLedgers() throws IOException {
        writeBobsLedger();
        bobsViewBefore = view(bob);
        writeAlicesLedger();
    }

    @Test
    void bobsAnswersAreTheSameBeforeAndAfterAliceWritesHerLedger() throws IOException {
        Map<String, JsonNode> bobsView = bobsView();
        Map<String, JsonNode> alicesView = view(alice);

        SoftAssertions softly = new SoftAssertions();
        for (String uri : READS) {
            softly.assertThat(bobsView.get(uri)).as("Bob's %s", uri).isEqualTo(bobsViewBefore.get(uri));
            softly.check(() -> Answers.assertNoneMention("Bob's " + uri, bobsView.get(uri), "Alice", "ALICE", alice));
            // Her answer differs, so her ledger would have shown in his if it leaked.
            softly.assertThat(alicesView.get(uri)).as("Alice's %s", uri).isNotEqualTo(bobsView.get(uri));
        }
        softly.assertAll();

        // Her rates for dollars don't convert his dollars, and her rate for pounds doesn't replace his of the same day.
        assertThat(find(bobsView.get("/api/reports/balances" + AS_OF + "&currency=BASE"), "accountCode", "CASH")
                .get("balance").isNull()).isTrue();
        JsonNode bobsRates = bobsView.get("/api/rates").get("latest");
        assertThat(find(bobsRates, "currency", "USD").get("date").isNull()).isTrue();
        assertThat(find(bobsRates, "currency", "GBP").get("perEuro").asText()).isEqualTo("0.90");
        assertThat(find(alicesView.get("/api/rates").get("latest"), "currency", "GBP").get("perEuro").asText())
                .isEqualTo("0.85");
        // Her ledger doesn't add up any more (writeAlicesLedger), and his integrity check doesn't notice.
        assertThat(alicesView.get("/api/reports/integrity")).isNotEmpty();
        assertThat(bobsView.get("/api/reports/integrity")).isEmpty();
    }

    @Test
    void bobCanNeitherReadNorChangeNorDeleteAnythingOfAlices() throws IOException {
        Map<String, JsonNode> alicesView = view(alice);
        String bobsCommand = expense(bobsCash, bobsGroceries, null);
        Set<String> bobsRates = rateKeys(bobsViewBefore.get("/api/rates/manual"));

        SoftAssertions softly = new SoftAssertions();
        for (JsonNode entry : elements(alicesView.get("/api/entries?size=200").get("content"))) {
            long id = entry.get("id").asLong();
            // With her entry's version and with a stale one: an entry he doesn't have is missing whatever the version.
            for (String version : List.of("?version=" + entry.get("version").asInt(), "?version=99")) {
                answersAsIfMissing(softly, HttpMethod.PUT, "/api/entries/%d" + version, id, bobsCommand);
                answersAsIfMissing(softly, HttpMethod.DELETE, "/api/entries/%d" + version, id, null);
            }
            answersAsIfMissing(softly, HttpMethod.GET, "/api/entries/%d", id, null);
        }
        // Her system accounts too, which he would get 409 for if they were his.
        for (JsonNode account : elements(alicesView.get("/api/accounts"))) {
            answersAsIfMissing(softly, HttpMethod.PATCH, "/api/accounts/%d", account.get("id").asLong(), """
                    {"name": "Bob's now", "archived": true, "defaultCurrency": "EUR"}""");
        }
        // With the other type too, which he would get 409 for if the category were his.
        for (JsonNode category : elements(alicesView.get("/api/categories"))) {
            answersAsIfMissing(softly, HttpMethod.PATCH, "/api/categories/%d", category.get("id").asLong(), """
                    {"name": "Bob's now", "archived": true, "type": "%s"}"""
                    .formatted(category.get("type").asText().equals("INCOME") ? "EXPENSE" : "INCOME"));
        }
        for (JsonNode counterparty : elements(alicesView.get("/api/counterparties"))) {
            answersAsIfMissing(softly, HttpMethod.PATCH, "/api/counterparties/%d", counterparty.get("id").asLong(), """
                    {"name": "Bob's now", "archived": true, "kind": "MERCHANT"}""");
        }
        // A rate of the same day and currency as one of his is his own to delete (next test).
        for (JsonNode rate : elements(alicesView.get("/api/rates/manual"))) {
            if (!bobsRates.contains(rateKey(rate))) {
                answersAsIfMissing(softly, HttpMethod.DELETE, "/api/rates/manual?date=%s&currency=" + rate.get("quote")
                        .asText(), rate.get("date").asText(), "2030-01-01", null);
            }
        }
        softly.assertAll();

        assertThat(view(alice)).isEqualTo(alicesView);
        assertThat(bobsView()).isEqualTo(bobsViewBefore);
    }

    @Test
    void bobCannotReferToAlicesObjectsInHisOwnWrites() throws IOException {
        Map<String, JsonNode> alicesView = view(alice);
        long alicesCash = find(alicesView.get("/api/accounts"), "code", "CASH").get("id").asLong();
        long alicesGroceries = find(alicesView.get("/api/categories"), "code", "GROCERIES").get("id").asLong();
        long bobsCurrent = accountId(bob, "CURRENT_ACCOUNT");
        long bobsUnallocated = accountId(bob, "UNALLOCATED");
        long bobsLoans = accountId(bob, "LOANS_ASSET");
        List<Reference> references = List.of(
                new Reference("an expense from her account", alicesCash, id -> expense(id, bobsGroceries, null)),
                new Reference("an expense in her category", alicesHobby, id -> expense(bobsCash, id, null)),
                new Reference("an expense paid to her payee", alicesLandlord,
                        id -> expense(bobsCash, bobsGroceries, id)),
                new Reference("an income in her category", alicesGifts, id -> """
                        {"kind": "INCOME", "entryDate": "2026-08-20", "accountId": %d, "currency": "EUR",
                         "amount": "1", "categoryId": %d}""".formatted(bobsCash, id)),
                new Reference("a transfer to her account", alicesBank, id -> """
                        {"kind": "TRANSFER", "entryDate": "2026-08-20", "fromAccountId": %d, "toAccountId": %d,
                         "currency": "EUR", "amount": "1"}""".formatted(bobsCash, id)),
                new Reference("a transfer with her counterparty", alicesFriend, id -> """
                        {"kind": "TRANSFER", "entryDate": "2026-08-20", "fromAccountId": %d, "toAccountId": %d,
                         "currency": "EUR", "amount": "1", "counterpartyId": %d}""".formatted(bobsCash, bobsCurrent,
                        id)),
                new Reference("a shared expense in her category", alicesGroceries, id -> """
                        {"kind": "SHARED_EXPENSE", "entryDate": "2026-08-20", "accountId": %d, "currency": "EUR",
                         "total": "1", "categoryId": %d}""".formatted(bobsCash, id)),
                new Reference("a loan to her friend", alicesFriend, id -> """
                        {"kind": "LOAN_GIVEN", "entryDate": "2026-08-20", "fromAccountId": %d, "counterpartyId": %d,
                         "currency": "EUR", "amount": "1"}""".formatted(bobsCash, id)),
                new Reference("a repayment by her friend", alicesFriend, id -> """
                        {"kind": "LOAN_REPAID", "entryDate": "2026-08-20", "toAccountId": %d, "counterpartyId": %d,
                         "currency": "EUR", "amount": "1"}""".formatted(bobsCash, id)),
                new Reference("an exchange into her account", alicesBank, id -> """
                        {"kind": "CURRENCY_EXCHANGE", "entryDate": "2026-08-20", "fromAccountId": %d,
                         "fromCurrency": "EUR", "fromAmount": "1", "toAccountId": %d, "toCurrency": "USD",
                         "toAmount": "1"}""".formatted(bobsCash, id)),
                new Reference("an opening balance of her account", alicesSharedAccount, id -> """
                        {"kind": "OPENING_BALANCE", "entryDate": "2026-08-20", "accountId": %d, "currency": "EUR",
                         "amount": "1"}""".formatted(id)),
                new Reference("a manual posting to her account", alicesLoans, id -> """
                        {"kind": "MANUAL", "entryDate": "2026-08-20", "postings": [
                          {"accountId": %d, "currency": "EUR", "amount": "1", "counterpartyId": %d},
                          {"accountId": %d, "currency": "EUR", "amount": "-1"}]}""".formatted(id, bobsFriend,
                        bobsCash)),
                new Reference("a manual posting in her category", alicesGifts, id -> """
                        {"kind": "MANUAL", "entryDate": "2026-08-20", "postings": [
                          {"accountId": %d, "currency": "EUR", "amount": "1"},
                          {"accountId": %d, "currency": "EUR", "amount": "-1", "categoryId": %d}]}"""
                        .formatted(bobsCash, bobsUnallocated, id)),
                new Reference("a manual posting with her counterparty", alicesFriend, id -> """
                        {"kind": "MANUAL", "entryDate": "2026-08-20", "postings": [
                          {"accountId": %d, "currency": "EUR", "amount": "1", "counterpartyId": %d},
                          {"accountId": %d, "currency": "EUR", "amount": "-1"}]}""".formatted(bobsLoans, id,
                        bobsCash)));
        String bobsEntry = "/api/entries/%d?version=%d".formatted(bobsExpense.get("id").asLong(),
                bobsExpense.get("version").asInt());

        SoftAssertions softly = new SoftAssertions();
        for (Reference reference : references) {
            String hers = reference.command().apply(reference.alicesId());
            String missing = reference.command().apply(MISSING);
            // In a new entry, and in place of one of his own.
            for (MvcTestResult[] answers : List.of(
                    new MvcTestResult[] {bobsRequest(HttpMethod.POST, "/api/entries", hers),
                            post(bob, "/api/entries", missing)},
                    new MvcTestResult[] {bobsRequest(HttpMethod.PUT, bobsEntry, hers), put(bob, bobsEntry, missing)})) {
                softly.assertThat(answers[0].getResponse().getStatus()).as(reference.what()).isEqualTo(422);
                softly.assertThat(withoutDigits(answers[0])).as(reference.what()).isEqualTo(withoutDigits(answers[1]));
            }
        }
        String settings = """
                {"baseCurrency": "EUR", "sharedAccountId": %d, "defaultShareRatio": "0.5"}""";
        MvcTestResult herSharedAccount = bobsRequest(HttpMethod.PUT, "/api/settings",
                settings.formatted(alicesSharedAccount));
        softly.assertThat(herSharedAccount.getResponse().getStatus()).as("her shared account").isEqualTo(422);
        softly.assertThat(withoutDigits(herSharedAccount)).as("her shared account")
                .isEqualTo(withoutDigits(put(bob, "/api/settings", settings.formatted(MISSING))));
        // As a filter, her objects find nothing, like objects that don't exist.
        for (String filter : List.of("accountId=%d", "categoryId=%d", "counterpartyId=%d")) {
            long id = filter.startsWith("account") ? alicesCash : filter.startsWith("category") ? alicesHobby
                    : alicesFriend;
            JsonNode found = bobReads("/api/entries?" + filter.formatted(id));
            softly.assertThat(found.get("totalElements").asLong()).as(filter).isZero();
            softly.assertThat(found).as(filter).isEqualTo(ok(get(bob, "/api/entries?" + filter.formatted(MISSING))));
        }
        softly.assertThat(body(bobsRequest(HttpMethod.GET, "/api/reports/counterparty-balances?accountCode=ALICE_LOANS",
                null), HttpStatus.NOT_FOUND).get("detail").asText()).isEqualTo("Account ALICE_LOANS not found.");
        softly.assertAll();
        assertThat(bobsView()).isEqualTo(bobsViewBefore);

        // Codes and names are unique per user only, so taking hers tells him nothing: a clash would.
        assertThat(bobsRequest(HttpMethod.POST, "/api/accounts", """
                {"code": "ALICE_BANK", "name": "Alice's bank", "type": "ASSET"}""")).hasStatus(HttpStatus.CREATED);
        assertThat(bobsRequest(HttpMethod.POST, "/api/categories", """
                {"code": "ALICE_HOBBY", "name": "Alice's hobby", "type": "EXPENSE"}""")).hasStatus(HttpStatus.CREATED);
        assertThat(bobsRequest(HttpMethod.POST, "/api/counterparties", """
                {"name": "Alice's friend"}""")).hasStatus(HttpStatus.CREATED);
        // A rate of his own for her day and currency, and deleting his rate of a day she has one for, leave hers.
        ok(bobsRequest(HttpMethod.POST, "/api/rates/manual", """
                {"date": "2026-08-01", "base": "EUR", "quote": "USD", "rate": "2"}"""));
        assertThat(bobsRequest(HttpMethod.DELETE, "/api/rates/manual?date=2026-08-01&currency=GBP", null))
                .hasStatus(HttpStatus.NO_CONTENT);
        // So do rates of his own from a file, for her days and currencies.
        assertThat(ok(bobsRequest(() -> mvc.post().uri("/api/rates/manual/csv").multipart()
                .file(new MockMultipartFile("file", "rates.csv", "text/csv", """
                        date,base,quote,rate
                        2026-08-15,EUR,USD,1.5
                        2026-08-01,EUR,GBP,0.7
                        """.getBytes(StandardCharsets.UTF_8)))
                .with(member(bob))
                .exchange())).get("saved").asInt()).isEqualTo(2);
        assertThat(rateKeys(bobReads("/api/rates/manual"))).contains("2026-08-15 USD", "2026-08-01 GBP");
        assertThat(view(alice)).isEqualTo(alicesView);
    }

    @Test
    void anImportSeesOnlyTheUsersOwnLedger() throws IOException {
        Map<String, JsonNode> alicesView = view(alice);
        byte[] transactions = withoutRow(fixture("transactions.csv"), 10);

        JsonNode dryRun = ok(bobsRequest(() -> importWorkbook(bob, transactions, null)));
        JsonNode committed = ok(bobsRequest(() -> importWorkbook(bob, transactions, "false")));

        // Alice imported the same files, but for Bob every row is new: none is skipped as imported before.
        assertThat(dryRun.get("entriesByKind")).isEqualTo(alicesImport.get("entriesByKind"));
        assertThat(committed.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("userId").asText()).isEqualTo(bob);
        assertThat(committed.get("entriesByKind")).isEqualTo(alicesImport.get("entriesByKind"));
        assertThat(committed.get("skipped")).isEqualTo(alicesImport.get("skipped"));
        // The balances after the import are his accounts' alone.
        List<String> bobsAccounts = bobReads("/api/accounts").findValuesAsText("id");
        assertThat(committed.get("balances").findValuesAsText("accountId")).isNotEmpty()
                .allMatch(bobsAccounts::contains);
        assertThat(bobReads("/api/entries").get("totalElements").asLong()).isEqualTo(4 + 24);
        assertThat(view(alice)).isEqualTo(alicesView);
    }

    @Test
    void theUserIsTheAccessTokensSubjectAndNothingElse() throws IOException {
        Map<String, JsonNode> alicesView = view(alice);

        // A parameter, a header or a field of the body that names Alice changes nothing.
        assertThat(ok(bobsRequest(() -> mvc.get().uri("/api/accounts").param("userId", alice).param("user_id", alice)
                .param("sub", alice).header("X-User-Id", alice).with(member(bob)).exchange())))
                .isEqualTo(bobsViewBefore.get("/api/accounts"));
        assertThat(bobsRequest(HttpMethod.POST, "/api/counterparties", """
                {"name": "Planted", "userId": "%1$s", "user_id": "%1$s", "sub": "%1$s"}""".formatted(alice)))
                .hasStatus(HttpStatus.CREATED);
        body(bobsRequest(HttpMethod.POST, "/api/entries", """
                {"kind": "EXPENSE", "entryDate": "2026-08-20", "accountId": %d, "currency": "EUR", "amount": "1",
                 "categoryId": %d, "memo": "Planted", "userId": "%s"}""".formatted(bobsCash, bobsGroceries, alice)),
                HttpStatus.CREATED);
        ok(bobsRequest(HttpMethod.PUT, "/api/settings", """
                {"baseCurrency": "GBP", "defaultShareRatio": "0.5", "userId": "%s"}""".formatted(alice)));
        // D-100: his zone is his own, whatever else the body names.
        ok(bobsRequest(HttpMethod.PUT, "/api/settings/time-zone", """
                {"timeZone": "Asia/Kolkata", "userId": "%s"}""".formatted(alice)));
        assertThat(bobReads("/api/me").get("timeZone").asText()).isEqualTo("Asia/Kolkata");
        // D-103: the list of accepted ids is the same for everyone, and holds nothing of anyone.
        assertThat(bobReads("/api/settings/time-zones").get(0).asText()).isNotBlank();
        assertThat(bobReads("/api/counterparties").findValuesAsText("name")).contains("Planted");
        assertThat(bobReads("/api/entries").get("totalElements").asLong()).isEqualTo(5);
        assertThat(bobReads("/api/settings").get("baseCurrency").asText()).isEqualTo("GBP");
        assertThat(view(alice)).isEqualTo(alicesView);

        // The same with an access token signed like Keycloak's, rather than spring-security-test's stand-in.
        long alicesEntry = alicesView.get("/api/entries?size=200").get("content").get(0).get("id").asLong();
        assertThat(bobsRequest(() -> request(HttpMethod.GET, "/api/entries/%d?userId=%s".formatted(alicesEntry, alice),
                token(bob), null))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(read(bobsRequest(() -> request(HttpMethod.GET, "/api/counterparties?userId=" + alice, token(bob),
                null)), JsonNode.class).findValuesAsText("name")).containsExactly("Bob's friend", "Planted");
    }

    @Test
    void bobsDemoDataAndDeletingAllOfHisDataLeaveAlicesAsTheyWere() throws IOException {
        Map<String, JsonNode> alicesView = view(alice);
        Map<String, Long> alicesRows = rowsOf(alice);

        // He has entries, so the demo is refused until he deletes his data.
        assertThat(bobsRequest(HttpMethod.POST, "/api/demo-data", null)).hasStatus(HttpStatus.CONFLICT);
        assertThat(bobsRequest(HttpMethod.DELETE, "/api/me/data", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rowsOf(bob)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(rowsOf(alice)).isEqualTo(alicesRows);
        assertThat(view(alice)).isEqualTo(alicesView);

        ok(bobsRequest(HttpMethod.POST, "/api/demo-data", null));
        assertThat(rowsOf(alice)).isEqualTo(alicesRows);
        assertThat(view(alice)).isEqualTo(alicesView);
        // His demo ledger holds nothing of hers, and her broken posting doesn't show in his integrity check.
        Map<String, JsonNode> bobsView = bobsView();
        SoftAssertions softly = new SoftAssertions();
        for (String uri : READS) {
            softly.check(() -> Answers.assertNoneMention("Bob's " + uri, bobsView.get(uri), "Alice", "ALICE", alice));
        }
        softly.assertAll();
        assertThat(bobsView.get("/api/reports/integrity")).isEmpty();

        assertThat(bobsRequest(HttpMethod.DELETE, "/api/me/data", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rowsOf(bob)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(rowsOf(alice)).isEqualTo(alicesRows);
        assertThat(view(alice)).isEqualTo(alicesView);
    }

    /** A shared browser or a proxy must never hand one user's answer to the next. */
    @Test
    void noAnswerOfTheApiMayBeStored() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        for (String uri : READS) {
            softly.assertThat(bobsRequest(HttpMethod.GET, uri, null).getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                    .as(uri).contains("no-store");
        }
        softly.assertAll();
    }

    /**
     * Family ledgers with three users (F3a; ADR 0003, topic K): Alice and Bob in family ledger A, Alice alone in B,
     * Carol in neither. For everything of B, Bob gets the answer for a family ledger that doesn't exist, and so does
     * Carol for everything of A and B, reads and writes alike. Nobody reaches a personal ledger, or another family
     * ledger's members and categories, through a family endpoint. Bob sees only A's family data, and no family answer
     * holds a sub or an email address. Bob's requests on B reach every family endpoint for
     * {@link #everyEndpointIsCheckedHere}.
     */
    @Test
    void familyLedgersAreReachedByTheirActiveMembersOnly() throws IOException {
        String carol = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        Map<String, JsonNode> alicesViewBefore = view(alice);
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "categoryIds": [%d]}"""
                .formatted(categoryId(alice, "GROCERIES"))).get("id").asLong();
        join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 9, 1));
        body(post(alice, "/api/family-ledgers/" + familyA + "/members", """
                {"displayName": "Grandma"}"""), HttpStatus.CREATED);
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "USD", "displayName": "Alice", "splitRule": "CUSTOM",
                 "categoryIds": [%d]}""".formatted(alicesHobby)).get("id").asLong();
        long bSeat = created(post(alice, "/api/family-ledgers/" + familyB + "/members", """
                {"displayName": "Alice's cousin"}"""));
        long bCategory = find(ok(get(alice, "/api/family-ledgers/" + familyB + "/categories")), "code",
                "ALICE_HOBBY").get("id").asLong();
        long familyC = newFamily(bob, """
                {"name": "Bob's allotment", "baseCurrency": "EUR", "displayName": "Bob"}""").get("id").asLong();
        // Family ledgers change Alice's personal answers only by the merge at creation (D-11, F4a): her GROCERIES and
        // ALICE_HOBBY became the family categories of A and B, which take their place wherever her answers named
        // them, marked with their family budget in the category list and the cash flow.
        long groceriesInA = find(ok(get(alice, "/api/family-ledgers/" + familyA + "/categories")), "code",
                "GROCERIES").get("id").asLong();
        long alicesGroceries = find(alicesViewBefore.get("/api/categories"), "code", "GROCERIES").get("id").asLong();
        List<Merged> merged = List.of(new Merged(alicesGroceries, groceriesInA, "GROCERIES", familyA, "Home"),
                new Merged(alicesHobby, bCategory, "ALICE_HOBBY", familyB, "ALICE_SECRET_BUDGET"));
        assertThat(view(alice)).isEqualTo(merged(alicesViewBefore, merged));
        long alicesPersonal = personalLedger(alice);
        long bobsPersonal = personalLedger(bob);
        List<FamilyRequest> requests = List.of(
                new FamilyRequest(HttpMethod.GET, "", null),
                new FamilyRequest(HttpMethod.PATCH, "", """
                        {"name": "Mine now", "baseCurrency": "EUR"}"""),
                new FamilyRequest(HttpMethod.PUT, "/split-rule", """
                        {"rule": "EQUAL"}"""),
                new FamilyRequest(HttpMethod.GET, "/members", null),
                new FamilyRequest(HttpMethod.POST, "/members", """
                        {"displayName": "Intruder"}"""),
                new FamilyRequest(HttpMethod.PATCH, "/members/" + bSeat, """
                        {"displayName": "Intruder"}"""),
                new FamilyRequest(HttpMethod.DELETE, "/members/" + bSeat, null),
                new FamilyRequest(HttpMethod.GET, "/categories", null),
                new FamilyRequest(HttpMethod.POST, "/categories", """
                        {"code": "INTRUDER", "name": "Intruder", "type": "EXPENSE"}"""),
                new FamilyRequest(HttpMethod.PATCH, "/categories/" + bCategory, """
                        {"name": "Intruder", "archived": true}"""),
                new FamilyRequest(HttpMethod.DELETE, "/categories/" + bCategory, null));

        SoftAssertions softly = new SoftAssertions();
        for (FamilyRequest request : requests) {
            String uri = "/api/family-ledgers/%d" + request.path();
            // Bob on B, and on either personal ledger, his own included.
            for (long ledger : List.of(familyB, alicesPersonal, bobsPersonal)) {
                answersAsIfMissing(softly, request.method(), uri, ledger, request.body());
            }
            // Carol on A and B, and on Alice's personal ledger.
            for (long ledger : List.of(familyA, familyB, alicesPersonal)) {
                answersAsIfMissingTo(softly, carol, request.method(), uri.formatted(ledger),
                        uri.formatted(MISSING), request.body());
            }
        }
        // In a family ledger of his own, B's members and categories and Alice's personal ones are missing.
        String inC = "/api/family-ledgers/" + familyC;
        for (long bMember : List.of(bSeat, jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub = ?")
                .params(familyB, alice).query(Long.class).single())) {
            answersAsIfMissing(softly, HttpMethod.PATCH, inC + "/members/%d", bMember, """
                    {"displayName": "Intruder"}""");
            answersAsIfMissing(softly, HttpMethod.DELETE, inC + "/members/%d", bMember, null);
        }
        for (long category : List.of(bCategory, alicesHobby, alicesGifts)) {
            answersAsIfMissing(softly, HttpMethod.PATCH, inC + "/categories/%d", category, """
                    {"name": "Intruder", "archived": true}""");
            answersAsIfMissing(softly, HttpMethod.DELETE, inC + "/categories/%d", category, null);
        }
        // Nor can he start a family ledger with her categories.
        String newLedger = """
                {"name": "Bob's other", "baseCurrency": "EUR", "displayName": "Bob", "categoryIds": [%d]}""";
        MvcTestResult withHers = bobsRequest(HttpMethod.POST, "/api/family-ledgers", newLedger.formatted(alicesHobby));
        softly.assertThat(withHers.getResponse().getStatus()).as("a family ledger with her category").isEqualTo(404);
        softly.assertThat(withoutDigits(withHers)).as("a family ledger with her category")
                .isEqualTo(withoutDigits(post(bob, "/api/family-ledgers", newLedger.formatted(MISSING))));
        softly.assertAll();

        // In A, Bob sees the family's data, and nothing of Alice's own.
        String inA = "/api/family-ledgers/" + familyA;
        assertThat(bobReads(inA).get("name").asText()).isEqualTo("Home");
        assertThat(bobReads(inA + "/members").findValuesAsText("displayName")).containsExactly("Dad", "Mum", "Grandma");
        assertThat(bobReads(inA + "/categories").findValuesAsText("code")).containsExactly("GROCERIES");
        assertThat(bobReads("/api/family-ledgers").findValuesAsText("id"))
                .containsExactlyInAnyOrder(String.valueOf(familyA), String.valueOf(familyC));
        assertThat(ok(get(carol, "/api/family-ledgers"))).isEmpty();
        // A member who isn't an owner changes nothing of A's (bobsRequest compares her rows, A's included).
        assertThat(bobsRequest(HttpMethod.PATCH, inA, """
                {"name": "Bob's now"}""")).hasStatus(HttpStatus.CONFLICT);
        assertThat(bobsRequest(HttpMethod.DELETE, inA + "/categories/" + find(bobReads(inA + "/categories"), "code",
                "GROCERIES").get("id").asLong(), null)).hasStatus(HttpStatus.CONFLICT);

        // No family answer, to anyone, names a sub or an email address.
        List<JsonNode> answers = new ArrayList<>();
        for (String user : List.of(alice, bob, carol)) {
            answers.add(Answers.of(get(user, "/api/family-ledgers")));
            for (long ledger : List.of(familyA, familyB, familyC)) {
                for (String read : List.of("", "/members", "/categories")) {
                    answers.add(Answers.of(get(user, "/api/family-ledgers/" + ledger + read)));
                }
            }
        }
        assertThat(answers).allSatisfy(answer -> {
            Answers.assertNoneMention(answer, alice, bob, carol, "@example.com", "User ", "userSub", "email");
            assertThat(Answers.names(answer)).doesNotContain("sub");
        });
        for (String name : List.of("Mum", "Dad", "Grandma", "Alice's cousin", "Bob")) {
            assertThat(Answers.mentions(answers, name)).as(name).isTrue();
        }

        // Bob's personal answers gain A's family category, and nothing of B's or of Alice's own (D-11, F4a).
        Map<String, JsonNode> bobsViewAfter = bobsView();
        assertThat(bobsViewAfter).isEqualTo(withFamilyCategory(bobsViewBefore,
                find(bobReads("/api/categories"), "id", String.valueOf(groceriesInA))));
        assertThat(find(bobsViewAfter.get("/api/categories"), "id", String.valueOf(groceriesInA))
                .get("familyLedgerId").asLong()).isEqualTo(familyA);
        Set<Long> notBobs = new TreeSet<>(List.of(alicesGroceries, alicesHobby, bCategory));
        ok(get(alice, "/api/family-ledgers/" + familyB + "/categories")).forEach(c -> notBobs.add(c.get("id").asLong()));
        view(alice).get("/api/categories").forEach(c -> {
            if (!c.has("familyLedgerId")) {
                notBobs.add(c.get("id").asLong());
            }
        });
        assertThat(categoryIds(bobsViewAfter)).isNotEmpty().doesNotContainAnyElementsOf(notBobs);
        assertThat(view(carol)).isEqualTo(carolsViewBefore);
    }

    /**
     * A member's own display name (F3b; D-3), whose path names no member. Bob on Alice's family ledger B and on either
     * personal ledger, and Carol, who is in no family ledger, on A and B, get the answer for a family ledger that
     * doesn't exist, and change nothing. In A, which Alice and Bob share, Bob changes his own membership and nothing
     * else, and a name another member has is refused without a change.
     */
    @Test
    void aMemberReachesOnlyTheirOwnMembershipThroughMe() throws IOException {
        String carol = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum"}""").get("id").asLong();
        long bobInA = join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 9, 1));
        body(post(alice, "/api/family-ledgers/" + familyA + "/members", """
                {"displayName": "Grandma"}"""), HttpStatus.CREATED);
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "USD", "displayName": "Alice"}""").get("id").asLong();
        String me = "/api/family-ledgers/%d/members/me";
        String intruder = """
                {"displayName": "Intruder"}""";

        SoftAssertions softly = new SoftAssertions();
        for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
            answersAsIfMissing(softly, HttpMethod.PATCH, me, ledger, intruder);
        }
        for (long ledger : List.of(familyA, familyB, personalLedger(alice))) {
            answersAsIfMissingTo(softly, carol, HttpMethod.PATCH, me.formatted(ledger), me.formatted(MISSING),
                    intruder);
        }
        softly.assertAll();

        String theOthers = membershipsBut(bobInA);
        JsonNode renamed = ok(call(bob, HttpMethod.PATCH, me.formatted(familyA), """
                {"displayName": "Papa"}"""));
        assertThat(renamed.get("id").asLong()).isEqualTo(bobInA);
        assertThat(membershipsBut(bobInA)).isEqualTo(theOthers);
        assertThat(ok(get(alice, "/api/family-ledgers/" + familyA + "/members")).findValuesAsText("displayName"))
                .containsExactly("Papa", "Mum", "Grandma");

        String everyone = membershipsBut(-1);
        assertThat(call(bob, HttpMethod.PATCH, me.formatted(familyA), """
                {"displayName": "MUM"}""")).hasStatus(HttpStatus.CONFLICT);
        assertThat(membershipsBut(-1)).isEqualTo(everyone);

        assertThat(bobsView()).isEqualTo(bobsViewBefore);
        assertThat(view(carol)).isEqualTo(carolsViewBefore);
    }

    /**
     * Family records (F4a; ADR 0003, topics E and K), with three users: Alice and Bob in family ledger A, Alice alone
     * (with a member without an account) in B, Carol in neither. For every record, balance and journal endpoint, Bob
     * on B, and Carol on A and B, get the answer for a family ledger that doesn't exist. In A, Bob sees Alice's records
     * by her display name, but none of her accounts' names, ids or codes and none of her personal entries' ids, in the
     * raw answers. He can't reach the entries the family budget posted into her ledger through any personal endpoint,
     * nor pay a record with her account; and a record she paid isn't his to delete.
     */
    @Test
    void familyRecordsAreReachedByTheirActiveMembersOnly() throws IOException {
        String carol = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01",
                 "categoryIds": [%d]}""".formatted(categoryId(alice, "GROCERIES"))).get("id").asLong();
        long bobInA = join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        long kidInA = created(post(alice, "/api/family-ledgers/" + familyA + "/members", """
                {"displayName": "Kid"}"""));
        String inA = "/api/family-ledgers/" + familyA;
        long groceriesInA = find(ok(get(alice, inA + "/categories")), "code", "GROCERIES").get("id").asLong();
        long alicesRecord = created(post(alice, inA + "/records", """
                {"date": "2026-08-20", "categoryId": %d, "amount": "60", "comment": "Weekly shop",
                 "payerMemberId": %d, "paymentAccountId": %d, "accountAmount": "65.43"}""".formatted(groceriesInA,
                find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong(), alicesBank)));
        long bobsRecordInA = created(post(bob, inA + "/records", """
                {"date": "2026-08-21", "categoryId": %d, "amount": "9", "payerMemberId": %d}"""
                .formatted(groceriesInA, kidInA)));
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "USD", "displayName": "Alice", "startDate": "2026-08-01",
                 "categoryIds": [%d]}""".formatted(alicesHobby)).get("id").asLong();
        String inB = "/api/family-ledgers/" + familyB;
        long hobbyInB = find(ok(get(alice, inB + "/categories")), "code", "ALICE_HOBBY").get("id").asLong();
        long bRecord = created(post(alice, inB + "/records", """
                {"date": "2026-08-22", "categoryId": %d, "amount": "25", "payerMemberId": %d, "paymentLater": true}"""
                .formatted(hobbyInB, find(ok(get(alice, inB + "/members")), "displayName", "Alice").get("id")
                        .asLong())));
        List<FamilyRequest> requests = List.of(
                new FamilyRequest(HttpMethod.GET, "/records", null),
                new FamilyRequest(HttpMethod.POST, "/records", """
                        {"date": "2026-08-23", "categoryId": %d, "amount": "1", "payerMemberId": %d}"""
                        .formatted(hobbyInB, bobInA)),
                new FamilyRequest(HttpMethod.GET, "/records/" + bRecord, null),
                new FamilyRequest(HttpMethod.PATCH, "/records/" + bRecord + "?version=0", """
                        {"comment": "Intruder"}"""),
                new FamilyRequest(HttpMethod.DELETE, "/records/" + bRecord + "?version=0", null),
                new FamilyRequest(HttpMethod.GET, "/balances", null),
                new FamilyRequest(HttpMethod.GET, "/journal", null));

        SoftAssertions softly = new SoftAssertions();
        for (FamilyRequest request : requests) {
            String uri = "/api/family-ledgers/%d" + request.path();
            // Bob on B, and on either personal ledger.
            for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
                answersAsIfMissing(softly, request.method(), uri, ledger, request.body());
            }
            for (long ledger : List.of(familyA, familyB, personalLedger(alice))) {
                answersAsIfMissingTo(softly, carol, request.method(), uri.formatted(ledger), uri.formatted(MISSING),
                        request.body());
            }
        }
        // B's record through A is missing too.
        answersAsIfMissing(softly, HttpMethod.GET, inA + "/records/%d", bRecord, null);
        answersAsIfMissing(softly, HttpMethod.PATCH, inA + "/records/%d?version=0", bRecord, """
                {"comment": "Intruder"}""");
        answersAsIfMissing(softly, HttpMethod.DELETE, inA + "/records/%d?version=0", bRecord, null);
        softly.assertAll();

        // In A, Bob reads the family's data: Alice as "Mum", and nothing of her own.
        List<JsonNode> answers = new ArrayList<>();
        for (String read : List.of("/records", "/records/" + alicesRecord, "/balances", "/journal")) {
            answers.add(bobReads(inA + read));
        }
        for (String word : List.of("Mum", "Dad", "Kid", "Weekly shop")) {
            assertThat(Answers.mentions(answers, word)).as(word).isTrue();
        }
        Map<String, JsonNode> alicesView = view(alice);
        List<String> alicesOwn = new ArrayList<>();
        for (JsonNode account : alicesView.get("/api/accounts")) {
            alicesOwn.add(account.get("code").asText());
            alicesOwn.add(account.get("name").asText());
        }
        assertThat(alicesOwn).contains("ALICE_BANK", "FAMILY_DEBT_" + familyA, "Debt to family budget: Home");
        for (JsonNode answer : answers) {
            Answers.assertNoneMention(answer, alicesOwn.toArray(String[]::new));
            assertThat(fieldNames(answer)).doesNotContain("accountId", "entryId", "account", "entry",
                    "postings", "userId", "sub", "email", "paymentAccountId");
        }
        // Every id in them is one of A's records, members or categories: none is an account's or an entry's.
        JsonNode records = bobReads(inA + "/records").get("content");
        List<String> membersOfA = ok(get(alice, inA + "/members")).findValuesAsText("id");
        assertThat(records.findValuesAsText("memberId")).isNotEmpty().allMatch(membersOfA::contains);
        assertThat(StreamSupport.stream(records.spliterator(), false).map(r -> r.get("id").asLong()).toList())
                .containsExactlyInAnyOrder(alicesRecord, bobsRecordInA);
        assertThat(records.findValues("category")).allSatisfy(category -> assertThat(category.get("id").asLong())
                .isEqualTo(groceriesInA));

        // The entries posted into her ledger are hers: missing to Bob through every personal endpoint.
        List<Long> alicesPosted = jdbc.sql("""
                SELECT l.entry_id FROM family_entry_link l JOIN journal_entry e ON e.id = l.entry_id
                WHERE l.family_ledger_id = ? AND e.user_id = ?""").params(familyA, alice).query(Long.class).list();
        assertThat(alicesPosted).hasSize(3);
        SoftAssertions personal = new SoftAssertions();
        String bobsCommand = expense(bobsCash, bobsGroceries, null);
        for (long entry : alicesPosted) {
            answersAsIfMissing(personal, HttpMethod.GET, "/api/entries/%d", entry, null);
            answersAsIfMissing(personal, HttpMethod.PUT, "/api/entries/%d?version=0", entry, bobsCommand);
            answersAsIfMissing(personal, HttpMethod.DELETE, "/api/entries/%d?version=0", entry, null);
        }
        // Her debt account, and her account in a record of his: as missing as any account of hers.
        long alicesDebt = find(alicesView.get("/api/accounts"), "code", "FAMILY_DEBT_" + familyA).get("id").asLong();
        MvcTestResult herDebt = bobsRequest(HttpMethod.POST, "/api/entries", expense(alicesDebt, bobsGroceries, null));
        personal.assertThat(withoutDigits(herDebt)).isEqualTo(withoutDigits(post(bob, "/api/entries",
                expense(MISSING, bobsGroceries, null))));
        String paidWith = """
                {"date": "2026-08-24", "categoryId": %d, "amount": "1", "payerMemberId": %d, "paymentAccountId": %d}""";
        MvcTestResult herAccount = bobsRequest(HttpMethod.POST, inA + "/records", paidWith.formatted(groceriesInA,
                bobInA, alicesBank));
        personal.assertThat(herAccount.getResponse().getStatus()).isEqualTo(422);
        personal.assertThat(withoutDigits(herAccount)).isEqualTo(withoutDigits(post(bob, inA + "/records",
                paidWith.formatted(groceriesInA, bobInA, MISSING))));
        // A record Alice paid is hers to delete; his own posted share is read-only to him.
        personal.assertThat(bobsRequest(HttpMethod.DELETE, inA + "/records/" + alicesRecord + "?version=0", null)
                .getResponse().getStatus()).isEqualTo(409);
        long bobsShare = jdbc.sql("""
                SELECT l.entry_id FROM family_entry_link l JOIN journal_entry e ON e.id = l.entry_id
                WHERE l.record_id = ? AND e.user_id = ?""").params(alicesRecord, bob).query(Long.class).single();
        personal.assertThat(delete(bob, "/api/entries/" + bobsShare + "?version=0").getResponse().getStatus())
                .isEqualTo(409);
        personal.assertAll();

        assertThat(view(carol)).isEqualTo(carolsViewBefore);
    }

    /**
     * The payer's side (F4c): Alice pays two expenses of A, with her account and "Specify later", each with a private
     * note. Only her answers hold {@code yourPayment}; Bob's raw answers in A have no such field, and nobody's family
     * answer holds a note. Her payment entry is missing to Bob and Carol through the payment endpoint and to Bob's
     * delete; Bob changes none of her payment through the expense, and neither his payment entry nor his expense takes
     * her account. His own note stays his.
     */
    @Test
    void aPaymentIsThePayersOwn() throws IOException {
        String carol = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01",
                 "categoryIds": [%d]}""".formatted(categoryId(alice, "GROCERIES"))).get("id").asLong();
        long bobInA = join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        String inA = "/api/family-ledgers/" + familyA;
        long groceriesInA = find(ok(get(alice, inA + "/categories")), "code", "GROCERIES").get("id").asLong();
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        JsonNode paid = body(post(alice, inA + "/records", """
                {"date": "2026-08-20", "categoryId": %d, "amount": "60", "payerMemberId": %d, "paymentAccountId": %d,
                 "accountAmount": "65.43", "privateNote": "ALICE_PRIVATE_NOTE"}""".formatted(groceriesInA, mumInA,
                alicesBank)), HttpStatus.CREATED);
        JsonNode later = body(post(alice, inA + "/records", """
                {"date": "2026-08-21", "categoryId": %d, "amount": "8", "payerMemberId": %d, "paymentLater": true,
                 "privateNote": "ALICE_PRIVATE_LATER"}""".formatted(groceriesInA, mumInA)), HttpStatus.CREATED);
        long alicesPayment = paid.get("yourPayment").get("entryId").asLong();
        assertThat(paid.get("yourPayment").get("accountId").asLong()).isEqualTo(alicesBank);
        assertThat(later.get("yourPayment").get("later").asBoolean()).isTrue();

        List<String> reads = List.of("/records", "/records/" + paid.get("id").asLong(),
                "/records/" + later.get("id").asLong(), "/journal", "/balances");
        for (String read : reads) {
            // bobReads refuses anything with "ALICE" in it: her notes included.
            JsonNode bobs = bobReads(inA + read);
            assertThat(fieldNames(bobs)).as(read).doesNotContain("yourPayment", "privateNote", "memo",
                    "entryId", "accountId", "accountName", "originalAmount", "originalCurrency");
            // Her side's dollars are hers alone, in the answers and the journal (D-88).
            assertThat(Answers.numbers(bobs)).as(read).doesNotContain("65.43");
            Answers.assertNoneMention(read, bobs, "USD");
            Answers.assertNoneMention(read, ok(get(alice, inA + read)), "ALICE_PRIVATE");
        }
        assertThat(ok(get(alice, inA + "/records")).get("content").findValues("yourPayment")).hasSize(2);
        assertThat(paid.get("yourPayment").get("amount").asText() + " " + paid.get("yourPayment").get("currency")
                .asText()).isEqualTo("65.43 USD");

        SoftAssertions softly = new SoftAssertions();
        String payment = "/api/entries/%d/family-payment?version=0";
        String intrusion = """
                {"amount": "1", "accountId": %d, "memo": "Intruder"}""".formatted(bobsCash);
        answersAsIfMissing(softly, HttpMethod.PATCH, payment, alicesPayment, intrusion);
        answersAsIfMissing(softly, HttpMethod.DELETE, "/api/entries/%d?version=0", alicesPayment, null);
        answersAsIfMissingTo(softly, carol, HttpMethod.PATCH, payment.formatted(alicesPayment),
                payment.formatted(MISSING), intrusion);
        softly.assertAll();

        // Her payment is hers: not Bob's to change through the expense (bobsRequest compares her rows).
        String hers = inA + "/records/" + paid.get("id").asLong() + "?version=0";
        assertThat(bobsRequest(HttpMethod.PATCH, hers, """
                {"paymentAccountId": %d}""".formatted(bobsCash))).hasStatus(HttpStatus.CONFLICT);
        assertThat(bobsRequest(HttpMethod.PATCH, hers, """
                {"amount": "61", "date": "2026-08-22"}""")).hasStatus(HttpStatus.CONFLICT);
        // His own expense, all on him: her account pays neither it nor his payment entry, as a missing one doesn't.
        JsonNode his = body(post(bob, inA + "/records", """
                {"date": "2026-08-22", "categoryId": %d, "amount": "5", "payerMemberId": %d, "paymentAccountId": %d,
                 "split": {"method": "ONE_MEMBER", "memberId": %d}}""".formatted(groceriesInA, bobInA, bobsCash,
                bobInA)), HttpStatus.CREATED);
        long bobsPayment = his.get("yourPayment").get("entryId").asLong();
        String account = """
                {"accountId": %d}""";
        MvcTestResult herAccount = bobsRequest(HttpMethod.PATCH, payment.formatted(bobsPayment),
                account.formatted(alicesBank));
        assertThat(herAccount).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(withoutDigits(herAccount)).isEqualTo(withoutDigits(call(bob, HttpMethod.PATCH,
                payment.formatted(bobsPayment), account.formatted(MISSING))));
        String record = inA + "/records/" + his.get("id").asLong() + "?version=0";
        String paidWith = """
                {"paymentAccountId": %d}""";
        MvcTestResult herAccountOnHisExpense = bobsRequest(HttpMethod.PATCH, record, paidWith.formatted(alicesBank));
        assertThat(herAccountOnHisExpense).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(withoutDigits(herAccountOnHisExpense)).isEqualTo(withoutDigits(call(bob, HttpMethod.PATCH, record,
                paidWith.formatted(MISSING))));
        // His note changes his entry only.
        assertThat(ok(bobsRequest(HttpMethod.PATCH, payment.formatted(bobsPayment), """
                {"memo": "BOB_PRIVATE_NOTE"}""")).get("memo").asText()).isEqualTo("BOB_PRIVATE_NOTE");
        for (String read : List.of("/records", "/records/" + his.get("id").asLong(), "/journal")) {
            JsonNode alices = ok(get(alice, inA + read));
            Answers.assertNoneMention(read, alices, "BOB_PRIVATE_NOTE");
        }
        assertThat(ok(get(alice, inA + "/records/" + his.get("id").asLong())).has("yourPayment")).isFalse();

        assertThat(view(carol)).isEqualTo(carolsViewBefore);
    }

    /**
     * Settlements (F4d; D-24): each side's account is that side's. Alice pays Bob from her bank in A; his side goes to
     * his "Payments without a specified account", and he puts it on a wallet of his. Each side's {@code yourPayment} is
     * in that side's answers only, and nobody's answer holds the other side's account; the other side's entry is
     * missing to Bob and Carol, and Bob's own side takes neither her account nor her amount. {@code POST /settlements}
     * answers Bob on B, and Carol on A and B, as a missing family ledger does.
     */
    @Test
    void aSettlementSideIsItsMembersOwn() throws IOException {
        String carol = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01"}""")
                .get("id").asLong();
        long bobInA = join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        String inA = "/api/family-ledgers/" + familyA;
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "EUR", "displayName": "Alice",
                 "startDate": "2026-08-01"}""").get("id").asLong();
        String inB = "/api/family-ledgers/" + familyB;
        long sam = created(post(alice, inB + "/members", """
                {"displayName": "Sam"}"""));
        long aliceInB = find(ok(get(alice, inB + "/members")), "displayName", "Alice").get("id").asLong();
        created(post(alice, inB + "/settlements", """
                {"date": "2026-08-20", "amount": "5", "payerMemberId": %d, "payeeMemberId": %d, "paymentLater": true}"""
                .formatted(aliceInB, sam)));
        long bobsWallet = created(post(bob, "/api/accounts", """
                {"code": "BOB_WALLET", "name": "BOB_PRIVATE_WALLET", "type": "ASSET"}"""));

        JsonNode paid = body(post(alice, inA + "/settlements", """
                {"date": "2026-08-20", "amount": "40", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d, "accountCurrency": "EUR"}""".formatted(mumInA, bobInA, alicesBank)),
                HttpStatus.CREATED);
        long settlement = paid.get("id").asLong();
        long alicesSide = paid.get("yourPayment").get("entryId").asLong();
        assertThat(paid.get("yourPayment").get("accountId").asLong()).isEqualTo(alicesBank);

        // Bob reads the settlement: his own side on "Specify later", nothing of hers.
        String path = inA + "/records/" + settlement;
        JsonNode his = bobReads(path);
        long bobsSide = his.get("yourPayment").get("entryId").asLong();
        assertThat(his.get("yourPayment").get("later").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT user_id FROM journal_entry WHERE id = ?").param(bobsSide).query(String.class)
                .single()).isEqualTo(bob);
        for (String read : List.of("/records", "/journal", "/balances")) {
            assertThat(bobReads(inA + read).findValuesAsText("accountId")).as(read)
                    .doesNotContain(String.valueOf(alicesBank));
        }
        // He puts his side on his wallet (his own write, which changes the family's link, so not one of bobsRequest's).
        assertThat(ok(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d}""".formatted(bobsWallet))).get("yourPayment").get("accountName").asText())
                .isEqualTo("BOB_PRIVATE_WALLET");
        for (String read : List.of("/records", "/records/" + settlement, "/journal", "/balances")) {
            JsonNode alices = ok(get(alice, inA + read));
            Answers.assertNoneMention(read, alices, "BOB_PRIVATE", "BOB_WALLET");
            assertThat(Answers.numbers(alices)).as(read).doesNotContain(String.valueOf(bobsWallet), String.valueOf(bobsSide));
        }
        assertThat(ok(get(alice, path)).get("yourPayment").get("entryId").asLong()).isEqualTo(alicesSide);

        SoftAssertions softly = new SoftAssertions();
        // Her side's entry: missing to Bob and Carol, through every personal endpoint and the payment endpoint.
        String payment = "/api/entries/%d/family-payment?version=0";
        String intrusion = """
                {"accountId": %d}""".formatted(bobsCash);
        answersAsIfMissing(softly, HttpMethod.PATCH, payment, alicesSide, intrusion);
        answersAsIfMissing(softly, HttpMethod.GET, "/api/entries/%d", alicesSide, null);
        answersAsIfMissing(softly, HttpMethod.DELETE, "/api/entries/%d?version=0", alicesSide, null);
        answersAsIfMissingTo(softly, carol, HttpMethod.PATCH, payment.formatted(alicesSide),
                payment.formatted(MISSING), intrusion);
        // Recording a settlement: Bob on B and on either personal ledger, Carol on A, B and Alice's personal ledger.
        String settle = """
                {"date": "2026-08-21", "amount": "1", "payerMemberId": %d, "payeeMemberId": %d, "paymentLater": true}"""
                .formatted(bobInA, sam);
        String uri = "/api/family-ledgers/%d/settlements";
        for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
            answersAsIfMissing(softly, HttpMethod.POST, uri, ledger, settle);
        }
        for (long ledger : List.of(familyA, familyB, personalLedger(alice))) {
            answersAsIfMissingTo(softly, carol, HttpMethod.POST, uri.formatted(ledger), uri.formatted(MISSING), settle);
        }
        softly.assertAll();

        // Her account on his side reads as a missing account; the amount and deleting it are hers.
        String account = """
                {"paymentAccountId": %d}""";
        MvcTestResult herAccount = bobsRequest(HttpMethod.PATCH, path + "?version=0", account.formatted(alicesBank));
        assertThat(herAccount).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(withoutDigits(herAccount)).isEqualTo(withoutDigits(call(bob, HttpMethod.PATCH, path + "?version=0",
                account.formatted(MISSING))));
        MvcTestResult herAccountOnHisEntry = bobsRequest(HttpMethod.PATCH, payment.formatted(bobsSide).replace(
                "version=0", "version=" + ok(get(bob, "/api/entries/" + bobsSide)).get("version").asInt()), """
                {"accountId": %d}""".formatted(alicesBank));
        assertThat(herAccountOnHisEntry).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(bobsRequest(HttpMethod.PATCH, path + "?version=0", """
                {"amount": "1"}""")).hasStatus(HttpStatus.CONFLICT);
        assertThat(bobsRequest(HttpMethod.DELETE, path + "?version=0", null)).hasStatus(HttpStatus.CONFLICT);
        // His settlement with her from his own side's wallet: her side is hers, on her placeholder.
        assertThat(bobsRequest(HttpMethod.POST, inA + "/settlements", """
                {"date": "2026-08-22", "amount": "1", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(bobInA, mumInA, alicesBank)))
                .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(view(carol)).isEqualTo(carolsViewBefore);
    }

    /**
     * Incomes (F4d, C5): Alice receives an income of A into her bank, with a private note. Her receipt is hers, as her
     * payment is (F4c): only her answers hold {@code yourPayment}, nobody's family answer holds the note, and her receipt
     * is missing to Bob and Carol through every personal endpoint and the payment endpoint. Bob's share is his, posted
     * as income, and names the record's type ({@code recordType}) in his own answers only. An income through {@code
     * POST /records} answers Bob on B, and Carol on A and B, as a missing family ledger does.
     */
    @Test
    void anIncomesReceiptIsTheReceiversOwn() throws IOException {
        String carol = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01"}""")
                .get("id").asLong();
        long bobInA = join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        String inA = "/api/family-ledgers/" + familyA;
        String category = """
                {"code": "FAMILY_INCOME", "name": "Family income", "type": "INCOME"}""";
        long salaryInA = created(post(alice, inA + "/categories", category));
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "EUR", "displayName": "Alice",
                 "startDate": "2026-08-01"}""").get("id").asLong();
        long giftsInB = created(post(alice, "/api/family-ledgers/" + familyB + "/categories", category));

        JsonNode received = body(post(alice, inA + "/records", """
                {"type": "INCOME", "date": "2026-08-20", "categoryId": %d, "amount": "1000", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "1087.65", "privateNote": "ALICE_PRIVATE_BONUS"}"""
                .formatted(salaryInA, mumInA,
                alicesBank)), HttpStatus.CREATED);
        long alicesReceipt = received.get("yourPayment").get("entryId").asLong();
        assertThat(received.get("yourPayment").get("accountId").asLong()).isEqualTo(alicesBank);
        for (String read : List.of("/records", "/records/" + received.get("id").asLong(), "/journal", "/balances")) {
            assertThat(fieldNames(bobReads(inA + read))).as(read).doesNotContain("yourPayment", "privateNote", "memo",
                    "entryId", "accountId", "accountName");
            Answers.assertNoneMention(read, ok(get(alice, inA + read)), "ALICE_PRIVATE");
        }
        // Bob's share: income on his UNALLOCATED, marked as an income's in his own entry only.
        JsonNode bobsShare = bobReads("/api/entries?size=200").get("content").get(0);
        assertThat(bobsShare.get("family").get("recordType").asText()).isEqualTo("INCOME");
        assertThat(bobsShare.get("postings").findValuesAsText("amount")).contains("-500.00");

        SoftAssertions softly = new SoftAssertions();
        String payment = "/api/entries/%d/family-payment?version=0";
        String intrusion = """
                {"accountId": %d, "memo": "Intruder"}""".formatted(bobsCash);
        answersAsIfMissing(softly, HttpMethod.PATCH, payment, alicesReceipt, intrusion);
        answersAsIfMissing(softly, HttpMethod.GET, "/api/entries/%d", alicesReceipt, null);
        answersAsIfMissing(softly, HttpMethod.DELETE, "/api/entries/%d?version=0", alicesReceipt, null);
        answersAsIfMissingTo(softly, carol, HttpMethod.PATCH, payment.formatted(alicesReceipt),
                payment.formatted(MISSING), intrusion);
        String income = """
                {"type": "INCOME", "date": "2026-08-21", "categoryId": %d, "amount": "1", "payerMemberId": %d}"""
                .formatted(giftsInB, bobInA);
        String uri = "/api/family-ledgers/%d/records";
        for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
            answersAsIfMissing(softly, HttpMethod.POST, uri, ledger, income);
        }
        for (long ledger : List.of(familyA, familyB, personalLedger(alice))) {
            answersAsIfMissingTo(softly, carol, HttpMethod.POST, uri.formatted(ledger), uri.formatted(MISSING), income);
        }
        softly.assertAll();
        // Her receipt is hers: Bob changes neither its amount nor its account through the income.
        String hers = inA + "/records/" + received.get("id").asLong() + "?version=0";
        assertThat(bobsRequest(HttpMethod.PATCH, hers, """
                {"amount": "1", "paymentAccountId": %d}""".formatted(bobsCash))).hasStatus(HttpStatus.CONFLICT);
        assertThat(bobsRequest(HttpMethod.DELETE, hers, null)).hasStatus(HttpStatus.CONFLICT);

        assertThat(view(carol)).isEqualTo(carolsViewBefore);
    }

    /**
     * The settlement lock (D-28): Alice records a settlement with Bob in A, and Bob puts his side on his wallet. Only
     * Alice, who recorded it, reads who locked it ({@code lockedBy}), as Bob's display name and nothing of his
     * accounts; Bob and Gran, another member with an account, don't. Her changes of the date and amount and her
     * deletion are refused without naming anything of his, and his rows are the same afterwards.
     */
    @Test
    void aSettlementsLockNamesItsOtherSideToItsRecorderOnly() throws IOException {
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01"}""")
                .get("id").asLong();
        long bobInA = join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        String dave = newUser();
        ok(get(dave, "/api/accounts"));
        join(familyA, dave, "Gran", "MEMBER", LocalDate.of(2026, 8, 1));
        String inA = "/api/family-ledgers/" + familyA;
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long bobsWallet = created(post(bob, "/api/accounts", """
                {"code": "BOB_WALLET", "name": "BOB_PRIVATE_WALLET", "type": "ASSET"}"""));
        JsonNode paid = body(post(alice, inA + "/settlements", """
                {"date": "2026-08-20", "amount": "40", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d, "accountCurrency": "EUR"}""".formatted(mumInA, bobInA, alicesBank)),
                HttpStatus.CREATED);
        String path = inA + "/records/" + paid.get("id").asLong();
        assertThat(paid.has("lockedBy")).isFalse();
        ok(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d}""".formatted(bobsWallet)));
        Map<String, String> bobsRows = digestOf(bob);

        JsonNode hers = ok(get(alice, path));
        assertThat(hers.get("lockedBy")).isEqualTo(json.readTree("""
                {"memberId": %d, "displayName": "Dad"}""".formatted(bobInA)));
        for (String read : List.of("/records", "/records/" + paid.get("id").asLong(), "/journal", "/balances")) {
            JsonNode alices = ok(get(alice, inA + read));
            Answers.assertNoneMention(read, alices, "BOB_PRIVATE", "BOB_WALLET");
            assertThat(Answers.numbers(alices)).as(read).doesNotContain(String.valueOf(bobsWallet));
            for (String other : List.of(bob, dave)) {
                assertThat(fieldNames(ok(get(other, inA + read)))).as(read).doesNotContain("lockedBy");
            }
        }
        long alicesSide = hers.get("yourPayment").get("entryId").asLong();
        int alicesVersion = ok(get(alice, "/api/entries/" + alicesSide)).get("version").asInt();
        for (MvcTestResult refused : List.of(
                patch(alice, path + "?version=0", """
                        {"amount": "41"}"""),
                patch(alice, path + "?version=0", """
                        {"date": "2026-08-21"}"""),
                delete(alice, path + "?version=0"),
                patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(alicesSide, alicesVersion), """
                        {"amount": "41"}"""),
                delete(alice, "/api/entries/%d?version=%d".formatted(alicesSide, alicesVersion)))) {
            assertThat(refused).hasStatus(HttpStatus.CONFLICT);
            assertThat(body(refused, HttpStatus.CONFLICT).get("detail").asText()).startsWith("Dad has put their side")
                    .doesNotContain("BOB_", String.valueOf(bobsWallet));
        }
        assertThat(digestOf(bob)).isEqualTo(bobsRows);
    }

    /**
     * Other currencies (F4e, F8a, F8b): Alice settles in dollars with Bob, who puts his side on his rouble account with
     * the roubles he got: that amount, his account and its currency are in his answers only, and Carol's view stays as
     * it was. D-47's total uses each member's own rates (D-49): Alice's dollar rate gives her one, and never reaches
     * Bob's answer, which, with no rate of his own, names the dollars missing. F4e's {@code /conversion} is gone.
     */
    @Test
    void aSidesOwnAmountAndTheRatesOfATotalAreTheMembersOwn() throws IOException {
        String carol = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-08-01", "base": "EUR", "quote": "RUB", "rate": "100"}"""));
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-08-01", "base": "EUR", "quote": "USD", "rate": "1.12"}"""));
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01"}""")
                .get("id").asLong();
        long bobInA = join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        String inA = "/api/family-ledgers/" + familyA;
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "EUR", "displayName": "Alice",
                 "startDate": "2026-08-01"}""").get("id").asLong();

        SoftAssertions softly = new SoftAssertions();
        for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
            answersAsIfMissing(softly, HttpMethod.GET, "/api/family-ledgers/%d/balances", ledger, null);
        }
        for (long ledger : List.of(familyA, familyB, personalLedger(alice))) {
            answersAsIfMissingTo(softly, carol, HttpMethod.GET, "/api/family-ledgers/%d/balances".formatted(ledger),
                    "/api/family-ledgers/%d/balances".formatted(MISSING), null);
        }
        softly.assertAll();
        assertThat(get(alice, inA + "/conversion?amount=9000&currency=RUB&date=2026-08-20"))
                .hasStatus(HttpStatus.NOT_FOUND);

        long bobsRoubles = created(post(bob, "/api/accounts", """
                {"code": "BOB_RUB", "name": "BOB_PRIVATE_ROUBLES", "type": "ASSET", "defaultCurrency": "RUB"}"""));
        JsonNode paid = body(post(alice, inA + "/settlements", """
                {"date": "2026-08-20", "amount": "56.00", "currency": "USD", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mumInA, bobInA, alicesBank)), HttpStatus.CREATED);
        String path = inA + "/records/" + paid.get("id").asLong();
        assertThat(paid.get("amount").asText() + " " + paid.get("currency").asText()).isEqualTo("56.00 USD");
        // His own write: his side on his rouble account, with what he got.
        JsonNode his = ok(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d, "accountAmount": "4321.98"}""".formatted(bobsRoubles)));
        assertThat(his.get("yourPayment").get("amount").asText()).isEqualTo("4321.98");
        assertThat(his.get("yourPayment").get("currency").asText()).isEqualTo("RUB");
        for (String read : List.of("/records", "/records/" + paid.get("id").asLong(), "/journal", "/balances")) {
            JsonNode alices = ok(get(alice, inA + read));
            assertThat(Answers.numbers(alices)).as(read).doesNotContain("4321", "4321.98", String.valueOf(bobsRoubles));
            Answers.assertNoneMention(read, alices, "RUB", "BOB_");
        }
        // D-47's total by each member's own rates: Alice's dollar rate gives hers, and none of hers reaches Bob's.
        JsonNode alicesTotal = ok(get(alice, inA + "/balances")).get("total");
        assertThat(alicesTotal.get("missingCurrencies")).isEmpty();
        assertThat(alicesTotal.get("rates").findValuesAsText("perEuro")).containsExactly("1.12");
        JsonNode bobsTotal = bobReads(inA + "/balances").get("total");
        assertThat(bobsTotal.get("missingCurrencies")).isEqualTo(Answers.json("[\"USD\"]"));
        assertThat(bobsTotal.get("rates")).isEmpty();
        assertThat(Answers.numbers(bobsTotal)).doesNotContain("1.12");
        assertThat(view(carol)).isEqualTo(carolsViewBefore);
    }

    /**
     * Invites (F5; D-17, D-18; ADR 0003, topics G and K), with four users: Alice owns A, where Bob is a member and Sam
     * has no account, and B alone; Carol and Dave belong to neither. The owners' invite endpoints answer Bob on B, and
     * on either personal ledger, and Carol on A and B, as for a family ledger that doesn't exist; Bob, a member of A,
     * gets 409, not A's invites. A token lets nobody into anything but its own family budget: before Carol accepts it,
     * she reads nothing of B but the lookup, which holds no sub, email address, account, record or category of
     * Alice's; a used, revoked or guessed token gets the one invalid answer, also for Carol holding the one Dave used;
     * and Bob can't take a second place in A. Taking Sam's place posts into Dave's personal ledger only: Alice's and
     * Bob's personal rows stay as they were (D-8).
     */
    @Test
    void invitesLetTheirHoldersIntoTheirOwnFamilyBudgetOnly() throws IOException {
        String carol = newUser();
        String dave = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01"}""")
                .get("id").asLong();
        join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        String inA = "/api/family-ledgers/" + familyA;
        long sam = created(post(alice, inA + "/members", """
                {"displayName": "Sam"}"""));
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long rentInA = created(post(alice, inA + "/categories", """
                {"code": "RENT", "name": "Rent", "type": "EXPENSE"}"""));
        created(post(alice, inA + "/records", """
                {"date": "2026-08-10", "categoryId": %d, "amount": "90.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountCurrency": "EUR"}""".formatted(rentInA, mumInA, alicesBank)));
        created(post(alice, inA + "/records", """
                {"date": "2026-08-20", "categoryId": %d, "amount": "30.00", "payerMemberId": %d}"""
                .formatted(rentInA, sam)));
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "EUR", "displayName": "Alice", "categoryIds": [%d],
                 "startDate": "2026-08-01"}""".formatted(alicesGifts)).get("id").asLong();
        String inB = "/api/family-ledgers/" + familyB;
        long aliceInB = find(ok(get(alice, inB + "/members")), "displayName", "Alice").get("id").asLong();
        long giftsInB = find(ok(get(alice, inB + "/categories")), "code", "ALICE_GIFTS").get("id").asLong();
        created(post(alice, inB + "/records", """
                {"type": "INCOME", "date": "2026-08-12", "categoryId": %d, "amount": "77.77", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountCurrency": "EUR", "comment": "ALICE_PRIVATE_COMMENT"}"""
                .formatted(giftsInB, aliceInB,
                alicesBank)));
        String tokenOfB = invite(alice, inB, """
                {"kind": "NEW_MEMBER"}""");
        long inviteOfB = ok(get(alice, inB + "/invites")).get(0).get("id").asLong();
        String usedOfA = invite(alice, inA, """
                {"kind": "NEW_MEMBER"}""");
        assertThat(inviteCall(dave, "accept", "{\"token\": \"%s\", \"displayName\": \"Dave\"}".formatted(usedOfA)))
                .hasStatus(HttpStatus.OK);
        String newMemberOfA = invite(alice, inA, """
                {"kind": "NEW_MEMBER"}""");
        String revokedOfB = invite(alice, inB, """
                {"kind": "NEW_MEMBER"}""");
        assertThat(delete(alice, inB + "/invites/" + ok(get(alice, inB + "/invites")).get(0).get("id").asLong()))
                .hasStatus(HttpStatus.NO_CONTENT);

        // The owners' endpoints.
        List<FamilyRequest> requests = List.of(
                new FamilyRequest(HttpMethod.POST, "/invites", """
                        {"kind": "NEW_MEMBER"}"""),
                new FamilyRequest(HttpMethod.GET, "/invites", null),
                new FamilyRequest(HttpMethod.DELETE, "/invites/" + inviteOfB, null));
        SoftAssertions softly = new SoftAssertions();
        for (FamilyRequest request : requests) {
            String uri = "/api/family-ledgers/%d" + request.path();
            for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
                answersAsIfMissing(softly, request.method(), uri, ledger, request.body());
            }
            for (long ledger : List.of(familyA, familyB, personalLedger(alice))) {
                answersAsIfMissingTo(softly, carol, request.method(), uri.formatted(ledger), uri.formatted(MISSING),
                        request.body());
            }
            // A member of A who isn't its owner gets the owners' rule, not its invites.
            MvcTestResult asMember = bobsRequest(request.method(), uri.formatted(familyA), request.body());
            softly.assertThat(asMember.getResponse().getStatus()).as("%s %s in A", request.method(), request.path())
                    .isEqualTo(409);
            softly.check(() -> Answers.assertNoneMention(Answers.of(asMember), "createdBy", "Sam"));
        }
        // B's invite through A's path is missing, even for A's owner.
        softly.assertThat(delete(alice, inA + "/invites/" + inviteOfB).getResponse().getStatus()).isEqualTo(404);

        // The holders' endpoints: Bob's token of A can't make him a second member; the others let nobody in, with one
        // answer, word for word.
        String guessed = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        List<String> invalid = new ArrayList<>();
        // Ten requests of Bob's, the most a minute (InviteRateLimit).
        Map<String, List<String>> tokens = Map.of("lookup", List.of(guessed, usedOfA, revokedOfB),
                "accept", List.of(usedOfA, revokedOfB), "decline", List.of(guessed, revokedOfB));
        for (String action : List.of("lookup", "accept", "decline")) {
            String bodyFor = "{\"token\": \"%s\", \"displayName\": \"Bob\"}";
            MvcTestResult second = bobsRequest(() -> inviteCall(bob, action, bodyFor.formatted(newMemberOfA)));
            softly.assertThat(second.getResponse().getStatus()).as(action + " of A's").isEqualTo(409);
            for (String token : tokens.get(action)) {
                MvcTestResult answer = bobsRequest(() -> inviteCall(bob, action, bodyFor.formatted(token)));
                softly.assertThat(answer.getResponse().getStatus()).as(action).isEqualTo(404);
                invalid.add(Answers.rawText(answer));
            }
            MvcTestResult carols = inviteCall(carol, action, bodyFor.formatted(usedOfA));
            softly.assertThat(carols.getResponse().getStatus()).as("Carol's " + action).isEqualTo(404);
            invalid.add(Answers.rawText(carols));
        }
        softly.assertThat(invalid.stream().map(answer -> answer.replaceAll("/api/invites/\\w+", "PATH")))
                .containsOnly(invalid.getFirst().replaceAll("/api/invites/\\w+", "PATH"));
        softly.assertThat(invalid.getFirst()).contains("This invite is not valid. Ask for a new one.");
        softly.assertThat(ok(get(alice, inA + "/members")).findValuesAsText("displayName"))
                .containsExactly("Mum", "Dad", "Sam", "Dave");
        softly.assertAll();

        // Carol holds a valid token of B: before accepting it, she reads nothing of B but its lookup.
        JsonNode lookup = ok(inviteCall(carol, "lookup", "{\"token\": \"%s\"}".formatted(tokenOfB)));
        assertThat(lookup.get("ledgerName").asText()).isEqualTo("ALICE_SECRET_BUDGET");
        Answers.assertNoneMention(lookup, alice, alice + "@example.com", "ALICE_BANK", "ALICE_PRIVATE");
        assertThat(Answers.numbers(lookup)).doesNotContain("77.77", String.valueOf(alicesBank));
        assertThat(fieldNames(lookup)).doesNotContain("memberId", "accountId", "id", "sub", "email", "records",
                "ledgerId");
        assertThat(lookup.findValuesAsText("categoryId")).isSubsetOf(ok(get(carol, "/api/categories"))
                .findValuesAsText("id"));
        SoftAssertions carols = new SoftAssertions();
        for (String path : List.of("", "/members", "/categories", "/records", "/balances", "/journal", "/invites",
                "/report")) {
            answersAsIfMissingTo(carols, carol, HttpMethod.GET, inB + path, "/api/family-ledgers/" + MISSING + path,
                    null);
        }
        answersAsIfMissingTo(carols, carol, HttpMethod.POST, inB + "/records", "/api/family-ledgers/" + MISSING
                + "/records", """
                {"type": "INCOME", "date": "2026-08-12", "categoryId": %d, "amount": "1.00", "payerMemberId": %d}"""
                .formatted(giftsInB, aliceInB));
        carols.assertAll();
        assertThat(view(carol)).isEqualTo(carolsViewBefore);

        // Taking Sam's place writes only into Dave's own personal ledger (D-8).
        Map<String, String> alicesPersonal = digestOf(alice);
        Map<String, String> bobsPersonal = digestOf(bob);
        alicesPersonal.keySet().removeAll(FAMILY_ROWS);
        bobsPersonal.keySet().removeAll(FAMILY_ROWS);
        String samsPlace = invite(alice, inA, """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "2026-08-15"}""".formatted(sam));
        String erin = newUser();
        ok(inviteCall(erin, "accept", "{\"token\": \"%s\", \"displayName\": \"Erin\"}".formatted(samsPlace)));
        Map<String, String> alicesAfter = digestOf(alice);
        Map<String, String> bobsAfter = digestOf(bob);
        alicesAfter.keySet().removeAll(FAMILY_ROWS);
        bobsAfter.keySet().removeAll(FAMILY_ROWS);
        assertThat(alicesAfter).isEqualTo(alicesPersonal);
        assertThat(bobsAfter).isEqualTo(bobsPersonal);
        Answers.assertNoneMention(ok(get(erin, inA + "/records")), "ALICE_BANK", "Alice's bank");
        assertThat(get(erin, inB)).hasStatus(HttpStatus.NOT_FOUND);
    }

    /**
     * Leaving and removal (F6a; D-19, ADR 0003 topic J's "F6a plan"), with three users: Alice owns A, where Bob and
     * Carol are members, and B alone. Bob, a member who isn't an owner, removes nobody: the owners' 409, and nothing
     * of anyone's changes. DELETE /members/me answers Bob on B and on either personal ledger, and Carol on B and on
     * Alice's personal ledger, as for a family budget that doesn't exist. Then Bob leaves and Alice removes Carol:
     * from then on each gets the missing family budget's answer from every family endpoint of A, reads and writes
     * alike, and their personal answers hold nothing of A any more, not even its new name: no family category, no
     * family link, no debt account that names it.
     */
    @Test
    void aMemberWhoLeftOrWasRemovedReadsNothingOfTheBudget() throws IOException {
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01",
                 "categoryIds": [%d]}""".formatted(categoryId(alice, "GROCERIES"))).get("id").asLong();
        String inA = "/api/family-ledgers/" + familyA;
        join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        long carolInA = join(familyA, carol, "Carol", "MEMBER", LocalDate.of(2026, 8, 1));
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long seat = created(post(alice, inA + "/members", """
                {"displayName": "Sam"}"""));
        long groceriesInA = find(ok(get(alice, inA + "/categories")), "code", "GROCERIES").get("id").asLong();
        long record = created(post(alice, inA + "/records", """
                {"date": "2026-08-10", "categoryId": %d, "amount": "90.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountCurrency": "EUR"}""".formatted(groceriesInA, mumInA, alicesBank)));
        long invite = body(post(alice, inA + "/invites", """
                {"kind": "NEW_MEMBER"}"""), HttpStatus.CREATED).get("id").asLong();
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "EUR", "displayName": "Alice"}""").get("id").asLong();
        String me = "/api/family-ledgers/%d/members/me";

        SoftAssertions softly = new SoftAssertions();
        String everyone = membershipsBut(-1);
        for (long member : List.of(mumInA, carolInA, seat)) {
            MvcTestResult answer = bobsRequest(HttpMethod.DELETE, inA + "/members/" + member, null);
            softly.assertThat(answer.getResponse().getStatus()).as("Bob removes member %d", member).isEqualTo(409);
        }
        softly.assertThat(membershipsBut(-1)).as("the memberships after Bob's removals").isEqualTo(everyone);
        for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
            answersAsIfMissing(softly, HttpMethod.DELETE, me, ledger, null);
        }
        for (long ledger : List.of(familyB, personalLedger(alice))) {
            answersAsIfMissingTo(softly, carol, HttpMethod.DELETE, me.formatted(ledger), me.formatted(MISSING), null);
        }
        softly.assertAll();

        assertThat(call(bob, HttpMethod.DELETE, inA + "/members/me", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(alice, inA + "/members/" + carolInA)).hasStatus(HttpStatus.NO_CONTENT);
        ok(patch(alice, inA, """
                {"name": "ALICE_RENAMED_HOME"}"""));

        List<FamilyRequest> requests = List.of(
                new FamilyRequest(HttpMethod.GET, "", null),
                new FamilyRequest(HttpMethod.PATCH, "", """
                        {"name": "Mine now"}"""),
                new FamilyRequest(HttpMethod.PUT, "/split-rule", """
                        {"rule": "EQUAL"}"""),
                new FamilyRequest(HttpMethod.GET, "/members", null),
                new FamilyRequest(HttpMethod.POST, "/members", """
                        {"displayName": "Intruder"}"""),
                new FamilyRequest(HttpMethod.PATCH, "/members/me", """
                        {"displayName": "Intruder"}"""),
                new FamilyRequest(HttpMethod.DELETE, "/members/me", null),
                new FamilyRequest(HttpMethod.PATCH, "/members/" + seat, """
                        {"displayName": "Intruder"}"""),
                new FamilyRequest(HttpMethod.DELETE, "/members/" + seat, null),
                new FamilyRequest(HttpMethod.GET, "/categories", null),
                new FamilyRequest(HttpMethod.POST, "/categories", """
                        {"code": "INTRUDER", "name": "Intruder", "type": "EXPENSE"}"""),
                new FamilyRequest(HttpMethod.PATCH, "/categories/" + groceriesInA, """
                        {"name": "Intruder"}"""),
                new FamilyRequest(HttpMethod.DELETE, "/categories/" + groceriesInA, null),
                new FamilyRequest(HttpMethod.GET, "/records", null),
                new FamilyRequest(HttpMethod.POST, "/records", """
                        {"date": "2026-08-12", "categoryId": %d, "amount": "1.00", "payerMemberId": %d}"""
                        .formatted(groceriesInA, seat)),
                new FamilyRequest(HttpMethod.POST, "/settlements", """
                        {"date": "2026-08-12", "amount": "1.00", "payerMemberId": %d, "payeeMemberId": %d}"""
                        .formatted(seat, mumInA)),
                new FamilyRequest(HttpMethod.GET, "/records/" + record, null),
                new FamilyRequest(HttpMethod.PATCH, "/records/" + record + "?version=0", """
                        {"comment": "Intruder"}"""),
                new FamilyRequest(HttpMethod.DELETE, "/records/" + record + "?version=0", null),
                new FamilyRequest(HttpMethod.GET, "/balances", null),
                new FamilyRequest(HttpMethod.GET, "/journal", null),
                new FamilyRequest(HttpMethod.POST, "/invites", """
                        {"kind": "NEW_MEMBER"}"""),
                new FamilyRequest(HttpMethod.GET, "/invites", null),
                new FamilyRequest(HttpMethod.DELETE, "/invites/" + invite, null));
        SoftAssertions afterwards = new SoftAssertions();
        for (FamilyRequest request : requests) {
            String uri = "/api/family-ledgers/%d" + request.path();
            answersAsIfMissing(afterwards, request.method(), uri, familyA, request.body());
            answersAsIfMissingTo(afterwards, carol, request.method(), uri.formatted(familyA), uri.formatted(MISSING),
                    request.body());
        }
        afterwards.assertAll();

        for (String user : List.of(bob, carol)) {
            assertThat(ok(get(user, "/api/family-ledgers"))).as("%s's family budgets", user).isEmpty();
            Map<String, JsonNode> view = user.equals(bob) ? bobsView() : view(user);
            view.forEach((uri, answer) -> Answers.assertNoneMention(user + "'s personal answers, " + uri, answer, "familyLedgerId",
                    "familyLedgerName", "ALICE_RENAMED_HOME", "ALICE_SECRET_BUDGET"));
            assertThat(view.get("/api/entries?size=200").get("content").findValuesAsText("family")).containsOnly("null");
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM account a JOIN ledger_member p ON p.ledger_id = a.ledger_id
                    WHERE p.user_sub = ? AND p.ledger_type = 'PERSONAL' AND a.family_ledger_id IS NOT NULL""")
                    .param(user).query(Long.class).single()).as("%s's debt accounts that name a family budget", user)
                    .isZero();
        }
        assertThat(ok(get(alice, inA + "/members")).findValuesAsText("status"))
                .containsExactly("ACTIVE", "LEFT", "LEFT", "ACTIVE");
    }

    /**
     * What "Delete all my data" touches, and new owners (F6a; D-20, D-15), with three users: Alice owns A, where Bob is
     * a member, and B alone; Carol is in neither. Bob's preview lists A, with his own role and balance, and nothing of B
     * or of Alice's own; Carol's lists nothing; neither holds a sub or an email address. Making an owner answers Bob on
     * B and on either personal ledger, and Carol on A and B, as for a family budget that doesn't exist; Bob in A, a
     * member, gets the owners' 409 and makes nobody an owner, himself included.
     */
    @Test
    void theDeletionPreviewAndNewOwnersAreTheMembersOwn() throws IOException {
        String carol = newUser();
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01"}""")
                .get("id").asLong();
        String inA = "/api/family-ledgers/" + familyA;
        long bobInA = join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long sam = created(post(alice, inA + "/members", """
                {"displayName": "Sam"}"""));
        created(post(alice, inA + "/records", """
                {"date": "2026-08-10", "categoryId": %d, "amount": "90.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountCurrency": "EUR"}""".formatted(created(post(alice, inA + "/categories", """
                        {"code": "RENT", "name": "Rent", "type": "EXPENSE"}""")), mumInA, alicesBank)));
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "EUR", "displayName": "Alice"}""").get("id").asLong();
        body(post(alice, "/api/family-ledgers/" + familyB + "/invites", """
                {"kind": "NEW_MEMBER"}"""), HttpStatus.CREATED);

        JsonNode bobs = bobReads("/api/me/family-memberships");
        assertThat(bobs.get("memberships").findValuesAsText("ledgerId")).containsExactly(String.valueOf(familyA));
        JsonNode bobInHome = bobs.get("memberships").get(0);
        assertThat(bobInHome.get("role").asText()).isEqualTo("MEMBER");
        assertThat(bobInHome.get("balances").get(0).get("amount").asText()).isEqualTo("30.00");
        assertThat(bobInHome.get("pendingInvites").asInt()).isZero();
        assertThat(bobs.get("left").asLong()).isZero();
        JsonNode carols = ok(get(carol, "/api/me/family-memberships"));
        assertThat(carols.get("memberships")).isEmpty();
        for (JsonNode preview : List.of(bobs, carols, ok(get(alice, "/api/me/family-memberships")))) {
            Answers.assertNoneMention(preview, alice, bob, carol, "@example.com", "ALICE_BANK");
            assertThat(Answers.numbers(preview)).doesNotContain(String.valueOf(alicesBank));
        }
        Answers.assertNoneMention(bobs, "ALICE_SECRET_BUDGET", "Sam");
        assertThat(Answers.numbers(bobs)).doesNotContain(String.valueOf(familyB));

        String owner = "/api/family-ledgers/%d/members/" + sam + "/owner";
        SoftAssertions softly = new SoftAssertions();
        for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
            answersAsIfMissing(softly, HttpMethod.POST, owner, ledger, null);
        }
        for (long ledger : List.of(familyA, familyB, personalLedger(alice))) {
            answersAsIfMissingTo(softly, carol, HttpMethod.POST, owner.formatted(ledger), owner.formatted(MISSING), null);
        }
        String memberships = membershipsBut(-1);
        for (long member : List.of(bobInA, mumInA, sam)) {
            MvcTestResult answer = bobsRequest(HttpMethod.POST, inA + "/members/" + member + "/owner", null);
            softly.assertThat(answer.getResponse().getStatus()).as("Bob makes member %d an owner", member)
                    .isEqualTo(409);
        }
        softly.assertThat(membershipsBut(-1)).as("the memberships after Bob's requests").isEqualTo(memberships);
        softly.assertAll();
    }

    /**
     * A return's own entries and a claimed seat (F6b; D-37, D-35), with three users: Alice owns A, where Bob was a
     * member and left, and Sam has no account; Carol belongs to it only once she takes Sam's place. While away, Bob puts
     * an entry of his own on his former debt account, dated after today. His lookup of an invite back lists that entry,
     * his own data, and nothing of Alice's personal ledger; accepting answers 409 and changes none of Alice's rows. The
     * entry is in no answer to Alice or to Carol, whose lookup lists no entries at all. Once Carol has taken Sam's place,
     * the members' answers name her as a claimed seat, and hold no sub or email address.
     */
    @Test
    void aReturnsOwnEntriesAndAClaimedSeatAreTheMembersOwn() throws IOException {
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01"}""")
                .get("id").asLong();
        String inA = "/api/family-ledgers/" + familyA;
        join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long sam = created(post(alice, inA + "/members", """
                {"displayName": "Sam"}"""));
        long rentInA = created(post(alice, inA + "/categories", """
                {"code": "RENT", "name": "Rent", "type": "EXPENSE"}"""));
        created(post(alice, inA + "/records", """
                {"date": "2026-08-10", "categoryId": %d, "amount": "90.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "97.65", "privateNote": "ALICE_PRIVATE_RENT"}"""
                .formatted(rentInA, mumInA,
                alicesBank)));
        assertThat(call(bob, HttpMethod.DELETE, inA + "/members/me", null)).hasStatus(HttpStatus.NO_CONTENT);
        LocalDate later = utcToday().plusDays(3);
        long bobsEntry = created(post(bob, "/api/entries", """
                {"kind": "MANUAL", "entryDate": "%s", "memo": "BOB_AFTER_RETURN", "postings": [
                  {"accountId": %d, "currency": "EUR", "amount": "-7.00"},
                  {"accountId": %d, "currency": "EUR", "amount": "7.00"}]}""".formatted(later,
                accountId(bob, "FAMILY_DEBT_" + familyA), accountId(bob, "CASH"))));
        String backToA = invite(alice, inA, """
                {"kind": "NEW_MEMBER"}""");

        JsonNode bobs = body(bobsRequest(() -> inviteCall(bob, "lookup", "{\"token\": \"%s\"}".formatted(backToA))),
                HttpStatus.OK);
        assertThat(bobs.get("entriesAfterReturn").findValuesAsText("entryId"))
                .containsExactly(String.valueOf(bobsEntry));
        assertThat(bobs.get("entriesAfterReturn").get(0).get("memo").asText()).isEqualTo("BOB_AFTER_RETURN");
        Answers.assertNoneMention(bobs, "ALICE_", alice, "@example.com");
        // Her account's id as a number of its own: the digits inside another value (a timestamp's microseconds) aren't it.
        assertThat(Answers.numbers(bobs)).doesNotContain(String.valueOf(alicesBank));
        String memberships = membershipsBut(-1);
        MvcTestResult refused = bobsRequest(() -> inviteCall(bob, "accept",
                "{\"token\": \"%s\", \"displayName\": \"Dad\", \"categoryIds\": []}".formatted(backToA)));
        Answers.assertNoneMention(body(refused, HttpStatus.CONFLICT), "ALICE_", alice);
        assertThat(membershipsBut(-1)).isEqualTo(memberships);

        JsonNode carols = ok(inviteCall(carol, "lookup", "{\"token\": \"%s\"}".formatted(invite(alice, inA, """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "2026-08-15"}""".formatted(sam)))));
        assertThat(carols.get("entriesAfterReturn").isNull()).isTrue();
        Answers.assertNoneMention(carols, "BOB_AFTER_RETURN");
        assertThat(Answers.numbers(carols)).doesNotContain(String.valueOf(bobsEntry));
        for (String read : List.of("", "/members", "/records", "/journal", "/balances", "/invites")) {
            Answers.assertNoneMention(read, ok(get(alice, inA + read)), "BOB_AFTER_RETURN");
        }

        String token = invite(alice, inA, """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "2026-08-15"}""".formatted(sam));
        ok(inviteCall(carol, "accept", "{\"token\": \"%s\", \"displayName\": \"Carol\", \"categoryIds\": []}"
                .formatted(token)));
        for (String user : List.of(alice, carol)) {
            JsonNode members = ok(get(user, inA + "/members"));
            assertThat(find(members, "displayName", "Carol").get("claimedSeat").asBoolean()).as(user).isTrue();
            assertThat(find(members, "displayName", "Mum").get("claimedSeat").asBoolean()).as(user).isFalse();
            assertThat(fieldNames(members)).as(user).containsExactlyInAnyOrder("id", "displayName", "role", "status",
                    "joinDate", "hasAccount", "share", "leftDate", "claimedSeat");
            Answers.assertNoneMention(user, members, alice, bob, carol, "@example.com", "BOB_");
        }
    }

    /** A new invite of the owner's to the family ledger at the path: its token. */
    private String invite(String owner, String familyPath, String request) throws IOException {
        String link = body(post(owner, familyPath + "/invites", request), HttpStatus.CREATED).get("link").asText();
        return link.substring(link.indexOf('#') + 1);
    }

    /** POST /api/invites/{action}, from an address of its own (InviteRateLimit counts per address). */
    private MvcTestResult inviteCall(String user, String action, String body) {
        String address = "198.18.0." + (INVITE_ADDRESSES.incrementAndGet() % 250 + 1);
        return mvc.post().uri("/api/invites/" + action).with(member(user)).with(request -> {
            request.setRemoteAddr(address);
            return request;
        }).contentType("application/json").content(body).exchange();
    }

    private static final java.util.concurrent.atomic.AtomicInteger INVITE_ADDRESSES =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Every field name in the JSON, at any depth. */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(names::add);
        }
        node.elements().forEachRemaining(child -> names.addAll(fieldNames(child)));
        return names;
    }

    /** A personal category of the creator's that became a family category at creation (D-11, F4a). */
    private record Merged(long personal, long family, String code, long familyLedgerId, String familyLedgerName) {
    }

    /**
     * The user's view before, with each merged personal category replaced by its family category: in the category
     * list, marked with its family budget; as the category of their postings and counterparties; and in the cash flow,
     * whose rows of it are marked with the family budget.
     */
    private Map<String, JsonNode> merged(Map<String, JsonNode> before, List<Merged> merged) throws IOException {
        Map<String, JsonNode> view = new LinkedHashMap<>();
        for (var answer : before.entrySet()) {
            JsonNode copy = answer.getValue().deepCopy();
            for (Merged category : merged) {
                replaceCategory(copy, answer.getKey().contains("/cash-flow"), category);
            }
            // Read back, so that numbers have the node types an answer's have.
            view.put(answer.getKey(), json.readTree(json.writeValueAsBytes(copy)));
        }
        return view;
    }

    private static void replaceCategory(JsonNode node, boolean cashFlow, Merged category) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (String field : List.of("categoryId", "lastCategoryId")) {
                if (object.path(field).asLong() == category.personal()) {
                    object.put(field, category.family());
                }
            }
            if (object.has("code") && object.path("id").asLong() == category.personal()) {
                object.put("id", category.family());
                object.put("familyLedgerId", category.familyLedgerId());
                object.put("familyLedgerName", category.familyLedgerName());
            }
        }
        node.forEach(child -> replaceCategory(child, cashFlow, category));
        if (cashFlow && node.isArray()) {
            // The cash flow's rows name a category by its code; the merged one's are the family category's now.
            node.forEach(row -> {
                if (row.isObject() && row.has("categoryCode") && !row.has("familyLedgerId")
                        && row.get("categoryCode").asText().equals(category.code())) {
                    ((ObjectNode) row).put("familyLedgerId", category.familyLedgerId());
                    ((ObjectNode) row).put("familyLedgerName", category.familyLedgerName());
                }
            });
        }
    }

    /** The user's view before, with a family category in the category list, after the one of the same name. */
    private static Map<String, JsonNode> withFamilyCategory(Map<String, JsonNode> before, JsonNode category) {
        Map<String, JsonNode> view = new LinkedHashMap<>(before);
        ArrayNode categories = before.get("/api/categories").deepCopy();
        int at = 0;
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).get("name").asText().compareTo(category.get("name").asText()) <= 0) {
                at = i + 1;
            }
        }
        categories.insert(at, category);
        view.put("/api/categories", categories);
        return view;
    }

    /** Every category id in a view: of the category list, and of postings and counterparties. */
    private static Set<Long> categoryIds(Map<String, JsonNode> view) {
        Set<Long> ids = new TreeSet<>();
        view.get("/api/categories").forEach(c -> ids.add(c.get("id").asLong()));
        view.values().forEach(answer -> {
            for (String field : List.of("categoryId", "lastCategoryId")) {
                answer.findValues(field).stream().filter(JsonNode::isNumber).forEach(id -> ids.add(id.asLong()));
            }
        });
        return ids;
    }

    /**
     * The family report (E1, F6c): it answers Bob on B and on either personal ledger, and Carol on A, B and Alice's
     * personal ledger, as a missing family ledger does. In A, Bob's report names Alice by her display name only and holds
     * none of her accounts, private notes, subs, email addresses or entries. Once Bob left A, it answers him on A as a
     * missing family ledger too; Carol's view stays as it was, and Alice's rows don't change through Bob's requests.
     */
    @Test
    void theFamilyReportIsReadByItsActiveMembersOnly() throws IOException {
        String carol = newUser();
        Map<String, JsonNode> carolsViewBefore = view(carol);
        long familyA = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-08-01",
                 "categoryIds": [%d]}""".formatted(categoryId(alice, "GROCERIES"))).get("id").asLong();
        String inA = "/api/family-ledgers/" + familyA;
        join(familyA, bob, "Dad", "MEMBER", LocalDate.of(2026, 8, 1));
        long mumInA = find(ok(get(alice, inA + "/members")), "displayName", "Mum").get("id").asLong();
        long groceriesInA = find(ok(get(alice, inA + "/categories")), "code", "GROCERIES").get("id").asLong();
        long record = created(post(alice, inA + "/records", """
                {"date": "2026-08-10", "categoryId": %d, "amount": "90.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountCurrency": "EUR", "privateNote": "ALICE_PRIVATE_NOTE"}"""
                .formatted(groceriesInA, mumInA, alicesBank)));
        long familyB = newFamily(alice, """
                {"name": "ALICE_SECRET_BUDGET", "baseCurrency": "EUR", "displayName": "Alice",
                 "startDate": "2026-08-01"}""").get("id").asLong();
        String report = "/api/family-ledgers/%d/report";

        SoftAssertions softly = new SoftAssertions();
        for (long ledger : List.of(familyB, personalLedger(alice), personalLedger(bob))) {
            answersAsIfMissing(softly, HttpMethod.GET, report, ledger, null);
        }
        for (long ledger : List.of(familyA, familyB, personalLedger(alice))) {
            answersAsIfMissingTo(softly, carol, HttpMethod.GET, report.formatted(ledger), report.formatted(MISSING),
                    null);
        }
        softly.assertAll();

        Map<String, String> alicesRows = digestOf(alice);
        JsonNode bobs = bobReads(report.formatted(familyA));
        assertThat(bobs.get("byCurrency").get(0).get("rows").get(0).get("total").asText()).isEqualTo("90.00");
        assertThat(bobs.get("members").findValuesAsText("displayName")).containsExactly("Mum", "Dad");
        Answers.assertNoneMention(bobs, alice, "@example.com", "ALICE_PRIVATE_NOTE", "ALICE_", "Alice's bank");
        assertThat(fieldNames(bobs)).doesNotContain("accountId", "entryId", "userSub", "sub", "email",
                "paymentAccountId", "yourPayment");
        List<String> numbers = bobs.findValues("memberId").stream().map(JsonNode::asText).toList();
        assertThat(numbers).doesNotContain(String.valueOf(alicesBank), String.valueOf(record));
        assertThat(digestOf(alice)).isEqualTo(alicesRows);

        assertThat(delete(bob, inA + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        softly = new SoftAssertions();
        answersAsIfMissing(softly, HttpMethod.GET, report, familyA, null);
        softly.assertAll();
        assertThat(view(carol)).isEqualTo(carolsViewBefore);
    }

    /** Every membership of every ledger but one, as text, to compare before and after. */
    private String membershipsBut(long memberId) {
        return jdbc.sql("SELECT string_agg(m::text, '|' ORDER BY m.id) FROM ledger_member m WHERE m.id <> ?")
                .param(memberId).query(String.class).single();
    }

    /**
     * A user's request for another's object, sent by the user: 404, word for word the answer for an object that
     * doesn't exist, and Alice's rows the same afterwards. For users other than Bob, whose requests go through
     * {@link #answersAsIfMissing}.
     */
    private void answersAsIfMissingTo(SoftAssertions softly, String user, HttpMethod method, String uri,
            String missingUri, String body) throws IOException {
        Map<String, String> alicesRows = digestOf(alice);
        MvcTestResult answer = call(user, method, uri, body);
        softly.assertThat(digestOf(alice)).as("Alice's rows after %s %s", method, uri).isEqualTo(alicesRows);
        softly.assertThat(answer.getResponse().getStatus()).as("%s %s", method, uri).isEqualTo(404);
        softly.assertThat(withoutDigits(answer)).as("%s %s", method, uri)
                .isEqualTo(withoutDigits(call(user, method, missingUri, body)));
    }

    private long personalLedger(String user) {
        return jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(user).query(Long.class).single();
    }

    /** A request to a family endpoint, by its path after the ledger's. */
    private record FamilyRequest(HttpMethod method, String path, String body) {
    }

    /**
     * Every endpoint of the application, as its handler mappings map them, handled at least one request of Bob's
     * against Alice's data in the tests above, or holds nothing of a user's. Runs after them, since it reads what
     * they recorded; run on its own, it fails.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void everyEndpointIsCheckedHere() {
        assertThat(CHECKED).as("endpoints that Bob's requests reached; this test needs the others of its class")
                .isNotEmpty();
        Set<String> mapped = mappedEndpoints();
        assertThat(mapped).as("mapped endpoints").containsAll(NOT_USER_SCOPED.keySet());

        Set<String> unchecked = new TreeSet<>(mapped);
        unchecked.removeAll(CHECKED);
        unchecked.removeAll(NOT_USER_SCOPED.keySet());
        assertThat(unchecked).as("endpoints that no request of Bob's against Alice's data reached").isEmpty();
    }

    /** A few entries in euros and dollars, and a manual rate for pounds. Dollars have no rate of his. */
    private void writeBobsLedger() throws IOException {
        bobsCash = accountId(bob, "CASH");
        bobsGroceries = categoryId(bob, "GROCERIES");
        bobsFriend = newCounterparty(bob, "Bob's friend");
        long current = accountId(bob, "CURRENT_ACCOUNT");
        newEntry(bob, """
                {"kind": "OPENING_BALANCE", "entryDate": "2026-08-01", "accountId": %d, "currency": "EUR",
                 "amount": "100"}""".formatted(bobsCash));
        bobsExpense = newEntry(bob, """
                {"kind": "EXPENSE", "entryDate": "2026-08-03", "accountId": %d, "currency": "USD", "amount": "30",
                 "categoryId": %d, "memo": "Bob's shopping"}""".formatted(bobsCash, bobsGroceries));
        newEntry(bob, """
                {"kind": "SHARED_EXPENSE", "entryDate": "2026-08-05", "accountId": %d, "currency": "EUR",
                 "total": "10", "categoryId": %d}""".formatted(current, bobsGroceries));
        newEntry(bob, """
                {"kind": "LOAN_GIVEN", "entryDate": "2026-08-06", "fromAccountId": %d, "counterpartyId": %d,
                 "currency": "EUR", "amount": "20"}""".formatted(current, bobsFriend));
        ok(post(bob, "/api/rates/manual", """
                {"date": "2026-08-01", "base": "EUR", "quote": "GBP", "rate": "0.90"}"""));
    }

    /**
     * Accounts, categories and counterparties of her own, settings, rates for Bob's dollars and pounds, an entry of
     * every kind in August 2026, and the synthetic workbook imported. Then one of her postings is changed past the
     * database's triggers, so that her integrity check reports something.
     */
    private void writeAlicesLedger() throws IOException {
        // In dollars by default, as before F8a; it pays a family record in whichever currency she names (D-89).
        alicesBank = created(post(alice, "/api/accounts", """
                {"code": "ALICE_BANK", "name": "Alice's bank", "type": "ASSET", "defaultCurrency": "USD"}"""));
        alicesLoans = created(post(alice, "/api/accounts", """
                {"code": "ALICE_LOANS", "name": "Alice's loans", "type": "ASSET", "requiresCounterparty": true}"""));
        alicesSharedAccount = created(post(alice, "/api/accounts", """
                {"code": "ALICE_SHARED", "name": "Alice's shared budget", "type": "LIABILITY"}"""));
        alicesHobby = created(post(alice, "/api/categories", """
                {"code": "ALICE_HOBBY", "name": "Alice's hobby", "type": "EXPENSE"}"""));
        alicesGifts = created(post(alice, "/api/categories", """
                {"code": "ALICE_GIFTS", "name": "Alice's gifts", "type": "INCOME"}"""));
        alicesLandlord = created(post(alice, "/api/counterparties", """
                {"name": "Alice's landlord", "kind": "PERSON"}"""));
        alicesFriend = created(post(alice, "/api/counterparties", """
                {"name": "Alice's friend", "kind": "PERSON"}"""));
        ok(put(alice, "/api/settings", """
                {"baseCurrency": "USD", "sharedAccountId": %d, "defaultShareRatio": "0.4"}"""
                .formatted(alicesSharedAccount)));
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-08-01", "base": "EUR", "quote": "USD", "rate": "1.10"}"""));
        ok(mvc.post().uri("/api/rates/manual/csv").multipart()
                .file(new MockMultipartFile("file", "rates.csv", "text/csv", """
                        date,base,quote,rate
                        2026-08-15,EUR,USD,1.12
                        2026-08-01,EUR,GBP,0.85
                        """.getBytes(StandardCharsets.UTF_8)))
                .with(member(alice))
                .exchange());

        long cash = accountId(alice, "CASH");
        long current = accountId(alice, "CURRENT_ACCOUNT");
        long groceries = categoryId(alice, "GROCERIES");
        long salary = newEntry(alice, """
                {"kind": "INCOME", "entryDate": "2026-08-02", "accountId": %d, "currency": "EUR", "amount": "2000",
                 "categoryId": %d, "memo": "Alice's salary"}""".formatted(current, categoryId(alice, "SALARY")))
                .get("id").asLong();
        for (String command : List.of("""
                {"kind": "OPENING_BALANCE", "entryDate": "2026-08-01", "accountId": %d, "currency": "EUR",
                 "amount": "500"}""".formatted(cash), """
                {"kind": "OPENING_BALANCE", "entryDate": "2026-08-01", "accountId": %d, "currency": "USD",
                 "amount": "1000"}""".formatted(alicesBank), """
                {"kind": "EXPENSE", "entryDate": "2026-08-03", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "40", "categoryId": %d, "memo": "Alice's shopping"}"""
                .formatted(alicesLandlord, cash, groceries), """
                {"kind": "EXPENSE", "entryDate": "2026-08-03", "accountId": %d, "currency": "USD", "amount": "25",
                 "categoryId": %d}""".formatted(alicesBank, alicesHobby), """
                {"kind": "TRANSFER", "entryDate": "2026-08-04", "fromAccountId": %d, "toAccountId": %d,
                 "currency": "EUR", "amount": "300"}""".formatted(current, accountId(alice, "SAVINGS_ACCOUNT")), """
                {"kind": "SHARED_EXPENSE", "entryDate": "2026-08-05", "accountId": %d, "currency": "EUR",
                 "total": "60", "categoryId": %d}""".formatted(current, groceries), """
                {"kind": "LOAN_GIVEN", "entryDate": "2026-08-06", "fromAccountId": %d, "counterpartyId": %d,
                 "currency": "EUR", "amount": "100"}""".formatted(current, alicesFriend), """
                {"kind": "LOAN_REPAID", "entryDate": "2026-08-07", "toAccountId": %d, "counterpartyId": %d,
                 "currency": "EUR", "amount": "40"}""".formatted(cash, alicesFriend), """
                {"kind": "CURRENCY_EXCHANGE", "entryDate": "2026-08-08", "fromAccountId": %d, "fromCurrency": "EUR",
                 "fromAmount": "90", "toAccountId": %d, "toCurrency": "USD", "toAmount": "100"}"""
                .formatted(current, alicesBank), """
                {"kind": "MANUAL", "entryDate": "2026-08-09", "memo": "Alice's gift", "postings": [
                  {"accountId": %d, "currency": "EUR", "amount": "5"},
                  {"accountId": %d, "currency": "EUR", "amount": "-5", "categoryId": %d}]}"""
                .formatted(cash, accountId(alice, "UNALLOCATED"), alicesGifts), """
                {"kind": "MANUAL", "entryDate": "2026-08-10", "postings": [
                  {"accountId": %d, "currency": "GBP", "amount": "50", "counterpartyId": %d},
                  {"accountId": %d, "currency": "GBP", "amount": "-50"}]}"""
                .formatted(alicesLoans, alicesFriend, cash))) {
            newEntry(alice, command);
        }
        alicesImport = ok(importWorkbook(alice, withoutRow(fixture("transactions.csv"), 10), "false"));
        assertThat(alicesImport.get("outcome").asText()).isEqualTo("COMMITTED");

        transactions.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE posting SET amount = amount + 0.01 WHERE entry_id = ? AND line_no = 0").param(salary)
                    .update();
        });
    }

    /** The user's answer to every read. */
    private Map<String, JsonNode> view(String user) throws IOException {
        Map<String, JsonNode> view = new LinkedHashMap<>();
        for (String uri : READS) {
            view.put(uri, ok(get(user, uri)));
        }
        return view;
    }

    /** Bob's answer to every read, once Alice has written her ledger. */
    private Map<String, JsonNode> bobsView() throws IOException {
        Map<String, JsonNode> view = new LinkedHashMap<>();
        for (String uri : READS) {
            view.put(uri, bobReads(uri));
        }
        return view;
    }

    /**
     * One of Bob's requests, sent once Alice has written her ledger. Whatever it asks, her rows are the same
     * afterwards, in every table and column; and the endpoint that handled it counts as checked.
     *
     * @param request sends the request as Bob
     */
    private MvcTestResult bobsRequest(Request request) throws IOException {
        Map<String, String> alicesRows = digestOf(alice);
        MvcTestResult result = request.send();
        String endpoint = endpointOf(result.getMvcResult());
        assertThat(digestOf(alice)).as("Alice's rows after Bob's request to %s", endpoint).isEqualTo(alicesRows);
        CHECKED.add(endpoint);
        return result;
    }

    private MvcTestResult bobsRequest(HttpMethod method, String uri, String body) throws IOException {
        return bobsRequest(() -> call(bob, method, uri, body));
    }

    /** Bob's read of a list, a search or a report of his: it answers, and nothing of Alice's is in it. */
    private JsonNode bobReads(String uri) throws IOException {
        JsonNode answer = ok(bobsRequest(HttpMethod.GET, uri, null));
        Answers.assertNoneMention("Bob's " + uri, answer, "Alice", "ALICE", alice);
        return answer;
    }

    /**
     * The endpoint that handled the request: its method and path pattern, and the parameters it requires, as the
     * handler mapping that chose the handler maps them.
     */
    private String endpointOf(MvcResult result) {
        MockHttpServletRequest request = result.getRequest();
        String what = request.getMethod() + " " + request.getRequestURI();
        assertThat(result.getHandler()).as("the handler of %s", what).isInstanceOf(HandlerMethod.class);
        Method handler = ((HandlerMethod) result.getHandler()).getMethod();
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        RequestMethod method = RequestMethod.resolve(request.getMethod());
        for (RequestMappingInfoHandlerMapping mapping : handlerMappings) {
            for (var mapped : mapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = mapped.getKey();
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                if (mapped.getValue().getMethod().equals(handler) && info.getPatternValues().contains(pattern)
                        && (methods.isEmpty() || methods.contains(method))) {
                    return endpoint(methods.isEmpty() ? "ANY" : method.name(), pattern, info);
                }
            }
        }
        throw new AssertionError("No handler mapping maps %s to %s".formatted(what, handler));
    }

    /** Every endpoint of the application's handler mappings. */
    private Set<String> mappedEndpoints() {
        Set<String> endpoints = new TreeSet<>();
        for (RequestMappingInfoHandlerMapping mapping : handlerMappings) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                List<String> methods = new ArrayList<>(info.getMethodsCondition().getMethods().stream()
                        .map(RequestMethod::name).toList());
                if (methods.isEmpty()) {
                    methods.add("ANY");
                }
                for (String method : methods) {
                    info.getPatternValues().forEach(pattern -> endpoints.add(endpoint(method, pattern, info)));
                }
            }
        }
        return endpoints;
    }

    /** Such as {@code GET /api/reports/balances [currency=BASE]}. */
    private static String endpoint(String method, String pattern, RequestMappingInfo info) {
        String params = info.getParamsCondition().getExpressions().stream().map(Object::toString).sorted()
                .collect(Collectors.joining(" & "));
        return method + " " + pattern + (params.isEmpty() ? "" : " [" + params + "]");
    }

    /** Bob's request for one of Alice's objects, by its id. */
    private void answersAsIfMissing(SoftAssertions softly, HttpMethod method, String uri, long alicesId, String body)
            throws IOException {
        answersAsIfMissing(softly, method, uri, alicesId, MISSING, body);
    }

    /**
     * Bob's request for one of Alice's objects gets 404, and word for word the answer to the same request for an
     * object that doesn't exist, apart from the numbers in it.
     *
     * @param uri with a %s or %d for the object's key
     */
    private void answersAsIfMissing(SoftAssertions softly, HttpMethod method, String uri, Object alicesKey,
            Object missingKey, String body) throws IOException {
        MvcTestResult answer = bobsRequest(method, uri.formatted(alicesKey), body);
        MvcTestResult answerIfMissing = call(bob, method, uri.formatted(missingKey), body);
        String what = method + " " + uri.formatted(alicesKey);
        softly.assertThat(answer.getResponse().getStatus()).as(what).isEqualTo(404);
        softly.assertThat(withoutDigits(answer)).as(what).isEqualTo(withoutDigits(answerIfMissing));
    }

    /** The response body with every number replaced, so that answers about different ids compare equal. */
    private static String withoutDigits(MvcTestResult result) {
        try {
            return Answers.rawText(result).replaceAll("\\d+", "N");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private long created(MvcTestResult result) throws IOException {
        return body(result, HttpStatus.CREATED).get("id").asLong();
    }

    private static String expense(long accountId, long categoryId, Long payeeId) {
        return """
                {"kind": "EXPENSE", "entryDate": "2026-08-20", "payeeId": %s, "accountId": %d, "currency": "EUR",
                 "amount": "1", "categoryId": %d}""".formatted(payeeId, accountId, categoryId);
    }

    private static List<JsonNode> elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    private static Set<String> rateKeys(JsonNode rates) {
        return Set.copyOf(elements(rates).stream().map(DataIsolationApiTests::rateKey).toList());
    }

    private static String rateKey(JsonNode rate) {
        return rate.get("date").asText() + " " + rate.get("quote").asText();
    }

    /**
     * One of Alice's objects in a command of Bob's.
     *
     * @param command the command with the object's id
     */
    private record Reference(String what, long alicesId, LongFunction<String> command) {
    }

    /** Sends a request. */
    @FunctionalInterface
    private interface Request {
        MvcTestResult send() throws IOException;
    }
}
