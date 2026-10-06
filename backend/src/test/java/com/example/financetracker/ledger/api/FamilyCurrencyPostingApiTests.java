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
                {"amount": "12000"}"""))).containsExactly(("ACCOUNT_AMOUNT %d your side is in EUR and the expense in "
                        + "RUB: name the amount that went from the account").formatted(dad));
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

    /**
     * D-89: the paying currency is chosen per payment. A 56.00 USD expense paid in dollars from Alice's euro cash, an
     * account that holds any currency, is an ordinary payment in dollars: no FX_EXCHANGE and no {@code accountAmount}.
     * Left to the cash's default, euros, it asks for the euros (422), and with them goes through FX_EXCHANGE. The paying
     * side is hers alone (D-88): Bob's answers and the journal hold neither its amount nor its currency.
     */
    @Test
    void aDollarRecordPaidInDollarsFromAEuroAccount() throws IOException {
        String paid = """
                {"date": "2026-09-10", "categoryId": %d, "amount": "56.00", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d, "split": %s%s}""";
        JsonNode inDollars = created(post(alice, uri + "/records", paid.formatted(groceries, mum, alicesCash, halves(),
                ", \"accountCurrency\": \"USD\"")));
        long aliceDebt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(lines(alice, inDollars.get("yourPayment").get("entryId").asLong())).containsExactly(
                alicesCash + " USD -56.00", aliceDebt + " USD 56.00");
        assertThat(own(inDollars)).isEqualTo("56.00 USD");

        assertThat(details(post(alice, uri + "/records", paid.formatted(groceries, mum, alicesCash, halves(), ""))))
                .containsExactly(("ACCOUNT_AMOUNT %d your side is in EUR and the expense in USD: name the amount that "
                        + "went from the account").formatted(mum));
        JsonNode inEuros = created(post(alice, uri + "/records", paid.formatted(groceries, mum, alicesCash, halves(),
                ", \"accountCurrency\": \"EUR\", \"accountAmount\": \"51.50\"")));
        long fx = accountId(alice, "FX_EXCHANGE");
        assertThat(lines(alice, inEuros.get("yourPayment").get("entryId").asLong())).containsExactly(
                alicesCash + " EUR -51.50", fx + " EUR 51.50", fx + " USD -56.00", aliceDebt + " USD 56.00");
        assertThat(own(inEuros)).isEqualTo("51.50 EUR");
        assertThat(inEuros.has("originalAmount") || inEuros.has("originalCurrency")).isFalse();

        // D-88: Bob sees the record's amount, currency and shares, and nothing of her side.
        for (String read : List.of("/records/" + inEuros.get("id").asLong(), "/records",
                "/journal?recordId=" + inEuros.get("id").asLong())) {
            JsonNode his = ok(get(bob, uri + read));
            assertThat(his.findValues("originalAmount")).as(read).isEmpty();
            assertThat(his.findValues("originalCurrency")).as(read).isEmpty();
            assertThat(his.findValues("yourPayment")).as(read).isEmpty();
            assertThat(his.toString()).as(read).doesNotContain("51.50", "EUR");
        }
        JsonNode his = ok(get(bob, uri + "/records/" + inEuros.get("id").asLong()));
        assertThat(his.get("amount").asText() + " " + his.get("currency").asText()).isEqualTo("56.00 USD");
        assertThat(his.get("shares").findValuesAsText("amount")).containsExactly("28.00", "28.00");
        // The paying currency goes only with an account of hers: "Specify later" is in the record's currency.
        assertThat(details(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "56.00", "currency": "USD", "payerMemberId": %d,
                 "paymentLater": true, "accountCurrency": "EUR"}""".formatted(groceries, mum)))).containsExactly(
                ("ACCOUNT_AMOUNT %d a paying currency other than the expense's, USD, goes only with an account of "
                        + "yours; \"Specify later\" is in USD").formatted(mum));
    }

    /**
     * D-89: {@code accountAmount} is asked again when the record's amount or currency, the account or the paying
     * currency changes, and kept when only the date or the comment changes. Alice pays 56.00 USD from her euro cash,
     * 51.50 EUR; each refused change leaves the record at its version.
     */
    @Test
    void theAccountAmountIsAskedAgainOnlyWhenTheSideMoves() throws IOException {
        long savings = accountId(alice, "SAVINGS_ACCOUNT");
        JsonNode paid = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "56.00", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "51.50", "split": %s}"""
                .formatted(groceries, mum, alicesCash, halves())));
        String path = uri + "/records/" + paid.get("id").asLong();
        String asked = ("ACCOUNT_AMOUNT %d your side is in EUR and the expense in USD: name the amount that went from "
                + "the account").formatted(mum);

        JsonNode dated = ok(patch(alice, path + "?version=0", """
                {"date": "2026-09-11"}"""));
        assertThat(own(dated) + " v" + dated.get("version").asInt()).isEqualTo("51.50 EUR v1");
        JsonNode commented = ok(patch(alice, path + "?version=1", """
                {"comment": "Market"}"""));
        assertThat(own(commented) + " v" + commented.get("version").asInt()).isEqualTo("51.50 EUR v2");

        assertThat(details(patch(alice, path + "?version=2", """
                {"amount": "60.00"}"""))).containsExactly(asked);
        assertThat(details(patch(alice, path + "?version=2", """
                {"currency": "GBP", "amount": "45.00"}"""))).containsExactly(asked.replace("USD", "GBP"));
        assertThat(details(patch(alice, path + "?version=2", """
                {"paymentAccountId": %d}""".formatted(savings)))).containsExactly(asked);
        assertThat(details(patch(alice, path + "?version=2", """
                {"accountCurrency": "GBP"}"""))).containsExactly(asked.replace("in EUR", "in GBP"));
        assertThat(ok(get(alice, path)).get("version").asInt()).isEqualTo(2);
        assertThat(own(ok(get(alice, path)))).isEqualTo("51.50 EUR");

        // To another account in euros, with its euros: the side moves, neither version nor journal (D-16).
        JsonNode moved = ok(patch(alice, path + "?version=2", """
                {"paymentAccountId": %d, "accountAmount": "51.60"}""".formatted(savings)));
        assertThat(own(moved) + " v" + moved.get("version").asInt()).isEqualTo("51.60 EUR v2");
        // In dollars from the same savings account: the record's amount, no amount to name.
        JsonNode dollars = ok(patch(alice, path + "?version=2", """
                {"accountCurrency": "USD"}"""));
        assertThat(own(dollars)).isEqualTo("56.00 USD");
        assertThat(lines(alice, dollars.get("yourPayment").get("entryId").asLong())).containsExactly(
                savings + " USD -56.00", accountId(alice, "FAMILY_DEBT_" + family) + " USD 56.00");
        // The choice stays with the payment: a new amount keeps it in dollars, and asks for nothing.
        JsonNode more = ok(patch(alice, path + "?version=2", """
                {"amount": "58.00"}"""));
        assertThat(own(more) + " v" + more.get("version").asInt()).isEqualTo("58.00 USD v3");
        // Through her payment entry (F4c): back to euros, with them.
        long entry = more.get("yourPayment").get("entryId").asLong();
        int entryVersion = ok(get(alice, "/api/entries/" + entry)).get("version").asInt();
        assertThat(details(patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(entry, entryVersion),
                """
                {"accountCurrency": "EUR"}"""))).containsExactly(asked);
        assertThat(patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(entry, entryVersion), """
                {"accountCurrency": "EUR", "accountAmount": "53.40"}""")).hasStatus(HttpStatus.OK);
        assertThat(own(ok(get(alice, path)))).isEqualTo("53.40 EUR");
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + paid.get("id").asLong()))))
                .noneMatch(change -> change.contains("53.40") || change.contains("51.5"));
    }

    /**
     * D-89 for a settlement's other side: Bob puts his side of a 20.00 USD settlement on his euro cash in dollars,
     * with nothing to name, or in euros with the euros.
     */
    @Test
    void aSettlementsOtherSideChoosesItsPayingCurrency() throws IOException {
        JsonNode settled = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-10", "amount": "20.00", "currency": "USD", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, usdCard)));
        String path = uri + "/records/" + settled.get("id").asLong();
        assertThat(details(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d}""".formatted(bobsCash)))).containsExactly(
                "ACCOUNT_AMOUNT %d your side is in EUR: name the amount that went into the account".formatted(dad));
        JsonNode inDollars = ok(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d, "accountCurrency": "USD"}""".formatted(bobsCash)));
        assertThat(own(inDollars)).isEqualTo("20.00 USD");
        assertThat(lines(bob, inDollars.get("yourPayment").get("entryId").asLong())).containsExactly(
                bobsCash + " USD 20.00", accountId(bob, "FAMILY_DEBT_" + family) + " USD -20.00");
        JsonNode inEuros = ok(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d, "accountCurrency": "EUR", "accountAmount": "18.30"}""".formatted(bobsCash)));
        assertThat(own(inEuros)).isEqualTo("18.30 EUR");
        assertThat(ok(get(alice, path)).toString()).doesNotContain("18.30");
    }

    /** The reader's own side, from their {@code yourPayment}, as "amount CUR". */
    private static String own(JsonNode record) {
        return record.get("yourPayment").get("amount").asText() + " " + record.get("yourPayment").get("currency")
                .asText();
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
