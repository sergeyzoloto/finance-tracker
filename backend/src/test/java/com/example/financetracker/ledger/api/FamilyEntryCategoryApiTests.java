package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * UX-9, D-477 and D-478: a personal entry linked to a family record names the record's category
 * ({@code family.recordCategory}), so that the payer's payment and an income's receipt read with it in the entry list,
 * as a share does; and the entry list's Category filter finds such an entry by that category, exactly when its
 * Category cell shows it. Display only: no posting changes.
 */
class FamilyEntryCategoryApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    /** The user's entries linked to the family budget, by link type, then entry id: "link:recordCategory name". */
    private List<String> linked(String user) throws IOException {
        List<String> lines = new ArrayList<>();
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            JsonNode family = entry.get("family");
            if (family.isNull()) {
                continue;
            }
            JsonNode category = family.get("recordCategory");
            lines.add(family.get("link").asText() + ":" + family.path("recordType").asText("-") + ":"
                    + (category == null ? "none" : category.get("id").asLong() + " " + category.get("name").asText()));
        }
        return lines.stream().sorted().toList();
    }

    private JsonNode filtered(String user, long categoryId, String more) throws IOException {
        return ok(get(user, "/api/entries?categoryId=" + categoryId + more));
    }

    private static List<String> links(JsonNode page) {
        List<String> lines = new ArrayList<>();
        page.get("content").forEach(e -> lines.add(e.get("family").isNull() ? "own" : e.get("family").get("link").asText()));
        return lines.stream().sorted().toList();
    }

    /** A payment of an expense, of a refund and an income's receipt name the record's category; so does a share. */
    @Test
    void paymentsReceiptsAndSharesNameTheRecordsCategory() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        String paid = "\"paymentAccountId\": %d,".formatted(current);
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum, paid)));
        created(post(alice, uri + "/records", """
                {"date": "2026-09-11", "categoryId": %d, "amount": "6.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "refund": true}""".formatted(rent, mum, current)));
        created(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-12", "categoryId": %d, "amount": "90.00", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(salary, mum, current)));
        // A settlement has no category: neither side's entry names one.
        created(post(alice, uri + "/settlements", """
                {"date": "2026-09-13", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d, "paymentLater": true}"""
                .formatted(mum, dad)));

        assertThat(linked(alice)).containsExactly(
                "PAYMENT:EXPENSE:%d Groceries".formatted(groceries),
                "PAYMENT:EXPENSE:%d Rent".formatted(rent),
                "PAYMENT:INCOME:%d Salary".formatted(salary),
                "SETTLEMENT:SETTLEMENT:none",
                "SHARE:EXPENSE:%d Groceries".formatted(groceries),
                "SHARE:EXPENSE:%d Rent".formatted(rent),
                "SHARE:INCOME:%d Salary".formatted(salary));
        assertThat(linked(bob)).containsExactly(
                "SETTLEMENT:SETTLEMENT:none",
                "SHARE:EXPENSE:%d Groceries".formatted(groceries),
                "SHARE:EXPENSE:%d Rent".formatted(rent),
                "SHARE:INCOME:%d Salary".formatted(salary));
        // The payment itself has no categorized line: display only.
        for (JsonNode entry : ok(get(alice, "/api/entries?size=200")).get("content")) {
            if (!entry.get("family").isNull() && entry.get("family").get("link").asText().equals("PAYMENT")) {
                entry.get("postings").forEach(p -> assertThat(p.get("categoryId").isNull()).isTrue());
            }
        }

        // Renamed and archived, the family category is named as it is now.
        ok(patch(alice, uri + "/categories/" + groceries, """
                {"name": "Food", "archived": true}"""));
        assertThat(linked(alice)).contains("PAYMENT:EXPENSE:%d Food".formatted(groceries),
                "SHARE:EXPENSE:%d Food".formatted(groceries));
    }

    /** An opening balance and a correction are about the member, not a record: no category. */
    @Test
    void anOpeningBalanceAndACorrectionNameNoCategory() throws IOException {
        long early = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, LATER)))
                .get("id").asLong();
        created(post(bob, uri + "/records", expense("2026-09-10", groceries, "60.00", dad, LATER)));

        // Carol takes Kid's place from 09-20: Kid's balance before it is her opening balance.
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        accept(carol, kidsPlace("2026-09-20"), "Carol");
        assertThat(linked(carol)).containsExactly("OPENING_BALANCE:-:none");

        // Dad leaves and comes back; a change before his return goes into his correction.
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        accept(bob, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Dad");
        ok(patch(alice, uri + "/records/" + early + "?version=0", """
                {"amount": "120.00"}"""));
        assertThat(linked(bob)).containsExactly("CORRECTION:-:none");
    }

    /**
     * D-478: the Category filter finds the payment by its record's category, as the share; not by another category;
     * not once its link is detached; with the count and paging as for any filter.
     */
    @Test
    void theCategoryFilterFindsAPaymentByItsRecordsCategory() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "10.00", mum,
                "\"paymentAccountId\": %d,".formatted(current))));
        created(post(alice, uri + "/records", expense("2026-09-11", rent, "20.00", mum, LATER)));
        long ownGroceries = newEntry(alice, """
                {"kind": "EXPENSE", "entryDate": "2026-09-12", "accountId": %d, "currency": "EUR", "amount": "3.00",
                 "categoryId": %d}""".formatted(current, groceries)).get("id").asLong();

        JsonNode groceriesPage = filtered(alice, groceries, "");
        assertThat(links(groceriesPage)).containsExactly("PAYMENT", "SHARE", "own");
        assertThat(groceriesPage.get("totalElements").asLong()).isEqualTo(3);
        assertThat(links(filtered(alice, rent, ""))).containsExactly("PAYMENT", "SHARE");
        // Paged: one a page, three pages, newest first.
        JsonNode first = filtered(alice, groceries, "&size=1&page=0");
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("content").get(0).get("id").asLong()).isEqualTo(ownGroceries);
        assertThat(links(filtered(alice, groceries, "&size=1&page=2"))).hasSize(1);
        // A category of no record finds nothing of the family's.
        assertThat(links(filtered(alice, categoryId(alice, "SALARY"), ""))).isEmpty();

        // Dad's own payment, then he leaves: his links are detached, so the family category no longer finds them.
        created(post(bob, uri + "/records", expense("2026-09-14", groceries, "8.00", dad,
                "\"paymentAccountId\": %d,".formatted(accountId(bob, "CURRENT_ACCOUNT")))));
        assertThat(links(filtered(bob, groceries, ""))).containsExactly("PAYMENT", "SHARE", "SHARE");
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(filtered(bob, groceries, "").get("totalElements").asLong()).isZero();
        for (JsonNode entry : ok(get(bob, "/api/entries?size=200")).get("content")) {
            assertThat(entry.get("family").isNull()).isTrue();
        }
    }
}
