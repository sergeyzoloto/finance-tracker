package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.financetracker.transaction.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class ApiTests extends IntegrationTest {

    private final String alice = token(UUID.randomUUID().toString());
    private final String bob = token(UUID.randomUUID().toString());

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
        String category = """
                {"name": "Rent", "type": "EXPENSE"}""";
        String transaction = """
                {"categoryId": 1, "amount": 1.00, "occurredOn": "2026-01-01"}""";
        List<Object[]> endpoints = List.of(
                new Object[] {GET, "/api/categories", null},
                new Object[] {POST, "/api/categories", category},
                new Object[] {GET, "/api/categories/1", null},
                new Object[] {PUT, "/api/categories/1", category},
                new Object[] {DELETE, "/api/categories/1", null},
                new Object[] {GET, "/api/transactions?from=2026-01-01&to=2026-01-31&categoryId=1", null},
                new Object[] {POST, "/api/transactions", transaction},
                new Object[] {GET, "/api/transactions/1", null},
                new Object[] {PUT, "/api/transactions/1", transaction},
                new Object[] {DELETE, "/api/transactions/1", null},
                new Object[] {GET, "/api/dashboard/summary?from=2026-01-01&to=2026-01-31", null});

        SoftAssertions softly = new SoftAssertions();
        invalidTokens.forEach((kind, token) -> endpoints.forEach(e -> softly
                .assertThat(request((HttpMethod) e[0], (String) e[1], token, (String) e[2]).getResponse().getStatus())
                .as("%s %s with %s token", e[0], e[1], kind)
                .isEqualTo(401)));
        softly.assertAll();
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE keycloak_id = ?").param(subject).query(Long.class).single())
                .as("rejected tokens must not provision users").isZero();
    }

    @Test
    void usersNeverSeeOrChangeEachOthersRows() throws IOException {
        long category = createCategory(alice, "Rent", "EXPENSE");
        long transaction = createTransaction(alice, category, "12.00", "2026-01-15");
        String categoryUri = "/api/categories/" + category;
        String transactionUri = "/api/transactions/" + transaction;

        assertThat(body(request(GET, "/api/categories", bob, null))).isEmpty();
        assertThat(body(request(GET, "/api/transactions", bob, null))).isEmpty();
        assertThat(body(request(GET, "/api/transactions?categoryId=" + category, bob, null))).isEmpty();
        assertThat(request(GET, categoryUri, bob, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(request(PUT, categoryUri, bob, """
                {"name": "Hacked", "type": "INCOME"}""")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(request(DELETE, categoryUri, bob, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(request(GET, transactionUri, bob, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(request(PUT, transactionUri, bob, """
                {"categoryId": %d, "amount": 99.00, "occurredOn": "2026-01-15"}""".formatted(category)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(request(DELETE, transactionUri, bob, null)).hasStatus(HttpStatus.NOT_FOUND);
        // Bob can't file his own transaction under Alice's category either.
        assertThat(request(POST, "/api/transactions", bob, """
                {"categoryId": %d, "amount": 1.00, "occurredOn": "2026-01-15"}""".formatted(category)))
                .hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(body(request(GET, categoryUri, alice, null)).get("name").asText()).isEqualTo("Rent");
        assertThat(body(request(GET, transactionUri, alice, null)).get("amount").decimalValue()).isEqualByComparingTo("12.00");
    }

    @Test
    void firstRequestProvisionsTheUserExactlyOnce() {
        String subject = UUID.randomUUID().toString();
        String token = token(subject);

        assertThat(request(GET, "/api/categories", token, null)).hasStatusOk();
        assertThat(request(GET, "/api/transactions", token, null)).hasStatusOk();

        var users = jdbc.sql("SELECT email, display_name FROM users WHERE keycloak_id = ?").param(subject).query().listOfRows();
        assertThat(users).containsExactly(Map.of("email", subject + "@example.com", "display_name", "User " + subject));
    }

    @Test
    void transactionListFiltersByInclusiveDateRangeAndCategory() throws IOException {
        long rent = createCategory(alice, "Rent", "EXPENSE");
        long food = createCategory(alice, "Food", "EXPENSE");
        createTransaction(alice, rent, "1.00", "2025-12-31");
        long first = createTransaction(alice, rent, "2.00", "2026-01-01");
        long last = createTransaction(alice, rent, "3.00", "2026-01-31");
        createTransaction(alice, rent, "4.00", "2026-02-01");
        long otherCategory = createTransaction(alice, food, "5.00", "2026-01-15");

        JsonNode january = body(request(GET, "/api/transactions?from=2026-01-01&to=2026-01-31", alice, null));
        JsonNode januaryRent = body(request(GET, "/api/transactions?from=2026-01-01&to=2026-01-31&categoryId=" + rent, alice, null));

        assertThat(january.findValuesAsText("id")).containsExactly(id(last), id(otherCategory), id(first));
        assertThat(january.findValuesAsText("occurredOn")).containsExactly("2026-01-31", "2026-01-15", "2026-01-01");
        assertThat(januaryRent.findValuesAsText("id")).containsExactly(id(last), id(first));
        assertThat(body(request(GET, "/api/transactions", alice, null))).hasSize(5);
    }

    @Test
    void crudRoundTrip() throws IOException {
        long category = createCategory(alice, "Groceries", "EXPENSE");
        assertThat(request(PUT, "/api/categories/" + category, alice, """
                {"name": "Food", "type": "EXPENSE"}""")).hasStatusOk();
        long salary = createCategory(alice, "Salary", "INCOME");
        long transaction = createTransaction(alice, category, "10.00", "2026-01-01");

        assertThat(request(PUT, "/api/transactions/" + transaction, alice, """
                {"categoryId": %d, "amount": 25.5, "occurredOn": "2026-01-02", "note": "moved"}""".formatted(salary)))
                .hasStatusOk();
        var updated = request(GET, "/api/transactions/" + transaction, alice, null);
        assertThat(updated).hasStatusOk();
        assertThat(updated.getResponse().getContentAsString()).doesNotContain("userId");
        assertThat(read(updated, Transaction.class)).isEqualTo(
                new Transaction(transaction, null, salary, new BigDecimal("25.50"), LocalDate.of(2026, 1, 2), "moved"));
        assertThat(body(request(GET, "/api/categories/" + category, alice, null)).get("name").asText()).isEqualTo("Food");

        assertThat(request(DELETE, "/api/categories/" + salary, alice, null)).hasStatus(HttpStatus.CONFLICT);
        assertThat(request(DELETE, "/api/transactions/" + transaction, alice, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(request(DELETE, "/api/categories/" + salary, alice, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(request(GET, "/api/transactions/" + transaction, alice, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(request(GET, "/api/categories/" + salary, alice, null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCategorysTypeIsFixedAfterCreation() throws IOException {
        long rent = createCategory(alice, "Rent", "EXPENSE");
        String uri = "/api/categories/" + rent;
        long transaction = createTransaction(alice, rent, "12.00", "2026-01-15");

        assertThat(request(PUT, uri, alice, """
                {"name": "Rent", "type": "INCOME"}""")).hasStatus(HttpStatus.CONFLICT);
        // Renaming, with the type left as it stands, still works.
        assertThat(request(PUT, uri, alice, """
                {"name": "Mortgage", "type": "EXPENSE"}""")).hasStatusOk();

        JsonNode category = body(request(GET, uri, alice, null));
        assertThat(category.get("name").asText()).isEqualTo("Mortgage");
        assertThat(category.get("type").asText()).isEqualTo("EXPENSE");
        // The transaction filed under it keeps its meaning.
        assertThat(body(request(GET, "/api/transactions/" + transaction, alice, null)).get("categoryId").asLong())
                .isEqualTo(rent);
    }

    private JsonNode body(MvcTestResult result) throws IOException {
        assertThat(result).hasStatusOk();
        return read(result, JsonNode.class);
    }

    private static String id(long id) {
        return String.valueOf(id);
    }
}
