package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Posting per currency (F8a; D-45, D-87, D-10, D-14; ADR 0004) in the family of {@link FamilyApiTest}: a record's shares
 * and every line on a debt account are in the record's currency, so a debt account holds several; the paying side is
 * on its account in the account's currency, and when that isn't the record's, FX_EXCHANGE in the member's own ledger
 * takes the difference, with no rate. A change or a deletion re-posts every member's entries with the record, in the
 * same transaction; a refused one leaves all of them as they were. The invariants (per currency) hold after each test.
 */
class FamilyCurrencyPostingApiTests extends FamilyApiTest {

    private long usdCard;
    private long alicesCash;
    private long bobsCash;

    @BeforeEach
    void accounts() throws IOException {
        usdCard = body(post(alice, "/api/accounts", """
                {"code": "USD_CARD", "name": "Dollar card", "type": "ASSET", "defaultCurrency": "USD"}"""),
                HttpStatus.CREATED).get("id").asLong();
        alicesCash = accountId(alice, "CASH");
        bobsCash = accountId(bob, "CASH");
    }

    /**
     * 56.00 USD paid with Alice's dollar card, half each for Mum and Dad: no FX_EXCHANGE, the card and her debt account
     * in dollars, and both shares in dollars beside whatever euros their debt accounts hold.
     */
    @Test
    void aRecordAndItsAccountInTheSameCurrency() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-05", groceries, "10.00", mum,
                "\"paymentAccountId\": %d,".formatted(alicesCash), halves())));
        JsonNode paid = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "56.00", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d, "split": %s}""".formatted(groceries, mum, usdCard, halves())));
        long aliceDebt = accountId(alice, "FAMILY_DEBT_" + family);
        long bobDebt = accountId(bob, "FAMILY_DEBT_" + family);
        long aliceUnallocated = accountId(alice, "UNALLOCATED");
        long bobUnallocated = accountId(bob, "UNALLOCATED");
        assertThat(lines(alice, paid.get("yourPayment").get("entryId").asLong())).containsExactly(
                usdCard + " USD -56.00", aliceDebt + " USD 56.00");
        assertThat(posted(alice, paid)).containsExactlyInAnyOrder(
                "FAMILY_PAYMENT PAYMENT %d USD -56.00, %d USD 56.00".formatted(usdCard, aliceDebt),
                "FAMILY_SHARE SHARE %d USD 28.00 %d, %d USD -28.00".formatted(aliceUnallocated, groceries, aliceDebt));
        assertThat(posted(bob, paid)).containsExactly(
                "FAMILY_SHARE SHARE %d USD 28.00 %d, %d USD -28.00".formatted(bobUnallocated, groceries, bobDebt));
        // Each debt account holds both currencies; its postings show minus its member's balance in each (D-10).
        assertThat(debt(alice)).isEqualTo(Map.of("EUR", "5.00", "USD", "28.00"));
        assertThat(debt(bob)).isEqualTo(Map.of("EUR", "-5.00", "USD", "-28.00"));
    }

    /**
     * Dad pays 9000 RUB from his euro cash, 95.50 EUR as he names it (D-74, D-87): cash and FX_EXCHANGE in euros,
     * FX_EXCHANGE and his debt account in roubles, the shares in roubles. Then he receives a 3000 RUB income into the
     * same cash as 31.00 EUR, the other way round.
     */
    @Test
    void roublesPaidFromEuros() throws IOException {
        JsonNode paid = created(post(bob, uri + "/records", """
                {"date": "2026-09-14", "categoryId": %d, "amount": "9000", "currency": "RUB", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "95.50", "split": %s}"""
                .formatted(groceries, dad, bobsCash, halves())));
        long fx = accountId(bob, "FX_EXCHANGE");
        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        assertThat(lines(bob, paid.get("yourPayment").get("entryId").asLong())).containsExactly(
                bobsCash + " EUR -95.50", fx + " EUR 95.50", fx + " RUB -9000.00", debt + " RUB 9000.00");
        assertThat(posted(alice, paid)).containsExactly("FAMILY_SHARE SHARE %d RUB 4500.00 %d, %d RUB -4500.00"
                .formatted(accountId(alice, "UNALLOCATED"), groceries, accountId(alice, "FAMILY_DEBT_" + family)));

        JsonNode received = created(post(bob, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-15", "categoryId": %d, "amount": "3000", "currency": "RUB",
                 "payerMemberId": %d, "paymentAccountId": %d, "accountAmount": "31.00", "split": %s}"""
                .formatted(salary, dad, bobsCash, halves())));
        assertThat(lines(bob, received.get("yourPayment").get("entryId").asLong())).containsExactly(
                bobsCash + " EUR 31.00", fx + " EUR -31.00", fx + " RUB 3000.00", debt + " RUB -3000.00");
        assertThat(debt(bob)).isEqualTo(Map.of("RUB", "3000.00"));
        assertThat(debt(alice)).isEqualTo(Map.of("RUB", "-3000.00"));
    }

    /** Kid, who has no account, pays 30.00 USD: nobody's payment entry, and the shares in dollars. */
    @Test
    void aSeatWithoutAnAccountPays() throws IOException {
        JsonNode paid = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "30.00", "currency": "USD", "payerMemberId": %d}"""
                .formatted(groceries, kid)));
        assertThat(paid.has("yourPayment")).isFalse();
        assertThat(posted(alice, paid)).containsExactly("FAMILY_SHARE SHARE %d USD 10.00 %d, %d USD -10.00".formatted(
                accountId(alice, "UNALLOCATED"), groceries, accountId(alice, "FAMILY_DEBT_" + family)));
        assertThat(posted(bob, paid)).containsExactly("FAMILY_SHARE SHARE %d USD 10.00 %d, %d USD -10.00".formatted(
                accountId(bob, "UNALLOCATED"), groceries, accountId(bob, "FAMILY_DEBT_" + family)));
        assertThat(debt(alice)).isEqualTo(Map.of("USD", "-10.00"));
    }

    /**
     * A settlement in dollars (D-46) that Alice pays from her euro cash, 18.40 EUR as she names it: her side through
     * her FX_EXCHANGE, Bob's in dollars on his placeholder.
     */
    @Test
    void aSettlementInDollarsPaidFromEuros() throws IOException {
        JsonNode settled = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-10", "amount": "20.00", "currency": "USD", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "18.40"}""".formatted(mum, dad, alicesCash)));
        long fx = accountId(alice, "FX_EXCHANGE");
        assertThat(lines(alice, settled.get("yourPayment").get("entryId").asLong())).containsExactly(
                alicesCash + " EUR -18.40", fx + " EUR 18.40", fx + " USD -20.00",
                accountId(alice, "FAMILY_DEBT_" + family) + " USD 20.00");
        assertThat(posted(bob, settled)).containsExactly("FAMILY_SETTLEMENT SETTLEMENT %d USD 20.00, %d USD -20.00"
                .formatted(accountId(bob, "UNSPECIFIED_PAYMENTS"), accountId(bob, "FAMILY_DEBT_" + family)));
        assertThat(debt(alice)).isEqualTo(Map.of("USD", "20.00"));
        assertThat(debt(bob)).isEqualTo(Map.of("USD", "-20.00"));
    }

    /**
     * D-14: Dad's change of his rouble expense re-posts his payment and both shares with the record, in place (same
     * entries, new versions), in the one request; a refused change (a new amount without what went from his euro cash)
     * leaves the record and every entry as they were; his deletion takes every entry with it.
     */
    @Test
    void changesAndDeletionsRepostEveryEntryTogether() throws IOException {
        JsonNode paid = created(post(bob, uri + "/records", """
                {"date": "2026-09-14", "categoryId": %d, "amount": "9000", "currency": "RUB", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "95.50", "split": %s}"""
                .formatted(groceries, dad, bobsCash, halves())));
        String path = uri + "/records/" + paid.get("id").asLong();
        Map<Long, Integer> bobsBefore = entries(bob);
        Map<Long, Integer> alicesBefore = entries(alice);
        List<String> bobsPosted = posted(bob, paid);

        assertThat(details(patch(bob, path + "?version=0", """
                {"amount": "12000"}"""))).containsExactly(("ACCOUNT_AMOUNT %d the account is in EUR and the expense in "
                        + "RUB: name the amount that went from it").formatted(dad));
        assertThat(ok(get(bob, path)).get("version").asInt()).isZero();
        assertThat(entries(bob)).isEqualTo(bobsBefore);
        assertThat(entries(alice)).isEqualTo(alicesBefore);
        assertThat(posted(bob, paid)).isEqualTo(bobsPosted);

        JsonNode changed = ok(patch(bob, path + "?version=0", """
                {"amount": "12000", "accountAmount": "127.00"}"""));
        assertThat(changed.get("version").asInt()).isOne();
        assertThat(entries(bob).keySet()).isEqualTo(bobsBefore.keySet());
        assertThat(entries(alice).keySet()).isEqualTo(alicesBefore.keySet());
        bobsBefore.forEach((id, version) -> assertThat(entries(bob).get(id)).as("entry %d", id).isEqualTo(version + 1));
        alicesBefore.forEach((id, version) -> assertThat(entries(alice).get(id)).isEqualTo(version + 1));
        long fx = accountId(bob, "FX_EXCHANGE");
        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        assertThat(lines(bob, changed.get("yourPayment").get("entryId").asLong())).containsExactly(
                bobsCash + " EUR -127.00", fx + " EUR 127.00", fx + " RUB -12000.00", debt + " RUB 12000.00");
        assertThat(posted(alice, changed)).containsExactly("FAMILY_SHARE SHARE %d RUB 6000.00 %d, %d RUB -6000.00"
                .formatted(accountId(alice, "UNALLOCATED"), groceries, accountId(alice, "FAMILY_DEBT_" + family)));

        assertThat(delete(bob, path + "?version=1")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(entries(bob)).isEmpty();
        assertThat(entries(alice)).isEmpty();
        assertThat(debt(bob)).isEmpty();
    }

    /** Mum and Dad half each. */
    private String halves() {
        return """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}""".formatted(mum, dad);
    }

    /** The user's entries posted from the family ledger, by id, with their versions. */
    private Map<Long, Integer> entries(String user) {
        Map<Long, Integer> entries = new TreeMap<>();
        try {
            for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
                if (!entry.get("family").isNull()) {
                    entries.put(entry.get("id").asLong(), entry.get("version").asInt());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return entries;
    }

    /** The user's entries of the record as "KIND LINK account CUR amount [category], …". */
    private List<String> posted(String user, JsonNode record) throws IOException {
        List<String> posted = new ArrayList<>();
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            if (!entry.get("family").isNull()
                    && entry.get("family").get("recordId").asLong() == record.get("id").asLong()) {
                List<String> postings = new ArrayList<>();
                entry.get("postings").forEach(p -> postings.add(p.get("accountId").asLong() + " "
                        + p.get("currency").asText() + " " + p.get("amount").asText()
                        + (p.get("categoryId").isNull() ? "" : " " + p.get("categoryId").asLong())));
                posted.add(entry.get("kind").asText() + " " + entry.get("family").get("link").asText() + " "
                        + String.join(", ", postings));
            }
        }
        return posted;
    }

    /** An entry's postings as "account CUR amount", in their order. */
    private List<String> lines(String user, long entryId) throws IOException {
        List<String> lines = new ArrayList<>();
        ok(get(user, "/api/entries/" + entryId)).get("postings").forEach(p -> lines.add(p.get("accountId").asLong()
                + " " + p.get("currency").asText() + " " + p.get("amount").asText()));
        return lines;
    }

    /** The user's debt account for the family, by currency, as the sum of its postings. */
    private Map<String, String> debt(String user) throws IOException {
        Map<String, String> sums = new TreeMap<>();
        jdbc.sql("""
                SELECT p.currency, sum(p.amount) AS total FROM posting p
                WHERE p.account_id = ? GROUP BY p.currency""")
                .param(accountId(user, "FAMILY_DEBT_" + family))
                .query(row -> {
                    sums.put(row.getString("currency"), row.getBigDecimal("total").setScale(2).toPlainString());
                });
        return sums;
    }
}
