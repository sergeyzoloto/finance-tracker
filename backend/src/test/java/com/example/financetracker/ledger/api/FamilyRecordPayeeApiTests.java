package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * UX-11, D-523: an entry that a family budget posted, such as a member's share, names its record's payee
 * ({@code family.recordPayee}) exactly when the reader may see that payee (D-479 to D-482, D-486): the payer always,
 * another ACTIVE member while the payer, an ACTIVE member with an account, shows their payees. The entry list's text
 * search and Payee filter, and its summary, follow the same rule; a hidden payee never shows, matches or counts.
 */
class FamilyRecordPayeeApiTests extends FamilyApiTest {

    private static final String SHOP = "Corner shop UX11";

    private long current;
    private long shop;
    private long expense;

    @BeforeEach
    void mumPaysWithAPayee() throws IOException {
        current = accountId(alice, "CURRENT_ACCOUNT");
        shop = newCounterparty(alice, SHOP);
        expense = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "30.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "payeeId": %d}""".formatted(groceries, mum, current, shop))).get("id")
                .asLong();
    }

    private JsonNode showPayees(String user, boolean on) throws IOException {
        return ok(patch(user, uri + "/me", "{\"showsPayees\": %s}".formatted(on)));
    }

    /** The user's entries linked to the family budget as "link recordId payee", the payee "-" when there is none. */
    private List<String> linked(String user) throws IOException {
        List<String> lines = new ArrayList<>();
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            JsonNode family = entry.get("family");
            if (family.isNull()) {
                continue;
            }
            JsonNode payee = family.get("recordPayee");
            lines.add(family.get("link").asText() + " " + family.path("recordId").asText("-") + " "
                    + (payee == null ? "-" : payee.get("name").asText()));
        }
        return lines.stream().sorted().toList();
    }

    private long found(String user, String query) throws IOException {
        return ok(get(user, "/api/entries?" + query)).get("totalElements").asLong();
    }

    @Test
    void thePayerSeesHerOwnRecordsPayeeOnHerShareAndPayment() throws IOException {
        assertThat(linked(alice)).containsExactly("PAYMENT %d %s".formatted(expense, SHOP),
                "SHARE %d %s".formatted(expense, SHOP));
        // Hers, whatever her switch: off by default.
        assertThat(ok(get(alice, uri)).get("showsPayees").asBoolean()).isFalse();
        assertThat(found(alice, "q=corner")).isEqualTo(2);
        // The single entry reads the same.
        for (JsonNode entry : ok(get(alice, "/api/entries?size=200")).get("content")) {
            assertThat(ok(get(alice, "/api/entries/" + entry.get("id").asLong())).get("family").get("recordPayee")
                    .get("name").asText()).isEqualTo(SHOP);
        }
    }

    /**
     * Dad sees it on his share only while Mum shows her payees: the cell, the text search and the Payee filter by name
     * in any case; hidden, his page, its total and the summary are those of a record without a payee.
     */
    @Test
    void anotherMemberSeesItOnlyWhileThePayerShowsPayees() throws IOException {
        long bobsCorner = newCounterparty(bob, "corner SHOP ux11");
        String byName = "counterpartyId=" + bobsCorner;
        assertThat(linked(bob)).containsExactly("SHARE %d -".formatted(expense));
        assertThat(found(bob, "q=corner")).isZero();
        assertThat(found(bob, byName)).isZero();
        JsonNode hiddenPage = ok(get(bob, "/api/entries?size=200"));
        JsonNode hiddenSummary = ok(get(bob, "/api/entries/summary"));
        assertThat(hiddenSummary.get("payees").asLong()).isZero();
        assertThat(hiddenSummary.get("withoutPayee").asLong()).isEqualTo(1);

        showPayees(alice, true);
        assertThat(linked(bob)).containsExactly("SHARE %d %s".formatted(expense, SHOP));
        assertThat(found(bob, "q=corner")).isEqualTo(1);
        assertThat(found(bob, "q=CORNER")).isEqualTo(1);
        assertThat(found(bob, byName)).isEqualTo(1);
        JsonNode shown = ok(get(bob, "/api/entries/summary?" + byName));
        assertThat(shown.get("count").asLong()).isEqualTo(1);
        assertThat(shown.get("payees").asLong()).isEqualTo(1);
        assertThat(shown.get("withoutPayee").asLong()).isZero();
        assertThat(ok(get(bob, "/api/entries/summary?q=corner")).get("count").asLong()).isEqualTo(1);

        showPayees(alice, false);
        assertThat(linked(bob)).containsExactly("SHARE %d -".formatted(expense));
        assertThat(found(bob, "q=corner")).isZero();
        assertThat(found(bob, byName)).isZero();
        assertThat(ok(get(bob, "/api/entries/summary?q=corner")).get("count").asLong()).isZero();
        assertThat(ok(get(bob, "/api/entries?size=200"))).isEqualTo(hiddenPage);
        assertThat(ok(get(bob, "/api/entries/summary"))).isEqualTo(hiddenSummary);
        Answers.assertNoneMention(ok(get(bob, "/api/entries?size=200")), SHOP);

        // Shown, but the record without a payee: the same page and summary as hidden.
        showPayees(alice, true);
        int version = ok(get(alice, uri + "/records/" + expense)).get("version").asInt();
        ok(patch(alice, uri + "/records/%d?version=%d".formatted(expense, version), "{\"payeeId\": null}"));
        assertThat(ok(get(bob, "/api/entries?size=200"))).isEqualTo(hiddenPage);
        assertThat(ok(get(bob, "/api/entries/summary"))).isEqualTo(hiddenSummary);
    }

    /** Never for a settlement, nor for an opening balance (a claimed seat's) or a correction, which have no record. */
    @Test
    void neverForASettlementOrAnOpeningBalance() throws IOException {
        showPayees(alice, true);
        created(post(alice, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, current)));
        // Kid shares the expense; Carol takes Kid's place after it, with an opening balance.
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        accept(carol, kidsPlace(utcToday().toString()), "Carol");
        created(post(alice, uri + "/records", """
                {"date": "%s", "categoryId": %d, "amount": "9.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "payeeId": %d}""".formatted(utcToday(), groceries, mum, current, shop)));

        assertThat(linked(alice)).filteredOn(line -> line.startsWith("SETTLEMENT")).singleElement().asString()
                .endsWith(" -");
        assertThat(linked(bob)).filteredOn(line -> line.startsWith("SETTLEMENT")).singleElement().asString()
                .endsWith(" -");
        List<String> carols = linked(carol);
        assertThat(carols).filteredOn(line -> line.startsWith("OPENING_BALANCE")).singleElement().asString()
                .isEqualTo("OPENING_BALANCE - -");
        assertThat(carols).filteredOn(line -> line.startsWith("SHARE")).singleElement().asString().endsWith(SHOP);
    }

    /** A payer who LEFT shows nothing: his links are detached, and the others' shares no longer name his payee. */
    @Test
    void neverWhenThePayerHasLeft() throws IOException {
        long dads = dadPaysWithAPayeeHeShows();
        assertThat(linked(alice)).contains("SHARE %d Bob's shop UX11".formatted(dads));
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(linked(alice)).contains("SHARE %d -".formatted(dads));
        assertThat(found(alice, "q=bob")).isZero();
    }

    /** Nor one who deleted all their data, FORMER. */
    @Test
    void neverWhenThePayerIsFormer() throws IOException {
        long dads = dadPaysWithAPayeeHeShows();
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(linked(alice)).contains("SHARE %d -".formatted(dads));
        assertThat(found(alice, "q=bob")).isZero();
        assertThat(ok(get(alice, "/api/entries/summary?q=bob")).get("count").asLong()).isZero();
    }

    private long dadPaysWithAPayeeHeShows() throws IOException {
        long bobsShop = newCounterparty(bob, "Bob's shop UX11");
        long dads = created(post(bob, uri + "/records", """
                {"date": "2026-09-15", "categoryId": %d, "amount": "9.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "payeeId": %d}""".formatted(groceries, dad, accountId(bob, "CURRENT_ACCOUNT"), bobsShop)))
                .get("id").asLong();
        showPayees(bob, true);
        return dads;
    }
}
