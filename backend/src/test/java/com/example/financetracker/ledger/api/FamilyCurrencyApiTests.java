package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Records in their own currency (F8a; D-45, D-46, D-87, D-12; ADR 0004) in the family of {@link FamilyApiTest}, whose
 * main currency is EUR: each record keeps its currency, and its shares are split in it at its minor unit; no exchange
 * rate is looked up, so a rouble record after the ECB stopped publishing roubles needs none (D-66). A member's account
 * in another currency than the record's names what went from or into it ({@code accountAmount}), which the record
 * service asks for whenever the record's amount moves. A settlement is in one currency. Synthetic amounts throughout.
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

    /**
     * Three records of the rule's equal shares among Mum, Dad and Kid: 90.00 EUR paid by Kid, 56.00 USD paid with
     * Alice's dollar card, and 9000 RUB paid from Bob's rouble account in 2026, long after the ECB's last rouble rate,
     * with no rouble rate stored anywhere. Each keeps its amount and currency; the dollars split 18.68, 18.66 and 18.66,
     * the payer taking the remainder (D-12).
     */
    @Test
    void recordsInEurosDollarsAndRoublesKeepTheirCurrency() throws IOException {
        assertThat(jdbc.sql("SELECT count(*) FROM exchange_rate WHERE quote_currency = 'RUB'").query(Long.class)
                .single()).isZero();
        JsonNode euros = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "90.00", kid, "")));
        assertThat(money(euros)).isEqualTo("90.00 EUR");
        assertThat(shares(euros)).containsExactly("Mum 30.00 null", "Dad 30.00 null", "Kid 30.00 null");

        JsonNode dollars = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "56", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(groceries, mum, usdCard)));
        assertThat(money(dollars)).isEqualTo("56.00 USD");
        assertThat(shares(dollars)).containsExactly("Mum 18.68 null", "Dad 18.66 null", "Kid 18.66 null");
        assertThat(dollars.get("yourPayment").get("amount").asText() + " "
                + dollars.get("yourPayment").get("currency").asText()).isEqualTo("56.00 USD");
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + dollars.get("id").asLong()))).getFirst())
                .startsWith("CREATE by Mum: date null→2026-09-10, category null→Groceries, amount null→56.00, "
                        + "currency null→USD, payer null→Mum");

        JsonNode roubles = created(post(bob, uri + "/records", """
                {"date": "2026-09-14", "categoryId": %d, "amount": "9000", "currency": "RUB", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(groceries, dad, rubAccount)));
        assertThat(money(roubles)).isEqualTo("9000.00 RUB");
        assertThat(shares(roubles)).containsExactly("Mum 3000.00 null", "Dad 3000.00 null", "Kid 3000.00 null");
        assertThat(ok(get(alice, uri + "/records/" + roubles.get("id").asLong())).has("yourPayment")).isFalse();
        // The deprecated base amount of F4e is ignored: no rate, no conversion.
        JsonNode ignored = created(post(alice, uri + "/records", """
                {"date": "2026-09-14", "categoryId": %d, "amount": "9000", "currency": "RUB", "baseAmount": "95.50",
                 "payerMemberId": %d}""".formatted(groceries, kid)));
        assertThat(money(ignored)).isEqualTo("9000.00 RUB");
    }

    /**
     * D-12 at the record's own minor unit: 10.01 USD halves to 5.01 and 5.00 (the remainder to the first by join
     * order, as neither paid), 1001 JPY to 501 and 500, and 1.001 BHD, a currency of three decimals, to 0.501 and 0.500.
     * An amount or a share with more decimals than its currency has is refused.
     */
    @Test
    void oddMinorUnitsSplitInTheRecordsCurrency() throws IOException {
        String halves = """
                {"date": "2026-09-10", "categoryId": %d, "amount": "%s", "currency": "%s", "payerMemberId": %d,
                 "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}}""";
        assertThat(shares(created(post(alice, uri + "/records", halves.formatted(groceries, "10.01", "USD", kid, mum,
                dad))))).containsExactly("Mum 5.01 5000", "Dad 5.00 5000");
        assertThat(shares(created(post(alice, uri + "/records", halves.formatted(groceries, "1001", "JPY", kid, mum,
                dad))))).containsExactly("Mum 501 5000", "Dad 500 5000");
        JsonNode dinars = created(post(alice, uri + "/records", halves.formatted(groceries, "1.001", "BHD", kid, mum,
                dad)));
        assertThat(money(dinars)).isEqualTo("1.001 BHD");
        assertThat(shares(dinars)).containsExactly("Mum 0.501 5000", "Dad 0.500 5000");

        assertThat(details(post(alice, uri + "/records", halves.formatted(groceries, "1000.5", "JPY", kid, mum, dad))))
                .containsExactly("AMOUNT null the amount 1000.5 has more decimals than JPY has (0)");
        assertThat(details(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "1000", "currency": "JPY", "payerMemberId": %d,
                 "split": {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "500.5"},
                 {"memberId": %d, "amount": "499.5"}]}}""".formatted(groceries, kid, mum, dad)))).containsExactly(
                "SHARE %d Mum needs an amount of 0 or more, with at most 0 decimals".formatted(mum),
                "SHARE %d Dad needs an amount of 0 or more, with at most 0 decimals".formatted(dad));
        assertThat(body(post(alice, uri + "/records", halves.formatted(groceries, "10", "XYZ", kid, mum, dad)),
                HttpStatus.BAD_REQUEST).get("errors").findValuesAsText("field")).containsExactly("currency");
    }

    /**
     * Alice pays a 50.00 EUR expense with her dollar card: she names the 54.00 USD that went from it (D-87), never a
     * rate. Each change of the expense's amount asks for it again; a new date keeps it; a new currency, the card's,
     * makes the card's line the expense's amount; "Specify later" takes none.
     */
    @Test
    void anAccountInAnotherCurrencyNamesWhatWentFromIt() throws IOException {
        String paid = """
                {"date": "2026-09-10", "categoryId": %d, "amount": "50.00", "payerMemberId": %d,
                 "paymentAccountId": %d%s}""";
        assertThat(details(post(alice, uri + "/records", paid.formatted(groceries, mum, usdCard, "")))).containsExactly(
                "ACCOUNT_AMOUNT %d the account is in USD and the expense in EUR: name the amount that went from it"
                        .formatted(mum));
        assertThat(details(post(alice, uri + "/records", paid.formatted(groceries, mum, usdCard,
                ", \"accountAmount\": \"54.001\"")))).containsExactly(
                "ACCOUNT_AMOUNT %d the amount 54.001 has more decimals than USD has (2)".formatted(mum));
        assertThat(details(post(alice, uri + "/records", paid.formatted(groceries, mum, accountId(alice, "CASH"),
                ", \"accountAmount\": \"54.00\"")))).containsExactly(("ACCOUNT_AMOUNT %d the account is in EUR, the "
                        + "expense's currency, so what went from it is the expense's amount").formatted(mum));
        assertThat(details(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "50.00", "payerMemberId": %d, "paymentLater": true,
                 "accountAmount": "54.00"}""".formatted(groceries, mum)))).containsExactly(("ACCOUNT_AMOUNT %d name "
                        + "the amount only with an account of yours in another currency than the expense's, EUR")
                        .formatted(mum));

        JsonNode record = created(post(alice, uri + "/records", paid.formatted(groceries, mum, usdCard,
                ", \"accountAmount\": \"54\"")));
        String path = uri + "/records/" + record.get("id").asLong();
        assertThat(money(record)).isEqualTo("50.00 EUR");
        assertThat(own(record)).isEqualTo("54.00 USD");
        assertThat(shares(record)).containsExactly("Mum 16.68 null", "Dad 16.66 null", "Kid 16.66 null");

        assertThat(details(patch(alice, path + "?version=0", """
                {"amount": "60.00"}"""))).containsExactly(("ACCOUNT_AMOUNT %d the account is in USD and the expense "
                        + "in EUR: name the amount that went from it").formatted(mum));
        JsonNode more = ok(patch(alice, path + "?version=0", """
                {"amount": "60.00", "accountAmount": "64.80"}"""));
        assertThat(money(more) + ", " + own(more)).isEqualTo("60.00 EUR, 64.80 USD");
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + record.get("id").asLong()))).getFirst())
                .isEqualTo("UPDATE by Mum: amount 50.00→60.00, share of Mum 16.68→20.00, share of Dad 16.66→20.00, "
                        + "share of Kid 16.66→20.00");
        JsonNode later = ok(patch(alice, path + "?version=1", """
                {"date": "2026-09-11"}"""));
        assertThat(money(later) + ", " + own(later)).isEqualTo("60.00 EUR, 64.80 USD");
        // Only what went from her card: version and journal stay as they are (D-16).
        JsonNode corrected = ok(patch(alice, path + "?version=2", """
                {"accountAmount": "65.00"}"""));
        assertThat(own(corrected) + " v" + corrected.get("version").asInt()).isEqualTo("65.00 USD v2");
        JsonNode inDollars = ok(patch(alice, path + "?version=2", """
                {"currency": "USD", "amount": "65.00"}"""));
        assertThat(money(inDollars) + ", " + own(inDollars)).isEqualTo("65.00 USD, 65.00 USD");
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + record.get("id").asLong()))).getFirst())
                .isEqualTo("UPDATE by Mum: amount 60.00→65.00, currency EUR→USD, share of Mum 20.00→21.68, "
                        + "share of Dad 20.00→21.66, share of Kid 20.00→21.66");
        assertThat(details(patch(alice, path + "?version=3", """
                {"currency": "EUR"}"""))).containsExactly("AMOUNT null the expense is in EUR now, not USD: give its "
                        + "amount in EUR");
        JsonNode placeholder = ok(patch(alice, path + "?version=3", """
                {"paymentLater": true}"""));
        assertThat(own(placeholder)).isEqualTo("65.00 USD");
        // Bob can't name her amounts.
        assertThat(detail(patch(bob, path + "?version=3", """
                {"accountAmount": "70"}"""), HttpStatus.CONFLICT)).startsWith("Only Mum, who paid it, can change");
    }

    /**
     * A settlement is in one currency (D-46). Alice pays Bob 50.00 EUR with her dollar card, naming the 56.00 USD that
     * went from it; his side waits in euros on his placeholder, and his rouble account takes the roubles he names. A
     * settlement in dollars settles dollars only.
     */
    @Test
    void aSettlementIsInOneCurrency() throws IOException {
        JsonNode settled = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-10", "amount": "50.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d, "accountAmount": "56.00"}""".formatted(mum, dad, usdCard)));
        String path = uri + "/records/" + settled.get("id").asLong();
        assertThat(money(settled) + ", " + own(settled)).isEqualTo("50.00 EUR, 56.00 USD");
        JsonNode his = ok(get(bob, path));
        assertThat(own(his)).isEqualTo("50.00 EUR");
        assertThat(details(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d}""".formatted(rubAccount)))).containsExactly(
                "ACCOUNT_AMOUNT %d the account is in RUB: name the amount that went into it".formatted(dad));
        assertThat(details(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d, "accountAmount": "49"}""".formatted(accountId(bob, "CASH")))))
                .containsExactly(("ACCOUNT_AMOUNT %d the account is in EUR, the settlement's currency, so your side "
                        + "is the settlement's amount").formatted(dad));
        assertThat(details(patch(bob, path + "?version=0", """
                {"paymentLater": true, "accountAmount": "5000"}"""))).containsExactly(("ACCOUNT_AMOUNT %d name the "
                        + "amount only with an account of yours in another currency than EUR").formatted(dad));
        JsonNode placed = ok(patch(bob, path + "?version=0", """
                {"paymentAccountId": %d, "accountAmount": "5000"}""".formatted(rubAccount)));
        assertThat(own(placed) + " v" + placed.get("version").asInt()).isEqualTo("5000.00 RUB v0");
        assertThat(detail(patch(alice, path + "?version=0", """
                {"currency": "USD", "amount": "56.00"}"""), HttpStatus.CONFLICT)).startsWith("Dad has put their side");

        JsonNode dollars = created(post(bob, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "20", "currency": "USD", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(dad, mum)));
        assertThat(money(dollars) + ", " + own(dollars)).isEqualTo("20.00 USD, 20.00 USD");
        assertThat(own(ok(get(alice, uri + "/records/" + dollars.get("id").asLong())))).isEqualTo("20.00 USD");
    }

    /**
     * No rate is looked up anywhere (D-87): F4e's conversion answers only the main currency's amount itself. The main
     * currency changes while records exist, and each record keeps its own; a new one takes the new main currency.
     */
    @Test
    void noRateAndTheMainCurrencyChanges() throws IOException {
        jdbc.sql("""
                INSERT INTO exchange_rate (rate_date, base_currency, quote_currency, rate, source, user_id)
                VALUES (DATE '2026-09-01', 'EUR', 'USD', 1.25, 'MANUAL', ?)""").param(alice).update();
        assertThat(ok(get(alice, uri + "/conversion?amount=11.20&currency=USD&date=2026-09-12"))).isEqualTo(
                json.readTree("""
                        {"amount": "11.20", "currency": "USD", "baseAmount": null, "baseCurrency": "EUR"}"""));
        assertThat(ok(get(alice, uri + "/conversion?amount=11.2&currency=EUR&date=2026-09-12"))).isEqualTo(
                json.readTree("""
                        {"amount": "11.20", "currency": "EUR", "baseAmount": "11.20", "baseCurrency": "EUR"}"""));

        JsonNode euros = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", kid, "")));
        assertThat(ok(patch(alice, uri, """
                {"baseCurrency": "USD"}""")).get("baseCurrency").asText()).isEqualTo("USD");
        assertThat(money(ok(get(alice, uri + "/records/" + euros.get("id").asLong())))).isEqualTo("30.00 EUR");
        assertThat(money(created(post(alice, uri + "/records", expense("2026-09-11", groceries, "12.00", kid, "")))))
                .isEqualTo("12.00 USD");
        ok(patch(alice, uri, """
                {"baseCurrency": "EUR"}"""));
    }

    /**
     * Balances and the report per currency (D-45, D-46): 90.00 EUR paid by Kid in equal thirds, 60.00 USD paid with
     * Alice's dollar card half each for Mum and Dad, 9000 RUB paid by Dad all on Mum, and Dad paying Mum back her 30.00
     * USD. In euros Kid is owed 60.00; in dollars nobody owes anything; in roubles Mum owes Dad 9000.00. The old fields
     * are the main currency's; the report agrees with the balances in each currency, and so do the debt accounts.
     */
    @Test
    void balancesAndTheReportPerCurrency() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "90.00", kid, "")));
        created(post(alice, uri + "/records", """
                {"date": "2026-09-11", "categoryId": %d, "amount": "60.00", "currency": "USD", "payerMemberId": %d,
                 "paymentAccountId": %d, "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}}""".formatted(groceries, mum, usdCard, mum, dad)));
        created(post(bob, uri + "/records", """
                {"date": "2026-09-12", "categoryId": %d, "amount": "9000", "currency": "RUB", "payerMemberId": %d,
                 "paymentAccountId": %d, "split": {"method": "ONE_MEMBER", "memberId": %d}}"""
                .formatted(groceries, dad, rubAccount, mum)));
        created(post(bob, uri + "/settlements", """
                {"date": "2026-09-13", "amount": "30.00", "currency": "USD", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(dad, mum)));

        JsonNode balances = ok(get(alice, uri + "/balances"));
        assertThat(perCurrency(balances)).containsExactly(
                "EUR: Mum 30.00 you, Dad 30.00, Kid -60.00",
                "RUB: Mum 9000.00 you, Dad -9000.00, Kid 0.00",
                "USD: Mum 0.00 you, Dad 0.00, Kid 0.00");
        assertThat(balances(alice)).containsExactly("Mum 30.00 you", "Dad 30.00", "Kid -60.00");

        JsonNode report = checkFamilyReport(alice, family, java.util.Map.of(alice, java.time.LocalDate.of(2026, 9, 1),
                bob, java.time.LocalDate.of(2026, 9, 1)));
        assertThat(report.get("byCurrency").findValuesAsText("currency")).containsExactly("EUR", "RUB", "USD");
        JsonNode dollars = report.get("byCurrency").get(2);
        assertThat(dollars.get("rows").get(0).get("total").asText()).isEqualTo("60.00");
        assertThat(dollars.get("totals").get(0).toString()).contains("\"expenseShares\":\"30.00\"",
                "\"expensesPaid\":\"60.00\"", "\"settlementsReceived\":\"30.00\"", "\"net\":\"0.00\"");
        assertThat(report.get("rows")).isEqualTo(report.get("byCurrency").get(0).get("rows"));
        // A period without dollars: no dollar section; the main currency's is always there.
        assertThat(ok(get(alice, uri + "/report?from=2026-09-12&to=2026-09-12")).get("byCurrency")
                .findValuesAsText("currency")).containsExactly("EUR", "RUB");
        // D-10 in each currency: no row of the integrity check.
        assertThat(ok(get(alice, "/api/reports/integrity"))).isEmpty();
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();
    }

    /** The balances in each currency as "CUR: name balance[ you], …". */
    private static java.util.List<String> perCurrency(JsonNode balances) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (JsonNode inCurrency : balances.get("byCurrency")) {
            java.util.List<String> members = new java.util.ArrayList<>();
            inCurrency.get("members").forEach(m -> members.add(m.get("displayName").asText() + " "
                    + m.get("balance").asText() + (m.get("you").asBoolean() ? " you" : "")));
            lines.add(inCurrency.get("currency").asText() + ": " + String.join(", ", members));
        }
        return lines;
    }

    /** A record's amount as "amount CUR", with the deprecated rate fields absent. */
    private static String money(JsonNode record) {
        assertThat(record.has("rate")).isFalse();
        assertThat(record.has("rateDate")).isFalse();
        return record.get("amount").asText() + " " + record.get("currency").asText();
    }

    /** The reader's own side, from their {@code yourPayment}, as "amount CUR". */
    private static String own(JsonNode record) {
        return record.get("yourPayment").get("amount").asText() + " " + record.get("yourPayment").get("currency")
                .asText();
    }
}
