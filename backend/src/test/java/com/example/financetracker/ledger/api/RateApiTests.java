package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** /api/rates: the rates Alice sees, and her manual rates, which Bob never sees. */
class RateApiTests extends LedgerApiTest {

    private final String alice = newUser();
    private final String bob = newUser();

    @Test
    void aManualRateIsTheUsersOwn() throws IOException {
        JsonNode saved = ok(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "EUR", "quote": "RUB", "rate": "95.50"}"""));

        assertThat(saved.toString()).isEqualTo("""
                {"date":"2026-09-01","base":"EUR","quote":"RUB","rate":"95.50"}""");
        assertThat(ok(get(alice, "/api/rates/manual"))).containsExactly(saved);
        JsonNode rub = find(ok(get(alice, "/api/rates")).get("latest"), "currency", "RUB");
        assertThat(rub.get("date").asText()).isEqualTo("2026-09-01");
        assertThat(rub.get("perEuro").asText()).isEqualTo("95.50");
        assertThat(rub.get("source").asText()).isEqualTo("MANUAL");
        assertThat(rub.get("inLedger").asBoolean()).isFalse();

        assertThat(ok(get(bob, "/api/rates/manual"))).isEmpty();
        assertThat(ok(get(bob, "/api/rates")).get("latest").findValuesAsText("currency")).doesNotContain("RUB");
        assertThat(body(delete(bob, "/api/rates/manual?date=2026-09-01&currency=RUB"), HttpStatus.NOT_FOUND)
                .get("detail").asText()).isEqualTo("No manual rate for RUB on 2026-09-01.");

        assertThat(delete(alice, "/api/rates/manual?date=2026-09-01&currency=RUB")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ok(get(alice, "/api/rates/manual"))).isEmpty();
    }

    @Test
    void aRateInEurosForOneUnitIsStoredAsUnitsForOneEuro() throws IOException {
        JsonNode saved = ok(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "RUB", "quote": "EUR", "rate": "0.0125"}"""));

        assertThat(saved.get("base").asText()).isEqualTo("EUR");
        assertThat(saved.get("quote").asText()).isEqualTo("RUB");
        assertThat(saved.get("rate").asText()).isEqualTo("80.00");
    }

    @Test
    void aManualRateIsCheckedAndMustBeAgainstTheEuro() throws IOException {
        assertThat(violations(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "USD", "quote": "RUB", "rate": "85"}""")))
                .containsExactly("one of base and quote must be EUR: rates between other currencies are computed "
                        + "through the euro");
        assertThat(violations(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "EUR", "quote": "EUR", "rate": "1"}""")))
                .containsExactly("base and quote are both EUR");
        JsonNode invalid = body(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "EUR", "quote": "XYZ", "rate": "-1"}"""), HttpStatus.BAD_REQUEST);
        assertThat(invalid.get("errors").findValuesAsText("field")).containsExactly("quote", "rate");
        assertThat(ok(get(alice, "/api/rates/manual"))).isEmpty();
    }

    @Test
    void aCsvFileSavesAllItsRatesOrNone() throws IOException {
        assertThat(ok(csv(alice, """
                ﻿date,base,quote,rate
                2026-09-01,EUR,RUB,95.5
                2026-09-02,eur,rub,96
                2026-09-02,KZT,EUR,0.002
                """)).get("saved").asInt()).isEqualTo(3);
        assertThat(ok(get(alice, "/api/rates/manual")).findValuesAsText("rate")).containsExactly("500.00", "96.00",
                "95.50");

        assertThat(violations(csv(alice, """
                date,base,quote,rate
                2026-09-03,EUR,RUB,97
                03.09.2026,EUR,RUB,97
                2026-09-04,USD,RUB,85
                2026-09-03,EUR,RUB,98
                2026-09-05,EUR,RUB,abc
                """))).containsExactly(
                "row 3: '03.09.2026' is not a date such as 2026-09-25",
                "row 4: one of base and quote must be EUR: rates between other currencies are computed through the "
                        + "euro",
                "row 5: row 2 has a rate for RUB on 2026-09-03 already",
                "row 6: the rate 'abc' is not a number such as 95.50");
        assertThat(violations(csv(alice, "date,currency,rate\n2026-09-03,RUB,97\n")))
                .containsExactly("the file needs the columns date,base,quote,rate in its first row; base, quote are "
                        + "missing");
        // Nothing of the files with errors was saved.
        assertThat(ok(get(alice, "/api/rates/manual"))).hasSize(3);
        assertThat(ok(get(bob, "/api/rates/manual"))).isEmpty();
    }

    @Test
    void theOverviewShowsTheDaysOnWhichPostingsCannotBeConverted() throws IOException {
        long cash = accountId(alice, "CASH");
        long groceries = categoryId(alice, "GROCERIES");
        for (String day : new String[] {"2026-08-03", "2026-08-20"}) {
            newEntry(alice, """
                    {"kind": "EXPENSE", "entryDate": "%s", "accountId": %d, "currency": "KZT", "amount": "5000",
                     "categoryId": %d}""".formatted(day, cash, groceries));
        }

        JsonNode overview = ok(get(alice, "/api/rates"));
        assertThat(overview.get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(overview.get("missing").toString()).isEqualTo("""
                [{"currency":"KZT","from":"2026-08-03","to":"2026-08-20","days":2}]""");
        JsonNode kzt = find(overview.get("latest"), "currency", "KZT");
        assertThat(kzt.get("date").isNull()).isTrue();
        assertThat(kzt.get("inLedger").asBoolean()).isTrue();

        // From 10 August on, KZT has a rate.
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-08-10", "base": "EUR", "quote": "KZT", "rate": "550"}"""));
        assertThat(ok(get(alice, "/api/rates")).get("missing").toString()).isEqualTo("""
                [{"currency":"KZT","from":"2026-08-03","to":"2026-08-03","days":1}]""");
    }

    private MvcTestResult csv(String user, String content) {
        return mvc.post().uri("/api/rates/manual/csv").multipart()
                .file(new MockMultipartFile("file", "rates.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                .with(member(user))
                .exchange();
    }

    private List<String> violations(MvcTestResult result) throws IOException {
        return StreamSupport.stream(body(result, HttpStatus.UNPROCESSABLE_ENTITY).get("violations").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
