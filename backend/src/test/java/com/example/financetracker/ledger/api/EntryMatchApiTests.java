package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * UX-8's similar entries, {@code GET /api/entries/matches} (D-453 to D-459, D-463): every criterion alone and all
 * together, loose text (D-458), amounts by their characters or within 1 (D-459), which saved entries count (D-457:
 * the user's personal expenses and incomes, and their own payments and receipts of family records), paging, the 400s,
 * and another user's entries never. With family budgets off: {@code FamilySwitchOffApiTests}.
 */
class EntryMatchApiTests extends FamilyApiTest {

    private final Map<String, Long> payees = new HashMap<>();

    /** A personal expense or income; a refund with {@code refund}. */
    private long entry(String user, String kind, String date, String payee, String memo, String account,
            String category, String amount, String currency, boolean refund) throws IOException {
        Long payeeId = null;
        if (payee != null) {
            payeeId = payees.get(user + "|" + payee);
            if (payeeId == null) {
                payeeId = newCounterparty(user, payee);
                payees.put(user + "|" + payee, payeeId);
            }
        }
        return newEntry(user, """
                {"kind": "%s", "entryDate": "%s", "payeeId": %s, "memo": %s, "accountId": %d, "currency": "%s",
                 "amount": "%s", "categoryId": %d}""".formatted(kind, date, payeeId,
                memo == null ? null : json(memo), accountId(user, account), currency, (refund ? "-" : "") + amount,
                categoryId(user, category))).get("id").asLong();
    }

    private long expense(String user, String date, String payee, String memo, String account, String category,
            String amount) throws IOException {
        return entry(user, "EXPENSE", date, payee, memo, account, category, amount, "EUR", false);
    }

    private long amountOnly(String user, String date, String amount, String currency) throws IOException {
        return entry(user, "EXPENSE", date, null, null, "CASH", "EATING_OUT", amount, currency, false);
    }

    private static String json(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** The query of the parameters given as name, value, name, value…; MockMvc encodes it. */
    private static String query(String... parameters) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < parameters.length; i += 2) {
            parts.add(parameters[i] + "=" + parameters[i + 1]);
        }
        return String.join("&", parts);
    }

    private JsonNode matches(String user, String... parameters) throws IOException {
        return ok(get(user, "/api/entries/matches?" + query(parameters)));
    }

    /** The ids of the entries that match, in the answer's order. */
    private List<Long> ids(String user, String... parameters) throws IOException {
        JsonNode answer = matches(user, parameters);
        List<Long> ids = new ArrayList<>();
        answer.get("content").forEach(item -> ids.add(item.get("entry").get("id").asLong()));
        assertThat(answer.get("totalElements").asLong()).isEqualTo(ids.size());
        return ids;
    }

    private static JsonNode item(JsonNode answer, long entryId) {
        for (JsonNode item : answer.get("content")) {
            if (item.get("entry").get("id").asLong() == entryId) {
                return item;
            }
        }
        throw new AssertionError("No entry " + entryId);
    }

    @Test
    void eachCriterionAloneAndAllTogether() throws IOException {
        long a = expense(alice, "2026-09-05", "Albert Heijn", "weekly shop", "CASH", "EATING_OUT", "11.35");
        long b = expense(alice, "2026-09-06", "Albert Heijn", "drinks", "CURRENT_ACCOUNT", "LEISURE", "20.00");
        long c = expense(alice, "2026-09-07", "Jumbo", "weekly shop", "CASH", "LEISURE", "11.80");
        long income = entry(alice, "INCOME", "2026-09-08", "Bank", "weekly interest", "CASH", "INTEREST", "11.35",
                "EUR", false);
        String cash = String.valueOf(accountId(alice, "CASH"));
        String eatingOut = String.valueOf(categoryId(alice, "EATING_OUT"));

        assertThat(ids(alice, "type", "EXPENSE", "payee", "albert")).containsExactly(b, a);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "weekly")).containsExactly(c, a);
        assertThat(ids(alice, "type", "EXPENSE", "accountId", cash)).containsExactly(c, a);
        assertThat(ids(alice, "type", "EXPENSE", "categoryId", String.valueOf(categoryId(alice, "LEISURE"))))
                .containsExactly(c, b);
        assertThat(ids(alice, "type", "EXPENSE", "amount", "11", "currency", "EUR")).containsExactly(c, a);
        assertThat(ids(alice, "type", "EXPENSE", "payee", "albert", "memo", "weekly", "accountId", cash, "categoryId",
                eatingOut, "amount", "11.35", "currency", "EUR")).containsExactly(a);
        // Each text criterion next to the amount's, whose clause follows it in the SQL.
        assertThat(ids(alice, "type", "EXPENSE", "payee", "albert", "amount", "11", "currency", "EUR")).containsExactly(a);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "weekly", "amount", "11")).containsExactly(c, a);
        // The more are filled, the shorter the list (D-453).
        assertThat(ids(alice, "type", "EXPENSE", "payee", "albert", "accountId", cash)).containsExactly(a);
        assertThat(ids(alice, "type", "INCOME", "memo", "weekly")).containsExactly(income);
        assertThat(ids(alice, "type", "INCOME", "payee", "albert")).isEmpty();

        // What a row shows: the entry as GET /api/entries gives it, its amount, currency, category and refund.
        JsonNode answer = matches(alice, "type", "EXPENSE", "payee", "albert");
        JsonNode first = item(answer, a);
        assertThat(new BigDecimal(first.get("amount").asText())).isEqualByComparingTo("11.35");
        assertThat(first.get("currency").asText()).isEqualTo("EUR");
        assertThat(first.get("categoryId").asLong()).isEqualTo(categoryId(alice, "EATING_OUT"));
        assertThat(first.get("refund").asBoolean()).isFalse();
        assertThat(first.get("entry")).isEqualTo(ok(get(alice, "/api/entries/" + a)));
    }

    @Test
    void textIsMatchedLoosely() throws IOException {
        long albert = expense(alice, "2026-09-05", "Albert Heijn", null, "CASH", "EATING_OUT", "1");
        long hedgehog = expense(alice, "2026-09-06", "Продукты Ёжик", "ёлочные игрушки", "CASH", "EATING_OUT", "2");
        long elena = expense(alice, "2026-09-07", "Елена", "50%_off \\ deal", "CASH", "EATING_OUT", "3");
        long other = expense(alice, "2026-09-08", "Other", "500 off", "CASH", "EATING_OUT", "4");

        // The other case, in Latin and Cyrillic; words in any order, part of a word.
        assertThat(ids(alice, "type", "EXPENSE", "payee", "ALBERT")).containsExactly(albert);
        assertThat(ids(alice, "type", "EXPENSE", "payee", "heijn alb")).containsExactly(albert);
        assertThat(ids(alice, "type", "EXPENSE", "payee", "прод")).containsExactly(hedgehog);
        assertThat(ids(alice, "type", "EXPENSE", "payee", "ПРОДУКТЫ")).containsExactly(hedgehog);
        assertThat(ids(alice, "type", "EXPENSE", "payee", "ёжик прод")).containsExactly(hedgehog);
        // ё and е, both ways, also as capitals.
        assertThat(ids(alice, "type", "EXPENSE", "payee", "ежик")).containsExactly(hedgehog);
        assertThat(ids(alice, "type", "EXPENSE", "payee", "ЕЖИК")).containsExactly(hedgehog);
        assertThat(ids(alice, "type", "EXPENSE", "payee", "ёлена")).containsExactly(elena);
        assertThat(ids(alice, "type", "EXPENSE", "payee", "ЁЛЕНА")).containsExactly(elena);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "елочн")).containsExactly(hedgehog);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "ЁЛОЧН")).containsExactly(hedgehog);
        // %, _ and \ are plain characters: as LIKE's wildcards they would find "500 off" too.
        assertThat(ids(alice, "type", "EXPENSE", "memo", "0%_")).containsExactly(elena);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "%_")).containsExactly(elena);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "_o")).containsExactly(elena);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "5_")).isEmpty();
        assertThat(ids(alice, "type", "EXPENSE", "memo", "\\ deal")).containsExactly(elena);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "off 50%")).containsExactly(elena);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "off")).containsExactly(other, elena);
        // Neither field looks at the other.
        assertThat(ids(alice, "type", "EXPENSE", "memo", "albert")).isEmpty();
        assertThat(ids(alice, "type", "EXPENSE", "payee", "off")).isEmpty();
        // Under 2 characters, trimmed, a text is no criterion: with another one it is left out.
        assertThat(ids(alice, "type", "EXPENSE", "payee", " o ", "memo", "off")).containsExactly(other, elena);
    }

    @Test
    void anAmountMatchesByItsCharactersOrWithinOne() throws IOException {
        long below = amountOnly(alice, "2026-09-01", "99.00", "EUR");
        long justAbove = amountOnly(alice, "2026-09-02", "99.01", "EUR");
        long justBelow = amountOnly(alice, "2026-09-03", "100.99", "EUR");
        long above = amountOnly(alice, "2026-09-04", "101.00", "EUR");
        assertThat(ids(alice, "type", "EXPENSE", "amount", "100", "currency", "EUR"))
                .containsExactly(justBelow, justAbove).doesNotContain(below, above);
        // Without the currency, or in another, only the characters: "100.99" holds "100".
        assertThat(ids(alice, "type", "EXPENSE", "amount", "100")).containsExactly(justBelow);
        assertThat(ids(alice, "type", "EXPENSE", "amount", "100", "currency", "USD")).containsExactly(justBelow);

        String bob = this.bob;
        long euros = amountOnly(bob, "2026-09-01", "1212.50", "EUR");
        long yen = amountOnly(bob, "2026-09-02", "1001", "JPY");
        long tenThousand = amountOnly(bob, "2026-09-03", "10010.00", "EUR");
        long dinar = amountOnly(bob, "2026-09-04", "1.001", "BHD");
        long dollars = amountOnly(bob, "2026-09-05", "100.50", "USD");
        amountOnly(bob, "2026-09-06", "100.70", "USD");
        // Each written with its currency's decimals: 1212.50, 1001, 10010.00, 1.001.
        assertThat(ids(bob, "type", "EXPENSE", "amount", "12.5")).containsExactly(euros);
        assertThat(ids(bob, "type", "EXPENSE", "amount", "1001")).containsExactly(tenThousand, yen);
        assertThat(ids(bob, "type", "EXPENSE", "amount", "1.001")).containsExactly(dinar);
        assertThat(ids(bob, "type", "EXPENSE", "amount", "1.001", "currency", "BHD")).containsExactly(dinar);
        // A line in another currency than the form's: by its characters only, never within 1.
        assertThat(ids(bob, "type", "EXPENSE", "amount", "100.5", "currency", "EUR")).containsExactly(dollars);
        assertThat(ids(bob, "type", "EXPENSE", "amount", "100.6", "currency", "EUR")).isEmpty();
        assertThat(ids(bob, "type", "EXPENSE", "amount", "100.6", "currency", "USD")).hasSize(2);
        JsonNode row = item(matches(bob, "type", "EXPENSE", "amount", "1001"), yen);
        assertThat(new BigDecimal(row.get("amount").asText())).isEqualByComparingTo("1001");
        assertThat(row.get("currency").asText()).isEqualTo("JPY");
    }

    /**
     * D-457: the user's own payment of a family expense and receipt of a family income count, with the record's
     * category, amount and refund; a record another member paid, the user's share of it, a settlement and a deleted
     * record don't; a refund is a personal one too.
     */
    @Test
    void familyRowsAreTheUsersOwnPaymentsAndReceipts() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        long shop = newCounterparty(alice, "Market hall");
        String payment = "\"paymentAccountId\": %d,".formatted(current);
        JsonNode paid = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "40.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "payeeId": %d, "privateNote": "family shop"}""".formatted(groceries, mum,
                current, shop)));
        JsonNode refund = created(post(alice, uri + "/records", """
                {"date": "2026-09-11", "categoryId": %d, "amount": "7.50", "payerMemberId": %d,
                 "paymentAccountId": %d, "refund": true}""".formatted(groceries, mum, current)));
        long bobsCash = accountId(bob, "CASH");
        created(post(bob, uri + "/records", expense("2026-09-12", groceries, "30.00", dad,
                "\"paymentAccountId\": %d,".formatted(bobsCash))));
        created(post(alice, uri + "/settlements", """
                {"date": "2026-09-13", "amount": "10", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, current)));
        JsonNode deleted = created(post(alice, uri + "/records", expense("2026-09-14", groceries, "25.00", mum,
                payment)));
        assertThat(delete(alice, uri + "/records/" + deleted.get("id").asLong() + "?version="
                + deleted.get("version").asInt())).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode received = created(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-15", "categoryId": %d, "amount": "500", "payerMemberId": %d,
                 "paymentAccountId": %d}""".formatted(salary, mum, current)));
        long personalRefund = entry(alice, "EXPENSE", "2026-09-16", null, null, "CURRENT_ACCOUNT", "EATING_OUT", "3",
                "EUR", true);

        long paymentEntry = paid.get("yourPayment").get("entryId").asLong();
        long refundEntry = refund.get("yourPayment").get("entryId").asLong();
        JsonNode expenses = matches(alice, "type", "EXPENSE", "accountId", String.valueOf(current));
        List<Long> found = new ArrayList<>();
        expenses.get("content").forEach(i -> found.add(i.get("entry").get("id").asLong()));
        assertThat(found).containsExactly(personalRefund, refundEntry, paymentEntry);

        JsonNode payRow = item(expenses, paymentEntry);
        assertThat(payRow.get("categoryId").asLong()).isEqualTo(groceries);
        assertThat(new BigDecimal(payRow.get("amount").asText())).isEqualByComparingTo("40");
        assertThat(payRow.get("currency").asText()).isEqualTo("EUR");
        assertThat(payRow.get("refund").asBoolean()).isFalse();
        assertThat(payRow.get("entry").get("family").get("link").asText()).isEqualTo("PAYMENT");
        JsonNode refundRow = item(expenses, refundEntry);
        assertThat(refundRow.get("refund").asBoolean()).isTrue();
        assertThat(new BigDecimal(refundRow.get("amount").asText())).isEqualByComparingTo("7.50");
        assertThat(item(expenses, personalRefund).get("refund").asBoolean()).isTrue();

        // The family category is the record's: her own share of Bob's record (posted with it) doesn't count.
        assertThat(ids(alice, "type", "EXPENSE", "categoryId", String.valueOf(groceries)))
                .containsExactly(refundEntry, paymentEntry);
        // The payee and the memo of her payment entry are her own (D-81) and her private note.
        assertThat(ids(alice, "type", "EXPENSE", "payee", "market")).containsExactly(paymentEntry);
        assertThat(ids(alice, "type", "EXPENSE", "memo", "family shop")).containsExactly(paymentEntry);
        // An amount: her line on the account, or on the debt account in the record's currency.
        assertThat(ids(alice, "type", "EXPENSE", "amount", "40", "currency", "EUR")).containsExactly(paymentEntry);
        assertThat(ids(alice, "type", "EXPENSE", "amount", "25", "currency", "EUR")).isEmpty();
        assertThat(ids(alice, "type", "EXPENSE", "amount", "30", "currency", "EUR")).isEmpty();
        assertThat(ids(alice, "type", "EXPENSE", "amount", "10", "currency", "EUR")).isEmpty();

        // Income: her receipt, with the income's category.
        JsonNode incomes = matches(alice, "type", "INCOME", "accountId", String.valueOf(current));
        long receipt = received.get("yourPayment").get("entryId").asLong();
        assertThat(incomes.get("totalElements").asLong()).isEqualTo(1);
        assertThat(item(incomes, receipt).get("categoryId").asLong()).isEqualTo(salary);
        assertThat(item(incomes, receipt).get("refund").asBoolean()).isFalse();

        // Bob's: his own payment, never Alice's.
        assertThat(ids(bob, "type", "EXPENSE", "categoryId", String.valueOf(groceries))).hasSize(1);
    }

    /** D-457: once Bob leaves, his payment's link is detached, and the entry is no longer a family row. */
    @Test
    void aLinkDetachedAfterLeavingIsLeftOut() throws IOException {
        long bobsCash = accountId(bob, "CASH");
        JsonNode paid = created(post(bob, uri + "/records", expense("2026-09-12", groceries, "30.00", dad,
                "\"paymentAccountId\": %d,".formatted(bobsCash))));
        long entry = paid.get("yourPayment").get("entryId").asLong();
        assertThat(ids(bob, "type", "EXPENSE", "accountId", String.valueOf(bobsCash))).containsExactly(entry);

        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ok(get(bob, "/api/entries/" + entry)).get("kind").asText()).isEqualTo("FAMILY_PAYMENT");
        assertThat(ids(bob, "type", "EXPENSE", "accountId", String.valueOf(bobsCash))).isEmpty();
        assertThat(ids(bob, "type", "EXPENSE", "amount", "30")).isEmpty();
    }

    /** H4: with family budgets on, an old shared expense isn't one a new entry can be, so it isn't listed. */
    @Test
    void anOldSharedExpenseIsLeftOutWhileFamilyBudgetsAreOn() throws IOException {
        newEntry(alice, """
                {"kind": "SHARED_EXPENSE", "entryDate": "2026-09-02", "accountId": %d, "currency": "EUR",
                 "total": "10.01", "categoryId": %d}""".formatted(accountId(alice, "CASH"),
                categoryId(alice, "EATING_OUT")));
        long own = expense(alice, "2026-09-03", null, null, "CASH", "EATING_OUT", "10.02");
        assertThat(ids(alice, "type", "EXPENSE", "amount", "10", "currency", "EUR")).containsExactly(own);
    }

    @Test
    void pagesAndTheCount() throws IOException {
        List<Long> made = new ArrayList<>();
        for (int day = 1; day <= 23; day++) {
            made.add(0, expense(alice, "2026-09-%02d".formatted(day), "Bakery", null, "CASH", "EATING_OUT", "2"));
        }
        JsonNode first = matches(alice, "type", "EXPENSE", "payee", "bakery");
        assertThat(first.get("totalElements").asLong()).isEqualTo(23);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("size").asInt()).isEqualTo(10);
        assertThat(first.get("content")).hasSize(10);
        assertThat(first.get("content").get(0).get("entry").get("id").asLong()).isEqualTo(made.get(0));
        JsonNode last = matches(alice, "type", "EXPENSE", "payee", "bakery", "page", "2");
        assertThat(last.get("content")).hasSize(3);
        assertThat(last.get("content").get(2).get("entry").get("id").asLong()).isEqualTo(made.get(22));
        assertThat(matches(alice, "type", "EXPENSE", "payee", "bakery", "size", "50").get("content")).hasSize(23);
    }

    @Test
    void aRequestWithoutACriterionOrWithABadValueIs400() {
        for (String query : List.of("type=EXPENSE", "type=EXPENSE&payee=a", "type=EXPENSE&payee= a &memo=b",
                "payee=albert", "type=TRANSFER&payee=albert", "type=EXPENSE&amount=-1", "type=EXPENSE&amount=1,5",
                "type=EXPENSE&amount=abc", "type=EXPENSE&amount=1.23456", "type=EXPENSE&amount=1&currency=eur",
                "type=EXPENSE&payee=" + "x".repeat(101), "type=EXPENSE&payee=ab&size=51",
                "type=EXPENSE&payee=ab&size=0", "type=EXPENSE&payee=ab&page=-1", "type=EXPENSE&accountId=x")) {
            assertThat(get(alice, "/api/entries/matches?" + query).getResponse().getStatus()).as(query).isEqualTo(400);
        }
    }

    /** Another user's entries never match, by any criterion, their ids included. */
    @Test
    void anotherUsersEntriesNeverMatch() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        expense(alice, "2026-09-05", "Albert Heijn", "weekly shop", "CASH", "EATING_OUT", "11.35");
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "40.00", mum,
                "\"paymentAccountId\": %d,".formatted(current))));
        for (String[] criterion : List.of(new String[] {"payee", "albert"}, new String[] {"memo", "weekly"},
                new String[] {"accountId", String.valueOf(accountId(alice, "CASH"))},
                new String[] {"accountId", String.valueOf(current)},
                new String[] {"categoryId", String.valueOf(categoryId(alice, "EATING_OUT"))},
                new String[] {"categoryId", String.valueOf(groceries)},
                new String[] {"amount", "11.35"}, new String[] {"amount", "40"})) {
            assertThat(matches(bob, "type", "EXPENSE", criterion[0], criterion[1]).get("totalElements").asLong())
                    .as(criterion[0]).isZero();
        }
    }
}
