package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Family expenses and incomes (F4d) created, changed and deleted at random, from a fixed seed, by two members with an
 * account and for one without (ADR 0003, topic K), with the default split rule changed now and then, payment edits
 * (F4c): a new
 * date, amount, payer or paying account, and settlements (F4d): recorded by a side with an account, changed by their
 * recorder (date, amount, comment, account) or put on an account by their other side, and deleted; while the other
 * side's part is on an account of theirs, the recorder's change of the date or amount and their deletion answer 409
 * (D-28). Since F8a the family's records and settlements are in euros, dollars or roubles (D-45, D-46), paid from the
 * members' accounts in all three, which name what went from or into them in their own currency whenever it isn't the
 * record's (D-87), on creation and on each new amount. After every operation the family ledger's invariants hold, in
 * each currency
 * ({@link FamilyInvariants}): the balances sum to zero, each debt account shows its member's family balance on every
 * record's date, and every posted entry balances; and since F6c the family report agrees with the balances and with each
 * member's personal cash flow (E1, E3; {@link #checkFamilyReport}).
 */
class FamilyRecordRandomTests extends LedgerApiTest {

    private static final long SEED = 20_260_930L;
    private static final int OPERATIONS = 240;

    private final Random random = new Random(SEED);
    private final String alice = newUser();
    private final String bob = newUser();

    /**
     * A record that isn't deleted: its type, who wrote it, who paid or received it, its version, and its currency
     * (D-45).
     */
    private record Live(long id, String type, String author, long payer, int version, String currency) {
    }

    /**
     * A settlement that isn't deleted: who recorded it, the other side if they have an account, its version, whether
     * the other side has put its part on an account of theirs (D-28), and its currency (D-46).
     */
    private record Settlement(long id, String recorder, String other, int version, boolean placed, String currency) {
    }

    /** Each account's currency, for what went from or into it (D-87). */
    private final Map<Long, String> accountCurrency = new LinkedHashMap<>();

    @Test
    void randomRecordsKeepTheInvariants() throws IOException {
        ok(get(bob, "/api/accounts"));
        JsonNode created = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-09-01",
                 "categoryIds": [%d, %d, %d]}""".formatted(categoryId(alice, "GROCERIES"), categoryId(alice, "HOUSING"),
                categoryId(alice, "SALARY")));
        long family = created.get("id").asLong();
        String uri = "/api/family-ledgers/" + family;
        long mum = created.get("memberId").asLong();
        long dad = join(family, bob, "Dad", "MEMBER", LocalDate.of(2026, 9, 1));
        long kid = body(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED).get("id").asLong();
        long[] members = {mum, dad, kid};
        Map<String, List<Long>> categories = new LinkedHashMap<>();
        for (JsonNode category : ok(get(alice, uri + "/categories"))) {
            categories.computeIfAbsent(category.get("type").asText(), type -> new ArrayList<>())
                    .add(category.get("id").asLong());
        }
        // Each member's own dollar rate, which no record uses since F8a (D-87); a dollar card of Alice's and a rouble
        // account of Bob's beside their euro accounts.
        ok(post(alice, "/api/rates/manual", """
                {"date": "2026-08-31", "base": "EUR", "quote": "USD", "rate": "1.10"}"""));
        ok(post(bob, "/api/rates/manual", """
                {"date": "2026-08-31", "base": "EUR", "quote": "USD", "rate": "1.20"}"""));
        long dollarCard = body(post(alice, "/api/accounts", """
                {"code": "USD_CARD", "name": "Dollar card", "type": "ASSET", "defaultCurrency": "USD"}"""),
                HttpStatus.CREATED).get("id").asLong();
        long roubles = body(post(bob, "/api/accounts", """
                {"code": "RUB_ACCOUNT", "name": "Rouble account", "type": "ASSET", "defaultCurrency": "RUB"}"""),
                HttpStatus.CREATED).get("id").asLong();
        Map<String, List<Long>> accounts = Map.of(
                alice, List.of(accountId(alice, "CASH"), accountId(alice, "CURRENT_ACCOUNT"), dollarCard),
                bob, List.of(accountId(bob, "CASH"), roubles));
        accounts.values().forEach(ids -> ids.forEach(id -> accountCurrency.put(id, "EUR")));
        accountCurrency.put(dollarCard, "USD");
        accountCurrency.put(roubles, "RUB");
        Map<String, Long> self = Map.of(alice, mum, bob, dad);

        Map<Long, Live> live = new LinkedHashMap<>();
        Map<Long, Settlement> settlements = new LinkedHashMap<>();
        Map<Long, String> users = Map.of(mum, alice, dad, bob);
        Map<String, Integer> done = new LinkedHashMap<>();
        for (int i = 0; i < OPERATIONS; i++) {
            int choice = random.nextInt(100);
            String actor = random.nextBoolean() ? alice : bob;
            if (choice < 5) {
                String rule = random.nextBoolean() ? """
                        {"rule": "EQUAL"}""" : """
                        {"rule": "CUSTOM", "shares": [%s]}""".formatted(percentShares(members, "share"));
                ok(put(alice, uri + "/split-rule", rule));
                done.merge("split rule", 1, Integer::sum);
            } else if (choice < 45 || live.isEmpty()) {
                long payer = random.nextInt(3) == 0 ? kid : self.get(actor);
                String currency = currency();
                String payment = payer == kid ? "" : payment(accounts.get(actor), currency) + ",";
                BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(50_000), 2);
                String type = random.nextInt(4) == 0 ? "INCOME" : "EXPENSE";
                String split = split(members, amount);
                JsonNode record = body(post(actor, uri + "/records", """
                        {"type": "%s", "date": "%s", "categoryId": %d, "amount": "%s", "currency": "%s", %s
                         "payerMemberId": %d, "split": %s}""".formatted(type,
                                LocalDate.of(2026, 9, 1).plusDays(random.nextInt(30)), pick(categories.get(type)),
                                amount, currency, payment, payer, split)),
                        HttpStatus.CREATED);
                live.put(record.get("id").asLong(), new Live(record.get("id").asLong(), type, actor, payer, 0,
                        currency));
                counted(done, currency, payment);
                done.merge(type.equals("INCOME") ? "income" : "create", 1, Integer::sum);
            } else if (choice < 55 || choice < 85 && choice >= 78 && settlements.isEmpty()) {
                // The actor pays or receives, with one of the other two members.
                long own = self.get(actor);
                long other = pick(List.of(mum, dad, kid).stream().filter(m -> m != own).toList());
                boolean pays = random.nextBoolean();
                BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(50_000), 2);
                String currency = currency();
                String payment = payment(accounts.get(actor), currency);
                JsonNode settled = body(post(actor, uri + "/settlements", """
                        {"date": "%s", "amount": "%s", "currency": "%s", "payerMemberId": %d, "payeeMemberId": %d,
                         %s}""".formatted(LocalDate.of(2026, 9, 1).plusDays(random.nextInt(30)), amount, currency,
                                pays ? own : other, pays ? other : own, payment)), HttpStatus.CREATED);
                counted(done, currency, payment);
                settlements.put(settled.get("id").asLong(), new Settlement(settled.get("id").asLong(), actor,
                        users.get(other), 0, false, currency));
                done.merge("settle", 1, Integer::sum);
            } else if (choice < 68) {
                Live record = pick(new ArrayList<>(live.values()));
                // The author or an owner (D-14): Alice owns the ledger.
                String editor = record.author().equals(bob) && random.nextBoolean() ? bob : alice;
                JsonNode changed = ok(patch(editor, uri + "/records/" + record.id() + "?version=" + record.version(),
                        """
                        {"categoryId": %d, "comment": "Change %d", "split": %s}""".formatted(
                                pick(categories.get(record.type())), i, split(members, amount(uri, record.id())))));
                live.put(record.id(), new Live(record.id(), record.type(), record.author(), record.payer(),
                        changed.get("version").asInt(), record.currency()));
                done.merge("change", 1, Integer::sum);
            } else if (choice >= 78 && choice < 85) {
                Settlement settlement = pick(new ArrayList<>(settlements.values()));
                String path = uri + "/records/" + settlement.id() + "?version=" + settlement.version();
                JsonNode changed;
                boolean placed = settlement.placed();
                if (settlement.other() != null && random.nextInt(3) == 0) {
                    // The other side puts its part on an account, or back: the version stays.
                    String payment = payment(accounts.get(settlement.other()), settlement.currency());
                    changed = ok(patch(settlement.other(), path, "{" + payment + "}"));
                    placed = !payment.contains("paymentLater");
                } else {
                    List<String> fields = new ArrayList<>();
                    if (random.nextBoolean()) {
                        fields.add("\"date\": \"%s\"".formatted(LocalDate.of(2026, 9, 1).plusDays(random.nextInt(30))));
                    }
                    boolean newAmount = fields.isEmpty() || random.nextBoolean();
                    if (newAmount) {
                        fields.add("\"amount\": \"%s\"".formatted(BigDecimal.valueOf(1 + random.nextInt(50_000), 2)));
                    }
                    if (random.nextBoolean()) {
                        fields.add(payment(accounts.get(settlement.recorder()), settlement.currency()));
                    } else if (newAmount && elsewhere(ok(get(settlement.recorder(), path.replaceAll("\\?.*", ""))))) {
                        // The recorder's side on an account in another currency follows the amount (D-87).
                        fields.add(accountAmount());
                    }
                    var answer = patch(settlement.recorder(), path, "{" + String.join(", ", fields) + "}");
                    if (placed) {
                        // The lock (D-28): its date and amount wait for the other side.
                        assertThat(answer).hasStatus(HttpStatus.CONFLICT);
                        done.merge("locked", 1, Integer::sum);
                        FamilyInvariants.check(jdbc, family);
                        continue;
                    }
                    changed = ok(answer);
                }
                settlements.put(settlement.id(), new Settlement(settlement.id(), settlement.recorder(),
                        settlement.other(), changed.get("version").asInt(), placed, settlement.currency()));
                done.merge("settlement change", 1, Integer::sum);
            } else if (choice < 78) {
                Live record = pick(new ArrayList<>(live.values()));
                // The payer with an account, else the author or an owner (D-14).
                String editor = record.payer() == mum ? alice : record.payer() == dad ? bob
                        : record.author().equals(bob) && random.nextBoolean() ? bob : alice;
                JsonNode current = ok(get(editor, uri + "/records/" + record.id()));
                List<String> fields = new ArrayList<>();
                long payer = record.payer();
                boolean named = false;
                if (random.nextInt(3) == 0) {
                    // To a member without an account, or to the editor, who says how they paid.
                    payer = random.nextBoolean() ? kid : self.get(editor);
                    if (payer != record.payer()) {
                        fields.add("\"payerMemberId\": " + payer);
                        if (payer != kid) {
                            fields.add(payment(accounts.get(editor), record.currency()));
                            named = true;
                        }
                    }
                } else if (payer != kid && random.nextBoolean()) {
                    fields.add(payment(accounts.get(editor), record.currency()));
                    named = true;
                }
                boolean byAmounts = current.get("splitMethod").asText().equals("AMOUNT");
                // The editor's own side on an account in another currency, which a new amount asks the amount of
                // (D-87), unless they name an account now.
                boolean elsewhere = !named && payer == record.payer() && elsewhere(current);
                if (random.nextBoolean()) {
                    fields.add("\"date\": \"%s\"".formatted(LocalDate.of(2026, 9, 1).plusDays(random.nextInt(30))));
                }
                if (fields.isEmpty() || random.nextBoolean()) {
                    BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(50_000), 2);
                    fields.add("\"amount\": \"%s\"".formatted(amount));
                    if (elsewhere) {
                        fields.add(accountAmount());
                    }
                    if (byAmounts) {
                        fields.add("\"split\": " + amounts(members, amount));
                    }
                }
                JsonNode changed = ok(patch(editor, uri + "/records/" + record.id() + "?version=" + record.version(),
                        "{" + String.join(", ", fields) + "}"));
                live.put(record.id(), new Live(record.id(), record.type(), record.author(), payer,
                        changed.get("version").asInt(), record.currency()));
                done.merge("payment", 1, Integer::sum);
            } else if (!settlements.isEmpty() && random.nextInt(4) == 0) {
                // Its recorder deletes a settlement.
                Settlement settlement = pick(new ArrayList<>(settlements.values()));
                var answer = delete(settlement.recorder(), uri + "/records/" + settlement.id() + "?version="
                        + settlement.version());
                if (settlement.placed()) {
                    assertThat(answer).hasStatus(HttpStatus.CONFLICT);
                    done.merge("locked", 1, Integer::sum);
                } else {
                    assertThat(answer).hasStatus(HttpStatus.NO_CONTENT);
                    settlements.remove(settlement.id());
                    done.merge("settlement delete", 1, Integer::sum);
                }
            } else {
                Live record = pick(new ArrayList<>(live.values()));
                // The payer with an account, else the author or an owner (D-14).
                String deleter = record.payer() == mum ? alice : record.payer() == dad ? bob
                        : record.author().equals(bob) && random.nextBoolean() ? bob : alice;
                assertThat(delete(deleter, uri + "/records/" + record.id() + "?version=" + record.version()))
                        .hasStatus(HttpStatus.NO_CONTENT);
                live.remove(record.id());
                done.merge("delete", 1, Integer::sum);
            }
            FamilyInvariants.check(jdbc, family);
            // E1's report against the balances, and E3's check for both members with an account (F6c).
            checkFamilyReport(i % 2 == 0 ? alice : bob, family,
                    Map.of(alice, LocalDate.of(2026, 9, 1), bob, LocalDate.of(2026, 9, 1)));
        }

        // What the seed gives: every kind of operation, many times.
        assertThat(done).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(Map.entry("create", 70),
                Map.entry("income", 28), Map.entry("change", 36), Map.entry("payment", 20), Map.entry("delete", 31),
                Map.entry("split rule", 6), Map.entry("settle", 25), Map.entry("settlement change", 13),
                Map.entry("settlement delete", 5), Map.entry("locked", 6), Map.entry("in USD", 30),
                Map.entry("in RUB", 17), Map.entry("from another currency", 36)));
        assertThat(live).hasSize(67);
        assertThat(live.values()).extracting(Live::currency).contains("EUR", "USD", "RUB");
        assertThat(live.values()).filteredOn(record -> record.type().equals("INCOME")).isNotEmpty();
        assertThat(settlements).hasSize(20);
        assertThat(settlements.values()).extracting(Settlement::currency).contains("EUR", "USD");
        Map<Long, BigDecimal> balances = FamilyInvariants.check(jdbc, family);
        JsonNode answered = ok(get(bob, uri + "/balances")).get("byCurrency").get(0).get("members");
        for (JsonNode member : answered) {
            assertThat(new BigDecimal(member.get("balance").asText())).as(member.get("displayName").asText())
                    .isEqualByComparingTo(balances.get(member.get("memberId").asLong()));
        }
        assertThat(ok(get(alice, uri + "/records?size=200")).get("totalElements").asInt())
                .isEqualTo(live.size() + settlements.size());
    }

    /** A record's or a settlement's currency: euros mostly, a quarter dollars, an eighth roubles (D-45). */
    private String currency() {
        return switch (random.nextInt(8)) {
            case 0, 1 -> "USD";
            case 2 -> "RUB";
            default -> "EUR";
        };
    }

    /** Counts what a new record or settlement was in, and whether an account in another currency paid it. */
    private static void counted(Map<String, Integer> done, String currency, String payment) {
        if (!currency.equals("EUR")) {
            done.merge("in " + currency, 1, Integer::sum);
        }
        if (payment.contains("accountAmount")) {
            done.merge("from another currency", 1, Integer::sum);
        }
    }

    /** Whether the reader's own side of the record is on an account in another currency than the record's (D-87). */
    private static boolean elsewhere(JsonNode record) {
        return record.has("yourPayment")
                && !record.get("yourPayment").get("currency").asText().equals(record.get("currency").asText());
    }

    /** What went from or into an account in another currency than the record's (D-87). */
    private String accountAmount() {
        return "\"accountAmount\": \"%s\"".formatted(BigDecimal.valueOf(1 + random.nextInt(50_000), 2));
    }

    /** The record's amount, as the family reads it. */
    private String amount(String uri, long recordId) throws IOException {
        return ok(get(alice, uri + "/records/" + recordId)).get("amount").asText();
    }

    /**
     * How the payer paid: one of their accounts, with what went from or into it when its currency isn't the record's
     * (D-87), or "Specify later" now and then.
     */
    private String payment(List<Long> accounts, String currency) {
        if (random.nextInt(4) == 0) {
            return "\"paymentLater\": true";
        }
        long account = pick(accounts);
        return accountCurrency.get(account).equals(currency) ? "\"paymentAccountId\": " + account
                : "\"paymentAccountId\": %d, %s".formatted(account, accountAmount());
    }

    /** A split by amounts of the amount among the three members. */
    private String amounts(long[] members, BigDecimal amount) {
        long cents = amount.movePointRight(2).longValueExact();
        long first = (long) (random.nextDouble() * (cents + 1));
        long second = (long) (random.nextDouble() * (cents - first + 1));
        return """
                {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "%s"},
                 {"memberId": %d, "amount": "%s"}, {"memberId": %d, "amount": "%s"}]}""".formatted(
                members[0], BigDecimal.valueOf(first, 2), members[1], BigDecimal.valueOf(second, 2),
                members[2], BigDecimal.valueOf(cents - first - second, 2));
    }

    /** A split of each kind: the ledger's rule, percentages, amounts, or all on one member. */
    private String split(long[] members, Object amount) {
        return switch (random.nextInt(4)) {
            case 0 -> """
                    {"method": "RULE"}""";
            case 1 -> """
                    {"method": "PERCENT", "shares": [%s]}""".formatted(percentShares(members, "basisPoints"));
            case 2 -> amounts(members, new BigDecimal(amount.toString()));
            default -> """
                    {"method": "ONE_MEMBER", "memberId": %d}""".formatted(members[random.nextInt(members.length)]);
        };
    }

    /** Every member's share in basis points, summing to 10000, under the given field name. */
    private String percentShares(long[] members, String field) {
        int first = random.nextInt(10_001);
        int second = random.nextInt(10_001 - first);
        int[] shares = {first, second, 10_000 - first - second};
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < members.length; i++) {
            entries.add("{\"memberId\": %d, \"%s\": %d}".formatted(members[i], field, shares[i]));
        }
        return String.join(", ", entries);
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
