package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The journal's currencies (F8d; D-93): every row of a record's journal stores the currency its amounts are in, the
 * record's right after the change, and the journal says it, so that a record whose currency changed still reads each
 * old row in the currency it had then. The old values of the row that changes the currency are in the old currency.
 */
class FamilyJournalCurrencyApiTests extends FamilyApiTest {

    /** The journal's rows, newest first, as "ACTION row-currency: field old(oldCurrency)→new(newCurrency), …". */
    private List<String> rows(long record) throws IOException {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : ok(get(alice, uri + "/journal?recordId=" + record)).get("content")) {
            List<String> fields = new ArrayList<>();
            for (JsonNode change : row.get("changes")) {
                String field = change.get("field").asText();
                if (!field.equals("amount") && !field.equals("share") && !field.equals("currency")) {
                    continue;
                }
                fields.add(field + (change.get("member").isNull() ? "" : " of " + change.get("member")
                        .get("displayName").asText()) + " " + change.get("old").asText()
                        + (change.path("oldCurrency").isMissingNode() ? "" : "(" + change.path("oldCurrency").asText() + ")")
                        + "→" + change.get("new").asText()
                        + (change.path("newCurrency").isMissingNode() ? "" : "(" + change.path("newCurrency").asText() + ")"));
            }
            rows.add(row.get("action").asText() + " " + row.get("currency").asText() + ": " + String.join(", ", fields));
        }
        return rows;
    }

    @Test
    void everyRowSaysItsCurrencyAndAChangeOfCurrencyReadsEachSideInItsOwn() throws IOException {
        long record = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "10.00", "payerMemberId": %d, "paymentLater": true,
                 "split": {"method": "ONE_MEMBER", "memberId": %d}}""".formatted(groceries, mum, dad))).get("id")
                .asLong();
        ok(patch(alice, uri + "/records/%d?version=0".formatted(record), "{\"amount\": \"12.00\"}"));
        // To dollars: the amount changes with the currency; its old amounts are in euros, its new ones in dollars.
        ok(patch(alice, uri + "/records/%d?version=1".formatted(record), """
                {"currency": "USD", "amount": "13.00"}"""));
        ok(patch(alice, uri + "/records/%d?version=2".formatted(record), "{\"amount\": \"14.00\"}"));
        assertThat(delete(alice, uri + "/records/%d?version=3".formatted(record))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(rows(record)).containsExactly(
                "DELETE USD: ",
                "UPDATE USD: amount 13.00(USD)→14.00(USD), share of Dad 13.00(USD)→14.00(USD)",
                "UPDATE USD: amount 12.00(EUR)→13.00(USD), currency EUR→USD, share of Dad 12.00(EUR)→13.00(USD)",
                "UPDATE EUR: amount 10.00(EUR)→12.00(EUR), share of Dad 10.00(EUR)→12.00(EUR)",
                "CREATE EUR: amount null→10.00(EUR), share of Dad null→10.00(EUR)");
        // The summary is the record as it is now: in dollars, whatever the rows say.
        JsonNode newest = ok(get(alice, uri + "/journal?recordId=" + record)).get("content").get(0);
        assertThat(newest.get("record").get("currency").asText()).isEqualTo("USD");
        // Every row of the ledger has its currency in the database too.
        assertThat(jdbc.sql("SELECT string_agg(currency, ',' ORDER BY id) FROM family_record_change WHERE record_id = ?")
                .param(record).query(String.class).single()).isEqualTo("EUR,EUR,USD,USD,USD");
    }

    @Test
    void aCreationInAnotherCurrencyAndASettlementAreInTheirOwn() throws IOException {
        long dollars = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "9.99", "currency": "USD", "payerMemberId": %d,
                 "paymentLater": true}""".formatted(groceries, mum))).get("id").asLong();
        assertThat(rows(dollars)).containsExactly("CREATE USD: amount null→9.99(USD), currency null→USD, "
                + "share of Mum null→3.33(USD), share of Dad null→3.33(USD), share of Kid null→3.33(USD)");
        long settlement = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-11", "amount": "2.50", "currency": "USD", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(mum, dad))).get("id").asLong();
        assertThat(rows(settlement)).containsExactly("CREATE USD: amount null→2.50(USD), currency null→USD");
    }
}
