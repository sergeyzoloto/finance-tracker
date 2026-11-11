package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.Answers;
import com.example.financetracker.ledger.FamilyPayments;
import com.example.financetracker.ledger.demo.DemoFamily;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.fasterxml.jackson.databind.JsonNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * With the feature switch off (D-25), as in production until F7: every family endpoint answers 404 exactly like an
 * unknown path, since it doesn't exist, the personal endpoints work as before, and {@code /api/me} tells the frontend.
 * The family endpoints come from the own mappings of {@link FamilyLedgerController}, {@link FamilyRecordController},
 * {@link FamilyPaymentController} (F4c, a payer's payment entry) and {@link FamilyInviteController} (F5, invites), so
 * a new one can't be left out. The one change of
 * the personal endpoints: UNALLOCATED can't be archived (F4a, D-8).
 */
@TestPropertySource(properties = FamilySwitch.PROPERTY + "=false")
class FamilySwitchOffApiTests extends LedgerApiTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactions;

    private final String user = newUser();

    @Test
    void everyFamilyEndpointAnswers404LikeAnUnknownPath() throws IOException {
        List<String[]> endpoints = familyEndpoints();
        // 22 until F4c; POST /settlements since F4d; GET /conversion from F4e until F8b; six for invites since F5;
        // leaving (DELETE /members/me), making an owner and what "Delete all my data" touches since F6a; the report
        // since F6c; the member's own PATCH /me (their payees) since UX-9.
        assertThat(endpoints).hasSize(34);
        assertThat(context.getBeanNamesForType(FamilyLedgerController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FamilyRecordController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FamilyPaymentController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FamilyInviteController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FamilyMembershipsController.class)).isEmpty();
        // Without it, a payment entry's deletion answers 409 as before (EntryService).
        assertThat(context.getBeanNamesForType(FamilyPayments.class)).isEmpty();
        String body = """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna", "rule": "EQUAL", "code": "RENT",
                 "type": "EXPENSE", "date": "2026-09-01", "categoryId": 1, "amount": "1", "payerMemberId": 1,
                 "accountId": 1, "kind": "NEW_MEMBER", "token": "AAAA"}""";

        SoftAssertions softly = new SoftAssertions();
        for (String[] endpoint : endpoints) {
            HttpMethod method = HttpMethod.valueOf(endpoint[0]);
            String path = endpoint[1];
            String unknown = path.replace("/api/family-ledgers", "/api/no-such-thing")
                    .replace("/family-payment", "/no-such-thing").replace("/api/invites", "/api/no-such-thing")
                    .replace("/api/me/family-memberships", "/api/me/no-such-thing");
            MvcTestResult answer = call(user, method, path, body);
            MvcTestResult unknownAnswer = call(user, method, unknown, body);
            softly.assertThat(answer.getResponse().getStatus()).as("%s %s", method, path).isEqualTo(404);
            softly.assertThat(Answers.rawText(answer).replace(path.substring(1), "PATH")
                    .replace(path, "PATH")).as("%s %s", method, path)
                    .isEqualTo(Answers.rawText(unknownAnswer).replace(unknown.substring(1), "PATH")
                            .replace(unknown, "PATH"));
        }
        softly.assertAll();
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_member WHERE user_sub = ? AND ledger_type = 'SHARED'")
                .param(user).query(Long.class).single()).isZero();
    }

    @Test
    void theRestOfTheApiWorksAndMeSaysTheSwitchIsOff() throws IOException {
        assertThat(ok(get(user, "/api/me")).get("features").get("familyLedgers").asBoolean()).isFalse();
        assertThat(ok(get(user, "/api/accounts"))).isNotEmpty();
        assertThat(post(user, "/api/categories", """
                {"code": "RENT", "name": "Rent", "type": "EXPENSE"}""")).hasStatus(HttpStatus.CREATED);
        // UNALLOCATED can be renamed, but no longer archived (F4a): a family budget posts its shares there (D-8).
        long unallocated = accountId(user, "UNALLOCATED");
        assertThat(ok(patch(user, "/api/accounts/" + unallocated, """
                {"name": "Free money"}""")).get("name").asText()).isEqualTo("Free money");
        assertThat(body(patch(user, "/api/accounts/" + unallocated, """
                {"archived": true}"""), HttpStatus.CONFLICT).get("detail").asText())
                .isEqualTo("UNALLOCATED can be renamed, but not archived: a family budget posts its shares there.");
        assertThat(ok(patch(user, "/api/accounts/" + accountId(user, "RESERVE"), """
                {"archived": true}""")).get("archived").asBoolean()).isTrue();
        // No personal ledger gets a family budget's account.
        assertThat(ok(get(user, "/api/accounts")).findValuesAsText("code"))
                .noneMatch(code -> code.startsWith("FAMILY_DEBT_") || code.equals("UNSPECIFIED_PAYMENTS"));
        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
    }

    /**
     * For a user without family budgets every personal answer reads as before F4a's parts 5 and 6: the family fields of
     * categories, cash flow rows and the integrity check are left out, not null, and the demo passes the check.
     */
    @Test
    void personalAnswersHoldNoFamilyFieldsForAUserWithoutFamilies() throws IOException {
        ok(post(user, "/api/demo-data", null));
        List<String> reads = List.of("/api/categories", "/api/accounts", "/api/counterparties", "/api/entries?size=200",
                "/api/reports/cash-flow?from=2026-01-01&to=2026-12-31",
                "/api/reports/cash-flow?from=2026-01-01&to=2026-12-31&currency=BASE",
                "/api/reports/balances?asOf=2026-12-31", "/api/reports/balances?asOf=2026-12-31&currency=BASE",
                "/api/reports/counterparty-balances?accountCode=LOANS_ASSET&currency=BASE",
                "/api/reports/net-worth?asOf=2026-12-31",
                "/api/reports/integrity");
        SoftAssertions softly = new SoftAssertions();
        for (String read : reads) {
            JsonNode answer = ok(get(user, read));
            for (String word : List.of("familyLedgerId", "familyLedgerName", "debtBalance", "familyBalance",
                    "FAMILY_DEBT_", "UNSPECIFIED_PAYMENTS")) {
                softly.assertThat(Answers.mentions(answer, word)).as("%s mentions %s", read, word).isFalse();
            }
        }
        softly.assertAll();
        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
        assertThat(ok(get(user, "/api/entries?size=200")).get("content").findValues("family"))
                .allSatisfy(family -> assertThat(family.isNull()).isTrue());
    }

    /**
     * UX-1: with the switch off, a debt account to a family budget (from a time the switch was on, written here with
     * plain SQL) names no budget in either shape of the balances (D-299), and the cash flow's family scopes are a 400
     * (D-302), while ALL and PERSONAL answer as ever.
     */
    @Test
    void debtAccountsNameNoBudgetAndFamilyScopesAre400() throws IOException {
        ok(get(user, "/api/accounts"));
        long family = jdbc.sql("INSERT INTO ledger (type, name, base_currency, split_rule) "
                + "VALUES ('SHARED', 'Home', 'EUR', 'EQUAL') RETURNING id").query(Long.class).single();
        join(family, user, "Me", "OWNER", LocalDate.of(2026, 9, 1));
        long personal = jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(user).query(Long.class).single();
        // The posting service's account, as it writes it, with app.writer set for this statement's transaction.
        jdbc.sql("""
                WITH writer AS (SELECT set_config('app.writer', 'family-posting', true))
                INSERT INTO account (user_id, ledger_id, code, name, type, is_system, default_currency, family_ledger_id)
                SELECT ?, ?, ?, 'Debt to family budget: Home', 'LIABILITY', true, 'EUR', ? FROM writer""")
                .params(user, personal, "FAMILY_DEBT_" + family, family).update();
        for (String shape : List.of("", "?currency=BASE")) {
            JsonNode balances = ok(get(user, "/api/reports/balances" + shape));
            assertThat(balances.findValuesAsText("accountCode")).contains("FAMILY_DEBT_" + family);
            assertThat(Answers.mentions(balances, "familyLedgerId")).as(shape).isFalse();
            assertThat(Answers.mentions(balances, "familyLedgerName")).as(shape).isFalse();
        }
        String period = "/api/reports/cash-flow?from=2026-01-01&to=2026-12-31";
        for (String shape : List.of("", "&currency=BASE")) {
            for (String scope : List.of("FAMILY", "FAMILY_MINE", "FAMILY_OTHERS")) {
                assertThat(get(user, period + shape + "&scope=" + scope)).as(scope + shape)
                        .hasStatus(HttpStatus.BAD_REQUEST);
                assertThat(get(user, period + shape + "&scope=" + scope + "&familyLedgerId=" + family))
                        .as(scope + shape).hasStatus(HttpStatus.BAD_REQUEST);
            }
            assertThat(get(user, period + shape + "&scope=PERSONAL")).hasStatus(HttpStatus.OK);
            assertThat(get(user, period + shape + "&scope=ALL")).hasStatus(HttpStatus.OK);
        }
    }

    /**
     * The demo with the switch off is exactly as before F6c (H1): no family budget, no new field in its answer, the
     * same entries and balances as {@code DemoDataApiTests} counted before the demo family, and the categories that the
     * demo family would bring stay personal.
     */
    /** D3b: with the switch off, an import into a family budget answers like a missing one; the personal rows import. */
    @Test
    void anImportIntoAFamilyBudgetAnswers404() throws IOException {
        long family = jdbc.sql("""
                INSERT INTO ledger (type, name, base_currency, split_rule, start_date)
                VALUES ('SHARED', 'Home', 'EUR', 'EQUAL', DATE '2024-01-01') RETURNING id""").query(Long.class).single();
        long member = join(family, user, "Owner", "OWNER", java.time.LocalDate.of(2024, 1, 1));
        long seat = jdbc.sql("""
                INSERT INTO ledger_member (ledger_id, ledger_type, display_name, role, status, join_date)
                VALUES (?, 'SHARED', 'Sam', 'MEMBER', 'ACTIVE', DATE '2024-01-01') RETURNING id""")
                .param(family).query(Long.class).single();
        assertThat(member).isNotEqualTo(seat);
        assertThat(importExport(user, com.example.financetracker.ledger.importer.ImportFixtures.bytes(
                "transactions.csv"), com.example.financetracker.ledger.importer.ImportFixtures.partnerRows(), family,
                seat, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ok(importWorkbook(user, personalRows(), null)).get("outcome").asText()).isEqualTo("DRY_RUN");
    }

    @Test
    void theDemoCreatesNoFamilyBudget() throws IOException {
        JsonNode demo = ok(post(user, "/api/demo-data", null));

        assertThat(demo.has("familyLedgerId")).isFalse();
        int total = demo.get("entriesByKind").properties().stream().mapToInt(kind -> kind.getValue().asInt()).sum();
        assertThat(total).isEqualTo(138);
        assertThat(ok(get(user, "/api/entries?size=1")).get("totalElements").asInt()).isEqualTo(total);
        JsonNode balances = ok(get(user, "/api/reports/balances"));
        assertThat(find(balances, "accountCode", "CURRENT_ACCOUNT").get("balance").asText()).isEqualTo("6054.12");
        assertThat(find(balances, "accountCode", "USD_ACCOUNT").get("balance").asText()).isEqualTo("1247.00");
        assertThat(find(balances, "accountCode", "CREDIT_CARD").get("balance").asText()).isEqualTo("94.69");
        assertThat(ok(get(user, "/api/reports/shared-settlement")).get(0).get("balance").asText()).isEqualTo("-88.60");
        JsonNode categories = ok(get(user, "/api/categories"));
        for (String code : DemoFamily.CATEGORIES) {
            assertThat(find(categories, "code", code).has("familyLedgerId")).as(code).isFalse();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_member WHERE user_sub = ? AND ledger_type = 'SHARED'")
                .param(user).query(Long.class).single()).isZero();
        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
    }

    /** Each endpoint of the family controllers as {method, path}, with 1 for every id in the path. */
    private static List<String[]> familyEndpoints() {
        List<String[]> endpoints = new ArrayList<>();
        for (Class<?> controller : List.of(FamilyLedgerController.class, FamilyRecordController.class,
                FamilyPaymentController.class, FamilyInviteController.class, FamilyMembershipsController.class)) {
            endpoints.addAll(endpoints(controller));
        }
        return endpoints;
    }

    private static List<String[]> endpoints(Class<?> controller) {
        // FamilyInviteController maps whole paths, under two bases.
        RequestMapping root = controller.getAnnotation(RequestMapping.class);
        String base = root == null ? "" : root.value()[0];
        List<String[]> endpoints = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                String path = base + (mapping.value().length == 0 ? "" : mapping.value()[0]);
                endpoints.add(new String[] {mapping.method()[0].name(), path.replaceAll("\\{\\w+}", "1")});
            }
        }
        return endpoints;
    }
    /**
     * UX-8 (D-457): with family budgets off, New entry's similar entries hold no family row, here a payment of a family
     * expense from a time the switch was on (written with plain SQL as the posting service writes it), and the old
     * shared expense counts as an expense.
     */
    @Test
    void similarEntriesHoldNoFamilyRowAndTheOldSharedExpense() throws IOException {
        ok(get(user, "/api/accounts"));
        long cash = find(ok(get(user, "/api/accounts")), "code", "CASH").get("id").asLong();
        long eatingOut = categoryId(user, "EATING_OUT");
        long shared = newEntry(user, """
                {"kind": "SHARED_EXPENSE", "entryDate": "2026-09-02", "accountId": %d, "currency": "EUR",
                 "total": "40.01", "categoryId": %d}""".formatted(cash, eatingOut)).get("id").asLong();
        long own = newEntry(user, """
                {"kind": "EXPENSE", "entryDate": "2026-09-03", "accountId": %d, "currency": "EUR",
                 "amount": "40.02", "categoryId": %d}""".formatted(cash, eatingOut)).get("id").asLong();

        long family = jdbc.sql("INSERT INTO ledger (type, name, base_currency, split_rule, start_date) "
                + "VALUES ('SHARED', 'Home', 'EUR', 'EQUAL', DATE '2026-09-01') RETURNING id").query(Long.class).single();
        long member = join(family, user, "Me", "OWNER", LocalDate.of(2026, 9, 1));
        long personal = jdbc.sql("SELECT ledger_id FROM ledger_member WHERE user_sub = ? AND ledger_type = 'PERSONAL'")
                .param(user).query(Long.class).single();
        long payment = new TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("SELECT set_config('app.writer', 'family-posting', true), "
                    + "set_config('app.own_ledger', ?, true)").param(String.valueOf(personal)).query().listOfRows();
            long category = jdbc.sql("INSERT INTO category (ledger_id, code, name, type) "
                    + "VALUES (?, 'HOME_FOOD', 'Food', 'EXPENSE') RETURNING id").param(family).query(Long.class)
                    .single();
            long debt = jdbc.sql("""
                    INSERT INTO account (user_id, ledger_id, code, name, type, is_system, default_currency,
                                         family_ledger_id)
                    VALUES (?, ?, ?, 'Debt to family budget: Home', 'LIABILITY', true, 'EUR', ?) RETURNING id""")
                    .params(user, personal, "FAMILY_DEBT_" + family, family).query(Long.class).single();
            long record = jdbc.sql("""
                    INSERT INTO family_record (ledger_id, type, record_date, category_id, payer_member_id,
                        original_amount, original_currency, base_amount, split_method, author_member_id,
                        updated_by_member_id)
                    VALUES (?, 'EXPENSE', DATE '2026-09-10', ?, ?, 40.03, 'EUR', 40.03, 'EQUAL', ?, ?) RETURNING id""")
                    .params(family, category, member, member, member).query(Long.class).single();
            jdbc.sql("INSERT INTO family_share (ledger_id, record_id, member_id, amount, updated_by_member_id) "
                    + "VALUES (?, ?, ?, 40.03, ?)").params(family, record, member, member).update();
            long entry = jdbc.sql("INSERT INTO journal_entry (user_id, ledger_id, entry_date, kind) "
                    + "VALUES (?, ?, DATE '2026-09-10', 'FAMILY_PAYMENT') RETURNING id").params(user, personal)
                    .query(Long.class).single();
            jdbc.sql("INSERT INTO posting (entry_id, line_no, account_id, currency, amount) VALUES (?, 0, ?, 'EUR', ?)")
                    .params(entry, cash, new BigDecimal("-40.03")).update();
            jdbc.sql("INSERT INTO posting (entry_id, line_no, account_id, currency, amount) VALUES (?, 1, ?, 'EUR', ?)")
                    .params(entry, debt, new BigDecimal("40.03")).update();
            jdbc.sql("INSERT INTO family_entry_link (entry_id, family_ledger_id, member_id, record_id, link_type, "
                    + "system_owned) VALUES (?, ?, ?, ?, 'PAYMENT', false)").params(entry, family, member, record)
                    .update();
            return entry;
        });

        JsonNode answer = ok(get(user, "/api/entries/matches?type=EXPENSE&amount=40&currency=EUR"));
        List<Long> ids = new ArrayList<>();
        answer.get("content").forEach(item -> ids.add(item.get("entry").get("id").asLong()));
        assertThat(ids).containsExactly(own, shared).doesNotContain(payment);
        assertThat(ok(get(user, "/api/entries/matches?type=EXPENSE&accountId=" + cash)).get("totalElements")
                .asLong()).isEqualTo(2);
    }
}
