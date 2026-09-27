package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongFunction;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Strict isolation between users (rule 11), endpoint by endpoint. Anyone can sign up to the production realm, so no
 * user may see, change or use anything of another user's.
 * <p>
 * Alice's ledger has every kind of row a user owns: accounts, categories and counterparties of her own, entries of
 * every kind, settings, manual rates and an import, on the same days and in the same currencies as Bob's. Bob can't
 * list, read, change, delete or refer to any of it; for each of her objects he gets the answer he gets for an object
 * that doesn't exist; and every read of his answers the same before and after she writes her ledger.
 * {@link #everyOperationOfTheApiIsCheckedHere} fails for a new endpoint until it is checked here too.
 */
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

    /** Every operation of the API: the reads above, and the writes of the tests below. */
    private static final Set<String> CHECKED = Set.of(
            "get /api/me",
            "get /api/accounts", "post /api/accounts", "patch /api/accounts/{id}",
            "get /api/categories", "post /api/categories", "patch /api/categories/{id}",
            "get /api/counterparties", "post /api/counterparties", "patch /api/counterparties/{id}",
            "get /api/entries", "post /api/entries",
            "get /api/entries/{id}", "put /api/entries/{id}", "delete /api/entries/{id}",
            "get /api/reports/balances", "get /api/reports/cash-flow", "get /api/reports/counterparty-balances",
            "get /api/reports/net-worth", "get /api/reports/shared-settlement", "get /api/reports/integrity",
            "post /api/import",
            "get /api/rates", "get /api/rates/manual", "post /api/rates/manual", "delete /api/rates/manual",
            "post /api/rates/manual/csv",
            "get /api/settings", "put /api/settings");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

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
        Map<String, JsonNode> bobsView = view(bob);
        Map<String, JsonNode> alicesView = view(alice);

        SoftAssertions softly = new SoftAssertions();
        for (String uri : READS) {
            softly.assertThat(bobsView.get(uri)).as("Bob's %s", uri).isEqualTo(bobsViewBefore.get(uri));
            softly.assertThat(bobsView.get(uri).toString()).as("Bob's %s", uri)
                    .doesNotContain("Alice", "ALICE", alice);
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
        assertThat(view(bob)).isEqualTo(bobsViewBefore);
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
                    new MvcTestResult[] {post(bob, "/api/entries", hers), post(bob, "/api/entries", missing)},
                    new MvcTestResult[] {put(bob, bobsEntry, hers), put(bob, bobsEntry, missing)})) {
                softly.assertThat(answers[0].getResponse().getStatus()).as(reference.what()).isEqualTo(422);
                softly.assertThat(withoutDigits(answers[0])).as(reference.what()).isEqualTo(withoutDigits(answers[1]));
            }
        }
        String settings = """
                {"baseCurrency": "EUR", "sharedAccountId": %d, "defaultShareRatio": "0.5"}""";
        MvcTestResult herSharedAccount = put(bob, "/api/settings", settings.formatted(alicesSharedAccount));
        softly.assertThat(herSharedAccount.getResponse().getStatus()).as("her shared account").isEqualTo(422);
        softly.assertThat(withoutDigits(herSharedAccount)).as("her shared account")
                .isEqualTo(withoutDigits(put(bob, "/api/settings", settings.formatted(MISSING))));
        // As a filter, her objects find nothing, like objects that don't exist.
        for (String filter : List.of("accountId=%d", "categoryId=%d", "counterpartyId=%d")) {
            long id = filter.startsWith("account") ? alicesCash : filter.startsWith("category") ? alicesHobby
                    : alicesFriend;
            JsonNode found = ok(get(bob, "/api/entries?" + filter.formatted(id)));
            softly.assertThat(found.get("totalElements").asLong()).as(filter).isZero();
            softly.assertThat(found).as(filter).isEqualTo(ok(get(bob, "/api/entries?" + filter.formatted(MISSING))));
        }
        softly.assertThat(body(get(bob, "/api/reports/counterparty-balances?accountCode=ALICE_LOANS"),
                HttpStatus.NOT_FOUND).get("detail").asText()).isEqualTo("Account ALICE_LOANS not found.");
        softly.assertAll();
        assertThat(view(bob)).isEqualTo(bobsViewBefore);

        // Codes and names are unique per user only, so taking hers tells him nothing: a clash would.
        assertThat(post(bob, "/api/accounts", """
                {"code": "ALICE_BANK", "name": "Alice's bank", "type": "ASSET"}""")).hasStatus(HttpStatus.CREATED);
        assertThat(post(bob, "/api/categories", """
                {"code": "ALICE_HOBBY", "name": "Alice's hobby", "type": "EXPENSE"}""")).hasStatus(HttpStatus.CREATED);
        assertThat(post(bob, "/api/counterparties", """
                {"name": "Alice's friend"}""")).hasStatus(HttpStatus.CREATED);
        // A rate of his own for her day and currency, and deleting his rate of a day she has one for, leave hers.
        ok(post(bob, "/api/rates/manual", """
                {"date": "2026-08-01", "base": "EUR", "quote": "USD", "rate": "2"}"""));
        assertThat(delete(bob, "/api/rates/manual?date=2026-08-01&currency=GBP")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(view(alice)).isEqualTo(alicesView);
    }

    @Test
    void anImportSeesOnlyTheUsersOwnLedger() throws IOException {
        Map<String, JsonNode> alicesView = view(alice);
        byte[] transactions = withoutRow(fixture("transactions.csv"), 10);

        JsonNode dryRun = ok(importWorkbook(bob, transactions, null));
        JsonNode committed = ok(importWorkbook(bob, transactions, "false"));

        // Alice imported the same files, but for Bob every row is new: none is skipped as imported before.
        assertThat(dryRun.get("entriesByKind")).isEqualTo(alicesImport.get("entriesByKind"));
        assertThat(committed.get("outcome").asText()).isEqualTo("COMMITTED");
        assertThat(committed.get("userId").asText()).isEqualTo(bob);
        assertThat(committed.get("entriesByKind")).isEqualTo(alicesImport.get("entriesByKind"));
        assertThat(committed.get("skipped")).isEqualTo(alicesImport.get("skipped"));
        // The balances after the import are his accounts' alone.
        List<String> bobsAccounts = ok(get(bob, "/api/accounts")).findValuesAsText("id");
        assertThat(committed.get("balances").findValuesAsText("accountId")).isNotEmpty()
                .allMatch(bobsAccounts::contains);
        assertThat(ok(get(bob, "/api/entries")).get("totalElements").asLong()).isEqualTo(4 + 24);
        assertThat(view(alice)).isEqualTo(alicesView);
    }

    @Test
    void theUserIsTheAccessTokensSubjectAndNothingElse() throws IOException {
        Map<String, JsonNode> alicesView = view(alice);

        // A parameter, a header or a field of the body that names Alice changes nothing.
        assertThat(ok(mvc.get().uri("/api/accounts").param("userId", alice).param("user_id", alice)
                .param("sub", alice).header("X-User-Id", alice).with(member(bob)).exchange()))
                .isEqualTo(bobsViewBefore.get("/api/accounts"));
        assertThat(post(bob, "/api/counterparties", """
                {"name": "Planted", "userId": "%1$s", "user_id": "%1$s", "sub": "%1$s"}""".formatted(alice)))
                .hasStatus(HttpStatus.CREATED);
        newEntry(bob, """
                {"kind": "EXPENSE", "entryDate": "2026-08-20", "accountId": %d, "currency": "EUR", "amount": "1",
                 "categoryId": %d, "memo": "Planted", "userId": "%s"}""".formatted(bobsCash, bobsGroceries, alice));
        ok(put(bob, "/api/settings", """
                {"baseCurrency": "GBP", "defaultShareRatio": "0.5", "userId": "%s"}""".formatted(alice)));
        assertThat(ok(get(bob, "/api/counterparties")).findValuesAsText("name")).contains("Planted");
        assertThat(ok(get(bob, "/api/entries")).get("totalElements").asLong()).isEqualTo(5);
        assertThat(ok(get(bob, "/api/settings")).get("baseCurrency").asText()).isEqualTo("GBP");
        assertThat(view(alice)).isEqualTo(alicesView);

        // The same with an access token signed like Keycloak's, rather than spring-security-test's stand-in.
        long alicesEntry = alicesView.get("/api/entries?size=200").get("content").get(0).get("id").asLong();
        assertThat(request(HttpMethod.GET, "/api/entries/%d?userId=%s".formatted(alicesEntry, alice), token(bob), null))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(read(request(HttpMethod.GET, "/api/counterparties?userId=" + alice, token(bob), null),
                JsonNode.class).findValuesAsText("name")).containsExactly("Bob's friend", "Planted");
    }

    @Test
    void everyOperationOfTheApiIsCheckedHere() throws IOException {
        Set<String> operations = new TreeSet<>();
        ok(get(bob, "/api/openapi")).get("paths").properties().forEach(path -> path.getValue().fieldNames()
                .forEachRemaining(method -> operations.add(method + " " + path.getKey())));

        assertThat(operations).containsExactlyInAnyOrderElementsOf(CHECKED);
    }

    /** A shared browser or a proxy must never hand one user's answer to the next. */
    @Test
    void noAnswerOfTheApiMayBeStored() {
        SoftAssertions softly = new SoftAssertions();
        for (String uri : READS) {
            softly.assertThat(get(bob, uri).getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).as(uri)
                    .contains("no-store");
        }
        softly.assertAll();
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

    /** Bob's request for one of Alice's objects, by its id. */
    private void answersAsIfMissing(SoftAssertions softly, HttpMethod method, String uri, long alicesId, String body) {
        answersAsIfMissing(softly, method, uri, alicesId, MISSING, body);
    }

    /**
     * Bob's request for one of Alice's objects gets 404, and word for word the answer to the same request for an
     * object that doesn't exist, apart from the numbers in it.
     *
     * @param uri with a %s or %d for the object's key
     */
    private void answersAsIfMissing(SoftAssertions softly, HttpMethod method, String uri, Object alicesKey,
            Object missingKey, String body) {
        MvcTestResult answer = call(bob, method, uri.formatted(alicesKey), body);
        MvcTestResult answerIfMissing = call(bob, method, uri.formatted(missingKey), body);
        String what = method + " " + uri.formatted(alicesKey);
        softly.assertThat(answer.getResponse().getStatus()).as(what).isEqualTo(404);
        softly.assertThat(withoutDigits(answer)).as(what).isEqualTo(withoutDigits(answerIfMissing));
    }

    /** The response body with every number replaced, so that answers about different ids compare equal. */
    private static String withoutDigits(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString().replaceAll("\\d+", "N");
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
}
