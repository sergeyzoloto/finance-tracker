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
import java.util.concurrent.atomic.AtomicInteger;

import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A membership's lifecycle at random (F6a; D-19, D-20, D-26; ADR 0003, topic K), from a fixed seed. Each round, four
 * users and Kid, who has no account, keep a family budget that Alice created on 2026-09-01, Bob and Carol in it from
 * the start and Dave outside. Then, operation after operation, a member with an account picked from the database:
 * records an expense or an income (by themselves or by Kid), changes an amount, deletes a record, leaves, removes a
 * member, makes another member an owner, sets a custom or an equal split rule, invites someone who left (or is outside)
 * back, or deletes all their data. Some are refused (409, 422); none fails, and none answers 404, since every actor is
 * an ACTIVE member. After every operation the family's invariants hold, those of members who left and returned
 * included ({@link FamilyInvariants}), and the integrity check finds nothing for any of the four. A round ends early
 * when the family budget is gone or archived. Records are dated in September 2026 or today, so that the seed draws the
 * same numbers on any day after September.
 */
class FamilyLifecycleRandomTests extends LedgerApiTest {

    private static final long SEED = 20_261_002L;
    private static final int ROUNDS = 6;
    private static final int OPERATIONS = 40;
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    private final Random random = new Random(SEED);

    @Test
    void randomLifecyclesKeepTheInvariants() throws IOException {
        Map<String, Integer> done = new LinkedHashMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            round(done);
        }
        // What the seed made of it, so that a change of the mix shows here.
        assertThat(done).containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }

    /** What the seed makes of six rounds: 214 operations, two rounds ending with an archived family budget. */
    private static final Map<String, Integer> EXPECTED = Map.ofEntries(Map.entry("records", 51),
            Map.entry("amounts", 4), Map.entry("deletes", 5), Map.entry("leaves", 11), Map.entry("removals", 20),
            Map.entry("owners", 7), Map.entry("split rules", 15), Map.entry("returns", 25),
            Map.entry("deletions of all data", 3), Map.entry("refused", 73), Map.entry("budgets archived", 2));

    private void round(Map<String, Integer> done) throws IOException {
        List<String> users = List.of(newUser(), newUser(), newUser(), newUser());
        Map<String, String> names = Map.of(users.get(0), "Alice", users.get(1), "Bob", users.get(2), "Carol",
                users.get(3), "Dave");
        for (String user : users) {
            ok(get(user, "/api/accounts"));
        }
        String alice = users.get(0);
        JsonNode created = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Alice", "startDate": "2026-09-01",
                 "categoryIds": [%d, %d]}""".formatted(categoryId(alice, "GROCERIES"), categoryId(alice, "SALARY")));
        long family = created.get("id").asLong();
        String uri = "/api/family-ledgers/" + family;
        join(family, users.get(1), "Bob", "MEMBER", LocalDate.of(2026, 9, 1));
        join(family, users.get(2), "Carol", "MEMBER", LocalDate.of(2026, 9, 1));
        long kid = body(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""), HttpStatus.CREATED).get("id").asLong();
        JsonNode categories = ok(get(alice, uri + "/categories"));
        Map<String, Long> category = Map.of("EXPENSE", find(categories, "code", "GROCERIES").get("id").asLong(),
                "INCOME", find(categories, "code", "SALARY").get("id").asLong());
        LocalDate today = jdbc.sql("SELECT current_date").query(LocalDate.class).single();

        for (int i = 0; i < OPERATIONS; i++) {
            List<Member> members = members(family);
            if (members.isEmpty()) {
                done.merge("budgets gone", 1, Integer::sum);
                return;
            }
            List<Member> active = members.stream().filter(m -> m.status().equals("ACTIVE") && m.sub() != null).toList();
            if (active.isEmpty()) {
                done.merge("budgets archived", 1, Integer::sum);
                return;
            }
            Member actor = pick(active);
            List<Member> owners = active.stream().filter(m -> m.role().equals("OWNER")).toList();
            int choice = random.nextInt(100);
            MvcTestResult answer;
            String what;
            if (choice < 35) {
                boolean byKid = members.stream().anyMatch(m -> m.id() == kid && m.status().equals("ACTIVE"))
                        && random.nextInt(3) == 0;
                answer = record(actor, uri, byKid ? kid : actor.id(), category, today);
                what = "records";
            } else if (choice < 45) {
                Long record = randomRecord(family);
                answer = record == null ? record(actor, uri, actor.id(), category, today)
                        : patch(actor.sub(), uri + "/records/" + record + "?version=" + version(record),
                                "{\"amount\": \"%s\"}".formatted(amount()));
                what = record == null ? "records" : "amounts";
            } else if (choice < 50) {
                Long record = randomRecord(family);
                answer = record == null ? record(actor, uri, actor.id(), category, today)
                        : delete(actor.sub(), uri + "/records/" + record + "?version=" + version(record));
                what = record == null ? "records" : "deletes";
            } else if (choice < 60) {
                answer = delete(actor.sub(), uri + "/members/me");
                what = "leaves";
            } else if (choice < 68) {
                Member owner = owners.isEmpty() ? actor : pick(owners);
                answer = delete(owner.sub(), uri + "/members/" + pick(members).id());
                what = "removals";
            } else if (choice < 75) {
                Member owner = owners.isEmpty() ? actor : pick(owners);
                answer = post(owner.sub(), uri + "/members/" + pick(members).id() + "/owner", null);
                what = "owners";
            } else if (choice < 82) {
                Member owner = owners.isEmpty() ? actor : pick(owners);
                answer = splitRule(owner.sub(), uri, members);
                what = "split rules";
            } else if (choice < 97) {
                Member owner = owners.isEmpty() ? actor : pick(owners);
                List<String> outside = users.stream().filter(u -> active.stream().noneMatch(m -> u.equals(m.sub())))
                        .toList();
                if (outside.isEmpty()) {
                    answer = record(actor, uri, actor.id(), category, today);
                    what = "records";
                } else {
                    answer = invite(owner.sub(), uri, pick(outside), names);
                    what = "returns";
                }
            } else {
                answer = delete(actor.sub(), "/api/me/data");
                what = "deletions of all data";
            }
            int status = answer.getResponse().getStatus();
            assertThat(status).as("operation %d (%s): %s", i, what, answer.getResponse().getContentAsString())
                    .isIn(200, 201, 204, 409, 422);
            done.merge(status >= 400 ? "refused" : what, 1, Integer::sum);
            FamilyInvariants.check(jdbc, family);
            for (String user : users) {
                assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s after operation %d (%s)",
                        names.get(user), i, what).isEmpty();
            }
        }
    }

    private record Member(long id, String sub, String status, String role) {
    }

    /** The family ledger's members as the database has them; none once it is gone. */
    private List<Member> members(long family) {
        return jdbc.sql("SELECT id, user_sub, status, role FROM ledger_member WHERE ledger_id = ? ORDER BY id")
                .param(family)
                .query((row, n) -> new Member(row.getLong("id"), row.getString("user_sub"), row.getString("status"),
                        row.getString("role")))
                .list();
    }

    /** An expense or an income of the rule, by the payer, paid "later" when the payer is the actor. */
    private MvcTestResult record(Member actor, String uri, long payer, Map<String, Long> category, LocalDate today) {
        String type = random.nextInt(4) == 0 ? "INCOME" : "EXPENSE";
        // A day of September, or today, the join date of whoever returns: the same draws whatever day it runs on.
        int day = random.nextInt(31);
        LocalDate date = day == 30 ? today : LocalDate.of(2026, 9, 1).plusDays(day);
        return post(actor.sub(), uri + "/records", """
                {"type": "%s", "date": "%s", "categoryId": %d, "amount": "%s", %s "payerMemberId": %d}"""
                .formatted(type, date, category.get(type), amount(), payer == actor.id() ? "\"paymentLater\": true,"
                        : "", payer));
    }

    /** A custom rule with a random share for each ACTIVE member, or equal shares. */
    private MvcTestResult splitRule(String owner, String uri, List<Member> members) {
        List<Member> active = members.stream().filter(m -> m.status().equals("ACTIVE")).toList();
        if (random.nextInt(3) == 0 || active.size() < 2) {
            return put(owner, uri + "/split-rule", "{\"rule\": \"EQUAL\"}");
        }
        List<String> shares = new ArrayList<>();
        int left = 10_000;
        for (int i = 0; i < active.size(); i++) {
            int share = i == active.size() - 1 ? left : random.nextInt(left + 1);
            left -= share;
            shares.add("{\"memberId\": %d, \"share\": %d}".formatted(active.get(i).id(), share));
        }
        return put(owner, uri + "/split-rule", "{\"rule\": \"CUSTOM\", \"shares\": %s}".formatted(shares));
    }

    /** The owner invites the user as a new member, and the user accepts: a return for one who left (D-26). */
    private MvcTestResult invite(String owner, String uri, String user, Map<String, String> names) throws IOException {
        MvcTestResult created = post(owner, uri + "/invites", "{\"kind\": \"NEW_MEMBER\"}");
        if (created.getResponse().getStatus() != 201) {
            return created;
        }
        String link = read(created, JsonNode.class).get("link").asText();
        return mvc.post().uri("/api/invites/accept").with(member(user)).with(request -> {
            request.setRemoteAddr("198.18.3." + (ADDRESSES.incrementAndGet() % 250 + 1));
            return request;
        }).contentType("application/json").content("{\"token\": \"%s\", \"displayName\": \"%s\"}"
                .formatted(link.substring(link.indexOf('#') + 1), names.get(user))).exchange();
    }

    private Long randomRecord(long family) {
        List<Long> records = jdbc.sql("SELECT id FROM family_record WHERE ledger_id = ? AND deleted_at IS NULL ORDER BY id")
                .param(family).query(Long.class).list();
        return records.isEmpty() ? null : pick(records);
    }

    private int version(long record) {
        return jdbc.sql("SELECT version FROM family_record WHERE id = ?").param(record).query(Integer.class).single();
    }

    private BigDecimal amount() {
        return BigDecimal.valueOf(1 + random.nextInt(30_000), 2);
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
