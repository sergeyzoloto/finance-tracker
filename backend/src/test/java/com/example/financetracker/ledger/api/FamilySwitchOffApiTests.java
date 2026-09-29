package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
 * The family endpoints come from {@link FamilyLedgerController}'s own mappings, so a new one can't be left out.
 */
@TestPropertySource(properties = FamilySwitch.PROPERTY + "=false")
class FamilySwitchOffApiTests extends LedgerApiTest {

    @Autowired
    private ApplicationContext context;

    private final String user = newUser();

    @Test
    void everyFamilyEndpointAnswers404LikeAnUnknownPath() throws IOException {
        List<String[]> endpoints = familyEndpoints();
        assertThat(endpoints).hasSize(14);
        assertThat(context.getBeanNamesForType(FamilyLedgerController.class)).isEmpty();
        String body = """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna", "rule": "EQUAL", "code": "RENT",
                 "type": "EXPENSE"}""";

        SoftAssertions softly = new SoftAssertions();
        for (String[] endpoint : endpoints) {
            HttpMethod method = HttpMethod.valueOf(endpoint[0]);
            String path = endpoint[1];
            String unknown = path.replace("/api/family-ledgers", "/api/no-such-thing");
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
        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
    }

    /** Each endpoint of FamilyLedgerController as {method, path}, with 1 for every id in the path. */
    private static List<String[]> familyEndpoints() {
        String base = FamilyLedgerController.class.getAnnotation(RequestMapping.class).value()[0];
        List<String[]> endpoints = new ArrayList<>();
        for (Method method : FamilyLedgerController.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                String path = base + (mapping.value().length == 0 ? "" : mapping.value()[0]);
                endpoints.add(new String[] {mapping.method()[0].name(), path.replaceAll("\\{\\w+}", "1")});
            }
        }
        return endpoints;
    }
}
