package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * UX-12, D-547, D-548: {@code GET /api/entries/family-payees} lists the payees that family records show on the user's
 * entries, by {@code RecordPayees}' rule, each name once in any case with its family budgets; and {@code payee} on the
 * entry list and its summary finds the entries whose Payee cell shows a name: the entry's payee, a line's
 * counterparty, or for an entry with neither its shown record payee.
 */
class FamilyPayeeFilterApiTests extends FamilyApiTest {

    private static final String SHOP = "Trefpunt UX12";

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

    private void showPayees(String user, String budget, boolean on) throws IOException {
        ok(patch(user, budget + "/me", "{\"showsPayees\": %s}".formatted(on)));
    }

    /** The family payees as "name: budget, budget". */
    private List<String> familyPayees(String user) throws IOException {
        List<String> lines = new ArrayList<>();
        for (JsonNode payee : ok(get(user, "/api/entries/family-payees"))) {
            List<String> budgets = new ArrayList<>();
            payee.get("budgets").forEach(b -> budgets.add(b.get("name").asText() + "#" + b.get("id").asLong()));
            lines.add(payee.get("name").asText() + ": " + String.join(", ", budgets));
        }
        return lines;
    }

    /** The filter; the test's request encodes the URI itself, so the name goes in as it is. */
    private static String byName(String name) {
        return "payee=" + name;
    }

    private long found(String user, String query) throws IOException {
        return ok(get(user, "/api/entries?" + query)).get("totalElements").asLong();
    }

    private long counted(String user, String query) throws IOException {
        return ok(get(user, "/api/entries/summary?" + query)).get("count").asLong();
    }

    @Test
    void thePayersOwnPayeeIsListedAndFindsHerShareAndPayment() throws IOException {
        assertThat(familyPayees(alice)).containsExactly(SHOP + ": Home#" + family);
        assertThat(found(alice, byName(SHOP))).isEqualTo(2);
        assertThat(found(alice, byName("  trefpunt ux12  "))).isEqualTo(2);
        assertThat(counted(alice, byName("TREFPUNT UX12"))).isEqualTo(2);
        assertThat(ok(get(alice, "/api/entries/summary?" + byName(SHOP))).get("payees").asLong()).isEqualTo(1);
        assertThat(found(alice, byName("Trefpunt"))).isZero();
        // Blank is absent.
        assertThat(found(alice, "payee=  ")).isEqualTo(found(alice, ""));
    }

    /** Dad's list and filter follow Mum's switch: hidden never listed and never found, shown both. */
    @Test
    void anotherMembersShownPayeeOnlyWhileShown() throws IOException {
        assertThat(familyPayees(bob)).isEmpty();
        assertThat(found(bob, byName(SHOP))).isZero();
        assertThat(counted(bob, byName(SHOP))).isZero();

        showPayees(alice, uri, true);
        assertThat(familyPayees(bob)).containsExactly(SHOP + ": Home#" + family);
        assertThat(found(bob, byName(SHOP))).isEqualTo(1);
        JsonNode summary = ok(get(bob, "/api/entries/summary?" + byName(SHOP)));
        assertThat(summary.get("count").asLong()).isEqualTo(1);
        assertThat(summary.get("payees").asLong()).isEqualTo(1);
        JsonNode share = ok(get(bob, "/api/entries?" + byName(SHOP))).get("content").get(0);
        assertThat(share.get("family").get("link").asText()).isEqualTo("SHARE");
        assertThat(share.get("family").get("recordId").asLong()).isEqualTo(expense);

        showPayees(alice, uri, false);
        assertThat(familyPayees(bob)).isEmpty();
        assertThat(found(bob, byName(SHOP))).isZero();
    }

    /**
     * Two spellings are one name, spelled as on the newest entry that shows it, with every budget it comes from, by
     * name; a non-member gets none of it.
     */
    @Test
    void oneNamePerSpellingWithItsBudgetsAndNoneForAnotherUser() throws IOException {
        showPayees(alice, uri, true);
        // Dad pays in Home with his own payee of the same name in capitals, later than Mum's.
        long bobsShop = newCounterparty(bob, SHOP.toUpperCase());
        created(post(bob, uri + "/records", """
                {"date": "2026-09-20", "categoryId": %d, "amount": "8.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "payeeId": %d}""".formatted(groceries, dad, accountId(bob, "CURRENT_ACCOUNT"), bobsShop)));
        // A second budget, "Away", where Mum pays the same shop, shown there too.
        JsonNode away = newFamily(alice, """
                {"name": "Away", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-09-01"}""");
        long awayId = away.get("id").asLong();
        String awayUri = "/api/family-ledgers/" + awayId;
        join(awayId, bob, "Dad", "MEMBER", LocalDate.of(2026, 9, 1));
        long travel = body(post(alice, awayUri + "/categories", """
                {"code": "TRAVEL_UX12", "name": "Travel", "type": "EXPENSE"}"""), HttpStatus.CREATED).get("id")
                .asLong();
        created(post(alice, awayUri + "/records", """
                {"date": "2026-09-05", "categoryId": %d, "amount": "12.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "payeeId": %d}""".formatted(travel, away.get("memberId").asLong(), current,
                shop)));
        showPayees(alice, awayUri, true);

        assertThat(familyPayees(bob)).containsExactly(
                "%s: Away#%d, Home#%d".formatted(SHOP.toUpperCase(), awayId, family));
        // His own counterparty and her shown payee find four of his entries: his payment, his share of his own record,
        // and his shares of both of hers.
        assertThat(found(bob, byName(SHOP))).isEqualTo(4);

        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        assertThat(familyPayees(carol)).isEmpty();
        assertThat(found(carol, byName(SHOP))).isZero();
    }

    /** The entry's own payee and a line's counterparty, by name; with {@code counterpartyId} 400; over 100, 400. */
    @Test
    void ownEntriesByTheirPayeeOrALinesCounterparty() throws IOException {
        long baker = newCounterparty(alice, "Baker UX12");
        newExpense(alice, "2026-09-03", "4.00", baker, null);
        long friend = newCounterparty(alice, "Friend UX12");
        newEntry(alice, """
                {"kind": "LOAN_GIVEN", "entryDate": "2026-09-04", "fromAccountId": %d, "counterpartyId": %d,
                 "currency": "EUR", "amount": "100"}""".formatted(current, friend));
        assertThat(found(alice, byName("baker ux12"))).isEqualTo(1);
        assertThat(found(alice, byName("FRIEND UX12"))).isEqualTo(1);
        assertThat(counted(alice, byName("Friend UX12"))).isEqualTo(1);

        for (String query : List.of(byName("Baker UX12") + "&counterpartyId=" + baker,
                byName("x".repeat(101)))) {
            assertThat(get(alice, "/api/entries?" + query)).as(query).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(alice, "/api/entries/summary?" + query)).as(query).hasStatus(HttpStatus.BAD_REQUEST);
        }
        // 100 after trimming is fine.
        assertThat(found(alice, byName("  " + "x".repeat(100) + "  "))).isZero();
    }
}
