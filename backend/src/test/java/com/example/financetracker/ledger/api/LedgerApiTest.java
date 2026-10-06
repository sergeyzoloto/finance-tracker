package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
        // The personal ledger and its member (V5), and the family ledgers and memberships (V6), by the member's sub.
        OWNED_ROWS.put("ledger", "FROM ledger t JOIN ledger_member m ON m.ledger_id = t.id WHERE m.user_sub = ?");
        OWNED_ROWS.put("ledger_member", "FROM ledger_member t WHERE t.user_sub = ?");
        // What the user's family ledgers hold beside them: every member, and the family categories (V6).
        OWNED_ROWS.put("family ledger_member", "FROM ledger_member t WHERE t.ledger_type = 'SHARED' AND t.ledger_id IN "
                + "(SELECT ledger_id FROM ledger_member WHERE user_sub = ?)");
        OWNED_ROWS.put("family category", "FROM category t WHERE t.ledger_id IN "
                + "(SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'SHARED')");
        // Their records, shares, links and journal (V7).
        for (String table : List.of("family_record", "family_share", "family_record_change")) {
            OWNED_ROWS.put("family " + table, "FROM " + table + " t WHERE t.ledger_id IN "
                    + "(SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'SHARED')");
        }
        OWNED_ROWS.put("family family_entry_link", "FROM family_entry_link t WHERE t.family_ledger_id IN "
                + "(SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'SHARED')");
        // Their invites (V9).
        OWNED_ROWS.put("family ledger_invite", "FROM ledger_invite t WHERE t.ledger_id IN "
                + "(SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'SHARED')");
    }

    /** The tables of {@link #OWNED_ROWS} that only a user in a family ledger has rows in. */
    protected static final Set<String> FAMILY_ROWS = Set.of("family ledger_member", "family category",
            "family family_record", "family family_share", "family family_record_change", "family family_entry_link",
            "family ledger_invite");

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

    /** A new family ledger of the user's, which must be valid. */
    protected JsonNode newFamily(String user, String request) throws IOException {
        return body(post(user, "/api/family-ledgers", request), HttpStatus.CREATED);
    }

    /**
     * A membership of a user with an account in a family ledger, written with plain SQL: no code adds one before F5's
     * invites. Under a CUSTOM split rule the member's share is 0, as for a member an owner adds.
     *
     * @return the membership's id
     */
    protected long join(long familyId, String sub, String displayName, String role, LocalDate joinDate) {
        return jdbc.sql("""
                INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date,
                                           share_bp)
                SELECT id, 'SHARED', ?, ?, ?, 'ACTIVE', ?, CASE split_rule WHEN 'CUSTOM' THEN 0 END
                FROM ledger WHERE id = ? RETURNING id""")
                .params(sub, displayName, role, joinDate, familyId).query(Long.class).single();
    }

    /**
     * E1's family report against the balances, and E3's check (F6c; ADR 0003 topic J, "F6c plan"), as {@code reader}
     * reads the family ledger: in every row the members' shares, and what they paid or received, add up to its total;
     * each member's totals are the sums of their rows, and their net over every record is their balance; and for each
     * member with an account in {@code personal} (their sub, and their join date), their personal cash flow's lines of
     * the family's categories are, month by month and category by category, their shares in the report of the records
     * from their join date. Each check holds in each currency of the report (D-45, F8a), and the deprecated fields are
     * the main currency's.
     *
     * @return the report of every record, as {@code reader} reads it
     */
    protected JsonNode checkFamilyReport(String reader, long familyId, Map<String, LocalDate> personal)
            throws IOException {
        String uri = "/api/family-ledgers/" + familyId;
        JsonNode report = ok(get(reader, uri + "/report"));
        JsonNode balancesAnswer = ok(get(reader, uri + "/balances"));
        // The deprecated fields are the main currency's section (F8a).
        JsonNode main = report.get("byCurrency").get(0);
        assertThat(main.get("currency").asText()).isEqualTo(report.get("currency").asText());
        assertThat(report.get("rows")).isEqualTo(main.get("rows"));
        assertThat(report.get("totals")).isEqualTo(main.get("totals"));
        assertThat(balancesAnswer.get("members")).isEqualTo(balancesAnswer.get("byCurrency").get(0).get("members"));
        Map<String, Map<Long, String>> balances = new HashMap<>();
        for (JsonNode inCurrency : balancesAnswer.get("byCurrency")) {
            Map<Long, String> members = new HashMap<>();
            inCurrency.get("members").forEach(member -> members.put(member.get("memberId").asLong(),
                    member.get("balance").asText()));
            balances.put(inCurrency.get("currency").asText(), members);
        }
        for (JsonNode section : report.get("byCurrency")) {
            String currency = section.get("currency").asText();
            Map<Long, BigDecimal[]> sums = new HashMap<>();
            for (JsonNode row : section.get("rows")) {
                String what = row.get("month").asText() + " " + row.get("categoryName").asText() + " " + currency;
                int at = row.get("categoryType").asText().equals("EXPENSE") ? 0 : 2;
                BigDecimal shares = BigDecimal.ZERO;
                BigDecimal paid = BigDecimal.ZERO;
                for (JsonNode contribution : row.get("members")) {
                    BigDecimal share = new BigDecimal(contribution.get("share").asText());
                    BigDecimal itsPaid = new BigDecimal(contribution.get("paid").asText());
                    shares = shares.add(share);
                    paid = paid.add(itsPaid);
                    BigDecimal[] sum = sums.computeIfAbsent(contribution.get("memberId").asLong(),
                            id -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                                    BigDecimal.ZERO});
                    sum[at] = sum[at].add(share);
                    sum[at + 1] = sum[at + 1].add(itsPaid);
                }
                assertThat(shares).as("the shares of " + what).isEqualByComparingTo(row.get("total").asText());
                assertThat(paid).as("what was paid or received of " + what)
                        .isEqualByComparingTo(row.get("total").asText());
            }
            // Over every record, each member's net in a currency is their balance in it (D-1, D-45).
            Map<Long, String> inCurrency = balances.getOrDefault(currency, Map.of());
            assertThat(section.get("totals")).hasSize(balancesAnswer.get("members").size());
            for (JsonNode total : section.get("totals")) {
                long member = total.get("memberId").asLong();
                BigDecimal[] sum = sums.getOrDefault(member,
                        new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                String[] fields = {"expenseShares", "expensesPaid", "incomeShares", "incomesReceived"};
                for (int i = 0; i < fields.length; i++) {
                    assertThat(new BigDecimal(total.get(fields[i]).asText()))
                            .as(fields[i] + " of member " + member + " in " + currency).isEqualByComparingTo(sum[i]);
                }
                BigDecimal net = sum[0].subtract(sum[1]).subtract(sum[2]).add(sum[3])
                        .subtract(new BigDecimal(total.get("settlementsPaid").asText()))
                        .add(new BigDecimal(total.get("settlementsReceived").asText()));
                assertThat(new BigDecimal(total.get("net").asText()))
                        .as("the net of member " + member + " in " + currency).isEqualByComparingTo(net)
                        .isEqualByComparingTo(inCurrency.getOrDefault(member, "0"));
            }
        }
        // D-40: the family's categories, so that a member's own entries in one (merged in by D-11) are added to
        // their share below; the family report alone only has a row where a record exists.
        Map<Long, String> familyCategoryName = new HashMap<>();
        Map<Long, String> familyCategoryType = new HashMap<>();
        for (JsonNode category : ok(get(reader, uri + "/categories"))) {
            familyCategoryName.put(category.get("id").asLong(), category.get("name").asText());
            familyCategoryType.put(category.get("id").asLong(), category.get("type").asText());
        }
        for (Map.Entry<String, LocalDate> member : personal.entrySet()) {
            String user = member.getKey();
            JsonNode own = ok(get(user, uri + "/report?from=" + member.getValue()));
            long me = StreamSupport.stream(own.get("members").spliterator(), false)
                    .filter(m -> m.get("you").asBoolean()).findFirst().orElseThrow().get("memberId").asLong();
            // Month, category and currency: the personal cash flow keeps each currency apart, as the report does.
            Map<String, BigDecimal> shares = new TreeMap<>();
            for (JsonNode section : own.get("byCurrency")) {
                for (JsonNode row : section.get("rows")) {
                    for (JsonNode contribution : row.get("members")) {
                        BigDecimal share = new BigDecimal(contribution.get("share").asText());
                        if (contribution.get("memberId").asLong() == me && share.signum() != 0) {
                            shares.merge(row.get("month").asText() + " " + row.get("categoryName").asText() + " "
                                    + section.get("currency").asText(), share, BigDecimal::add);
                        }
                    }
                }
            }
            // D-40: the line shows the whole category, so add the member's own entries in it (not posted by the
            // family budget) on top of their share, with the cash flow's sign convention (positive for both types).
            for (Map.Entry<Long, String> category : familyCategoryName.entrySet()) {
                String type = familyCategoryType.get(category.getKey());
                String path = "/api/entries?categoryId=" + category.getKey() + "&from=" + member.getValue()
                        + "&size=200";
                for (JsonNode entry : ok(get(user, path)).get("content")) {
                    if (entry.get("family").isNull()) {
                        String month = YearMonth.from(LocalDate.parse(entry.get("entryDate").asText())).toString();
                        for (JsonNode posting : entry.get("postings")) {
                            if (posting.path("categoryId").asLong() == category.getKey()) {
                                BigDecimal amount = new BigDecimal(posting.get("amount").asText());
                                BigDecimal signed = type.equals("EXPENSE") ? amount : amount.negate();
                                shares.merge(month + " " + category.getValue() + " "
                                        + posting.get("currency").asText(), signed, BigDecimal::add);
                            }
                        }
                    }
                }
            }
            Map<String, String> expected = new TreeMap<>();
            shares.forEach((key, value) -> expected.put(key, value.stripTrailingZeros().toPlainString()));
            Map<String, String> cashFlow = new TreeMap<>();
            for (JsonNode row : ok(get(user, "/api/reports/cash-flow?from=" + member.getValue() + "&to=2100-12-31"))) {
                if (row.path("familyLedgerId").asLong() == familyId) {
                    cashFlow.merge(row.get("month").asText() + " " + row.get("categoryName").asText() + " "
                            + row.get("currency").asText(),
                            new BigDecimal(row.get("total").asText()).stripTrailingZeros().toPlainString(),
                            (a, b) -> new BigDecimal(a).add(new BigDecimal(b)).stripTrailingZeros().toPlainString());
                }
            }
            assertThat(cashFlow).as("E3 (D-40): the family's lines of the personal cash flow of member " + me)
                    .isEqualTo(expected);
        }
        return report;
    }

    /** The element of the array whose field has the value. */
    protected static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(element -> value.equals(element.get(field).asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No element with %s %s in %s".formatted(field, value, array)));
    }
}
