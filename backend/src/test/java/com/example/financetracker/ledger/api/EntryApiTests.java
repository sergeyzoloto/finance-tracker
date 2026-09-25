package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** /api/entries: commands in, entries with their postings out, and the list with its filters. */
class EntryApiTests extends LedgerApiTest {

    private final String user = newUser();

    @Test
    void buildsTheEntryThatTheKindNamesWithAmountsAsStrings() throws IOException {
        long cash = accountId(user, "CASH");
        long groceries = categoryId(user, "GROCERIES");
        long shop = newCounterparty(user, "Corner shop");

        MvcTestResult result = post(user, "/api/entries", """
                {"kind": "EXPENSE", "entryDate": "2026-09-01", "payeeId": %d, "memo": "Weekly shop",
                 "accountId": %d, "currency": "EUR", "amount": "12.5", "categoryId": %d}"""
                .formatted(shop, cash, groceries));

        JsonNode entry = body(result, HttpStatus.CREATED);
        assertThat(result).headers().hasValue(HttpHeaders.LOCATION, "/api/entries/" + entry.get("id").asLong());
        assertThat(entry.get("kind").asText()).isEqualTo("EXPENSE");
        assertThat(entry.get("version").asInt()).isZero();
        assertThat(entry.get("entryDate").asText()).isEqualTo("2026-09-01");
        assertThat(entry.get("payeeId").asLong()).isEqualTo(shop);
        assertThat(entry.get("memo").asText()).isEqualTo("Weekly shop");
        JsonNode postings = entry.get("postings");
        assertThat(postings).hasSize(2);
        assertThat(postings.get(0).get("accountId").asLong()).isEqualTo(cash);
        assertThat(postings.get(0).get("amount").isTextual()).isTrue();
        assertThat(postings.get(0).get("amount").asText()).isEqualTo("-12.50");
        assertThat(postings.get(1).get("accountId").asLong()).isEqualTo(accountId(user, "UNALLOCATED"));
        assertThat(postings.get(1).get("amount").asText()).isEqualTo("12.50");
        assertThat(postings.get(1).get("categoryId").asLong()).isEqualTo(groceries);

        assertThat(ok(get(user, "/api/entries/" + entry.get("id").asLong()))).isEqualTo(entry);
    }

    @Test
    void splitsASharedExpenseByTheDefaultRatio() throws IOException {
        JsonNode entry = newEntry(user, """
                {"kind": "SHARED_EXPENSE", "entryDate": "2026-09-02", "accountId": %d, "currency": "EUR",
                 "total": "10.01", "categoryId": %d}""".formatted(accountId(user, "CURRENT_ACCOUNT"),
                categoryId(user, "GROCERIES")));

        // Rule 7: other = round(10.01 × 0.50, 2) = 5.01, own = 5.00.
        assertThat(entry.get("postings").findValuesAsText("amount")).containsExactly("-10.01", "5.00", "5.01");
        assertThat(entry.get("postings").get(2).get("accountId").asLong()).isEqualTo(accountId(user, "FAMILY_DEBT"));
    }

    @Test
    void anUnbalancedManualEntryIsRefusedWith422AndAReadableMessage() throws IOException {
        MvcTestResult result = post(user, "/api/entries", """
                {"kind": "MANUAL", "entryDate": "2026-09-03", "memo": "Correction", "postings": [
                  {"accountId": %d, "currency": "EUR", "amount": "100.00"},
                  {"accountId": %d, "currency": "EUR", "amount": "-90.00"}]}"""
                .formatted(accountId(user, "CASH"), accountId(user, "RESERVE")));

        JsonNode problem = body(result, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/problem+json");
        assertThat(problem.get("status").asInt()).isEqualTo(422);
        assertThat(problem.get("detail").asText())
                .isEqualTo("The postings in EUR do not balance: debits 100.00, credits 90.00, difference 10.00.");
        assertThat(texts(problem.get("violations")))
                .containsExactly("the postings in EUR do not balance: debits 100.00, credits 90.00, difference 10.00");
        assertThat(problem.has("trace")).isFalse();
        assertThat(ok(get(user, "/api/entries")).get("totalElements").asLong()).isZero();
    }

    @Test
    void aCommandThatCantBeBuiltIsRefusedWith422ListingEveryProblem() throws IOException {
        JsonNode problem = body(post(user, "/api/entries", """
                {"kind": "EXPENSE", "entryDate": "2026-09-03", "currency": "EUR", "amount": "0"}"""),
                HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(texts(problem.get("violations")))
                .containsExactly("the account is missing", "the amount must not be zero", "the category is missing");
    }

    @Test
    void anotherUsersAccountIsReportedAsMissing() throws IOException {
        String other = newUser();

        JsonNode problem = body(post(user, "/api/entries", """
                {"kind": "TRANSFER", "entryDate": "2026-09-03", "fromAccountId": %d, "toAccountId": %d,
                 "currency": "EUR", "amount": "5.00"}""".formatted(accountId(user, "CASH"),
                accountId(other, "CASH"))), HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(texts(problem.get("violations")))
                .containsExactly("posting 2: account %d does not exist".formatted(accountId(other, "CASH")));
    }

    @Test
    void aBodyThatIsNoEntryCommandIsRefusedWith400NamingTheField() throws IOException {
        JsonNode unknownKind = body(post(user, "/api/entries", """
                {"kind": "GIFT", "entryDate": "2026-09-03"}"""), HttpStatus.BAD_REQUEST);
        JsonNode noKind = body(post(user, "/api/entries", """
                {"entryDate": "2026-09-03"}"""), HttpStatus.BAD_REQUEST);
        JsonNode badAmount = body(post(user, "/api/entries", """
                {"kind": "MANUAL", "entryDate": "2026-09-03", "postings": [
                  {"accountId": 1, "currency": "EUR", "amount": "12,50"}]}"""), HttpStatus.BAD_REQUEST);
        JsonNode badDate = body(post(user, "/api/entries", """
                {"kind": "MANUAL", "entryDate": "03/09/2026", "postings": []}"""), HttpStatus.BAD_REQUEST);
        JsonNode notJson = body(post(user, "/api/entries", "{"), HttpStatus.BAD_REQUEST);

        assertThat(unknownKind.get("errors").get(0).get("field").asText()).isEqualTo("kind");
        assertThat(unknownKind.get("errors").get(0).get("message").asText()).isEqualTo(
                "'GIFT' is unknown; it must be one of EXPENSE, INCOME, TRANSFER, SHARED_EXPENSE, LOAN_GIVEN, "
                        + "LOAN_REPAID, CURRENCY_EXCHANGE, OPENING_BALANCE, MANUAL");
        assertThat(noKind.get("errors").get(0).get("message").asText()).startsWith("is missing; it must be one of");
        assertThat(badAmount.get("errors").get(0).get("field").asText()).isEqualTo("postings[0].amount");
        assertThat(badAmount.get("errors").get(0).get("message").asText())
                .isEqualTo("must be a decimal number, such as \"12.50\"");
        assertThat(badDate.get("errors").get(0).get("field").asText()).isEqualTo("entryDate");
        assertThat(notJson.get("detail").asText()).isEqualTo("The request body is not valid JSON.");
        for (JsonNode problem : List.of(unknownKind, noKind, badAmount, badDate, notJson)) {
            assertThat(problem.toString()).doesNotContain("com.example", "Exception", "trace");
        }
    }

    @Test
    void aStaleVersionIsRefusedWith409() throws IOException {
        JsonNode entry = newExpense(user, "2026-09-04", "10.00", null, "Lunch");
        String uri = "/api/entries/" + entry.get("id").asLong();
        String changed = """
                {"kind": "EXPENSE", "entryDate": "2026-09-04", "memo": "Dinner", "accountId": %d, "currency": "EUR",
                 "amount": "25.00", "categoryId": %d}""".formatted(accountId(user, "CASH"),
                categoryId(user, "EATING_OUT"));

        JsonNode updated = ok(put(user, uri + "?version=0", changed));
        assertThat(updated.get("version").asInt()).isEqualTo(1);
        assertThat(updated.get("memo").asText()).isEqualTo("Dinner");

        JsonNode stale = body(put(user, uri + "?version=0", changed), HttpStatus.CONFLICT);
        assertThat(stale.get("detail").asText()).isEqualTo(
                "Journal entry %d has changed since version 0. Reload it and try again.".formatted(entry.get("id").asLong()));
        assertThat(delete(user, uri + "?version=0")).hasStatus(HttpStatus.CONFLICT);
        assertThat(ok(get(user, uri)).get("memo").asText()).isEqualTo("Dinner");

        assertThat(put(user, uri, changed)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(delete(user, uri)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(delete(user, uri + "?version=1")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(user, uri)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void pagesAreSortedByDateThenIdDescending() throws IOException {
        long first = newExpense(user, "2026-01-10", "1.00", null, null).get("id").asLong();
        long second = newExpense(user, "2026-01-12", "2.00", null, null).get("id").asLong();
        long third = newExpense(user, "2026-01-11", "3.00", null, null).get("id").asLong();
        long fourth = newExpense(user, "2026-01-12", "4.00", null, null).get("id").asLong();
        long fifth = newExpense(user, "2026-01-09", "5.00", null, null).get("id").asLong();

        JsonNode page0 = ok(get(user, "/api/entries?size=2"));
        JsonNode page1 = ok(get(user, "/api/entries?size=2&page=1"));
        JsonNode page2 = ok(get(user, "/api/entries?size=2&page=2"));
        JsonNode page3 = ok(get(user, "/api/entries?size=2&page=3"));

        assertThat(ids(page0)).containsExactly(fourth, second);
        assertThat(ids(page1)).containsExactly(third, first);
        assertThat(ids(page2)).containsExactly(fifth);
        assertThat(ids(page3)).isEmpty();
        assertThat(page0.get("page").asInt()).isZero();
        assertThat(page0.get("size").asInt()).isEqualTo(2);
        assertThat(page0.get("totalElements").asLong()).isEqualTo(5);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page3.get("totalElements").asLong()).isEqualTo(5);
        // Every entry comes with its postings.
        assertThat(page0.get("content").get(0).get("postings").findValuesAsText("amount")).containsExactly("-4.00", "4.00");
        assertThat(ids(ok(get(user, "/api/entries")))).containsExactly(fourth, second, third, first, fifth);
    }

    @Test
    void pagingParametersAreValidated() throws IOException {
        JsonNode problem = body(get(user, "/api/entries?page=-1&size=500"), HttpStatus.BAD_REQUEST);

        assertThat(problem.get("errors").findValuesAsText("field")).containsExactlyInAnyOrder("page", "size");
        assertThat(body(get(user, "/api/entries?from=2026-02-01&to=2026-01-01"), HttpStatus.BAD_REQUEST)
                .get("detail").asText()).isEqualTo("'from' must not be after 'to'.");
        assertThat(body(get(user, "/api/entries?from=yesterday"), HttpStatus.BAD_REQUEST)
                .get("errors").get(0).get("field").asText()).isEqualTo("from");
    }

    @Test
    void filtersByDateAccountCategoryCounterpartyAndText() throws IOException {
        long bakery = newCounterparty(user, "Bakery Zoet");
        long friend = newCounterparty(user, "Friend");
        long early = newExpense(user, "2026-03-01", "3.00", bakery, "Bread 100% rye").get("id").asLong();
        long groceries = newExpense(user, "2026-03-15", "20.00", null, "Market_day").get("id").asLong();
        long salary = newEntry(user, """
                {"kind": "INCOME", "entryDate": "2026-03-25", "accountId": %d, "currency": "EUR", "amount": "2000",
                 "categoryId": %d, "memo": "March salary"}""".formatted(accountId(user, "CURRENT_ACCOUNT"),
                categoryId(user, "SALARY"))).get("id").asLong();
        long loan = newEntry(user, """
                {"kind": "LOAN_GIVEN", "entryDate": "2026-04-02", "fromAccountId": %d, "counterpartyId": %d,
                 "currency": "EUR", "amount": "50"}""".formatted(accountId(user, "CASH"), friend)).get("id").asLong();

        assertThat(search("from", "2026-03-15", "to", "2026-03-31")).containsExactly(salary, groceries);
        assertThat(search("to", "2026-03-01")).containsExactly(early);
        assertThat(search("accountId", String.valueOf(accountId(user, "CASH")))).containsExactly(loan, groceries, early);
        assertThat(search("categoryId", String.valueOf(categoryId(user, "GROCERIES")))).containsExactly(groceries, early);
        // As the payee, and as the counterparty of a posting.
        assertThat(search("counterpartyId", String.valueOf(bakery))).containsExactly(early);
        assertThat(search("counterpartyId", String.valueOf(friend))).containsExactly(loan);
        // In the memo or the payee's name, in any case; LIKE's wildcards are plain characters.
        assertThat(search("q", "SALARY")).containsExactly(salary);
        assertThat(search("q", "zoet")).containsExactly(early);
        assertThat(search("q", "100%")).containsExactly(early);
        assertThat(search("q", "1%y")).isEmpty();
        assertThat(search("q", "e_d")).isEmpty();
        assertThat(search("q", "t_day")).containsExactly(groceries);
        assertThat(search("q", "  ")).containsExactly(loan, salary, groceries, early);
        assertThat(search("accountId", String.valueOf(accountId(user, "CASH")), "from", "2026-03-10", "q", "market"))
                .containsExactly(groceries);
        // Another user's ids match nothing of this user's.
        assertThat(search("accountId", String.valueOf(accountId(newUser(), "CASH")))).isEmpty();
    }

    /** The ids of the user's entries that the query parameters, given as name and value pairs, find. */
    private List<Long> search(String... params) throws IOException {
        var request = mvc.get().uri("/api/entries").with(member(user));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return ids(ok(request.exchange()));
    }

    private static List<Long> ids(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false).map(e -> e.get("id").asLong()).toList();
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
