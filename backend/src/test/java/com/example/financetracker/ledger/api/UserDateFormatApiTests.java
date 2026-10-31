package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * D-304 (UX-1): each user's date format, stored like the time zone (D-101): null until chosen, one of four patterns,
 * reported by {@code /api/me}, set by PUT {@code /api/settings/date-format}, left alone by PUT of the settings and gone
 * with "Delete all my data".
 */
class UserDateFormatApiTests extends LedgerApiTest {

    private final String alice = newUser();

    private JsonNode setFormat(String user, String format) throws IOException {
        return ok(put(user, "/api/settings/date-format", format == null ? "{\"dateFormat\": null}"
                : "{\"dateFormat\": \"%s\"}".formatted(format)));
    }

    private JsonNode me(String user) throws IOException {
        return ok(get(user, "/api/me"));
    }

    @Test
    void theFourFormatsAndTheBrowsersAreAccepted() throws IOException {
        ok(get(alice, "/api/accounts"));
        assertThat(me(alice).get("dateFormat").isNull()).isTrue();
        for (String format : List.of("DD.MM.YYYY", "DD/MM/YYYY", "MM/DD/YYYY", "YYYY-MM-DD")) {
            assertThat(setFormat(alice, format)).isEqualTo(Answers.json("{\"dateFormat\": \"%s\"}".formatted(format)));
            assertThat(me(alice).get("dateFormat").asText()).isEqualTo(format);
        }
        // Back to as the browser shows dates; an empty body means the same.
        assertThat(setFormat(alice, null)).isEqualTo(Answers.json("{\"dateFormat\": null}"));
        assertThat(me(alice).get("dateFormat").isNull()).isTrue();
        setFormat(alice, "DD.MM.YYYY");
        assertThat(ok(put(alice, "/api/settings/date-format", "{}")).get("dateFormat").isNull()).isTrue();
    }

    @Test
    void anyOtherValueIs422AndChangesNothing() throws IOException {
        ok(get(alice, "/api/accounts"));
        setFormat(alice, "MM/DD/YYYY");
        for (String format : List.of("dd.mm.yyyy", "DD-MM-YYYY", "YYYY/MM/DD", "D.M.YYYY", "", " DD.MM.YYYY",
                "MEDIUM", "DD.MM.YYYY".repeat(20))) {
            JsonNode problem = body(put(alice, "/api/settings/date-format", "{\"dateFormat\": \"%s\"}".formatted(format)),
                    HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(problem.get("violations").get(0).asText()).as(format).isEqualTo(("'%s' is not a date format: "
                    + "use one of DD.MM.YYYY, DD/MM/YYYY, MM/DD/YYYY, YYYY-MM-DD, or null for the browser's")
                    .formatted(format));
        }
        assertThat(put(alice, "/api/settings/date-format", "{\"dateFormat\": 42}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(me(alice).get("dateFormat").asText()).isEqualTo("MM/DD/YYYY");
    }

    @Test
    void theSettingsPutLeavesItAloneAndDeleteAllTakesIt() throws IOException {
        ok(get(alice, "/api/accounts"));
        setFormat(alice, "YYYY-MM-DD");
        ok(put(alice, "/api/settings", """
                {"baseCurrency": "USD", "sharedAccountId": null, "defaultShareRatio": "0.40"}"""));
        assertThat(me(alice).get("dateFormat").asText()).isEqualTo("YYYY-MM-DD");
        assertThat(ok(get(alice, "/api/settings")).has("dateFormat")).isFalse();

        assertThat(delete(alice, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT count(*) FROM user_settings WHERE user_id = ?").param(alice).query(Long.class)
                .single()).isZero();
        assertThat(me(alice).get("dateFormat").isNull()).isTrue();
        ok(get(alice, "/api/accounts"));
        assertThat(me(alice).get("dateFormat").isNull()).isTrue();
    }

    /** Each user's own: Bob's format is none of Alice's business, and hers changes none of his. */
    @Test
    void theFormatIsTheUsersOwn() throws IOException {
        String bob = newUser();
        ok(get(alice, "/api/accounts"));
        ok(get(bob, "/api/accounts"));
        setFormat(alice, "DD.MM.YYYY");
        assertThat(me(bob).get("dateFormat").isNull()).isTrue();
        setFormat(bob, "MM/DD/YYYY");
        assertThat(me(alice).get("dateFormat").asText()).isEqualTo("DD.MM.YYYY");
    }
}
