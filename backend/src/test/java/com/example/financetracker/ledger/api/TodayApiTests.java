package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.StreamSupport;

import com.example.financetracker.TestClock;
import com.example.financetracker.ledger.Today;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-53: every "today" of the api is {@code Today}'s, the api's zone (UTC in production): the reports' default
 * {@code asOf}, the demo's last day and the rates that apply today. The clock is set to 10 September 2026, 23:30 UTC,
 * when Amsterdam and the JVM's own zone (Pacific/Kiritimati in the tests) are already on the 11th. Without a set clock,
 * the api's today is the JVM's date in its default zone and the database session's {@code current_date}, which the
 * family tests read as the api's today.
 */
class TodayApiTests extends LedgerApiTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);

    @Autowired
    private TestClock clock;

    @Autowired
    private Today today;

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

    /**
     * The rates page: a manual rate of the 10th applies today, one of the 11th, Amsterdam's and the JVM's today, is
     * the latest there is and applies to nothing until the api's today is the 11th (D-49, D-91).
     */
    @Test
    void theRatesPageTakesTheApisToday() throws IOException {
        ok(post(user, "/api/rates/manual", """
                {"date": "2026-09-10", "base": "EUR", "quote": "RUB", "rate": "95.50"}"""));
        ok(post(user, "/api/rates/manual", """
                {"date": "2026-09-11", "base": "EUR", "quote": "KZT", "rate": "550"}"""));
        JsonNode latest = ok(get(user, "/api/rates")).get("latest");
        assertThat(line(latest, "RUB")).isEqualTo("2026-09-10 MANUAL applies");
        assertThat(line(latest, "KZT")).isEqualTo("2026-09-11 MANUAL");

        clock.set(Instant.parse("2026-09-11T00:30:00Z"), ZoneOffset.UTC);
        assertThat(line(ok(get(user, "/api/rates")).get("latest"), "KZT")).isEqualTo("2026-09-11 MANUAL applies");
    }

    /**
     * With the system clock, the api's today, the JVM's date in its zone and the database session's
     * {@code current_date} are one date (the session takes the JVM's zone when its connection opens), whichever zone
     * the JVM runs in. Tried again when midnight falls between the readings.
     */
    @Test
    void withTheSystemClockTheApisTodayIsTheJvmsAndTheDatabasesDate() {
        clock.reset();
        for (int attempt = 0; attempt < 3; attempt++) {
            LocalDate before = LocalDate.now(ZoneId.systemDefault());
            LocalDate api = today.date();
            LocalDate database = jdbc.sql("SELECT current_date").query(LocalDate.class).single();
            if (before.equals(LocalDate.now(ZoneId.systemDefault()))) {
                assertThat(api).as("the api's today").isEqualTo(before);
                assertThat(database).as("the database's current_date").isEqualTo(before);
                return;
            }
        }
        throw new AssertionError("The date turned over in each of three attempts");
    }

    /** D-54: {@code /api/me} names the signed-in account's email, from the token. */
    @Test
    void meNamesTheAccountsEmail() throws IOException {
        JsonNode me = ok(get(user, "/api/me"));
        assertThat(me.get("email").asText()).isEqualTo(user + "@example.com");
        assertThat(me.get("name").asText()).isEqualTo("User " + user);
    }

    private static String line(JsonNode latest, String currency) {
        JsonNode rate = StreamSupport.stream(latest.spliterator(), false)
                .filter(r -> r.get("currency").asText().equals(currency)).findFirst().orElseThrow();
        return rate.get("date").asText() + " " + rate.get("source").asText()
                + (rate.get("applies").asBoolean() ? " applies" : "");
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
