package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.example.financetracker.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-53: every "today" of the api is {@code Today}'s, the api's zone (UTC in production): the reports' default
 * {@code asOf} and the demo's last day. The clock is set to 10 September 2026, 23:30 UTC, when the JVM's own zone
 * (Pacific/Kiritimati in the tests) is already on the 11th.
 */
class TodayApiTests extends LedgerApiTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);

    @Autowired
    private TestClock clock;

    private final String user = newUser();

    @BeforeEach
    void lateOnTheTenth() {
        clock.set(Instant.parse("2026-09-10T23:30:00Z"), ZoneOffset.UTC);
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    /** An entry of the 11th isn't in the balances without {@code asOf}; with the 11th asked for, it is. */
    @Test
    void theReportsDefaultDayIsTheApisToday() throws IOException {
        long cash = accountId(user, "CASH");
        long groceries = categoryId(user, "GROCERIES");
        for (String day : new String[] {"2026-09-10", "2026-09-11"}) {
            newEntry(user, """
                    {"kind": "EXPENSE", "entryDate": "%s", "accountId": %d, "currency": "EUR", "amount": "10",
                     "categoryId": %d}""".formatted(day, cash, groceries));
        }
        assertThat(cash(ok(get(user, "/api/reports/balances")))).isEqualTo("-10.00");
        assertThat(cash(ok(get(user, "/api/reports/balances?asOf=2026-09-11")))).isEqualTo("-20.00");
        assertThat(cash(ok(get(user, "/api/reports/balances?currency=BASE")))).isEqualTo("-10.00");
    }

    /** The demo ends on the api's today, not the JVM's. */
    @Test
    void theDemoEndsOnTheApisToday() throws IOException {
        ok(post(user, "/api/demo-data", null));
        JsonNode newest = ok(get(user, "/api/entries?size=1")).get("content").get(0);
        assertThat(LocalDate.parse(newest.get("entryDate").asText())).isBeforeOrEqualTo(TODAY);
    }

    /** D-54: {@code /api/me} names the signed-in account's email, from the token. */
    @Test
    void meNamesTheAccountsEmail() throws IOException {
        JsonNode me = ok(get(user, "/api/me"));
        assertThat(me.get("email").asText()).isEqualTo(user + "@example.com");
        assertThat(me.get("name").asText()).isEqualTo("User " + user);
    }

    private static String cash(JsonNode balances) {
        for (JsonNode row : balances) {
            if (row.get("accountCode").asText().equals("CASH")) {
                return row.get("balance").asText();
            }
        }
        throw new AssertionError("No CASH row in " + balances);
    }
}
