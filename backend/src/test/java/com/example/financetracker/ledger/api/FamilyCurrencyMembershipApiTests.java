package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Membership flows per currency (F8a; D-31, D-35, D-26, D-39, D-20, D-45; ADR 0004) in the family of
 * {@link FamilyApiTest}: a claimed seat's opening balance and a returning member's correction are one entry each, with
 * a pair of lines per currency whose amount isn't 0, kept in step as records before the join date change; the invite
 * lists them per currency; the deletion preview lists a member's balance in each currency. The invariants (per currency)
 * hold after each test.
 */
class FamilyCurrencyMembershipApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    /**
     * Before 09-15, Kid shares 90.00 EUR that Mum paid (+30.00) and pays 30.00 USD shared in thirds (+10.00 −30.00):
     * Carol, taking Kid's place on 09-15, takes on 30.00 EUR and −20.00 USD in one opening entry. A change of the
     * dollars before her date moves the dollar pair only; deleting the euros removes the euro pair.
     */
    @Test
    void aClaimsOpeningBalanceInEachCurrency() throws IOException {
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        JsonNode euros = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, LATER)));
        JsonNode dollars = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "30.00", "currency": "USD", "payerMemberId": %d}"""
                .formatted(groceries, kid)));
        created(post(alice, uri + "/records", expense("2026-09-20", groceries, "60.00", mum, LATER)));

        JsonNode lookup = ok(inviteCall(carol, "lookup", token(kidsPlace("2026-09-15"), null)));
        assertThat(amounts(lookup.get("openingBalances"))).containsExactly("EUR 30.00", "USD -20.00");
        assertThat(lookup.get("openingBalance").asText()).isEqualTo("30.00");
        assertThat(lookup.get("corrections").isNull()).isTrue();
        accept(carol, kidsPlace("2026-09-15"), "Carol");
        long debt = accountId(carol, "FAMILY_DEBT_" + family);
        long opening = accountId(carol, "OPENING_BALANCE");
        assertThat(openingLines(carol, "FAMILY_OPENING")).containsExactly(debt + " EUR -30.00",
                opening + " EUR 30.00", debt + " USD 20.00", opening + " USD -20.00");

        // Her own dollars before her date (D-32): 45.00 now, so −30.00.
        ok(patch(carol, uri + "/records/" + dollars.get("id").asLong() + "?version=0", """
                {"amount": "45.00"}"""));
        assertThat(openingLines(carol, "FAMILY_OPENING")).containsExactly(debt + " EUR -30.00",
                opening + " EUR 30.00", debt + " USD 30.00", opening + " USD -30.00");
        assertThat(delete(alice, uri + "/records/" + euros.get("id").asLong() + "?version=0"))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(openingLines(carol, "FAMILY_OPENING")).containsExactly(debt + " USD 30.00",
                opening + " USD -30.00");
        integrity(alice, bob, carol);
    }

    /**
     * Dad owes 30.00 EUR (his third of 90.00) and is owed 40.00 USD (he paid 60.00 USD of which a third is his), and the
     * deletion preview lists both. He leaves and comes back: his old debt account shows both, so the lookup lists no
     * correction. Then Mum's euros become 120.00 (his share 40.00) and his own dollars 90.00 (his share 30.00), both
     * dated before his return: one correction entry, +10.00 EUR and −20.00 USD (D-39).
     */
    @Test
    void aReturnsCorrectionInEachCurrency() throws IOException {
        JsonNode euros = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, LATER)));
        JsonNode dollars = created(post(bob, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "60.00", "currency": "USD", "payerMemberId": %d,
                 "paymentLater": true}""".formatted(groceries, dad)));
        JsonNode preview = ok(get(bob, "/api/me/family-memberships")).get("memberships").get(0);
        assertThat(amounts(preview.get("balances"))).containsExactly("EUR 30.00", "USD -40.00");
        assertThat(preview.get("balance").asText()).isEqualTo("30.00");

        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        String invite = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        JsonNode lookup = ok(inviteCall(bob, "lookup", token(invite, null)));
        assertThat(lookup.get("returning").asBoolean()).isTrue();
        assertThat(lookup.get("corrections")).isEmpty();
        assertThat(lookup.get("correction").asText()).isEqualTo("0.00");
        accept(bob, invite, "Dad");
        assertThat(openingLines(bob, "FAMILY_CORRECTION")).isEmpty();
        integrity(alice, bob);

        ok(patch(alice, uri + "/records/" + euros.get("id").asLong() + "?version=0", """
                {"amount": "120.00"}"""));
        ok(patch(bob, uri + "/records/" + dollars.get("id").asLong() + "?version=0", """
                {"amount": "90.00"}"""));
        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        long opening = accountId(bob, "OPENING_BALANCE");
        assertThat(openingLines(bob, "FAMILY_CORRECTION")).containsExactly(debt + " EUR -10.00",
                opening + " EUR 10.00", debt + " USD 20.00", opening + " USD -20.00");
        integrity(alice, bob);
    }

    /** D-10 in each currency, for each user's personal ledger, and the family's invariants. */
    private void integrity(String... users) throws IOException {
        for (String user : users) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
        com.example.financetracker.ledger.family.FamilyInvariants.check(jdbc, family);
    }

    /** A list of amounts as "CUR amount". */
    private static List<String> amounts(JsonNode amounts) {
        List<String> texts = new ArrayList<>();
        amounts.forEach(a -> texts.add(a.get("currency").asText() + " " + a.get("amount").asText()));
        return texts;
    }

    /** The lines of the user's one entry of the kind, as "account CUR amount"; none without one. */
    private List<String> openingLines(String user, String kind) throws IOException {
        List<String> lines = new ArrayList<>();
        int entries = 0;
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            if (entry.get("kind").asText().equals(kind)) {
                entries++;
                entry.get("postings").forEach(p -> lines.add(p.get("accountId").asLong() + " "
                        + p.get("currency").asText() + " " + p.get("amount").asText()));
            }
        }
        assertThat(entries).isLessThanOrEqualTo(1);
        return lines;
    }
}
