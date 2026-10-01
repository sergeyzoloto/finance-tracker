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
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Taking a seat at random (F5; D-18, D-10; ADR 0003, topic K), from a fixed seed. Each round, Alice and Bob keep a
 * family budget with two members without an account, Kid and Gran: expenses, incomes and settlements at random, a
 * quarter in dollars with the base amount entered, many paid or received by Kid or Gran. Then a new user takes one of
 * the two places from a random join date in September, and the three of them go on: new records, payment edits, family
 * edits and deletions of records before and after the join date, each by whoever the round picks, so that some are
 * refused (409, 422) and none fails. After the claim and after every operation the family's invariants hold
 * ({@link FamilyInvariants}, with the opening balance from the join date) and the integrity check finds nothing for
 * any of the three.
 */
class FamilyClaimRandomTests extends LedgerApiTest {

    private static final long SEED = 20_261_001L;
    private static final int ROUNDS = 6;
    private static final int BEFORE = 20;
    private static final int AFTER = 25;

    private final Random random = new Random(SEED);

    @Test
    void randomClaimsKeepTheInvariants() throws IOException {
        Map<String, Integer> done = new LinkedHashMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            round(done);
        }
        // What the seed made of it, so that a change of the mix shows here.
        assertThat(done).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(Map.entry("claims", 6),
                Map.entry("creates", 43), Map.entry("amounts", 6), Map.entry("amounts by the claimer", 5),
                Map.entry("dates", 4), Map.entry("comments", 10), Map.entry("accounts", 4), Map.entry("deletes", 12),
                Map.entry("tried before the join date", 39), Map.entry("refused", 66)));
    }

    private void round(Map<String, Integer> done) throws IOException {
        String alice = newUser();
        String bob = newUser();
        String claimer = newUser();
        ok(get(bob, "/api/accounts"));
        ok(get(claimer, "/api/accounts"));
        JsonNode created = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-09-01",
                 "categoryIds": [%d, %d]}""".formatted(categoryId(alice, "GROCERIES"), categoryId(alice, "SALARY")));
        long family = created.get("id").asLong();
        String uri = "/api/family-ledgers/" + family;
        long mum = created.get("memberId").asLong();
        long dad = join(family, bob, "Dad", "MEMBER", LocalDate.of(2026, 9, 1));
        long kid = body(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED).get("id").asLong();
        long gran = body(post(alice, uri + "/members", """
                {"displayName": "Gran"}"""), HttpStatus.CREATED).get("id").asLong();
        JsonNode categories = ok(get(alice, uri + "/categories"));
        Map<String, Long> category = Map.of("EXPENSE", find(categories, "code", "GROCERIES").get("id").asLong(),
                "INCOME", find(categories, "code", "SALARY").get("id").asLong());
        Map<String, Long> self = new LinkedHashMap<>(Map.of(alice, mum, bob, dad));
        Map<String, Long> cash = new LinkedHashMap<>(Map.of(alice, accountId(alice, "CASH"), bob, accountId(bob,
                "CASH"), claimer, accountId(claimer, "CASH")));
        List<Long> records = new ArrayList<>();

        for (int i = 0; i < BEFORE; i++) {
            String actor = random.nextBoolean() ? alice : bob;
            long payer = pick(List.of(self.get(actor), kid, gran));
            MvcTestResult answer = random.nextInt(4) == 0
                    ? settle(actor, uri, self.get(actor), pick(List.of(kid, gran)), cash.get(actor))
                    : record(actor, uri, payer, self.get(actor), cash.get(actor), category);
            records.add(body(answer, HttpStatus.CREATED).get("id").asLong());
            FamilyInvariants.check(jdbc, family);
        }

        long seat = random.nextBoolean() ? kid : gran;
        LocalDate joinDate = LocalDate.of(2026, 9, 1).plusDays(random.nextInt(30));
        String link = body(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "%s"}""".formatted(seat, joinDate)),
                HttpStatus.CREATED).get("link").asText();
        assertThat(mvc.post().uri("/api/invites/accept").with(member(claimer)).with(request -> {
            request.setRemoteAddr("198.18.2." + (family % 250 + 1));
            return request;
        }).contentType("application/json").content("{\"token\": \"%s\", \"displayName\": \"Claimer\"}"
                .formatted(link.substring(link.indexOf('#') + 1)))).hasStatus(HttpStatus.OK);
        self.put(claimer, seat);
        done.merge("claims", 1, Integer::sum);
        check(family, alice, bob, claimer);

        for (int i = 0; i < AFTER; i++) {
            // The claimer half the time, mostly on records of the place they took.
            String actor = random.nextBoolean() ? claimer : random.nextBoolean() ? alice : bob;
            int choice = random.nextInt(100);
            MvcTestResult answer;
            if (choice < 35 || records.isEmpty()) {
                long payer = pick(List.of(self.get(actor), seat == kid ? gran : kid));
                answer = random.nextInt(4) == 0
                        ? settle(actor, uri, self.get(actor), pick(List.of(mum, dad, kid, gran)), cash.get(actor))
                        : record(actor, uri, payer, self.get(actor), cash.get(actor), category);
                if (answer.getResponse().getStatus() == 201) {
                    records.add(body(answer, HttpStatus.CREATED).get("id").asLong());
                }
                count(done, answer, "creates");
            } else {
                List<Long> theirs = new ArrayList<>();
                for (JsonNode listed : ok(get(actor, uri + "/records?size=200")).get("content")) {
                    if (listed.get("payer").get("memberId").asLong() == seat
                            || listed.has("payee") && listed.get("payee").get("memberId").asLong() == seat) {
                        theirs.add(listed.get("id").asLong());
                    }
                }
                long id = actor.equals(claimer) && !theirs.isEmpty() && random.nextInt(4) > 0 ? pick(theirs)
                        : pick(records);
                JsonNode record = ok(get(actor, uri + "/records/" + id));
                String path = uri + "/records/" + id + "?version=" + record.get("version").asInt();
                if (LocalDate.parse(record.get("date").asText()).isBefore(joinDate)) {
                    done.merge("tried before the join date", 1, Integer::sum);
                }
                if (choice < 60) {
                    // The amount, by whoever: the payer with an account, or the author or an owner of a guest's.
                    BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(20_000), 2);
                    answer = patch(actor, path, record.get("originalCurrency").asText().equals("EUR")
                            ? "{\"amount\": \"%s\"}".formatted(amount)
                            : "{\"amount\": \"%s\", \"currency\": \"USD\", \"baseAmount\": \"%s\"}".formatted(amount,
                                    amount));
                    count(done, answer, actor.equals(claimer) ? "amounts by the claimer" : "amounts");
                } else if (choice < 70) {
                    answer = patch(actor, path, "{\"date\": \"%s\"}".formatted(LocalDate.of(2026, 9, 1)
                            .plusDays(random.nextInt(30))));
                    count(done, answer, "dates");
                } else if (choice < 85) {
                    answer = patch(actor, path, "{\"comment\": \"Note %d\"}".formatted(i));
                    count(done, answer, "comments");
                } else if (choice < 92 && record.has("yourPayment")) {
                    answer = patch(actor, path, random.nextBoolean() ? "{\"paymentLater\": true}"
                            : "{\"paymentAccountId\": %d}".formatted(cash.get(actor)));
                    count(done, answer, "accounts");
                } else {
                    answer = delete(actor, path);
                    if (answer.getResponse().getStatus() == 204) {
                        records.remove(Long.valueOf(id));
                    }
                    count(done, answer, "deletes");
                }
            }
            int status = answer.getResponse().getStatus();
            assertThat(status).as("operation %d: %s", i, answer.getResponse().getContentAsString())
                    .isIn(200, 201, 204, 409, 422);
            if (status >= 400) {
                done.merge("refused", 1, Integer::sum);
            }
            check(family, alice, bob, claimer);
        }
    }

    /** Counts an operation that went through. */
    private static void count(Map<String, Integer> done, MvcTestResult answer, String what) {
        if (answer.getResponse().getStatus() < 400) {
            done.merge(what, 1, Integer::sum);
        }
    }

    /** An expense or an income of the rule, a quarter in dollars with the base amount entered. */
    private MvcTestResult record(String actor, String uri, long payer, long own, long cash, Map<String, Long> category) {
        String type = random.nextInt(4) == 0 ? "INCOME" : "EXPENSE";
        BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(30_000), 2);
        String currency = random.nextInt(4) == 0
                ? "\"currency\": \"USD\", \"baseAmount\": \"%s\",".formatted(amount) : "";
        String payment = payer != own ? "" : random.nextInt(3) == 0 ? "\"paymentLater\": true,"
                : "\"paymentAccountId\": %d,".formatted(cash);
        return post(actor, uri + "/records", """
                {"type": "%s", "date": "%s", "categoryId": %d, "amount": "%s", %s %s "payerMemberId": %d}"""
                .formatted(type, LocalDate.of(2026, 9, 1).plusDays(random.nextInt(30)), category.get(type), amount,
                        currency, payment, payer));
    }

    /** A settlement between the actor and someone else, either way. */
    private MvcTestResult settle(String actor, String uri, long own, long other, long cash) {
        boolean pays = random.nextBoolean();
        return post(actor, uri + "/settlements", """
                {"date": "%s", "amount": "%s", "payerMemberId": %d, "payeeMemberId": %d, "paymentAccountId": %d}"""
                .formatted(LocalDate.of(2026, 9, 1).plusDays(random.nextInt(30)),
                        BigDecimal.valueOf(1 + random.nextInt(10_000), 2), pays ? own : other, pays ? other : own,
                        cash));
    }

    private void check(long family, String... users) throws IOException {
        FamilyInvariants.check(jdbc, family);
        for (String user : users) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
