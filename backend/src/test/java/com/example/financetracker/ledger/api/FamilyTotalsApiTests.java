package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-47's totals in the family of {@link FamilyApiTest} (main currency EUR), as of 15 October 2026: each member's
 * balances and report totals together in the main currency, by the rates of the member who reads (D-49, D-90, D-91),
 * rounded once; none when a currency has no rate, naming it. Nothing is posted or settled in the total.
 */
class FamilyTotalsApiTests extends FamilyApiTest {

    @Autowired
    private TestClock clock;

    @BeforeEach
    void onFifteenOctober() {
        clock.set(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    /** In euros only, the total is each member's balance, with no rate. */
    @Test
    void inTheMainCurrencyOnlyTheTotalIsTheBalance() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "90.00", kid, "")));
        JsonNode total = ok(get(alice, uri + "/balances")).get("total");
        assertThat(total.get("currency").asText() + " " + total.get("asOf").asText()).isEqualTo("EUR 2026-10-15");
        assertThat(members(total)).containsExactly(mum + " 30.00", dad + " 30.00", kid + " -60.00");
        assertThat(total.get("rates")).isEmpty();
        assertThat(total.get("missingCurrencies")).isEmpty();
    }

    /**
     * Mum and Kid owe in dollars, from two records Kid paid, all on Mum. Alice's own rates of 30 September (1.20) and
     * 10 October (1.10): her balances' total takes today's, 1.10, and the report's each month's month-end rate, the
     * current month's at today's. Bob has no dollar rate of his own and no ECB one: no total for him, naming USD.
     */
    @Test
    void eachMemberSeesTotalsByTheirOwnRates() throws IOException {
        for (String[] rate : new String[][] {{"2026-09-01", "1.25"}, {"2026-09-30", "1.20"}, {"2026-10-10", "1.10"}}) {
            ok(post(alice, "/api/rates/manual", """
                    {"date": "%s", "base": "EUR", "quote": "USD", "rate": "%s"}""".formatted(rate[0], rate[1])));
        }
        String onMum = """
                {"date": "%s", "categoryId": %d, "amount": "%s", "currency": "USD", "payerMemberId": %d,
                 "split": {"method": "ONE_MEMBER", "memberId": %d}}""";
        created(post(alice, uri + "/records", onMum.formatted("2026-09-10", groceries, "120.00", kid, mum)));
        created(post(alice, uri + "/records", onMum.formatted("2026-10-05", groceries, "110.00", kid, mum)));

        JsonNode balances = ok(get(alice, uri + "/balances")).get("total");
        // 230.00 USD at 1.10: 209.0909…, rounded once.
        assertThat(members(balances)).containsExactly(mum + " 209.09", dad + " 0.00", kid + " -209.09");
        assertThat(rates(balances)).containsExactly("USD 2026-10-10 1.10 MANUAL");

        JsonNode report = ok(get(alice, uri + "/report")).get("total");
        // 120.00 USD at September's month-end 1.20 and 110.00 USD at today's 1.10: 100.00 + 100.00.
        JsonNode mums = report.get("totals").get(0);
        assertThat(mums.get("expenseShares").asText() + " " + mums.get("net").asText()).isEqualTo("200.00 200.00");
        JsonNode kids = report.get("totals").get(2);
        assertThat(kids.get("expensesPaid").asText() + " " + kids.get("net").asText()).isEqualTo("200.00 -200.00");
        assertThat(rates(report)).containsExactly("USD 2026-09-30 1.20 MANUAL", "USD 2026-10-10 1.10 MANUAL");
        // The balances per currency are unchanged by the total (D-47: for display only).
        assertThat(ok(get(alice, uri + "/balances")).get("byCurrency").findValuesAsText("currency"))
                .containsExactly("EUR", "USD");

        for (String part : List.of("/balances", "/report")) {
            JsonNode bobs = ok(get(bob, uri + part)).get("total");
            assertThat(bobs.get("missingCurrencies").toString()).as(part).isEqualTo("[\"USD\"]");
            assertThat(bobs.get(part.equals("/balances") ? "members" : "totals")).as(part).isEmpty();
            assertThat(bobs.get("rates")).as(part).isEmpty();
            // Alice's rates are hers: none of them reaches Bob's answer.
            assertThat(bobs.toString()).as(part).doesNotContain("1.10", "1.20");
        }
    }

    /**
     * D-53 at the turn of a month: at 23:30 UTC on 31 October Amsterdam and the JVM's own zone (Pacific/Kiritimati in
     * the tests) are already in November, but the api's today is the 31st. The balances' total is as of the 31st at the
     * rate of the 31st, not at Alice's rate of 1 November, which is tomorrow's; and October is still the current month,
     * so its amounts in the report are at today's rate, not at a month-end rate of its own.
     */
    @Test
    void theTotalsTakeTheApisTodayAtTheTurnOfAMonth() throws IOException {
        clock.set(Instant.parse("2026-10-31T23:30:00Z"), ZoneOffset.UTC);
        for (String[] rate : new String[][] {{"2026-10-31", "1.10"}, {"2026-11-01", "2.00"}}) {
            ok(post(alice, "/api/rates/manual", """
                    {"date": "%s", "base": "EUR", "quote": "USD", "rate": "%s"}""".formatted(rate[0], rate[1])));
        }
        created(post(alice, uri + "/records", """
                {"date": "2026-10-20", "categoryId": %d, "amount": "110.00", "currency": "USD", "payerMemberId": %d,
                 "split": {"method": "ONE_MEMBER", "memberId": %d}}""".formatted(groceries, kid, mum)));

        JsonNode balances = ok(get(alice, uri + "/balances")).get("total");
        assertThat(balances.get("asOf").asText()).isEqualTo("2026-10-31");
        assertThat(members(balances)).containsExactly(mum + " 100.00", dad + " 0.00", kid + " -100.00");
        assertThat(rates(balances)).containsExactly("USD 2026-10-31 1.10 MANUAL");

        JsonNode report = ok(get(alice, uri + "/report")).get("total");
        assertThat(report.get("totals").get(0).get("expenseShares").asText()).isEqualTo("100.00");
        assertThat(rates(report)).containsExactly("USD 2026-10-31 1.10 MANUAL");

        clock.set(Instant.parse("2026-11-01T00:30:00Z"), ZoneOffset.UTC);
        JsonNode next = ok(get(alice, uri + "/balances")).get("total");
        assertThat(next.get("asOf").asText()).isEqualTo("2026-11-01");
        assertThat(members(next)).containsExactly(mum + " 55.00", dad + " 0.00", kid + " -55.00");
        assertThat(rates(next)).containsExactly("USD 2026-11-01 2.00 MANUAL");
    }

    /**
     * "No RUB rate": the ECB's last rouble rate (2022-03-01) never applies in 2026 (D-49), so a rouble record leaves no
     * total. Alice's rate of 1 August 2026, 95.50, then gives one, labelled manual and stale on 15 October.
     */
    @Test
    void noRoubleRateUntilTheMemberEntersOne() throws IOException {
        jdbc.sql("""
                INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source, user_id)
                VALUES (DATE '2022-03-01', 'EUR', 'RUB', 115.8, 'ECB', NULL)""").update();
        try {
            created(post(alice, uri + "/records", """
                    {"date": "2026-10-01", "categoryId": %d, "amount": "9550", "currency": "RUB", "payerMemberId": %d,
                     "split": {"method": "ONE_MEMBER", "memberId": %d}}""".formatted(groceries, kid, mum)));
            JsonNode none = ok(get(alice, uri + "/balances")).get("total");
            assertThat(none.get("missingCurrencies").toString()).isEqualTo("[\"RUB\"]");
            assertThat(none.get("members")).isEmpty();
            assertThat(ok(get(alice, uri + "/report")).get("total").get("missingCurrencies").toString())
                    .isEqualTo("[\"RUB\"]");

            ok(post(alice, "/api/rates/manual", """
                    {"date": "2026-08-01", "base": "EUR", "quote": "RUB", "rate": "95.50"}"""));
            JsonNode total = ok(get(alice, uri + "/balances")).get("total");
            assertThat(members(total)).containsExactly(mum + " 100.00", dad + " 0.00", kid + " -100.00");
            assertThat(total.get("rates").get(0).get("source").asText() + " "
                    + total.get("rates").get(0).get("stale").asBoolean()).isEqualTo("MANUAL true");
        } finally {
            jdbc.sql("DELETE FROM exchange_rate WHERE user_id IS NULL AND quote_currency = 'RUB'").update();
        }
    }

    private static List<String> members(JsonNode total) {
        List<String> members = new ArrayList<>();
        total.get("members").forEach(m -> members.add(m.get("memberId").asLong() + " " + m.get("balance").asText()));
        return members;
    }

    private static List<String> rates(JsonNode total) {
        List<String> rates = new ArrayList<>();
        total.get("rates").forEach(r -> rates.add(r.get("currency").asText() + " " + r.get("date").asText() + " "
                + r.get("perEuro").asText() + " " + r.get("source").asText()));
        return rates;
    }
}
