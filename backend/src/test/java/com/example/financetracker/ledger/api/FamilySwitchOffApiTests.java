package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.ledger.FamilyPayments;
import com.example.financetracker.ledger.family.FamilySwitch;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
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

    private final String user = newUser();

    @Test
    void everyFamilyEndpointAnswers404LikeAnUnknownPath() throws IOException {
        List<String[]> endpoints = familyEndpoints();
        // 22 until F4c; POST /settlements since F4d; GET /conversion since F4e; six for invites since F5; leaving
        // (DELETE /members/me) since F6a.
        assertThat(endpoints).hasSize(31);
        assertThat(context.getBeanNamesForType(FamilyLedgerController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FamilyRecordController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FamilyPaymentController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FamilyInviteController.class)).isEmpty();
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
                    .replace("/family-payment", "/no-such-thing").replace("/api/invites", "/api/no-such-thing");
            MvcTestResult answer = call(user, method, path, body);
            MvcTestResult unknownAnswer = call(user, method, unknown, body);
            softly.assertThat(answer.getResponse().getStatus()).as("%s %s", method, path).isEqualTo(404);
            softly.assertThat(answer.getResponse().getContentAsString().replace(path.substring(1), "PATH")
                    .replace(path, "PATH")).as("%s %s", method, path)
                    .isEqualTo(unknownAnswer.getResponse().getContentAsString().replace(unknown.substring(1), "PATH")
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
                "/api/reports/balances?asOf=2026-12-31", "/api/reports/net-worth?asOf=2026-12-31",
                "/api/reports/integrity");
        SoftAssertions softly = new SoftAssertions();
        for (String read : reads) {
            String answer = ok(get(user, read)).toString();
            softly.assertThat(answer).as(read).doesNotContain("familyLedgerId", "familyLedgerName", "debtBalance",
                    "familyBalance", "FAMILY_DEBT_", "UNSPECIFIED_PAYMENTS");
        }
        softly.assertAll();
        assertThat(ok(get(user, "/api/reports/integrity"))).isEmpty();
        assertThat(ok(get(user, "/api/entries?size=200")).get("content").findValues("family"))
                .allSatisfy(family -> assertThat(family.isNull()).isTrue());
    }

    /** Each endpoint of the family controllers as {method, path}, with 1 for every id in the path. */
    private static List<String[]> familyEndpoints() {
        List<String[]> endpoints = new ArrayList<>();
        for (Class<?> controller : List.of(FamilyLedgerController.class, FamilyRecordController.class,
                FamilyPaymentController.class, FamilyInviteController.class)) {
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
}
