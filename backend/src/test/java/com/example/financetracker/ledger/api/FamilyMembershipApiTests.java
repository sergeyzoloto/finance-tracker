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
     * account and may leave. The family budget is then deleted with its records, Kid with it, as "Delete all my data"
     * deletes it (D-36, replacing F6a's archived budget), which her deletion preview said beforehand: {@code DELETED}.
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
        assertThat(familyRows()).isNotEqualTo("0 0 0 0 0 0 0");
        JsonNode preview = ok(get(alice, "/api/me/family-memberships")).get("memberships");
        assertThat(find(preview, "ledgerId", String.valueOf(family)).get("outcome").asText()).isEqualTo("DELETED");
        long alicesDebt = accountId(alice, "FAMILY_DEBT_" + family);
        List<String> alicesEntries = postedEntries(alice);

        assertThat(delete(alice, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(alice, uri)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ok(get(alice, "/api/family-ledgers"))).isEmpty();
        // Gone with everything of it, Kid and Bob's membership included; what was posted stays as each one's own.
        assertThat(familyRows()).isEqualTo("0 0 0 0 0 0 0");
        assertThat(postedEntries(alice)).isEmpty();
        assertThat(ok(get(alice, "/api/entries?size=200")).get("content")).hasSize(alicesEntries.size());
        assertThat(find(ok(get(alice, "/api/accounts")), "id", String.valueOf(alicesDebt)).get("system").asBoolean())
                .isFalse();
        assertThat(ok(get(alice, "/api/me/family-memberships")).get("left").asInt()).isZero();
        for (String user : List.of(alice, bob)) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
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

    /**
     * The worked example of a return (D-26). Mum paid 90.00 on 09-05 and Dad 60.00 on 09-10, each split among the
     * three: Dad −10.00. He leaves; his debt account keeps −10.00, and then he deletes what was his payment, so it shows
     * +50.00 (his two shares, 30.00 and 20.00). Mum pays 30.00 on 09-20 for herself and Kid. Invited back as a new
     * member, he is told the correction beforehand: −10.00 less +50.00 = −60.00, which posts on his join date, today, as
     * debt account +60.00 and OPENING_BALANCE −60.00, so the account shows −10.00 again (D-10). The records that involve
     * him aren't frozen any more; a change of one before his join date moves the correction, and the entries of before
     * he left, which post to the debt account again, change only with the family budget.
     */
    @Test
    void aMemberWhoLeftComesBackWithOneCorrection() throws IOException {
        long early = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, LATER)))
                .get("id").asLong();
        created(post(bob, uri + "/records", expense("2026-09-10", groceries, "60.00", dad, LATER)));
        long debt = accountId(bob, "FAMILY_DEBT_" + family);
        long opening = accountId(bob, "OPENING_BALANCE");
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode payment = Arrays.stream(elements(ok(get(bob, "/api/entries?size=200")).get("content")))
                .filter(e -> e.get("kind").asText().equals("FAMILY_PAYMENT")).findFirst().orElseThrow();
        assertThat(delete(bob, "/api/entries/" + payment.get("id").asLong() + "?version="
                + payment.get("version").asInt())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(debtShown(bob, debt)).isEqualByComparingTo("50.00");
        created(post(alice, uri + "/records", expense("2026-09-20", groceries, "30.00", mum, LATER)));
        assertThat(balances(alice)).containsExactly("Mum -55.00 you", "Dad -10.00", "Kid 65.00");

        // A place isn't the way back: members are never merged (D-18).
        assertThat(detail(inviteCall(bob, "lookup", token(kidsPlace("2026-09-15"), null)), HttpStatus.CONFLICT))
                .isEqualTo("You were a member of this family budget before, so you can't take someone else's place; an "
                        + "invite as a new member brings you back.");
        String token = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        JsonNode lookup = ok(inviteCall(bob, "lookup", token(token, null)));
        assertThat(lookup.get("returning").asBoolean()).isTrue();
        assertThat(lookup.get("correction").asText()).isEqualTo("-60.00");
        assertThat(lookup.get("joinDate").asText()).isEqualTo(today().toString());
        assertThat(lookup.get("openingBalance").isNull()).isTrue();

        JsonNode back = accept(bob, token, "Dad");
        assertThat(back.get("memberId").asLong()).isEqualTo(dad);
        JsonNode dadNow = find(ok(get(alice, uri + "/members")), "displayName", "Dad");
        assertThat(dadNow.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(dadNow.get("joinDate").asText()).isEqualTo(today().toString());
        assertThat(dadNow.get("leftDate").isNull()).isTrue();
        assertThat(dadNow.get("role").asText()).isEqualTo("MEMBER");
        assertThat(accountId(bob, "FAMILY_DEBT_" + family)).isEqualTo(debt);
        JsonNode account = find(ok(get(bob, "/api/accounts")), "id", String.valueOf(debt));
        assertThat(account.get("system").asBoolean()).isTrue();
        assertThat(account.get("name").asText()).isEqualTo("Debt to family budget: Home");
        assertThat(postedEntries(bob)).containsExactly(
                "FAMILY_CORRECTION CORRECTION %d:60.00:null %d:-60.00:null".formatted(debt, opening));
        assertThat(debtShown(bob, debt)).isEqualByComparingTo("-10.00");
        assertThat(balances(bob)).containsExactly("Mum -55.00", "Dad -10.00 you", "Kid 65.00");
        // His GROCERIES, his own again since he left, merged back into the family's (D-11, D-26).
        JsonNode categories = ok(get(bob, "/api/categories"));
        assertThat(find(categories, "code", "GROCERIES").get("familyLedgerId").asLong()).isEqualTo(family);
        assertThat(categories.findValuesAsText("code").stream().filter(code -> code.equals("GROCERIES"))).hasSize(1);
        everyonesIntegrity();

        // Not frozen any more: a change before his join date moves the correction (D-31).
        assertThat(shares(ok(patch(alice, uri + "/records/" + early + "?version=0", """
                {"amount": "120.00"}""")))).containsExactly("Mum 40.00 null", "Dad 40.00 null", "Kid 40.00 null");
        assertThat(postedEntries(bob)).containsExactly(
                "FAMILY_CORRECTION CORRECTION %d:50.00:null %d:-50.00:null".formatted(debt, opening));
        assertThat(balances(bob)).containsExactly("Mum -75.00", "Dad 0.00 you", "Kid 75.00");
        everyonesIntegrity();
        // From today he shares as anyone does.
        assertThat(shares(created(post(alice, uri + "/records", expense(today().toString(), groceries, "30.00", mum,
                LATER))))).containsExactly("Mum 10.00 null", "Dad 10.00 null", "Kid 10.00 null");
        everyonesIntegrity();
        // His entries of before he left post to the debt account again: they change with the family budget only.
        JsonNode oldShare = Arrays.stream(elements(ok(get(bob, "/api/entries?size=200")).get("content")))
                .filter(e -> e.get("family").isNull() && e.get("kind").asText().equals("FAMILY_SHARE")).findFirst()
                .orElseThrow();
        assertThat(detail(delete(bob, "/api/entries/" + oldShare.get("id").asLong() + "?version="
                + oldShare.get("version").asInt()), HttpStatus.CONFLICT)).isEqualTo(("Entry %d posts to your debt to "
                        + "the family budget \"Home\", which changes only through the family budget.")
                        .formatted(oldShare.get("id").asLong()));
        assertThat(inviteCall(bob, "lookup", token(newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), null))).hasStatus(HttpStatus.CONFLICT);
    }

    /**
     * A member who leaves and returns on the same day (found in F6a's walk-through): the records of that day are posted
     * to them again, and their entries of before they left for those records come back attached to them, rather than
     * staying beside new ones. So nothing counts twice in their personal reports, and no correction is needed. Mum
     * paid 60.00 today and Dad 30.00, each split among the three: Dad's shares 20.00 and 10.00, his payment 30.00.
     */
    @Test
    void aMemberWhoReturnsTheSameDayIsntPostedTwice() throws IOException {
        String today = today().toString();
        created(post(alice, uri + "/records", expense(today, groceries, "60.00", mum, LATER)));
        created(post(bob, uri + "/records", expense(today, groceries, "30.00", dad, LATER)));
        List<String> before = postedEntries(bob);
        assertThat(before).hasSize(3);
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        String token = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        assertThat(ok(inviteCall(bob, "lookup", token(token, null))).get("correction").asText()).isEqualTo("0.00");

        accept(bob, token, "Dad");

        assertThat(postedEntries(bob)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(ok(get(bob, "/api/entries?size=200")).get("content")).hasSize(3);
        assertThat(balances(bob)).containsExactly("Mum -30.00", "Dad 0.00 you", "Kid 30.00");
        JsonNode groceriesRow = find(ok(get(bob, "/api/reports/cash-flow?from=" + today + "&to=" + today)),
                "categoryCode", "GROCERIES");
        assertThat(new BigDecimal(groceriesRow.get("total").asText())).isEqualByComparingTo("30.00");
        everyonesIntegrity();
    }

    /**
     * Owners (D-3, D-15, D-19): an owner makes a member with an account an owner, after which the former last owner may
     * leave; nobody else may, and only an ACTIVE member with an account becomes one.
     */
    @Test
    void anOwnerMakesAnotherMemberAnOwner() throws IOException {
        String carol = newUser();
        String erin = newUser();
        ok(get(erin, "/api/accounts"));
        long erinsSeat = join(family, erin, "Erin", "MEMBER", LocalDate.of(2026, 9, 1));
        assertThat(delete(erin, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        String owner = uri + "/members/%d/owner";

        assertThat(detail(post(bob, owner.formatted(dad), null), HttpStatus.CONFLICT)).startsWith("Only an owner");
        assertThat(detail(post(carol, owner.formatted(dad), null), HttpStatus.NOT_FOUND))
                .isEqualTo("Family budget %d not found.".formatted(family));
        assertThat(detail(post(alice, owner.formatted(kid), null), HttpStatus.CONFLICT))
                .isEqualTo("Kid has no account: only a member with an account can be an owner.");
        assertThat(detail(post(alice, owner.formatted(erinsSeat), null), HttpStatus.CONFLICT))
                .isEqualTo("A former member stays as they are.");
        assertThat(detail(post(alice, owner.formatted(mum), null), HttpStatus.CONFLICT))
                .isEqualTo("Mum is an owner already.");
        assertThat(detail(post(alice, owner.formatted(9_000_000_000L), null), HttpStatus.NOT_FOUND))
                .isEqualTo("Member 9000000000 not found.");

        JsonNode dadNow = ok(post(alice, owner.formatted(dad), null));
        assertThat(dadNow.get("role").asText()).isEqualTo("OWNER");
        assertThat(ok(get(bob, uri)).get("role").asText()).isEqualTo("OWNER");
        assertThat(delete(alice, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(find(ok(get(bob, uri + "/members")), "displayName", "Mum").get("status").asText()).isEqualTo("LEFT");
        assertThat(detail(post(bob, owner.formatted(mum), null), HttpStatus.CONFLICT))
                .isEqualTo("Mum has left the family budget.");
        assertThat(post(alice, owner.formatted(dad), null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    /**
     * What "Delete all my data" would touch (D-20), for its confirmation screen: each family budget the caller is an
     * ACTIVE member of, with their role and balance, and what becomes of it; of those they left, only how many.
     */
    @Test
    void theDeletionPreviewListsTheCallersOwnMembershipsOnly() throws IOException {
        ok(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 5000}, {"memberId": %d, "share": 3000},
                 {"memberId": %d, "share": 2000}]}""".formatted(mum, dad, kid)));
        created(post(alice, uri + "/records", expense("2026-09-05", groceries, "100.00", mum, LATER)));
        newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        long allotment = newFamily(alice, """
                {"name": "Allotment", "baseCurrency": "USD", "displayName": "Anna"}""").get("id").asLong();
        long away = newFamily(bob, """
                {"name": "Bob's club", "baseCurrency": "EUR", "displayName": "Bob"}""").get("id").asLong();
        join(away, alice, "Anna", "MEMBER", today());
        assertThat(delete(alice, "/api/family-ledgers/" + away + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);

        JsonNode alices = ok(get(alice, "/api/me/family-memberships"));
        assertThat(alices.get("left").asLong()).isEqualTo(1);
        assertThat(alices.get("memberships").findValuesAsText("name")).containsExactly("Allotment", "Home");
        JsonNode inAllotment = alices.get("memberships").get(0);
        assertThat(inAllotment.get("ledgerId").asLong()).isEqualTo(allotment);
        assertThat(inAllotment.get("outcome").asText()).isEqualTo("DELETED");
        assertThat(inAllotment.get("baseCurrency").asText()).isEqualTo("USD");
        JsonNode inHome = alices.get("memberships").get(1);
        assertThat(inHome.get("role").asText()).isEqualTo("OWNER");
        assertThat(inHome.get("balance").asText()).isEqualTo("-50.00");
        assertThat(inHome.get("outcome").asText()).isEqualTo("OWNERSHIP_PASSES");
        assertThat(inHome.get("newOwner").asText()).isEqualTo("Dad");
        assertThat(inHome.get("pendingInvites").asInt()).isEqualTo(1);
        assertThat(inHome.get("splitRuleReset").asBoolean()).isTrue();

        JsonNode bobs = ok(get(bob, "/api/me/family-memberships"));
        assertThat(bobs.get("left").asLong()).isZero();
        assertThat(bobs.get("memberships").findValuesAsText("name")).containsExactly("Bob's club", "Home");
        JsonNode bobInHome = bobs.get("memberships").get(1);
        assertThat(bobInHome.get("role").asText()).isEqualTo("MEMBER");
        assertThat(bobInHome.get("balance").asText()).isEqualTo("30.00");
        assertThat(bobInHome.get("outcome").asText()).isEqualTo("STAYS");
        assertThat(bobInHome.get("newOwner").isNull()).isTrue();
        assertThat(bobInHome.get("pendingInvites").asInt()).isZero();
        assertThat(bobInHome.get("splitRuleReset").asBoolean()).isTrue();
        assertThat(bobs.get("memberships").get(0).get("outcome").asText()).isEqualTo("DELETED");
        assertThat(ok(get(newUser(), "/api/me/family-memberships")).toString())
                .isEqualTo("{\"memberships\":[],\"left\":0}");
    }

    /** What the debt account shows today, as the balances report says it. */
    private BigDecimal debtShown(String user, long debt) throws IOException {
        return new BigDecimal(find(ok(get(user, "/api/reports/balances?asOf=" + today())), "accountId",
                String.valueOf(debt)).get("balance").asText());
    }

    /** Nobody's integrity check finds anything, and the invariants hold. */
    private void everyonesIntegrity() throws IOException {
        for (String user : List.of(alice, bob)) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
        FamilyInvariants.check(jdbc, family);
    }

    private String members() {
        return jdbc.sql("SELECT string_agg(m::text, '|' ORDER BY m.id) FROM ledger_member m WHERE m.ledger_id = ?")
                .param(family).query(String.class).single();
    }

    /** The family ledger's rows: the ledger, members, categories, records, links, journal and invites. */
    private String familyRows() {
        return jdbc.sql("""
                SELECT concat_ws(' ', (SELECT count(*) FROM ledger WHERE id = :f),
                    (SELECT count(*) FROM ledger_member WHERE ledger_id = :f),
                    (SELECT count(*) FROM category WHERE ledger_id = :f),
                    (SELECT count(*) FROM family_record WHERE ledger_id = :f),
                    (SELECT count(*) FROM family_entry_link WHERE family_ledger_id = :f),
                    (SELECT count(*) FROM family_record_change WHERE ledger_id = :f),
                    (SELECT count(*) FROM ledger_invite WHERE ledger_id = :f))""")
                .param("f", family).query(String.class).single();
    }
}
