package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Records in other currencies (F4e; D-13; ADR 0003, topics D and E) in the family of {@link FamilyApiTest}, whose base
 * currency is EUR: the base amount from the ECB's rate of the date or the latest before it, else from the acting
 * member's own manual rate, else entered ({@code RATE_MISSING} without one); an entered base amount always wins; HALF_UP
 * to the base currency's minor unit; a currency without minor units on either side. The payment in another currency
 * goes through the payer's FX_EXCHANGE and every entry balances per currency, with the debt accounts in EUR. A
 * settlement recorded in USD, whose other side puts its part on a RUB account with the amount it received there; and
 * edits that convert the base amount again, with their journal lines. The ECB's rates here are the tests' own,
 * removed afterwards.
 */
class FamilyCurrencyApiTests extends FamilyApiTest {

    private long usdCard;
    private long rubAccount;

    @BeforeEach
    void accountsInOtherCurrencies() throws IOException {
        usdCard = body(post(alice, "/api/accounts", """
                {"code": "USD_CARD", "name": "Dollar card", "type": "ASSET", "defaultCurrency": "USD"}"""),
                HttpStatus.CREATED).get("id").asLong();
        rubAccount = body(post(bob, "/api/accounts", """
                {"code": "RUB_ACCOUNT", "name": "Rouble account", "type": "ASSET", "defaultCurrency": "RUB"}"""),
                HttpStatus.CREATED).get("id").asLong();
    }

    @AfterEach
    void removeTheTestsEcbRates() {
        jdbc.sql("DELETE FROM exchange_rate WHERE user_id IS NULL AND rate_date BETWEEN ? AND ?")
                .params(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 30)).update();
    }

    /**
     * Alice pays 56.00 USD from her dollar card on 2026-09-10, when the ECB has 1.12 dollars to the euro: 50.00 EUR,
     * split 25.00 each. Her payment goes card −56.00 USD, FX_EXCHANGE +56.00 USD and −50.00 EUR, debt +50.00 EUR; Bob
     * sees the original amount and the rate, never her card. A Saturday takes Friday's rate.
     */
    @Test
    void aCardInAnotherCurrencyPaysThroughFxExchange() throws IOException {
        ecb("USD", "2026-09-09", "1.10");
        ecb("USD", "2026-09-10", "1.12");
        JsonNode paid = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "56.00", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d, "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}}""".formatted(groceries, mum, usdCard, mum, dad)));
        assertThat(money(paid)).isEqualTo("50.00 EUR from 56.00 USD at 0.892857142857 ECB 2026-09-10");
        assertThat(shares(paid)).containsExactly("Mum 25.00 5000", "Dad 25.00 5000");
        assertThat(paid.get("yourPayment").get("amount").asText()).isEqualTo("56.00");
        assertThat(paid.get("yourPayment").get("currency").asText()).isEqualTo("USD");
        long fx = accountId(alice, "FX_EXCHANGE");
        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(lines(alice, paid.get("yourPayment").get("entryId").asLong())).containsExactly(
                usdCard + " USD -56.00", fx + " USD 56.00", fx + " EUR -50.00", debt + " EUR 50.00");
        assertThat(balances(bob)).containsExactly("Mum -25.00", "Dad 25.00 you", "Kid 0.00");

        JsonNode asBob = ok(get(bob, uri + "/records/" + paid.get("id").asLong()));
        assertThat(money(asBob)).isEqualTo(money(paid));
        assertThat(asBob.has("yourPayment")).isFalse();
        assertThat(asBob.toString()).doesNotContain("Dollar card", "USD_CARD", String.valueOf(usdCard));
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + paid.get("id").asLong()))).getFirst()).startsWith(
                "CREATE by Mum: date null→2026-09-10, category null→Groceries, amount null→50.00, "
                        + "originalAmount null→56.00 USD, payer null→Mum");

        // Saturday 2026-09-12 takes the latest rate before it, 2026-09-10's here.
        assertThat(money(ok(get(alice, uri + "/conversion?amount=11.20&currency=USD&date=2026-09-12"))))
                .isEqualTo("10.00 EUR from 11.20 USD at 0.892857142857 ECB 2026-09-10");
        JsonNode saturday = created(post(alice, uri + "/records", """
                {"date": "2026-09-12", "categoryId": %d, "amount": "11.20", "currency": "USD", "payerMemberId": %d,
                 "paymentLater": true}""".formatted(groceries, mum)));
        assertThat(money(saturday)).isEqualTo("10.00 EUR from 11.20 USD at 0.892857142857 ECB 2026-09-10");
        // "Specify later" in dollars: the placeholder holds the dollars, through FX_EXCHANGE too.
        long placeholder = accountId(alice, "UNSPECIFIED_PAYMENTS");
        assertThat(lines(alice, saturday.get("yourPayment").get("entryId").asLong())).containsExactly(
                placeholder + " USD -11.20", fx + " USD 11.20", fx + " EUR -10.00", debt + " EUR 10.00");
        assertThat(ok(get(alice, "/api/reports/integrity"))).isEmpty();
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();
    }

    /**
     * Where the base amount comes from: the ECB's rate when it has one, at any age; else the acting member's own manual
     * rate (RUB, which the ECB doesn't publish); else the request gives it (RATE_MISSING). An entered base amount always
     * wins. HALF_UP to the cent, from the two euro rates.
     */
    @Test
    void theBaseAmountFromTheEcbAManualRateOrEntered() throws IOException {
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "EUR", "quote": "RUB", "rate": "100"}"""));
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "EUR", "quote": "USD", "rate": "1.25"}"""));
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-09-10", "base": "EUR", "quote": "USD", "rate": "1.30"}"""));
        ecb("USD", "2026-09-10", "1.12");
        String kidPaid = """
                {"date": "%s", "categoryId": %d, "amount": "%s", "currency": "%s", "payerMemberId": %d%s}""";

        // Kid paid 9000 RUB: Alice's own rate, 100 to the euro.
        JsonNode rub = created(post(alice, uri + "/records", kidPaid.formatted("2026-09-14", groceries, "9000",
                "RUB", kid, "")));
        assertThat(money(rub)).isEqualTo("90.00 EUR from 9000.00 RUB at 0.01 MANUAL 2026-09-01");
        // Bob has no rate for roubles, and Alice's is hers: he enters the base amount.
        assertThat(details(post(bob, uri + "/records", kidPaid.formatted("2026-09-14", groceries, "9000", "RUB", kid,
                "")))).containsExactly("RATE_MISSING null there is no exchange rate from RUB to EUR on or before "
                        + "2026-09-14: enter the amount in EUR, or add your own rate on the rates page");
        assertThat(ok(get(bob, uri + "/conversion?amount=9000&currency=RUB&date=2026-09-14")))
                .isEqualTo(json.readTree("""
                        {"amount": "9000.00", "currency": "RUB", "baseAmount": null, "baseCurrency": "EUR"}"""));
        assertThat(money(created(post(bob, uri + "/records", kidPaid.formatted("2026-09-14", groceries, "9000", "RUB",
                kid, ", \"baseAmount\": \"95.50\""))))).isEqualTo("95.50 EUR from 9000.00 RUB ENTERED");

        // The ECB's rate beats her own on the same day; before the ECB's first rate, hers stands in.
        assertThat(money(created(post(alice, uri + "/records", kidPaid.formatted("2026-09-10", groceries, "11.20",
                "USD", kid, ""))))).isEqualTo("10.00 EUR from 11.20 USD at 0.892857142857 ECB 2026-09-10");
        assertThat(money(created(post(alice, uri + "/records", kidPaid.formatted("2026-09-05", groceries, "10.00",
                "USD", kid, ""))))).isEqualTo("8.00 EUR from 10.00 USD at 0.80 MANUAL 2026-09-01");
        // An entered base amount wins over any rate.
        assertThat(money(created(post(alice, uri + "/records", kidPaid.formatted("2026-09-10", groceries, "11.20",
                "USD", kid, ", \"baseAmount\": \"9.99\""))))).isEqualTo("9.99 EUR from 11.20 USD ENTERED");

        // HALF_UP: 10.05 CHF at 2 to the euro is 5.025, so 5.03.
        ecb("CHF", "2026-09-10", "2");
        assertThat(money(created(post(alice, uri + "/records", kidPaid.formatted("2026-09-10", groceries, "10.05",
                "CHF", kid, ""))))).isEqualTo("5.03 EUR from 10.05 CHF at 0.50 ECB 2026-09-10");

        // The rules of the amounts, each with its code.
        assertThat(details(post(alice, uri + "/records", kidPaid.formatted("2026-09-10", groceries, "10", "EUR", kid,
                ", \"baseAmount\": \"11\"")))).containsExactly("BASE_AMOUNT null the amount is in EUR, the family "
                        + "budget's currency, so it is the base amount too");
        assertThat(details(post(alice, uri + "/records", kidPaid.formatted("2026-09-10", groceries, "10", "USD", kid,
                ", \"baseAmount\": \"1.001\"")))).containsExactly("BASE_AMOUNT null the base amount 1.001 has more "
                        + "decimals than EUR has (2)");
        assertThat(details(post(alice, uri + "/records", kidPaid.formatted("2026-09-10", groceries, "10", "USD", kid,
                ", \"baseAmount\": \"0\"")))).containsExactly("BASE_AMOUNT null the base amount must be above 0");
        assertThat(body(post(alice, uri + "/records", kidPaid.formatted("2026-09-10", groceries, "10", "XYZ", kid, "")),
                HttpStatus.BAD_REQUEST).get("errors").findValuesAsText("field")).containsExactly("currency");
    }

    /**
     * A currency without minor units, on either side: 1000 JPY in a euro budget, and a yen budget's expense of 10.03
     * EUR at 150 yen to the euro, 1504.5, so 1505 JPY by HALF_UP, paid from a euro account through FX_EXCHANGE with
     * the debt account in yen.
     */
    @Test
    void aCurrencyWithoutMinorUnits() throws IOException {
        ecb("JPY", "2026-09-10", "160");
        assertThat(details(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "1000.5", "currency": "JPY", "payerMemberId": %d}"""
                .formatted(groceries, kid)))).containsExactly("AMOUNT null the amount 1000.5 has more decimals than "
                        + "JPY has (0)");
        assertThat(money(created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "1000", "currency": "JPY", "payerMemberId": %d}"""
                .formatted(groceries, kid))))).isEqualTo("6.25 EUR from 1000 JPY at 0.00625 ECB 2026-09-10");

        ecb("JPY", "2026-09-09", "150");
        JsonNode tokyo = newFamily(alice, """
                {"name": "Tokyo", "baseCurrency": "JPY", "displayName": "Mum", "startDate": "2026-09-01"}""");
        String inTokyo = "/api/family-ledgers/" + tokyo.get("id").asLong();
        long food = body(post(alice, inTokyo + "/categories", """
                {"code": "FOOD", "name": "Food", "type": "EXPENSE"}"""), HttpStatus.CREATED).get("id").asLong();
        long current = accountId(alice, "CURRENT_ACCOUNT");
        JsonNode paid = created(post(alice, inTokyo + "/records", """
                {"date": "2026-09-09", "categoryId": %d, "amount": "10.03", "currency": "EUR", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(food, tokyo.get("memberId").asLong(), current)));
        assertThat(money(paid)).isEqualTo("1505 JPY from 10.03 EUR at 150.00 ECB 2026-09-09");
        long fx = accountId(alice, "FX_EXCHANGE");
        long debt = accountId(alice, "FAMILY_DEBT_" + tokyo.get("id").asLong());
        assertThat(lines(alice, paid.get("yourPayment").get("entryId").asLong())).containsExactly(
                current + " EUR -10.03", fx + " EUR 10.03", fx + " JPY -1505.00", debt + " JPY 1505.00");
        FamilyInvariants.check(jdbc, tokyo.get("id").asLong());
        assertThat(ok(get(alice, "/api/reports/integrity"))).isEmpty();
    }

    /**
     * Bob paid 100.00 EUR, half Alice's. She pays him 56.00 USD from her dollar card, 50.00 EUR at the ECB's 1.12: the
     * settlement's base amount, which settles. His part waits in EUR on his placeholder; he puts it on his rouble
     * account with the 5000 RUB he got, which only his entry and his answers hold. The base amount and the balances
     * don't move, and her date and amount are locked until he moves it back (D-28).
     */
    @Test
    void aSettlementInTwoCurrencies() throws IOException {
        ecb("USD", "2026-09-10", "1.12");
        created(post(bob, uri + "/records", """
                {"date": "2026-09-08", "categoryId": %d, "amount": "100.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}}""".formatted(groceries, dad, accountId(bob, "CASH"), mum,
                dad)));
        JsonNode settled = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-10", "amount": "56.00", "currency": "USD", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, usdCard)));
        String path = uri + "/records/" + settled.get("id").asLong();
        assertThat(money(settled)).isEqualTo("50.00 EUR from 56.00 USD at 0.892857142857 ECB 2026-09-10");
        assertThat(balances(alice)).containsExactly("Mum 0.00 you", "Dad 0.00", "Kid 0.00");
        long alicesFx = accountId(alice, "FX_EXCHANGE");
        long alicesDebt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(lines(alice, settled.get("yourPayment").get("entryId").asLong())).containsExactly(
                usdCard + " USD -56.00", alicesFx + " USD 56.00", alicesFx + " EUR -50.00", alicesDebt + " EUR 50.00");
        JsonNode his = ok(get(bob, path));
        long bobsEntry = his.get("yourPayment").get("entryId").asLong();
        long bobsDebt = accountId(bob, "FAMILY_DEBT_" + family);
        long bobsPlaceholder = accountId(bob, "UNSPECIFIED_PAYMENTS");
        assertThat(his.get("yourPayment").get("amount").asText() + " " + his.get("yourPayment").get("currency").asText())
                .isEqualTo("50.00 EUR");
        assertThat(lines(bob, bobsEntry)).containsExactly(bobsPlaceholder + " EUR 50.00", bobsDebt + " EUR -50.00");

        // His rouble account needs the roubles he got; "Specify later" and a euro account take none.
        assertThat(details(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d}""".formatted(rubAccount)))).containsExactly(
                "ACCOUNT_AMOUNT %d the account is in RUB: name the amount that went into it".formatted(dad));
        assertThat(details(patch(bob, path + "?version=0", """
                {"paymentLater": true, "accountAmount": "5000"}"""))).containsExactly(("ACCOUNT_AMOUNT %d name the "
                        + "amount only with an account of yours in another currency than EUR").formatted(dad));
        assertThat(details(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d, "accountAmount": "49"}""".formatted(accountId(bob, "CASH")))))
                .containsExactly(("ACCOUNT_AMOUNT %d the account is in EUR, the family budget's currency, so your "
                        + "side is the settlement's amount").formatted(dad));
        assertThat(details(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d, "accountAmount": "5000.001"}""".formatted(rubAccount)))).containsExactly(
                "ACCOUNT_AMOUNT %d the amount 5000.001 has more decimals than RUB has (2)".formatted(dad));
        JsonNode placed = ok(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d, "accountAmount": "5000"}""".formatted(rubAccount)));
        assertThat(placed.get("version").asInt()).isZero();
        assertThat(placed.get("yourPayment").get("amount").asText() + " "
                + placed.get("yourPayment").get("currency").asText()).isEqualTo("5000.00 RUB");
        long bobsFx = accountId(bob, "FX_EXCHANGE");
        assertThat(lines(bob, bobsEntry)).containsExactly(rubAccount + " RUB 5000.00", bobsFx + " RUB -5000.00",
                bobsFx + " EUR 50.00", bobsDebt + " EUR -50.00");
        assertThat(money(placed)).isEqualTo(money(settled));
        assertThat(balances(bob)).containsExactly("Mum 0.00", "Dad 0.00 you", "Kid 0.00");
        // His roubles are his: in no answer of Alice's, and not in the journal.
        for (String read : List.of("/records", "/records/" + settled.get("id").asLong(), "/journal", "/balances")) {
            assertThat(ok(get(alice, uri + read)).toString()).as(read).doesNotContain("5000.00", "RUB");
        }
        assertThat(ok(get(alice, path)).get("lockedBy").get("displayName").asText()).isEqualTo("Dad");
        assertThat(detail(patch(alice, path + "?version=0", """
                {"amount": "60.00"}"""), HttpStatus.CONFLICT)).startsWith("Dad has put their side");
        // A comment still changes, and leaves his roubles as they are.
        ok(patch(alice, path + "?version=0", """
                {"comment": "Groceries"}"""));
        assertThat(lines(bob, bobsEntry)).containsExactly(rubAccount + " RUB 5000.00", bobsFx + " RUB -5000.00",
                bobsFx + " EUR 50.00", bobsDebt + " EUR -50.00");

        // Back to "Specify later" through his entry: 50.00 EUR again, and she changes the amount to 67.20 USD.
        ok(patch(bob, "/api/entries/%d/family-payment?version=%d".formatted(bobsEntry,
                ok(get(bob, "/api/entries/" + bobsEntry)).get("version").asInt()), """
                {"later": true}"""));
        assertThat(lines(bob, bobsEntry)).containsExactly(bobsPlaceholder + " EUR 50.00", bobsDebt + " EUR -50.00");
        JsonNode more = ok(patch(alice, path + "?version=1", """
                {"amount": "67.20"}"""));
        assertThat(money(more)).isEqualTo("60.00 EUR from 67.20 USD at 0.892857142857 ECB 2026-09-10");
        assertThat(lines(bob, bobsEntry)).containsExactly(bobsPlaceholder + " EUR 60.00", bobsDebt + " EUR -60.00");
        assertThat(balances(alice)).containsExactly("Mum -10.00 you", "Dad 10.00", "Kid 0.00");
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + settled.get("id").asLong()))).getFirst())
                .isEqualTo("UPDATE by Mum: amount 50.00→60.00, originalAmount 56.00 USD→67.20 USD");
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();
    }

    /**
     * A new original amount, currency or date converts the base amount again, unless the request gives it, which alone
     * changes it too; each splits again by the stored split and posts again. A new currency needs its amount; a split
     * by amounts needs new amounts whenever the base amount moves. Only the payer changes them.
     */
    @Test
    void editsConvertTheBaseAmountAgain() throws IOException {
        ecb("USD", "2026-09-09", "1.10");
        ecb("USD", "2026-09-10", "1.12");
        JsonNode paid = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "56.00", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d, "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}}""".formatted(groceries, mum, usdCard, mum, dad)));
        long id = paid.get("id").asLong();
        String path = uri + "/records/" + id;
        String journal = uri + "/journal?recordId=" + id;

        JsonNode earlier = ok(patch(alice, path + "?version=0", """
                {"date": "2026-09-09"}"""));
        assertThat(money(earlier)).isEqualTo("50.91 EUR from 56.00 USD at 0.909090909091 ECB 2026-09-09");
        assertThat(shares(earlier)).containsExactly("Mum 25.46 5000", "Dad 25.45 5000");
        assertThat(changes(ok(get(bob, journal))).getFirst()).isEqualTo("UPDATE by Mum: date 2026-09-10→2026-09-09, "
                + "amount 50.00→50.91, share of Mum 25.00→25.46, share of Dad 25.00→25.45");
        JsonNode doubled = ok(patch(alice, path + "?version=1", """
                {"amount": "112.00"}"""));
        assertThat(money(doubled)).isEqualTo("101.82 EUR from 112.00 USD at 0.909090909091 ECB 2026-09-09");
        assertThat(changes(ok(get(bob, journal))).getFirst()).isEqualTo("UPDATE by Mum: amount 50.91→101.82, "
                + "originalAmount 56.00 USD→112.00 USD, share of Mum 25.46→50.91, share of Dad 25.45→50.91");
        JsonNode entered = ok(patch(alice, path + "?version=2", """
                {"baseAmount": "100.00"}"""));
        assertThat(money(entered)).isEqualTo("100.00 EUR from 112.00 USD ENTERED");
        assertThat(changes(ok(get(bob, journal))).getFirst()).isEqualTo("UPDATE by Mum: amount 101.82→100.00, "
                + "share of Mum 50.91→50.00, share of Dad 50.91→50.00");
        // A new currency needs its amount; with it, the euro amount is the base amount.
        assertThat(details(patch(alice, path + "?version=3", """
                {"currency": "EUR"}"""))).containsExactly("AMOUNT null the expense is in EUR now, not USD: give its "
                        + "amount in EUR");
        JsonNode inEuros = ok(patch(alice, path + "?version=3", """
                {"currency": "EUR", "amount": "95.00", "paymentAccountId": %d}""".formatted(accountId(alice,
                "CURRENT_ACCOUNT"))));
        assertThat(money(inEuros)).isEqualTo("95.00 EUR from 95.00 EUR");
        assertThat(changes(ok(get(bob, journal))).getFirst()).isEqualTo("UPDATE by Mum: amount 100.00→95.00, "
                + "originalAmount 112.00 USD→95.00 EUR, share of Mum 50.00→47.50, share of Dad 50.00→47.50");
        assertThat(lines(alice, inEuros.get("yourPayment").get("entryId").asLong())).containsExactly(
                accountId(alice, "CURRENT_ACCOUNT") + " EUR -95.00", accountId(alice, "FAMILY_DEBT_" + family)
                        + " EUR 95.00");

        // Only the payer changes them; and a split by amounts needs new amounts when the base amount moves.
        assertThat(detail(patch(bob, path + "?version=4", """
                {"baseAmount": "90"}"""), HttpStatus.CONFLICT)).isEqualTo("Only Mum, who paid it, can change the "
                        + "expense's date, amount, payer or paying account.");
        JsonNode byAmounts = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "56.00", "currency": "USD", "payerMemberId": %d,
                 "paymentLater": true, "split": {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "30.00"},
                 {"memberId": %d, "amount": "20.00"}]}}""".formatted(groceries, mum, mum, dad)));
        assertThat(details(patch(alice, uri + "/records/" + byAmounts.get("id").asLong() + "?version=0", """
                {"date": "2026-09-09"}"""))).containsExactly("AMOUNTS_NEEDED null the expense is split by amounts: "
                        + "send the new amounts with the new amount");
        assertThat(shares(ok(patch(alice, uri + "/records/" + byAmounts.get("id").asLong() + "?version=0", """
                {"date": "2026-09-09", "split": {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "30.91"},
                 {"memberId": %d, "amount": "20.00"}]}}""".formatted(mum, dad))))).containsExactly(
                "Mum 30.91 null", "Dad 20.00 null");
        assertThat(ok(get(alice, "/api/reports/integrity"))).isEmpty();
    }

    /** Income received into a rouble account at Bob's own rate: the receipt through FX_EXCHANGE, shares in euros. */
    @Test
    void anIncomeInRoubles() throws IOException {
        ok(post(bob, "/api/rates/manual", """
                {"date": "2026-09-01", "base": "EUR", "quote": "RUB", "rate": "100"}"""));
        JsonNode received = created(post(bob, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-14", "categoryId": %d, "amount": "9000", "currency": "RUB",
                 "payerMemberId": %d, "paymentAccountId": %d, "split": {"method": "PERCENT", "shares": [
                 {"memberId": %d, "basisPoints": 5000}, {"memberId": %d, "basisPoints": 5000}]}}"""
                .formatted(salary, dad, rubAccount, mum, dad)));
        assertThat(money(received)).isEqualTo("90.00 EUR from 9000.00 RUB at 0.01 MANUAL 2026-09-01");
        long fx = accountId(bob, "FX_EXCHANGE");
        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        assertThat(lines(bob, received.get("yourPayment").get("entryId").asLong())).containsExactly(
                rubAccount + " RUB 9000.00", fx + " RUB -9000.00", fx + " EUR 90.00", debt + " EUR -90.00");
        assertThat(balances(alice)).containsExactly("Mum -45.00 you", "Dad 45.00", "Kid 0.00");
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();
    }

    private void ecb(String currency, String date, String perEuro) {
        jdbc.sql("""
                INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source, user_id)
                VALUES (?, 'EUR', ?, ?, 'ECB', NULL)""")
                .params(LocalDate.parse(date), currency, new BigDecimal(perEuro)).update();
    }

    /** A record's or a conversion's amounts as "base CUR from original CUR at rate SOURCE date". */
    private static String money(JsonNode answer) {
        boolean conversion = answer.has("baseAmount");
        String base = conversion ? answer.get("baseAmount").asText() + " " + answer.get("baseCurrency").asText()
                : answer.get("amount").asText() + " " + answer.get("currency").asText();
        String original = conversion ? answer.get("amount").asText() + " " + answer.get("currency").asText()
                : answer.get("originalAmount").asText() + " " + answer.get("originalCurrency").asText();
        String source = answer.has("rateSource") ? " " + answer.get("rateSource").asText() : "";
        String rate = answer.has("rate") ? " at " + answer.get("rate").asText() : "";
        String day = answer.has("rateDate") ? " " + answer.get("rateDate").asText() : "";
        return base + " from " + original + (rate.isEmpty() ? source : rate + source + day);
    }

    /** An entry's postings as "account CUR amount", in their order. */
    private List<String> lines(String user, long entryId) throws IOException {
        return StreamSupport.stream(ok(get(user, "/api/entries/" + entryId)).get("postings").spliterator(), false)
                .map(p -> p.get("accountId").asLong() + " " + p.get("currency").asText() + " "
                        + p.get("amount").asText())
                .toList();
    }
}
