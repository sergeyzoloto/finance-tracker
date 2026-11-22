package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * HIST-2 part 1b (ADR 0006, section 13, criterion (a)): on seeded random sequences of family operations, each record's
 * journal as the API reads it from the versions equals the rows {@code family_record_change} holds for it, change by
 * change, field by field, named with the ledger's own member and category lists. Only D-1084 (the shares' order of an
 * AMOUNT split's creation) is normalised; a comment difference always fails (this test drives no erasure or rollback).
 */
class FamilyJournalEquivalenceApiTests extends FamilyApiTest {

    private static final int OPERATIONS = 25;
    private static final LocalDate BASE = LocalDate.of(2026, 9, 1);
    private static final Map<String, AtomicLong> TOTALS = Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    private Random random;
    private final Map<String, Long> counts = new java.util.LinkedHashMap<>();
    private final List<String> skips = new ArrayList<>();
    private final Set<Long> live = new LinkedHashSet<>();
    private final Set<Long> left = new LinkedHashSet<>();
    /** Carol's records, which the sequence touches only once she has left (dated the API's today at creation). */
    private final Set<Long> carols = new LinkedHashSet<>();
    /** Each AMOUNT creation's shares in the order the test sent them (D-1084). */
    private final Map<Long, List<Long>> amountRequests = new HashMap<>();
    private List<Long> lastAmountOrder;
    private long carolId;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void theJournalFromVersionsEqualsTheJournalRows(long seed) throws IOException {
        random = new Random(seed);
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        accept(carol, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Carol");
        carolId = find(ok(get(alice, uri + "/members")), "displayName", "Carol").get("id").asLong();
        // A NEW_MEMBER joins on the api's today, so her records are dated then (the only dates not from BASE).
        for (int r = 0; r < 2; r++) {
            carols.add(send("carol create", post(carol, uri + "/records", """
                    {"date": "%s", "categoryId": %d, "amount": "%s", "payerMemberId": %d, "paymentLater": true,
                     "comment": "Carol %d", "split": {"method": "RULE"}}""".formatted(today(), groceries, amount(),
                    carolId, r))).get("id").asLong());
        }
        int leaveAt = 5 + random.nextInt(OPERATIONS - 5);
        for (int i = 0; i < OPERATIONS; i++) {
            if (i == leaveAt) {
                leave(seed, carol);
            }
            int choice = random.nextInt(100);
            if (choice < 35 || live.isEmpty()) {
                create(i);
            } else if (choice < 65) {
                update(pick(live), i);
            } else if (choice < 75) {
                comment(pick(live), i);
            } else if (choice < 85) {
                settle(i);
            } else {
                remove(pick(live));
            }
        }
        compareAll(seed);
        assertThat(counts.getOrDefault("normalised D-1084", 0L)).as("seed %d: AMOUNT creates sent out of join order",
                seed).isEqualTo(counts.getOrDefault("expected D-1084", 0L));
        System.out.printf("seed %d: %s, refused %s, result passed%n", seed, counts, skips);
        counts.forEach((key, value) -> TOTALS.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(value));
        TOTALS.computeIfAbsent("seeds", k -> new AtomicLong()).incrementAndGet();
    }

    @AfterAll
    static void summary() {
        System.out.println("family journal equivalence totals: " + TOTALS);
        assertThat(TOTALS.getOrDefault("normalised D-1084", new AtomicLong()).get()).isPositive();
    }

    // --- Operations ---

    private void create(int i) throws IOException {
        long payer = pick(payers());
        boolean income = random.nextInt(5) == 0;
        String currency = random.nextInt(4) == 0 ? "USD" : "EUR";
        BigDecimal amount = amount();
        String comment = random.nextBoolean() ? ", \"comment\": \"Note " + i + "\"" : "";
        String body = """
                {"type": "%s", "date": "%s", "categoryId": %d, "amount": "%s", "currency": "%s", "payerMemberId": %d,
                 %s "split": %s%s}""".formatted(income ? "INCOME" : "EXPENSE", date(),
                income ? salary : random.nextBoolean() ? groceries : rent, amount, currency, payer,
                payer == kid ? "" : "\"paymentLater\": true,", "%SPLIT%", comment);
        lastAmountOrder = null;
        String split = split(amount);
        body = body.replace("\"split\": %SPLIT%", "\"split\": " + split);
        long id = send("create", post(payer == dad ? bob : alice, uri + "/records", body)).get("id").asLong();
        live.add(id);
        if (split.contains("AMOUNT")) {
            amountRequests.put(id, lastAmountOrder);
        }
    }

    private void update(long id, int i) throws IOException {
        JsonNode record = ok(get(alice, uri + "/records/" + id));
        if (refused(record, "update")) {
            return;
        }
        String path = uri + "/records/" + id + "?version=" + record.get("version").asInt();
        long payer = record.get("payer").get("memberId").asLong();
        if (record.get("type").asText().equals("SETTLEMENT")) {
            send("settlement update", patch(alice, path, """
                    {"date": "%s", "amount": "%s", "comment": "Settled %d"}""".formatted(date(), amount(), i)));
            return;
        }
        String editor = payer == dad ? bob : alice;
        long self = payer == dad ? dad : mum;
        List<String> fields = new ArrayList<>();
        BigDecimal amount = new BigDecimal(record.get("amount").asText());
        boolean newAmount = false;
        boolean newCurrency = false;
        if (random.nextBoolean()) {
            fields.add("\"date\": \"%s\"".formatted(date()));
        }
        if (random.nextBoolean()) {
            amount = amount();
            newAmount = true;
            fields.add("\"amount\": \"%s\"".formatted(amount));
            // A new currency only while the editor's side is on no account (else D-87 asks the account's amount).
            JsonNode own = ok(get(editor, uri + "/records/" + id)).path("yourPayment");
            if (random.nextInt(3) == 0 && (own.isMissingNode() || own.isNull() || own.path("later").asBoolean())) {
                newCurrency = true;
                fields.add("\"currency\": \"%s\"".formatted(record.get("currency").asText().equals("EUR") ? "USD"
                        : "EUR"));
            }
        }
        if (random.nextInt(3) == 0) {
            long to = left.contains(kid) || random.nextBoolean() ? self : kid;
            if (to != payer) {
                fields.add("\"payerMemberId\": " + to + (to == kid ? "" : ", \"paymentLater\": true"));
            }
        } else if (payer != kid && random.nextInt(4) == 0 && record.get("currency").asText().equals("EUR")
                && !newCurrency) {
            fields.add("\"paymentAccountId\": " + accountId(editor, "CASH"));
        }
        if (random.nextBoolean()) {
            fields.add("\"categoryId\": " + (record.get("type").asText().equals("INCOME") ? salary
                    : random.nextBoolean() ? groceries : rent));
        }
        if (random.nextBoolean()) {
            fields.add(random.nextInt(4) == 0 ? "\"comment\": null" : "\"comment\": \"Edit %d\"".formatted(i));
        }
        if (random.nextBoolean()) {
            fields.add("\"split\": " + split(amount));
        } else if (newAmount && record.get("splitMethod").asText().equals("AMOUNT")) {
            fields.add("\"split\": " + amounts(amount));
        }
        if (fields.isEmpty()) {
            fields.add("\"comment\": \"Edit %d\"".formatted(i));
        }
        send("update", patch(editor, path, "{" + String.join(", ", fields) + "}"));
    }

    private void comment(long id, int i) throws IOException {
        JsonNode record = ok(get(alice, uri + "/records/" + id));
        if (!refused(record, "comment")) {
            send("comment", patch(alice, uri + "/records/" + id + "?version=" + record.get("version").asInt(),
                    random.nextInt(3) == 0 ? "{\"comment\": null}" : "{\"comment\": \"Only %d\"}".formatted(i)));
        }
    }

    private void settle(int i) throws IOException {
        boolean pays = random.nextBoolean();
        live.add(send("settle", post(alice, uri + "/settlements", """
                {"date": "%s", "amount": "%s", "payerMemberId": %d, "payeeMemberId": %d, "comment": "Settle %d",
                 "paymentLater": true}""".formatted(date(), amount(), pays ? mum : dad, pays ? dad : mum, i)))
                .get("id").asLong());
    }

    private void remove(long id) throws IOException {
        JsonNode record = ok(get(alice, uri + "/records/" + id));
        if (refused(record, "delete")) {
            return;
        }
        long payer = record.get("payer").get("memberId").asLong();
        MvcTestResult answer = delete(payer == dad && !record.get("type").asText().equals("SETTLEMENT") ? bob : alice,
                uri + "/records/" + id + "?version=" + record.get("version").asInt());
        expect2xx("delete", answer);
        live.remove(id);
    }

    /** Odd seeds: Carol, who joined by invite, leaves; even seeds: the owner removes Kid, a seat with records. */
    private void leave(long seed, String carol) throws IOException {
        if (seed % 2 == 1) {
            expect2xx("leave", delete(carol, uri + "/members/me"));
            left.add(carolId);
            live.addAll(carols);
        } else {
            expect2xx("leave", delete(alice, uri + "/members/" + kid));
            left.add(kid);
        }
    }

    /**
     * A record whose payer or a share is of a member who left: the operation is sent (as the owner: a comment for an
     * update, else the deletion) and must answer 409 with a problem detail; any other answer fails the seed.
     */
    private boolean refused(JsonNode record, String what) throws IOException {
        boolean leaver = left.contains(record.get("payer").get("memberId").asLong());
        for (JsonNode share : record.get("shares")) {
            leaver |= left.contains(share.get("member").get("memberId").asLong());
        }
        if (!leaver) {
            return false;
        }
        String path = uri + "/records/" + record.get("id").asLong() + "?version=" + record.get("version").asInt();
        MvcTestResult answer = what.equals("delete") ? delete(alice, path)
                : patch(alice, path, "{\"comment\": \"Refused\"}");
        int status = answer.getResponse().getStatus();
        if (status != 409) {
            fail("%s of record %d, of a member who left, answered %d, not 409: %s", what, record.get("id").asLong(),
                    status, Answers.of(answer));
        }
        assertThat(Answers.of(answer).get("detail").asText()).isNotBlank();
        skips.add(what + " of record " + record.get("id").asLong());
        counts.merge("refused by 409", 1L, Long::sum);
        return true;
    }

    private JsonNode send(String what, MvcTestResult answer) throws IOException {
        expect2xx(what, answer);
        return Answers.of(answer);
    }

    private void expect2xx(String what, MvcTestResult answer) throws IOException {
        int status = answer.getResponse().getStatus();
        if (status / 100 != 2) {
            fail("%s answered %d: %s", what, status, Answers.of(answer));
        }
        counts.merge("operations", 1L, Long::sum);
    }

    private List<Long> payers() {
        return left.contains(kid) ? List.of(mum, dad) : List.of(mum, dad, kid);
    }

    private List<Long> splitMembers() {
        return payers();
    }

    private String split(BigDecimal amount) {
        List<Long> members = splitMembers();
        return switch (random.nextInt(4)) {
            case 0 -> "{\"method\": \"RULE\"}";
            case 1 -> {
                List<String> shares = new ArrayList<>();
                int rest = 10_000;
                for (int m = 0; m < members.size(); m++) {
                    int bp = m == members.size() - 1 ? rest : random.nextInt(rest + 1);
                    rest -= bp;
                    shares.add("{\"memberId\": %d, \"basisPoints\": %d}".formatted(members.get(m), bp));
                }
                yield "{\"method\": \"PERCENT\", \"shares\": [" + String.join(", ", shares) + "]}";
            }
            case 2 -> amounts(amount);
            default -> "{\"method\": \"ONE_MEMBER\", \"memberId\": %d}".formatted(pick(members));
        };
    }

    /** An AMOUNT split, its shares sent in a shuffled order (D-1084 is about that order). */
    private String amounts(BigDecimal amount) {
        List<Long> members = new ArrayList<>(splitMembers());
        Collections.shuffle(members, random);
        lastAmountOrder = List.copyOf(members);
        long rest = amount.movePointRight(2).longValueExact();
        List<String> shares = new ArrayList<>();
        for (int m = 0; m < members.size(); m++) {
            long cents = m == members.size() - 1 ? rest : random.nextLong(rest + 1);
            rest -= cents;
            shares.add("{\"memberId\": %d, \"amount\": \"%s\"}".formatted(members.get(m), BigDecimal.valueOf(cents, 2)));
        }
        return "{\"method\": \"AMOUNT\", \"shares\": [" + String.join(", ", shares) + "]}";
    }

    private BigDecimal amount() {
        return BigDecimal.valueOf(1 + random.nextInt(50_000), 2);
    }

    private String date() {
        return BASE.plusDays(random.nextInt(30)).toString();
    }

    private long pick(java.util.Collection<Long> values) {
        return new ArrayList<>(values).get(random.nextInt(values.size()));
    }

    // --- The comparison ---

    private void compareAll(long seed) throws IOException {
        Map<Long, String> members = new HashMap<>();
        ok(get(alice, uri + "/members")).forEach(m -> members.put(m.get("id").asLong(), m.get("displayName").asText()));
        Map<Long, String> categories = new HashMap<>();
        ok(get(alice, uri + "/categories")).forEach(c -> categories.put(c.get("id").asLong(), c.get("name").asText()));
        // A member who deleted their data (status FORMER) reads "Former member" in the journal (D-20; ADR 0006,
        // section 13, D-1018); no other id may be missing from the members' list.
        jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = ? AND status = 'FORMER'").param(family)
                .query(Long.class).list().forEach(former -> members.put(former, "Former member"));
        List<Long> joinOrder = jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = ? ORDER BY join_date, id")
                .param(family).query(Long.class).list();
        amountRequests.forEach((record, sent) -> {
            if (!sent.equals(joinOrder.stream().filter(sent::contains).toList())) {
                counts.merge("expected D-1084", 1L, Long::sum);
            }
        });
        List<Long> records = jdbc.sql("SELECT id FROM family_record WHERE ledger_id = ? ORDER BY id").param(family)
                .query(Long.class).list();
        for (long id : records) {
            JsonNode journal = ok(get(alice, uri + "/journal?size=200&recordId=" + id));
            List<JsonNode> actual = new ArrayList<>();
            journal.get("content").forEach(actual::add);
            assertThat(actual.size()).isLessThan(200);
            assertThat(journal.get("totalElements").asInt()).isEqualTo(actual.size());
            List<List<String>> expected = new ArrayList<>(jdbc.sql("""
                    SELECT action, changed_by_member_id, currency, changes::text AS changes FROM family_record_change
                    WHERE ledger_id = ? AND record_id = ? ORDER BY id""").param(family).param(id)
                    .query((row, n) -> expectedRow(row.getString("action"),
                            row.getObject("changed_by_member_id", Long.class), row.getString("currency"),
                            Answers.json(row.getString("changes")), members, categories)).list());
            Collections.reverse(expected);
            String where = "seed " + seed + ", record " + id;
            if (expected.size() != actual.size()) {
                fail("%s: %d rows but %d changes in the journal; rows %s, journal %s", where, expected.size(),
                        actual.size(), expected, actual);
            }
            for (int c = 0; c < expected.size(); c++) {
                compare(where + ", change " + c, expected.get(c), actualRow(actual.get(c)),
                        amountRequests.get(id), joinOrder, members);
            }
            counts.merge("records compared", 1L, Long::sum);
            counts.merge("changes compared", (long) expected.size(), Long::sum);
        }
    }

    /** A row of the old table as the journal would name it: "ACTION", "author", then one entry per field. */
    private static List<String> expectedRow(String action, Long author, String currency, JsonNode changes,
            Map<Long, String> members, Map<Long, String> categories) {
        List<String> row = new ArrayList<>(List.of(action, author + " " + members.get(author)));
        String before = currency;
        for (JsonNode change : changes) {
            if (change.get("field").asText().equals("currency") && !change.path("old").isNull()) {
                before = change.get("old").asText();
            }
        }
        for (JsonNode change : changes) {
            String field = change.get("field").asText();
            boolean amounts = field.equals("amount") || field.equals("share");
            String old = value(field, change.get("old"), members, categories);
            String now = value(field, change.get("new"), members, categories);
            JsonNode member = change.get("member");
            row.add(entry(field, member == null || member.isNull() ? null
                            : members.get(member.asLong()), old, now,
                    amounts && old != null ? before : null, amounts && now != null ? currency : null));
        }
        return row;
    }

    private static List<String> actualRow(JsonNode change) {
        List<String> row = new ArrayList<>(List.of(change.get("action").asText(),
                change.get("author").get("memberId").asLong() + " "
                        + change.get("author").get("displayName").asText()));
        for (JsonNode field : change.get("changes")) {
            String name = field.get("field").asText();
            boolean amounts = name.equals("amount") || name.equals("share");
            row.add(entry(name, field.get("member").isNull() ? null : field.get("member").get("displayName").asText(),
                    canonical(amounts, text(field.get("old"))), canonical(amounts, text(field.get("new"))),
                    text(field.get("oldCurrency")), text(field.get("newCurrency"))));
        }
        return row;
    }

    private static String value(String field, JsonNode value, Map<Long, String> members,
            Map<Long, String> categories) {
        if (value == null || value.isNull()) {
            return null;
        }
        return switch (field) {
            case "category" -> categories.get(value.asLong());
            case "payer", "payee" -> members.get(value.asLong());
            case "amount", "share" -> canonical(true, value.asText());
            default -> value.asText();
        };
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    /** An amount as a number, so that "10.00" and "10.0000" are one (the minor unit's comparison). */
    private static String canonical(boolean amount, String value) {
        return amount && value != null ? new BigDecimal(value).stripTrailingZeros().toPlainString() : value;
    }

    private static String entry(String field, String member, String old, String now, String oldCurrency,
            String newCurrency) {
        return field + "|" + member + "|" + old + "→" + now + "|" + oldCurrency + "→" + newCurrency;
    }

    /**
     * The rows must be equal, entry by entry. One exception (D-1084): a creation of an AMOUNT split the test sent out of
     * join order lists its shares in join order; then the other entries stay positional, the journal's shares must be
     * in join order and both sides' shares are compared as a multiset.
     */
    private void compare(String where, List<String> expected, List<String> actual, List<Long> sent,
            List<Long> joinOrder, Map<Long, String> members) {
        if (expected.get(0).equals("CREATE") && sent != null
                && !sent.equals(joinOrder.stream().filter(sent::contains).toList())) {
            positional(where, without(expected, true), without(actual, true));
            List<String> names = new ArrayList<>(joinOrder.stream().map(members::get).toList());
            List<String> shown = without(actual, false).stream().map(s -> s.split("\\|")[1]).toList();
            names.retainAll(shown);
            assertThat(shown).as("%s: the creation's shares in join order", where).isEqualTo(names);
            assertThat(without(actual, false)).as("%s: the shares", where)
                    .containsExactlyInAnyOrderElementsOf(without(expected, false));
            counts.merge("normalised D-1084", 1L, Long::sum);
            return;
        }
        positional(where, expected, actual);
    }

    private static void positional(String where, List<String> expected, List<String> actual) {
        for (int f = 0; f < Math.max(expected.size(), actual.size()); f++) {
            String e = f < expected.size() ? expected.get(f) : "(none)";
            String a = f < actual.size() ? actual.get(f) : "(none)";
            if (!e.equals(a)) {
                fail("%s, position %d: expected %s but the journal has %s", where, f, e, a);
            }
        }
    }

    /** The row without its share entries, or only them. */
    private static List<String> without(List<String> row, boolean shares) {
        return row.stream().filter(s -> s.startsWith("share|") != shares).toList();
    }
}
