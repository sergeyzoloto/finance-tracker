package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import com.example.financetracker.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The REST API of the ledger through MockMvc, as members whom spring-security-test's jwt() signs in. Every user is
 * fresh, so each starts with the starter ledger and nothing else.
 */
abstract class LedgerApiTest extends IntegrationTest {

    /** Every table with rows that a user owns, with the FROM clause that selects the user's rows in it as t. */
    private static final Map<String, String> OWNED_ROWS = new LinkedHashMap<>();

    static {
        for (String table : List.of("user_settings", "account", "category", "counterparty", "journal_entry",
                "import_batch", "exchange_rate")) {
            OWNED_ROWS.put(table, "FROM " + table + " t WHERE t.user_id = ?");
        }
        OWNED_ROWS.put("posting", "FROM posting t JOIN journal_entry e ON e.id = t.entry_id WHERE e.user_id = ?");
        OWNED_ROWS.put("users", "FROM users t WHERE t.keycloak_id = ?");
        // The personal ledger and its member (V5), by the member's sub.
        OWNED_ROWS.put("ledger", "FROM ledger t JOIN ledger_member m ON m.ledger_id = t.id WHERE m.user_sub = ?");
        OWNED_ROWS.put("ledger_member", "FROM ledger_member t WHERE t.user_sub = ?");
    }

    @Autowired
    protected JdbcClient jdbc;

    protected static String newUser() {
        return UUID.randomUUID().toString();
    }

    protected MvcTestResult call(String user, HttpMethod method, String uri, String body) {
        var request = mvc.method(method).uri(uri).with(member(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    protected MvcTestResult get(String user, String uri) {
        return call(user, HttpMethod.GET, uri, null);
    }

    protected MvcTestResult post(String user, String uri, String body) {
        return call(user, HttpMethod.POST, uri, body);
    }

    protected MvcTestResult put(String user, String uri, String body) {
        return call(user, HttpMethod.PUT, uri, body);
    }

    protected MvcTestResult patch(String user, String uri, String body) {
        return call(user, HttpMethod.PATCH, uri, body);
    }

    protected MvcTestResult delete(String user, String uri) {
        return call(user, HttpMethod.DELETE, uri, null);
    }

    /** The body of a response with the status. */
    protected JsonNode body(MvcTestResult result, HttpStatus status) throws IOException {
        assertThat(result).hasStatus(status);
        return read(result, JsonNode.class);
    }

    protected JsonNode ok(MvcTestResult result) throws IOException {
        return body(result, HttpStatus.OK);
    }

    protected long accountId(String user, String code) throws IOException {
        return find(ok(get(user, "/api/accounts")), "code", code).get("id").asLong();
    }

    protected long categoryId(String user, String code) throws IOException {
        return find(ok(get(user, "/api/categories")), "code", code).get("id").asLong();
    }

    protected long newCounterparty(String user, String name) throws IOException {
        return body(post(user, "/api/counterparties", """
                {"name": "%s"}""".formatted(name)), HttpStatus.CREATED).get("id").asLong();
    }

    /** Creates the entry, which must be valid. */
    protected JsonNode newEntry(String user, String command) throws IOException {
        return body(post(user, "/api/entries", command), HttpStatus.CREATED);
    }

    /** An expense of {@code amount} from CASH under GROCERIES. */
    protected JsonNode newExpense(String user, String date, String amount, Long payeeId, String memo)
            throws IOException {
        return newEntry(user, """
                {"kind": "EXPENSE", "entryDate": "%s", "payeeId": %s, "memo": %s, "accountId": %d, "currency": "EUR",
                 "amount": "%s", "categoryId": %d}""".formatted(date, payeeId, memo == null ? null : '"' + memo + '"',
                accountId(user, "CASH"), amount, categoryId(user, "GROCERIES")));
    }

    /**
     * POST /api/import of the synthetic workbook in {@code src/test/resources/import/}: its accounts and categories,
     * and these transactions.
     *
     * @param dryRun the parameter's value, or null to leave it out
     */
    protected MvcTestResult importWorkbook(String user, byte[] transactions, String dryRun) throws IOException {
        var request = mvc.post().uri("/api/import").multipart()
                .file(new MockMultipartFile("accounts", "accounts.csv", "text/csv", fixture("accounts.csv")))
                .file(new MockMultipartFile("categories", "categories.csv", "text/csv", fixture("categories.csv")))
                .file(new MockMultipartFile("transactions", "transactions.csv", "text/csv", transactions))
                .with(member(user));
        if (dryRun != null) {
            request.param("dryRun", dryRun);
        }
        return request.exchange();
    }

    /** A file of the synthetic workbook. */
    protected static byte[] fixture(String name) throws IOException {
        return new ClassPathResource("import/" + name).getContentAsByteArray();
    }

    /** The file without one spreadsheet row; the header is row 1. No field of the fixture spans lines. */
    protected static byte[] withoutRow(byte[] csv, int row) {
        List<String> lines = new ArrayList<>(List.of(new String(csv, StandardCharsets.UTF_8).split("\r\n")));
        lines.remove(row - 1);
        return (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** How many rows the user has in each table of owned rows, by table. */
    protected Map<String, Long> rowsOf(String user) {
        Map<String, Long> rows = new LinkedHashMap<>();
        OWNED_ROWS.forEach((table, from) -> rows.put(table,
                jdbc.sql("SELECT count(*) " + from).param(user).query(Long.class).single()));
        return rows;
    }

    /**
     * A digest of the user's rows in each table of owned rows, by table, over every column: the digests are equal
     * exactly when the rows are, whatever changed in them.
     */
    protected Map<String, String> digestOf(String user) {
        Map<String, String> digests = new LinkedHashMap<>();
        OWNED_ROWS.forEach((table, from) -> digests.put(table, jdbc.sql(
                "SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) " + from)
                .param(user).query(String.class).single()));
        return digests;
    }

    /** The element of the array whose field has the value. */
    protected static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(element -> value.equals(element.get(field).asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No element with %s %s in %s".formatted(field, value, array)));
    }
}
