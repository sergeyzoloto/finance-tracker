package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import com.example.financetracker.TestClock;
import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A membership's lifecycle (F6a; D-19, D-33, ADR 0003 topic J's "F6a plan") in the family of {@link FamilyApiTest}:
 * leaving, an owner's removal of a member with or without an account, the detach of a member with an account, the
 * split rule's fall back to equal shares, the last owner, and who may do what. Nothing is posted: the family's balances
 * stay, the records that involve whoever left are frozen, and the invariants hold after every test, those of members
 * who left included ({@link FamilyInvariants}).
 */
class FamilyMembershipApiTests extends FamilyApiTest {

    private static final String LATER = "\"paymentLater\": true,";

    @Autowired
    private TestClock clock;

    @AfterEach
    void resetTheClock() {
        clock.reset();
    }

    /**
     * The worked example of leaving with a balance. Mum paid 90.00 of groceries on 09-05, Dad 60.00 on 09-10, both
     * split equally among the three: Mum −40.00 (30.00 + 20.00 − 90.00), Dad −10.00 (30.00 + 20.00 − 60.00), Kid
     * +50.00. Bob leaves while the family owes him 10.00. His debt account showed −10.00 and keeps it, as an ordinary
     * liability of his; his shares and payment stay as his own entries, on his own GROCERIES; the family's balances
     * don't move, and what involves him is frozen.
     */
    @Test
    void leavingWithABalanceDetachesTheMemberAndPostsNothing() throws IOException {
        long early = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, LATER)))
                .get("id").asLong();
        created(post(bob, uri + "/records", expense("2026-09-10", groceries, "60.00", dad, LATER)));
        assertThat(balances(alice)).containsExactly("Mum -40.00 you", "Dad -10.00", "Kid 50.00");
        long bobsGroceries = categoryId(bob, "GROCERIES");
        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        List<String> alicesEntries = postedEntries(alice);
        assertThat(postedEntries(bob)).hasSize(3);
        assertThat(ok(get(bob, "/api/categories")).findValuesAsText("familyLedgerId")).contains(String.valueOf(family));

        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);

        // He reads nothing of the family budget any more.
        assertThat(detail(get(bob, uri), HttpStatus.NOT_FOUND)).isEqualTo("Family budget %d not found.".formatted(family));
        assertThat(ok(get(bob, "/api/family-ledgers"))).isEmpty();
        assertThat(ok(get(bob, "/api/categories")).findValuesAsText("familyLedgerId")).isEmpty();
        // The others see him as a member who left, today, with his balance as it was.
        JsonNode dadNow = find(ok(get(alice, uri + "/members")), "displayName", "Dad");
        assertThat(dadNow.get("status").asText()).isEqualTo("LEFT");
        assertThat(dadNow.get("role").asText()).isEqualTo("MEMBER");
        assertThat(dadNow.get("leftDate").asText()).isEqualTo(today().toString());
        assertThat(dadNow.get("hasAccount").asBoolean()).isTrue();
        assertThat(balances(alice)).containsExactly("Mum -40.00 you", "Dad -10.00", "Kid 50.00");
        assertThat(postedEntries(alice)).isEqualTo(alicesEntries);

        // His entries are his own now, on his own GROCERIES, which he had already (Bob joined without merging it).
        assertThat(postedEntries(bob)).isEmpty();
        JsonNode entries = ok(get(bob, "/api/entries?size=200")).get("content");
        assertThat(entries.findValuesAsText("kind")).containsExactlyInAnyOrder("FAMILY_SHARE", "FAMILY_SHARE",
                "FAMILY_PAYMENT");
        assertThat(entries.findValuesAsText("categoryId").stream().filter(id -> !id.equals("null")))
                .containsOnly(String.valueOf(bobsGroceries));
        assertThat(ok(get(bob, "/api/categories")).findValuesAsText("code").stream()
                .filter(code -> code.equals("GROCERIES"))).hasSize(1);
        JsonNode debtAccount = find(ok(get(bob, "/api/accounts")), "id", String.valueOf(debt));
        assertThat(debtAccount.get("name").asText()).isEqualTo("Debt to family budget: Home");
        assertThat(debtAccount.get("system").asBoolean()).isFalse();
        assertThat(new BigDecimal(find(ok(get(bob, "/api/reports/balances?asOf=2026-09-30")), "accountId",
                String.valueOf(debt)).get("balance").asText())).isEqualByComparingTo("-10.00");
        // He may rename it, and delete what was his payment, as any entry of his.
        assertThat(ok(patch(bob, "/api/accounts/" + debt, """
                {"name": "What Home owed me"}""")).get("name").asText()).isEqualTo("What Home owed me");
        JsonNode payment = Arrays.stream(elements(entries)).filter(e -> e.get("kind").asText().equals("FAMILY_PAYMENT"))
                .findFirst().orElseThrow();
        assertThat(delete(bob, "/api/entries/" + payment.get("id").asLong() + "?version="
                + payment.get("version").asInt())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(balances(alice)).containsExactly("Mum -40.00 you", "Dad -10.00", "Kid 50.00");

        // What involves him is frozen; a new record is shared by the others only.
        assertThat(detail(patch(alice, uri + "/records/" + early + "?version=0", """
                {"comment": "Party"}"""), HttpStatus.CONFLICT)).isEqualTo("The expense is frozen: a member it involves "
                        + "has left the family budget or deleted their data, so nobody can change it.");
        JsonNode later = created(post(alice, uri + "/records", expense("2026-09-20", groceries, "30.00", mum, LATER)));
        assertThat(shares(later)).containsExactly("Mum 15.00 null", "Kid 15.00 null");
        assertThat(ok(get(alice, uri + "/journal")).get("content").findValuesAsText("action"))
                .doesNotContain("SPLIT_RULE_RESET");
        for (String user : List.of(alice, bob)) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
    }

    /**
     * An owner removes Kid, who has no account and a history: Kid paid 30.00 of rent on 09-12, split equally (10.00
     * each), and shared Mum's 90.00 of groceries on 09-05 (30.00 each). Balances Mum −50.00, Dad 40.00, Kid 10.00.
     * Kid becomes a member who left, with that balance; the records that involve Kid freeze; the pending invite to
     * take Kid's place stops working. A member without an account whom nothing names is deleted, as before F6a.
     */
    @Test
    void anOwnerRemovesASeatWithHistory() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, LATER)));
        long kidsRent = created(post(alice, uri + "/records", expense("2026-09-12", rent, "30.00", kid, "")))
                .get("id").asLong();
        assertThat(balances(alice)).containsExactly("Mum -50.00 you", "Dad 40.00", "Kid 10.00");
        String token = kidsPlace("2026-09-10");
        List<String> bobsEntries = postedEntries(bob);

        assertThat(delete(alice, uri + "/members/" + kid)).hasStatus(HttpStatus.NO_CONTENT);

        JsonNode kidNow = find(ok(get(bob, uri + "/members")), "displayName", "Kid");
        assertThat(kidNow.get("status").asText()).isEqualTo("LEFT");
        assertThat(kidNow.get("leftDate").asText()).isEqualTo(today().toString());
        assertThat(balances(bob)).containsExactly("Mum -50.00", "Dad 40.00 you", "Kid 10.00");
        assertThat(postedEntries(bob)).isEqualTo(bobsEntries);
        assertThat(ok(get(alice, uri + "/invites")).findValuesAsText("status")).containsExactly("REVOKED");
        assertThat(inviteCall(newUser(), "lookup", token(token, null))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(patch(alice, uri + "/records/" + kidsRent + "?version=0", """
                {"comment": "Bike"}""")).hasStatus(HttpStatus.CONFLICT);
        assertThat(detail(delete(alice, uri + "/members/" + kid), HttpStatus.CONFLICT))
                .isEqualTo("Kid has left the family budget already.");
        assertThat(detail(patch(alice, uri + "/members/" + kid, """
                {"displayName": "Junior"}"""), HttpStatus.CONFLICT)).isEqualTo("Kid has left the family budget.");
        assertThat(detail(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d}""".formatted(kid)), HttpStatus.CONFLICT))
                .isEqualTo("Kid has left the family budget, so nobody takes their place.");

        long baby = created(post(alice, uri + "/members", """
                {"displayName": "Baby"}""")).get("id").asLong();
        assertThat(delete(alice, uri + "/members/" + baby)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("displayName")).containsExactly("Mum", "Dad", "Kid");
    }

    /**
     * The split rule's fall back to equal shares (topic B, H): under 50 / 30 / 20, Bob, with 30 %, leaves; the rule is
     * equal shares, journaled as a system change about him, and the next record is split between Mum and Kid. The
     * records before stay as they were split. Then Kid, with a share above 0 again, is removed: the same.
     */
    @Test
    void theRuleGoesBackToEqualSharesWhenAMemberWithAShareGoes() throws IOException {
        ok(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 5000}, {"memberId": %d, "share": 3000},
                 {"memberId": %d, "share": 2000}]}""".formatted(mum, dad, kid)));
        JsonNode before = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "100.00", mum, LATER)));
        assertThat(shares(before)).containsExactly("Mum 50.00 5000", "Dad 30.00 3000", "Kid 20.00 2000");

        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(ok(get(alice, uri)).get("splitRule").asText()).isEqualTo("EQUAL");
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("share")).containsOnly("null");
        JsonNode reset = ok(get(alice, uri + "/journal")).get("content").get(0);
        assertThat(reset.get("action").asText()).isEqualTo("SPLIT_RULE_RESET");
        assertThat(reset.get("about").get("displayName").asText()).isEqualTo("Dad");
        assertThat(reset.get("author").isNull()).isTrue();
        assertThat(reset.get("changes").toString()).contains("\"field\":\"splitRule\"", "\"old\":\"CUSTOM\"",
                "\"new\":\"EQUAL\"");
        assertThat(shares(ok(get(alice, uri + "/records/" + before.get("id").asLong()))))
                .containsExactly("Mum 50.00 5000", "Dad 30.00 3000", "Kid 20.00 2000");
        assertThat(shares(created(post(alice, uri + "/records", expense("2026-09-06", groceries, "40.00", mum, LATER)))))
                .containsExactly("Mum 20.00 null", "Kid 20.00 null");

        ok(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 7500}, {"memberId": %d, "share": 2500}]}"""
                .formatted(mum, kid)));
        assertThat(delete(alice, uri + "/members/" + kid)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ok(get(alice, uri)).get("splitRule").asText()).isEqualTo("EQUAL");
        assertThat(ok(get(alice, uri + "/journal")).get("content").get(0).get("about").get("displayName").asText())
                .isEqualTo("Kid");
    }

    /**
     * The last owner can't leave while another member with an account remains (D-19): 409 with its own code. Once an
     * owner has removed Bob, which detaches him through the posting service's writer, she is the last member with an
     * account and may leave; the family budget is then archived, Kid in it.
     */
    @Test
    void theLastOwnerStaysWhileAnotherMemberWithAnAccountRemains() throws IOException {
        created(post(bob, uri + "/records", expense("2026-09-10", groceries, "60.00", dad, LATER)));
        MvcTestResult refused = delete(alice, uri + "/members/me");
        JsonNode problem = body(refused, HttpStatus.CONFLICT);
        assertThat(problem.get("code").asText()).isEqualTo("LAST_OWNER");
        assertThat(problem.get("detail").asText()).isEqualTo("You are the family budget's last owner: make another "
                + "member with an account an owner first.");
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("status")).containsOnly("ACTIVE");

        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        assertThat(delete(alice, uri + "/members/" + dad)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(bob, uri)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(postedEntries(bob)).isEmpty();
        assertThat(find(ok(get(bob, "/api/accounts")), "id", String.valueOf(debt)).get("system").asBoolean()).isFalse();
        assertThat(archived()).isFalse();

        assertThat(delete(alice, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(alice, uri)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ok(get(alice, "/api/family-ledgers"))).isEmpty();
        assertThat(archived()).isTrue();
        assertThat(jdbc.sql("SELECT status FROM ledger_member WHERE ledger_id = ? ORDER BY id").param(family)
                .query(String.class).list()).containsExactly("LEFT", "LEFT", "ACTIVE");
    }

    /**
     * Who may leave and remove, with the answers every family endpoint gives: an outsider, a member who left and a
     * former member get the missing family budget's 404 from both; a member who isn't an owner gets the owners' 409
     * for removing anyone, and changes nothing; an owner gets 404 for a member of no ledger of hers, and 409 for one
     * who left or is a former member.
     */
    @Test
    void whoMayLeaveAndRemove() throws IOException {
        String carol = newUser();
        String erin = newUser();
        ok(get(erin, "/api/accounts"));
        long erinsSeat = join(family, erin, "Erin", "MEMBER", LocalDate.of(2026, 9, 1));
        assertThat(delete(erin, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        String missing = "/api/family-ledgers/9000000000";

        for (String outsider : List.of(carol, erin)) {
            for (String path : List.of("/members/me", "/members/" + kid)) {
                assertThat(detail(delete(outsider, uri + path), HttpStatus.NOT_FOUND).replace(String.valueOf(family), "N"))
                        .isEqualTo(detail(delete(outsider, missing + path), HttpStatus.NOT_FOUND)
                                .replace("9000000000", "N"));
            }
        }
        String before = members();
        for (long member : List.of(kid, mum)) {
            assertThat(detail(delete(bob, uri + "/members/" + member), HttpStatus.CONFLICT)).startsWith("Only an owner");
        }
        assertThat(members()).isEqualTo(before);
        assertThat(detail(delete(alice, uri + "/members/9000000000"), HttpStatus.NOT_FOUND))
                .isEqualTo("Member 9000000000 not found.");
        assertThat(detail(delete(alice, uri + "/members/" + erinsSeat), HttpStatus.CONFLICT))
                .isEqualTo("A former member stays as they are.");

        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(bob, uri + "/members/" + kid)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(detail(delete(alice, uri + "/members/" + dad), HttpStatus.CONFLICT))
                .isEqualTo("Dad has left the family budget already.");
    }

    /**
     * D-33: Carol joins by invite, so her GROCERIES and SALARY merge into the family's and are deleted, and her own
     * RENT, an income, stays private beside the family's RENT expense (D-30). Her shares use the family's GROCERIES,
     * which an owner then renames "Food", and RENT. When she leaves, the detach gives her a personal GROCERIES named
     * "Food" and, since her RENT has the code, a personal RENT_2 named "Rent", and moves her postings there; SALARY,
     * which nothing of hers refers to, isn't copied.
     */
    @Test
    void leavingCopiesTheFamilyCategoriesInUseAsPersonalOnes() throws IOException {
        String carol = newUser();
        long carolsRent = created(post(carol, "/api/categories", """
                {"code": "RENT", "name": "Side rent", "type": "INCOME"}""")).get("id").asLong();
        String token = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        JsonNode lookup = ok(inviteCall(carol, "lookup", token(token, null)));
        assertThat(lookup.get("keptPrivate").findValuesAsText("code")).containsExactly("RENT");
        assertThat(lookup.get("openingBalance").isNull()).isTrue();
        accept(carol, token, "Carol");
        assertThat(ok(get(carol, "/api/categories")).findValuesAsText("code").stream()
                .filter(code -> code.equals("GROCERIES"))).hasSize(1);
        String today = today().toString();
        created(post(alice, uri + "/records", expense(today, groceries, "20.00", mum, LATER)));
        created(post(alice, uri + "/records", expense(today, rent, "40.00", mum, LATER)));
        ok(patch(alice, uri + "/categories/" + groceries, """
                {"name": "Food"}"""));

        assertThat(delete(carol, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);

        JsonNode categories = ok(get(carol, "/api/categories"));
        assertThat(categories.findValuesAsText("familyLedgerId")).isEmpty();
        JsonNode food = find(categories, "code", "GROCERIES");
        assertThat(food.get("name").asText()).isEqualTo("Food");
        assertThat(food.get("type").asText()).isEqualTo("EXPENSE");
        JsonNode rentCopy = find(categories, "code", "RENT_2");
        assertThat(rentCopy.get("name").asText()).isEqualTo("Rent");
        assertThat(rentCopy.get("type").asText()).isEqualTo("EXPENSE");
        assertThat(find(categories, "code", "RENT").get("id").asLong()).isEqualTo(carolsRent);
        assertThat(categories.findValuesAsText("code")).doesNotContain("SALARY");
        assertThat(ok(get(carol, "/api/entries?size=200")).get("content").findValuesAsText("categoryId").stream()
                .filter(id -> !id.equals("null"))).containsExactlyInAnyOrder(food.get("id").asText(),
                        rentCopy.get("id").asText());
        assertThat(ok(get(carol, "/api/reports/integrity"))).isEmpty();
    }

    /**
     * "Today" for the family budget (F6a, the F5 follow-up): at 23:30 UTC on a summer evening, Amsterdam is past
     * midnight, while the api, which runs in UTC in production, is still on the day before. The start date, a claim's
     * join date when the form leaves it out, a new member's join date and a leave date are all that day, the one the
     * database's checks use too; a join date of Amsterdam's day is in the future. A record may carry Amsterdam's
     * date, as any date from the start date on.
     */
    @Test
    void todayIsTheApisDateAtHalfPastElevenUtc() throws IOException {
        Instant evening = Instant.parse("2026-07-15T23:30:00Z");
        assertThat(evening.atZone(ZoneId.of("Europe/Amsterdam")).toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 16));
        clock.set(evening, ZoneOffset.UTC);

        JsonNode summer = newFamily(alice, """
                {"name": "Summer", "baseCurrency": "EUR", "displayName": "Mum"}""");
        assertThat(summer.get("startDate").asText()).isEqualTo("2026-07-15");
        String summerUri = "/api/family-ledgers/" + summer.get("id").asLong();
        long seat = created(post(alice, summerUri + "/members", """
                {"displayName": "Gran"}""")).get("id").asLong();
        assertThat(find(ok(get(alice, summerUri + "/members")), "id", String.valueOf(seat)).get("joinDate").asText())
                .isEqualTo("2026-07-15");
        assertThat(details(post(alice, summerUri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "2026-07-16"}""".formatted(seat))))
                .containsExactly(("JOIN_DATE %d the join date 2026-07-16 is not between the family budget's start date "
                        + "2026-07-15 and today, 2026-07-15").formatted(seat));
        JsonNode claim = created(post(alice, summerUri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d}""".formatted(seat)));
        assertThat(claim.get("joinDate").asText()).isEqualTo("2026-07-15");

        String link = created(post(alice, summerUri + "/invites", """
                {"kind": "NEW_MEMBER"}""")).get("link").asText();
        String carol = newUser();
        String token = link.substring(link.indexOf('#') + 1);
        assertThat(ok(inviteCall(carol, "lookup", token(token, null))).get("joinDate").asText()).isEqualTo("2026-07-15");
        accept(carol, token, "Carol");
        assertThat(find(ok(get(alice, summerUri + "/members")), "displayName", "Carol").get("joinDate").asText())
                .isEqualTo("2026-07-15");
        created(post(alice, summerUri + "/records", """
                {"date": "2026-07-16", "categoryId": %d, "amount": "10.00", "payerMemberId": %d}"""
                .formatted(created(post(alice, summerUri + "/categories", """
                        {"code": "ICE", "name": "Ice cream", "type": "EXPENSE"}""")).get("id").asLong(), seat)));
        assertThat(delete(carol, summerUri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(find(ok(get(alice, summerUri + "/members")), "displayName", "Carol").get("leftDate").asText())
                .isEqualTo("2026-07-15");
        FamilyInvariants.check(jdbc, summer.get("id").asLong());
    }

    private String members() {
        return jdbc.sql("SELECT string_agg(m::text, '|' ORDER BY m.id) FROM ledger_member m WHERE m.ledger_id = ?")
                .param(family).query(String.class).single();
    }

    private boolean archived() {
        return jdbc.sql("SELECT archived_at IS NOT NULL FROM ledger WHERE id = ?").param(family).query(Boolean.class)
                .single();
    }
}
