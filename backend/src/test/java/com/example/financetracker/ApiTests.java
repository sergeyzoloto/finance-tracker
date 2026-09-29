package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** What holds for the whole API: who gets in, what a new user starts with, and whose objects a user sees. */
class ApiTests extends IntegrationTest {

    private final String alice = UUID.randomUUID().toString();
    private final String bob = UUID.randomUUID().toString();

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everyEndpointAnswers401WithoutAValidToken() {
        String subject = UUID.randomUUID().toString();
        Map<String, String> invalidTokens = new LinkedHashMap<>();
        invalidTokens.put("none", null);
        invalidTokens.put("malformed", "not-a-jwt");
        invalidTokens.put("expired", sign(claims(subject)
                .issueTime(Date.from(Instant.now().minusSeconds(420)))
                .expirationTime(Date.from(Instant.now().minusSeconds(120)))));
        invalidTokens.put("other issuer", sign(claims(subject).issuer("https://evil.test/realms/myapps")));
        // Same key ID as the realm's key, so only the signature itself gives it away.
        invalidTokens.put("bad signature", FakeKeycloak.sign(claims(subject).build(), FakeKeycloak.newRsaKey("test-key")));
        String entry = """
                {"kind": "MANUAL", "entryDate": "2026-01-01", "postings": []}""";
        List<Object[]> endpoints = List.of(
                new Object[] {GET, "/api/me", null},
                new Object[] {GET, "/api/openapi", null},
                new Object[] {GET, "/api/accounts", null},
                new Object[] {POST, "/api/accounts", """
                        {"code": "BANK", "name": "Bank", "type": "ASSET"}"""},
                new Object[] {PATCH, "/api/accounts/1", "{}"},
                new Object[] {GET, "/api/categories", null},
                new Object[] {POST, "/api/categories", """
                        {"code": "RENT", "name": "Rent", "type": "EXPENSE"}"""},
                new Object[] {PATCH, "/api/categories/1", "{}"},
                new Object[] {GET, "/api/counterparties", null},
                new Object[] {POST, "/api/counterparties", """
                        {"name": "Landlord"}"""},
                new Object[] {PATCH, "/api/counterparties/1", "{}"},
                new Object[] {GET, "/api/entries?from=2026-01-01&to=2026-01-31&accountId=1&q=rent", null},
                new Object[] {GET, "/api/entries/1", null},
                new Object[] {POST, "/api/entries", entry},
                new Object[] {PUT, "/api/entries/1?version=0", entry},
                new Object[] {DELETE, "/api/entries/1?version=0", null},
                new Object[] {GET, "/api/reports/balances", null},
                new Object[] {GET, "/api/reports/cash-flow?from=2026-01-01&to=2026-01-31", null},
                new Object[] {GET, "/api/reports/counterparty-balances?accountCode=LOANS_ASSET", null},
                new Object[] {GET, "/api/reports/net-worth", null},
                new Object[] {GET, "/api/reports/shared-settlement", null},
                new Object[] {GET, "/api/reports/integrity", null},
                new Object[] {GET, "/api/reports/balances?currency=BASE", null},
                new Object[] {GET, "/api/reports/cash-flow?from=2026-01-01&to=2026-01-31&currency=BASE", null},
                new Object[] {GET, "/api/reports/net-worth?currency=BASE", null},
                new Object[] {GET, "/api/rates", null},
                new Object[] {GET, "/api/rates/manual", null},
                new Object[] {POST, "/api/rates/manual", """
                        {"date": "2026-01-01", "base": "EUR", "quote": "RUB", "rate": "95"}"""},
                new Object[] {POST, "/api/rates/manual/csv", ""},
                new Object[] {DELETE, "/api/rates/manual?date=2026-01-01&currency=RUB", null},
                new Object[] {POST, "/api/import", ""},
                new Object[] {GET, "/api/settings", null},
                new Object[] {PUT, "/api/settings", """
                        {"baseCurrency": "EUR", "defaultShareRatio": "0.5"}"""},
                new Object[] {POST, "/api/demo-data", null},
                new Object[] {DELETE, "/api/me/data", null},
                new Object[] {GET, "/api/family-ledgers", null},
                new Object[] {POST, "/api/family-ledgers", """
                        {"name": "Family", "baseCurrency": "EUR", "displayName": "Me"}"""},
                new Object[] {GET, "/api/family-ledgers/1", null},
                new Object[] {PATCH, "/api/family-ledgers/1", "{}"},
                new Object[] {PUT, "/api/family-ledgers/1/split-rule", """
                        {"rule": "EQUAL"}"""},
                new Object[] {GET, "/api/family-ledgers/1/members", null},
                new Object[] {POST, "/api/family-ledgers/1/members", """
                        {"displayName": "Kid"}"""},
                new Object[] {PATCH, "/api/family-ledgers/1/members/1", """
                        {"displayName": "Kid"}"""},
                new Object[] {DELETE, "/api/family-ledgers/1/members/1", null},
                new Object[] {GET, "/api/family-ledgers/1/categories", null},
                new Object[] {POST, "/api/family-ledgers/1/categories", """
                        {"code": "RENT", "name": "Rent", "type": "EXPENSE"}"""},
                new Object[] {PATCH, "/api/family-ledgers/1/categories/1", "{}"},
                new Object[] {DELETE, "/api/family-ledgers/1/categories/1", null});

        SoftAssertions softly = new SoftAssertions();
        invalidTokens.forEach((kind, token) -> endpoints.forEach(e -> softly
                .assertThat(request((HttpMethod) e[0], (String) e[1], token, (String) e[2]).getResponse().getStatus())
                .as("%s %s with %s token", e[0], e[1], kind)
                .isEqualTo(401)));
        softly.assertAll();
        assertThat(count("SELECT count(*) FROM users WHERE keycloak_id = ?", subject))
                .as("rejected tokens must not provision users").isZero();
        assertThat(count("SELECT count(*) FROM user_settings WHERE user_id = ?", subject)).isZero();
    }

    @Test
    void onlyTheHealthEndpointIsOpenAndItTellsNothingButTheStatus() throws IOException {
        assertThat(body(request(GET, "/actuator/health", null, null))).isEqualTo(json.readTree("""
                {"status": "UP"}"""));
        assertThat(request(GET, "/actuator/env", null, null)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/actuator/env").with(member(alice)).exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aNewUsersFirstRequestsSeedTheStarterLedgerExactlyOnceEvenInParallel() throws Exception {
        String subject = UUID.randomUUID().toString();
        JsonNode seed = json.readTree(new ClassPathResource("seed/starter-ledger.json").getInputStream());
        int parallel = 8;
        CyclicBarrier start = new CyclicBarrier(parallel);
        ExecutorService threads = Executors.newFixedThreadPool(parallel);
        List<Future<MvcTestResult>> firstRequests = new ArrayList<>();
        try {
            for (int i = 0; i < parallel; i++) {
                String uri = i % 2 == 0 ? "/api/accounts" : "/api/categories";
                firstRequests.add(threads.submit(() -> {
                    start.await();
                    return mvc.get().uri(uri).with(member(subject)).exchange();
                }));
            }
            for (Future<MvcTestResult> request : firstRequests) {
                MvcTestResult result = request.get();
                assertThat(result).hasStatusOk();
                // Each request waits for the seed, so each sees all of it.
                assertThat(read(result, JsonNode.class)).hasSize(
                        result.getRequest().getRequestURI().equals("/api/accounts") ? seed.get("accounts").size()
                                : seed.get("categories").size());
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(mvc.get().uri("/api/settings").with(member(subject))).hasStatusOk();

        assertThat(count("SELECT count(*) FROM user_settings WHERE user_id = ? AND base_currency = 'EUR'", subject))
                .isEqualTo(1);
        assertThat(codes("account", subject)).containsExactlyInAnyOrderElementsOf(codes(seed.get("accounts")));
        assertThat(codes("category", subject)).containsExactlyInAnyOrderElementsOf(codes(seed.get("categories")));
        assertThat(jdbc.sql("SELECT code FROM account WHERE user_id = ? AND is_system ORDER BY code").param(subject)
                .query(String.class).list()).containsExactly("FX_EXCHANGE", "OPENING_BALANCE");
        // One personal ledger, whose one member is the user, holds all of it (V5).
        assertThat(jdbc.sql("""
                SELECT l.type || ' ' || m.role || ' ' || m.status FROM ledger l JOIN ledger_member m ON m.ledger_id = l.id
                WHERE m.user_sub = ?""").param(subject).query(String.class).list())
                .containsExactly("PERSONAL OWNER ACTIVE");
        assertThat(jdbc.sql("""
                SELECT ledger_id FROM account WHERE user_id = :sub UNION SELECT ledger_id FROM category WHERE user_id = :sub
                """).param("sub", subject).query(Long.class).list()).containsExactly(jdbc.sql(
                "SELECT ledger_id FROM ledger_member WHERE user_sub = ?").param(subject).query(Long.class).single());
        assertThat(jdbc.sql("SELECT email, display_name FROM users WHERE keycloak_id = ?").param(subject).query()
                .listOfRows()).containsExactly(Map.of("email", subject + "@example.com", "display_name", "User " + subject));
    }

    @Test
    void usersGet404ForEachOthersEntriesAccountsCategoriesAndCounterparties() throws IOException {
        JsonNode alicesAccounts = body(mvc.get().uri("/api/accounts").with(member(alice)).exchange());
        long cash = id(alicesAccounts, "CASH");
        long groceries = id(body(mvc.get().uri("/api/categories").with(member(alice)).exchange()), "GROCERIES");
        long landlord = created(write(alice, POST, "/api/counterparties", """
                {"name": "Landlord"}""")).get("id").asLong();
        String expense = """
                {"kind": "EXPENSE", "entryDate": "2026-02-01", "payeeId": %d, "accountId": %d, "currency": "EUR",
                 "amount": "10", "categoryId": %d}""".formatted(landlord, cash, groceries);
        long entry = created(write(alice, POST, "/api/entries", expense)).get("id").asLong();
        String entryUri = "/api/entries/" + entry;

        assertThat(mvc.get().uri(entryUri).with(member(bob))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(write(bob, PUT, entryUri + "?version=0", expense)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.delete().uri(entryUri + "?version=0").with(member(bob))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(write(bob, PATCH, "/api/accounts/" + cash, """
                {"name": "Mine"}""")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(write(bob, PATCH, "/api/categories/" + groceries, """
                {"name": "Mine"}""")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(write(bob, PATCH, "/api/counterparties/" + landlord, """
                {"name": "Mine"}""")).hasStatus(HttpStatus.NOT_FOUND);
        // The same answer as for an entry that doesn't exist, so it gives nothing away.
        JsonNode othersEntry = read(mvc.get().uri(entryUri).with(member(bob)).exchange(), JsonNode.class);
        assertThat(othersEntry.get("detail").asText()).isEqualTo("Journal entry %d not found.".formatted(entry));
        assertThat(othersEntry.get("title").asText()).isEqualTo("Not found");

        assertThat(body(mvc.get().uri("/api/entries").with(member(bob)).exchange()).get("totalElements").asLong())
                .isZero();
        assertThat(body(mvc.get().uri("/api/counterparties").with(member(bob)).exchange())).isEmpty();
        assertThat(body(mvc.get().uri("/api/accounts").with(member(bob)).exchange()).findValuesAsText("id"))
                .doesNotContainAnyElementsOf(alicesAccounts.findValuesAsText("id"));

        JsonNode unchanged = body(mvc.get().uri(entryUri).with(member(alice)).exchange());
        assertThat(unchanged.get("version").asInt()).isZero();
        assertThat(body(mvc.get().uri("/api/accounts").with(member(alice)).exchange())).isEqualTo(alicesAccounts);
        assertThat(body(mvc.get().uri("/api/counterparties").with(member(alice)).exchange()).findValuesAsText("name"))
                .containsExactly("Landlord");
    }

    @Test
    void theOpenApiDescriptionListsEveryEndpoint() throws IOException {
        MvcTestResult result = mvc.get().uri("/api/openapi").with(member(alice)).exchange();
        JsonNode openApi = body(result);
        // For reading, next to the build's other output.
        Files.writeString(Path.of("target", "openapi.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(openApi));

        Map<String, List<String>> operations = new LinkedHashMap<>();
        openApi.get("paths").properties().forEach(path -> operations.put(path.getKey(),
                StreamSupport.stream(((Iterable<String>) () -> path.getValue().fieldNames()).spliterator(), false)
                        .sorted().toList()));
        assertThat(operations).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("/api/me", List.of("get")),
                Map.entry("/api/accounts", List.of("get", "post")),
                Map.entry("/api/accounts/{id}", List.of("patch")),
                Map.entry("/api/categories", List.of("get", "post")),
                Map.entry("/api/categories/{id}", List.of("patch")),
                Map.entry("/api/counterparties", List.of("get", "post")),
                Map.entry("/api/counterparties/{id}", List.of("patch")),
                Map.entry("/api/entries", List.of("get", "post")),
                Map.entry("/api/entries/{id}", List.of("delete", "get", "put")),
                Map.entry("/api/reports/balances", List.of("get")),
                Map.entry("/api/reports/cash-flow", List.of("get")),
                Map.entry("/api/reports/counterparty-balances", List.of("get")),
                Map.entry("/api/reports/net-worth", List.of("get")),
                Map.entry("/api/reports/shared-settlement", List.of("get")),
                Map.entry("/api/reports/integrity", List.of("get")),
                Map.entry("/api/import", List.of("post")),
                Map.entry("/api/rates", List.of("get")),
                Map.entry("/api/rates/manual", List.of("delete", "get", "post")),
                Map.entry("/api/rates/manual/csv", List.of("post")),
                Map.entry("/api/settings", List.of("get", "put")),
                Map.entry("/api/demo-data", List.of("post")),
                Map.entry("/api/me/data", List.of("delete")),
                Map.entry("/api/family-ledgers", List.of("get", "post")),
                Map.entry("/api/family-ledgers/{ledgerId}", List.of("get", "patch")),
                Map.entry("/api/family-ledgers/{ledgerId}/split-rule", List.of("put")),
                Map.entry("/api/family-ledgers/{ledgerId}/members", List.of("get", "post")),
                Map.entry("/api/family-ledgers/{ledgerId}/members/{memberId}", List.of("delete", "patch")),
                Map.entry("/api/family-ledgers/{ledgerId}/categories", List.of("get", "post")),
                Map.entry("/api/family-ledgers/{ledgerId}/categories/{categoryId}", List.of("delete", "patch"))));

        // The user comes from the token, not from a parameter; a family ledger comes by its id in the path only.
        assertThat(openApi.get("paths").get("/api/accounts").get("get").has("parameters")).isFalse();
        assertThat(openApi.get("paths").get("/api/family-ledgers").get("post").has("parameters")).isFalse();
        assertThat(openApi.get("paths").get("/api/family-ledgers/{ledgerId}/members").get("get").get("parameters")
                .findValuesAsText("name")).containsExactly("ledgerId");
        JsonNode schemas = openApi.get("components").get("schemas");
        assertThat(schemas.get("PostingLine").get("properties").get("amount").get("type").asText()).isEqualTo("string");
        assertThat(schemas.get("EntryCommand").get("discriminator").get("propertyName").asText()).isEqualTo("kind");
    }

    private MvcTestResult write(String user, HttpMethod method, String uri, String body) {
        return mvc.method(method).uri(uri).with(member(user)).contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange();
    }

    private JsonNode body(MvcTestResult result) throws IOException {
        assertThat(result).hasStatusOk();
        return read(result, JsonNode.class);
    }

    private JsonNode created(MvcTestResult result) throws IOException {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, JsonNode.class);
    }

    private static long id(JsonNode array, String code) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(element -> element.get("code").asText().equals(code))
                .findFirst()
                .orElseThrow()
                .get("id")
                .asLong();
    }

    private long count(String sql, String userId) {
        return jdbc.sql(sql).param(userId).query(Long.class).single();
    }

    private List<String> codes(String table, String userId) {
        return jdbc.sql("SELECT code FROM " + table + " WHERE user_id = ?").param(userId).query(String.class).list();
    }

    private static List<String> codes(JsonNode seed) {
        return StreamSupport.stream(seed.spliterator(), false).map(element -> element.get("code").asText()).toList();
    }
}
