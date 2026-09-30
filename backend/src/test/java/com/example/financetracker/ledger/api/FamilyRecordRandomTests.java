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
 * Family records created, changed and deleted at random, from a fixed seed, by two members with an account and for
 * one without (ADR 0003, topic K), with the default split rule changed now and then. After every operation the family
 * ledger's invariants hold ({@link FamilyInvariants}): the balances sum to zero, each debt account shows its member's
 * family balance on every record's date, and every posted entry balances.
 */
class FamilyRecordRandomTests extends LedgerApiTest {

    private static final long SEED = 20_260_930L;
    private static final int OPERATIONS = 240;

    private final Random random = new Random(SEED);
    private final String alice = newUser();
    private final String bob = newUser();

    /** A record that isn't deleted: who wrote it, who paid, and its version. */
    private record Live(long id, String author, long payer, int version) {
    }

    @Test
    void randomRecordsKeepTheInvariants() throws IOException {
        ok(get(bob, "/api/accounts"));
        JsonNode created = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-09-01",
                 "categoryIds": [%d, %d]}""".formatted(categoryId(alice, "GROCERIES"), categoryId(alice, "HOUSING")));
        long family = created.get("id").asLong();
        String uri = "/api/family-ledgers/" + family;
        long mum = created.get("memberId").asLong();
        long dad = join(family, bob, "Dad", "MEMBER", LocalDate.of(2026, 9, 1));
        long kid = body(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED).get("id").asLong();
        long[] members = {mum, dad, kid};
        List<Long> categories = ok(get(alice, uri + "/categories")).findValuesAsText("id").stream().map(Long::valueOf)
                .toList();
        Map<String, List<Long>> accounts = Map.of(
                alice, List.of(accountId(alice, "CASH"), accountId(alice, "CURRENT_ACCOUNT")),
                bob, List.of(accountId(bob, "CASH")));
        Map<String, Long> self = Map.of(alice, mum, bob, dad);

        Map<Long, Live> live = new LinkedHashMap<>();
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
            } else if (choice < 55 || live.isEmpty()) {
                long payer = random.nextInt(3) == 0 ? kid : self.get(actor);
                String payment = payer == kid ? "" : random.nextInt(4) == 0 ? "\"paymentLater\": true,"
                        : "\"paymentAccountId\": %d,".formatted(pick(accounts.get(actor)));
                BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(50_000), 2);
                JsonNode record = body(post(actor, uri + "/records", """
                        {"date": "%s", "categoryId": %d, "amount": "%s", %s "payerMemberId": %d, "split": %s}"""
                        .formatted(LocalDate.of(2026, 9, 1).plusDays(random.nextInt(30)), pick(categories), amount,
                                payment, payer, split(members, amount))), HttpStatus.CREATED);
                live.put(record.get("id").asLong(), new Live(record.get("id").asLong(), actor, payer, 0));
                done.merge("create", 1, Integer::sum);
            } else if (choice < 85) {
                Live record = pick(new ArrayList<>(live.values()));
                // The author or an owner (D-14): Alice owns the ledger.
                String editor = record.author().equals(bob) && random.nextBoolean() ? bob : alice;
                JsonNode changed = ok(patch(editor, uri + "/records/" + record.id() + "?version=" + record.version(),
                        """
                        {"categoryId": %d, "comment": "Change %d", "split": %s}""".formatted(pick(categories), i,
                                split(members, amount(uri, record.id())))));
                live.put(record.id(), new Live(record.id(), record.author(), record.payer(),
                        changed.get("version").asInt()));
                done.merge("change", 1, Integer::sum);
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
        }

        // What the seed gives: every kind of operation, many times.
        assertThat(done).containsExactlyInAnyOrderEntriesOf(Map.of("create", 123, "change", 68, "delete", 38,
                "split rule", 11));
        assertThat(live).hasSize(85);
        Map<Long, BigDecimal> balances = FamilyInvariants.check(jdbc, family);
        JsonNode answered = ok(get(bob, uri + "/balances")).get("members");
        for (JsonNode member : answered) {
            assertThat(new BigDecimal(member.get("balance").asText())).as(member.get("displayName").asText())
                    .isEqualByComparingTo(balances.get(member.get("memberId").asLong()));
        }
        assertThat(ok(get(alice, uri + "/records?size=200")).get("totalElements").asInt()).isEqualTo(live.size());
    }

    /** The record's amount, as the family reads it. */
    private String amount(String uri, long recordId) throws IOException {
        return ok(get(alice, uri + "/records/" + recordId)).get("amount").asText();
    }

    /** A split of each kind: the ledger's rule, percentages, amounts, or all on one member. */
    private String split(long[] members, Object amount) {
        return switch (random.nextInt(4)) {
            case 0 -> """
                    {"method": "RULE"}""";
            case 1 -> """
                    {"method": "PERCENT", "shares": [%s]}""".formatted(percentShares(members, "basisPoints"));
            case 2 -> {
                long cents = new BigDecimal(amount.toString()).movePointRight(2).longValueExact();
                long first = (long) (random.nextDouble() * (cents + 1));
                long second = (long) (random.nextDouble() * (cents - first + 1));
                yield """
                        {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "%s"},
                         {"memberId": %d, "amount": "%s"}, {"memberId": %d, "amount": "%s"}]}""".formatted(
                        members[0], BigDecimal.valueOf(first, 2), members[1], BigDecimal.valueOf(second, 2),
                        members[2], BigDecimal.valueOf(cents - first - second, 2));
            }
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
