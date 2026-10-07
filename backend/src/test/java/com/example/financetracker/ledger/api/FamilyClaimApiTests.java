package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Taking a seat (F5; D-18, D-10, D-11, D-14, D-24, D-28; ADR 0003, topics E, F and G) in the family of
 * {@link FamilyApiTest}: Carol accepts an invite to take Kid's place. What the acceptance posts into her personal
 * ledger (every record from her join date, what Kid paid, received or settled on her placeholder, and her balance
 * before the join date as one opening balance), what changes afterwards, a new member, and the categories of whoever
 * joins. The invariants hold after every test, and the integrity check finds nothing.
 */
class FamilyClaimApiTests extends FamilyApiTest {

    private final String carol = newUser();

    /**
     * The worked example. Kid shared and paid records before and after 2026-09-15, while
     * nobody's account was theirs; Carol takes the place from that date.
     * <ul>
     * <li>Before it: Kid's third of 90.00 (+30.00), a third of 30.00 Kid paid (+10.00 −30.00), and
     * 20.00 Kid paid Mum (−20.00): −10.00, the opening balance.
     * <li>From it: a third of 60.00 (+20.00), a third of 45.00 Kid paid (+15.00 −45.00), 120.00 of
     * salary Kid received (+120.00 −40.00), 10.00 Mum paid Kid (+10.00), 5.00 Kid paid Dad (−5.00): +75.00.
     * </ul>
     * Carol's family balance is 65.00, and so is her debt account from the moment the claim commits (D-10).
     */
    @Test
    void aClaimPostsEveryRecordFromTheJoinDateAndTheBalanceBeforeIt() throws IOException {
        long alicesCurrent = accountId(alice, "CURRENT_ACCOUNT");
        long bobsCash = accountId(bob, "CASH");
        created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum,
                "\"paymentAccountId\": %d,".formatted(alicesCurrent))));
        long kidsDollars = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "30.00", "payerMemberId": %d}""".formatted(groceries, kid))).get("id").asLong();
        created(post(alice, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "20.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(kid, mum, alicesCurrent)));
        created(post(alice, uri + "/records", expense("2026-09-20", groceries, "60.00", mum,
                "\"paymentAccountId\": %d,".formatted(alicesCurrent))));
        long kidPaidLater = created(post(alice, uri + "/records", """
                {"date": "2026-09-22", "categoryId": %d, "amount": "45.00", "payerMemberId": %d}""".formatted(groceries, kid))).get("id").asLong();
        created(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-24", "categoryId": %d, "amount": "120.00", "payerMemberId": %d}"""
                .formatted(salary, kid)));
        long mumPaidKid = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-25", "amount": "10.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, kid, alicesCurrent))).get("id").asLong();
        created(post(bob, uri + "/settlements", """
                {"date": "2026-09-26", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(kid, dad, bobsCash)));
        assertThat(balances(alice)).containsExactly("Mum -105.00 you", "Dad 40.00", "Kid 65.00");
        List<String> alicesEntries = postedEntries(alice);
        List<String> bobsEntries = postedEntries(bob);

        JsonNode lookup = ok(inviteCall(carol, "lookup", token(kidsPlace("2026-09-15"), null)));
        assertThat(lookup.get("kind").asText()).isEqualTo("CLAIM");
        assertThat(lookup.get("seatName").asText()).isEqualTo("Kid");
        assertThat(lookup.get("joinDate").asText()).isEqualTo("2026-09-15");
        assertThat(lookup.get("invitedBy").asText()).isEqualTo("Mum");
        assertThat(lookup.get("ledgerName").asText()).isEqualTo("Home");
        assertThat(lookup.get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(lookup.get("displayName").asText()).isEqualTo("User " + carol);
        // The seat's balance before the join date, which she takes on (D-34): the family owed Kid 10.00.
        assertThat(lookup.get("openingBalances")).isEqualTo(Answers.json("[{\"currency\":\"EUR\",\"amount\":\"-10.00\"}]"));
        assertThat(lookup.has("openingBalance")).isFalse();

        String token = kidsPlace("2026-09-15");
        JsonNode joined = accept(carol, token, "Carol");
        assertThat(joined.get("id").asLong()).isEqualTo(family);
        assertThat(joined.get("memberId").asLong()).isEqualTo(kid);
        assertThat(joined.get("role").asText()).isEqualTo("MEMBER");
        JsonNode carolAsMember = find(ok(get(carol, uri + "/members")), "displayName", "Carol");
        assertThat(carolAsMember.get("joinDate").asText()).isEqualTo("2026-09-15");
        assertThat(carolAsMember.get("hasAccount").asBoolean()).isTrue();
        assertThat(balances(carol)).containsExactly("Mum -105.00", "Dad 40.00", "Carol 65.00 you");

        long debt = accountId(carol, "FAMILY_DEBT_" + family);
        long placeholder = accountId(carol, "UNSPECIFIED_PAYMENTS");
        long unallocated = accountId(carol, "UNALLOCATED");
        long opening = accountId(carol, "OPENING_BALANCE");
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(
                "FAMILY_OPENING OPENING_BALANCE %d:10.00:null %d:-10.00:null".formatted(debt, opening),
                "FAMILY_SHARE SHARE %d:20.00:%d %d:-20.00:null".formatted(unallocated, groceries, debt),
                "FAMILY_SHARE SHARE %d:15.00:%d %d:-15.00:null".formatted(unallocated, groceries, debt),
                "FAMILY_PAYMENT PAYMENT %d:-45.00:null %d:45.00:null".formatted(placeholder, debt),
                "FAMILY_SHARE SHARE %d:-40.00:%d %d:40.00:null".formatted(unallocated, salary, debt),
                "FAMILY_PAYMENT PAYMENT %d:120.00:null %d:-120.00:null".formatted(placeholder, debt),
                "FAMILY_SETTLEMENT SETTLEMENT %d:10.00:null %d:-10.00:null".formatted(placeholder, debt),
                "FAMILY_SETTLEMENT SETTLEMENT %d:-5.00:null %d:5.00:null".formatted(placeholder, debt));
        JsonNode openingEntry = ok(get(carol, "/api/entries?size=200")).get("content").findParents("family").stream()
                .filter(e -> e.get("kind").asText().equals("FAMILY_OPENING")).findFirst().orElseThrow();
        assertThat(openingEntry.get("entryDate").asText()).isEqualTo("2026-09-15");
        assertThat(openingEntry.get("family").get("link").asText()).isEqualTo("OPENING_BALANCE");
        assertThat(openingEntry.get("family").get("recordId").isNull()).isTrue();
        assertThat(ok(get(carol, "/api/reports/balances?asOf=2026-09-30")).findValuesAsText("accountCode"))
                .contains("FAMILY_DEBT_" + family);
        // The others' entries are as they were: nothing of theirs was posted again.
        assertThat(postedEntries(alice)).isEqualTo(alicesEntries);
        assertThat(postedEntries(bob)).isEqualTo(bobsEntries);
        for (String user : List.of(alice, bob, carol)) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }

        // What Kid paid from the join date is Carol's to put on an account now, and only hers (D-14).
        JsonNode asCarol = ok(get(carol, uri + "/records/" + kidPaidLater));
        assertThat(asCarol.get("yourPayment").get("later").asBoolean()).isTrue();
        assertThat(asCarol.get("yourPayment").get("amount").asText()).isEqualTo("45.00");
        assertThat(asCarol.get("canEditPayment").asBoolean()).isTrue();
        assertThat(asCarol.get("author").get("displayName").asText()).isEqualTo("Mum");
        JsonNode asAlice = ok(get(alice, uri + "/records/" + kidPaidLater));
        assertThat(asAlice.get("canEditPayment").asBoolean()).isFalse();
        assertThat(asAlice.get("canEdit").asBoolean()).isTrue();
        assertThat(detail(patch(alice, uri + "/records/" + kidPaidLater + "?version=0", """
                {"amount": "42.50"}"""), HttpStatus.CONFLICT))
                .isEqualTo("Only Carol, who paid it, can change the expense's date, amount, payer or paying account.");

        // A record before the join date is in her opening balance: the author changes it as before, and her opening
        // balance follows; it has no account of hers.
        JsonNode commented = ok(patch(alice, uri + "/records/" + kidsDollars + "?version=0", """
                {"comment": "School trip", "split": {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "5.00"},
                  {"memberId": %d, "amount": "5.00"}, {"memberId": %d, "amount": "20.00"}]}}""".formatted(mum, dad, kid)));
        assertThat(shares(commented)).containsExactly("Mum 5.00 null", "Dad 5.00 null", "Carol 20.00 null");
        assertThat(balances(carol)).containsExactly("Mum -110.00", "Dad 35.00", "Carol 75.00 you");
        // Kid's balance before the join date is 0 now: no opening balance.
        assertThat(postedEntries(carol).stream().filter(e -> e.startsWith("FAMILY_OPENING"))).isEmpty();
        assertThat(details(patch(carol, uri + "/records/" + kidsDollars + "?version=1", """
                {"paymentAccountId": %d}""".formatted(accountId(carol, "CASH"))))).containsExactly(("PAYMENT %d this "
                        + "expense is dated before Carol took their place in the family budget on 2026-09-15, so it is "
                        + "part of their opening balance and has no account of theirs").formatted(kid));
        // Her own change of what she paid then: the balance before the join date moves, and the opening with it.
        ok(patch(carol, uri + "/records/" + kidsDollars + "?version=1", """
                {"amount": "40.00", "split": {"method": "AMOUNT",
                 "shares": [{"memberId": %d, "amount": "10.00"}, {"memberId": %d, "amount": "10.00"},
                  {"memberId": %d, "amount": "20.00"}]}}""".formatted(mum, dad, kid)));
        assertThat(balances(carol)).containsExactly("Mum -105.00", "Dad 40.00", "Carol 65.00 you");
        assertThat(postedEntries(carol)).contains(
                "FAMILY_OPENING OPENING_BALANCE %d:10.00:null %d:-10.00:null".formatted(debt, opening));

        // Her side of what Mum paid her waits under "Specify later": no lock (D-28) until she puts it on an account.
        ok(patch(alice, uri + "/records/" + mumPaidKid + "?version=0", """
                {"amount": "12.00"}"""));
        assertThat(postedEntries(carol)).contains(
                "FAMILY_SETTLEMENT SETTLEMENT %d:12.00:null %d:-12.00:null".formatted(placeholder, debt));
        ok(patch(carol, uri + "/records/" + mumPaidKid + "?version=1", """
                {"paymentAccountId": %d}""".formatted(accountId(carol, "CASH"))));
        assertThat(detail(patch(alice, uri + "/records/" + mumPaidKid + "?version=1", """
                {"amount": "10.00"}"""), HttpStatus.CONFLICT)).isEqualTo("Carol has put their side of this settlement "
                        + "on an account of theirs, so its date and amount can't change; Carol can move it back to "
                        + "\"Specify later\" to allow it.");
        for (String user : List.of(alice, bob, carol)) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
    }

    /**
     * D-35, a new record before the claim's date, split equally: a claim changes nothing about who takes part, so the
     * rule splits Carol in from the start date, as it split Kid; the claim's date, 09-20, only divides her opening
     * balance from her entries. Mum paid 30.00 on 09-05 (a third Kid's), then after the claim:
     * <ul>
     * <li>30.00 on 09-10, by the rule: Mum, Dad and Carol 10.00 each; her opening balance goes from +10.00 to +20.00;
     * <li>30.00 on 09-20: the same three, and her 10.00 is a share entry;
     * <li>that one moved to 09-19, before the claim's date: she stays in it, the share entry goes, the opening balance
     * is +30.00; moved on to 09-25, the other way: the share entry is back, the opening balance +20.00 again.
     * </ul>
     * Nobody's balance moves with a date. F6a dropped her from the new record and from the moved one.
     */
    @Test
    void aClaimedSeatTakesPartFromTheStartDateUnderTheRule() throws IOException {
        long alicesCurrent = accountId(alice, "CURRENT_ACCOUNT");
        String paid = "\"paymentAccountId\": %d,".formatted(alicesCurrent);
        created(post(alice, uri + "/records", expense("2026-09-05", groceries, "30.00", mum, paid)));
        accept(carol, kidsPlace("2026-09-20"), "Carol");
        long debt = accountId(carol, "FAMILY_DEBT_" + family);
        long opening = accountId(carol, "OPENING_BALANCE");
        long unallocated = accountId(carol, "UNALLOCATED");
        assertThat(balances(carol)).containsExactly("Mum -20.00", "Dad 10.00", "Carol 10.00 you");
        assertThat(postedEntries(carol)).containsExactly("FAMILY_OPENING OPENING_BALANCE %d:-10.00:null %d:10.00:null"
                .formatted(debt, opening));

        JsonNode before = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30.00", mum, paid)));
        assertThat(shares(before)).containsExactly("Mum 10.00 null", "Dad 10.00 null", "Carol 10.00 null");
        assertThat(postedEntries(carol)).containsExactly("FAMILY_OPENING OPENING_BALANCE %d:-20.00:null %d:20.00:null"
                .formatted(debt, opening));
        assertThat(balances(carol)).containsExactly("Mum -40.00", "Dad 20.00", "Carol 20.00 you");

        JsonNode after = created(post(alice, uri + "/records", expense("2026-09-20", groceries, "30.00", mum, paid)));
        assertThat(shares(after)).containsExactly("Mum 10.00 null", "Dad 10.00 null", "Carol 10.00 null");
        String share = "FAMILY_SHARE SHARE %d:10.00:%d %d:-10.00:null".formatted(unallocated, groceries, debt);
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(
                "FAMILY_OPENING OPENING_BALANCE %d:-20.00:null %d:20.00:null".formatted(debt, opening), share);

        long moved = after.get("id").asLong();
        assertThat(shares(ok(patch(alice, uri + "/records/" + moved + "?version=0", """
                {"date": "2026-09-19"}""")))).containsExactly("Mum 10.00 null", "Dad 10.00 null", "Carol 10.00 null");
        assertThat(postedEntries(carol)).containsExactly("FAMILY_OPENING OPENING_BALANCE %d:-30.00:null %d:30.00:null"
                .formatted(debt, opening));
        assertThat(balances(carol)).containsExactly("Mum -60.00", "Dad 30.00", "Carol 30.00 you");
        everyonesIntegrity();

        ok(patch(alice, uri + "/records/" + moved + "?version=1", """
                {"date": "2026-09-25"}"""));
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(
                "FAMILY_OPENING OPENING_BALANCE %d:-20.00:null %d:20.00:null".formatted(debt, opening), share);
        assertThat(balances(carol)).containsExactly("Mum -60.00", "Dad 30.00", "Carol 30.00 you");
        everyonesIntegrity();
        assertThat(changes(ok(get(carol, uri + "/journal?recordId=" + moved)))).containsExactly(
                "UPDATE by Mum: date 2026-09-19→2026-09-25", "UPDATE by Mum: date 2026-09-20→2026-09-19",
                "CREATE by Mum: date null→2026-09-20, category null→Groceries, amount null→30.00, payer null→Mum, "
                        + "splitMethod null→EQUAL, share of Mum null→10.00, share of Dad null→10.00, "
                        + "share of Carol null→10.00");
    }

    /**
     * A new member joins today as a MEMBER, gets their debt account, and shares the new records of the rule from
     * today; under a custom rule their share is 0 until an owner changes it (D-12). Nothing before today is posted to
     * them (D-18).
     */
    @Test
    void aNewMemberJoinsTodayAndSharesWhatComesAfter() throws IOException {
        long alicesCurrent = accountId(alice, "CURRENT_ACCOUNT");
        created(post(alice, uri + "/records", expense("2026-09-05", groceries, "30.00", mum,
                "\"paymentAccountId\": %d,".formatted(alicesCurrent))));
        String token = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        JsonNode lookup = ok(inviteCall(carol, "lookup", token(token, null)));
        assertThat(lookup.get("kind").asText()).isEqualTo("NEW_MEMBER");
        assertThat(lookup.get("seatName").isNull()).isTrue();
        assertThat(lookup.get("joinDate").asText()).isEqualTo(today().toString());

        JsonNode joined = accept(carol, token, "Carol");
        assertThat(joined.get("role").asText()).isEqualTo("MEMBER");
        JsonNode carolAsMember = find(ok(get(alice, uri + "/members")), "displayName", "Carol");
        assertThat(carolAsMember.get("joinDate").asText()).isEqualTo(today().toString());
        assertThat(carolAsMember.get("share").isNull()).isTrue();
        assertThat(postedEntries(carol)).isEmpty();
        assertThat(find(ok(get(carol, "/api/accounts")), "code", "FAMILY_DEBT_" + family).get("name").asText())
                .isEqualTo("Debt to family budget: Home");
        assertThat(find(ok(get(alice, uri + "/invites")), "status", "ACCEPTED").get("acceptedBy").get("displayName")
                .asText()).isEqualTo("Carol");

        JsonNode now = created(post(alice, uri + "/records", expense(today().toString(), groceries, "40.00", mum,
                "\"paymentAccountId\": %d,".formatted(alicesCurrent))));
        assertThat(shares(now)).containsExactly("Mum 10.00 null", "Dad 10.00 null", "Kid 10.00 null",
                "Carol 10.00 null");
        assertThat(balances(carol)).contains("Carol 10.00 you");

        // Under a custom rule, a member who joins gets 0.
        ok(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 5000}, {"memberId": %d, "share": 3000},
                 {"memberId": %d, "share": 1000}, {"memberId": %d, "share": 1000}]}""".formatted(mum, dad, kid,
                joined.get("memberId").asLong())));
        String dave = newUser();
        long daveId = accept(dave, newInvite(alice, """
                {"kind": "NEW_MEMBER"}"""), "Dave").get("memberId").asLong();
        assertThat(find(ok(get(alice, uri + "/members")), "id", String.valueOf(daveId)).get("share").asInt()).isZero();
    }

    /**
     * Categories of a member who joins (D-11 as amended after the F4e review): Carol's Groceries, renamed "Food" and
     * used by her own expense, merges into the family's Groceries, which keeps its name, and her expense's posting
     * moves to it; her Salary merges too. Her Gifts is an expense, the family's an income: hers stays private, beside
     * it, and can't be brought. She brings Health, which becomes a family category with her posting on it.
     */
    @Test
    void aJoiningMembersCategoriesMergeByCodeAndTheChosenOnesComeAlong() throws IOException {
        long familyGifts = body(post(alice, uri + "/categories", """
                {"code": "GIFTS", "name": "Presents", "type": "INCOME"}"""), HttpStatus.CREATED).get("id").asLong();
        long herGroceries = categoryId(carol, "GROCERIES");
        ok(patch(carol, "/api/categories/" + herGroceries, """
                {"name": "Food"}"""));
        long herHealth = categoryId(carol, "HEALTH");
        long herGifts = categoryId(carol, "GIFTS");
        JsonNode food = newExpense(carol, "2026-09-03", "12.50", null, null);
        JsonNode medicine = newEntry(carol, """
                {"kind": "EXPENSE", "entryDate": "2026-09-04", "accountId": %d, "currency": "EUR", "amount": "8.00",
                 "categoryId": %d}""".formatted(accountId(carol, "CASH"), herHealth));

        String token = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        JsonNode lookup = ok(inviteCall(carol, "lookup", token(token, null)));
        assertThat(lookup.get("merges")).hasSize(2);
        JsonNode groceriesMerge = find(lookup.get("merges"), "code", "GROCERIES");
        assertThat(groceriesMerge.get("name").asText()).isEqualTo("Food");
        assertThat(groceriesMerge.get("familyName").asText()).isEqualTo("Groceries");
        assertThat(groceriesMerge.get("categoryId").asLong()).isEqualTo(herGroceries);
        assertThat(find(lookup.get("merges"), "code", "SALARY").get("familyName").asText()).isEqualTo("Salary");
        assertThat(lookup.get("keptPrivate")).hasSize(1);
        assertThat(lookup.get("keptPrivate").get(0).get("code").asText()).isEqualTo("GIFTS");
        assertThat(lookup.get("keptPrivate").get(0).get("type").asText()).isEqualTo("EXPENSE");
        assertThat(lookup.get("keptPrivate").get(0).get("familyType").asText()).isEqualTo("INCOME");
        assertThat(lookup.get("mayBring").findValuesAsText("code")).contains("HEALTH", "EATING_OUT")
                .doesNotContain("GROCERIES", "SALARY", "GIFTS");
        assertThat(lookup.get("categories").findValuesAsText("name"))
                .containsExactlyInAnyOrder("Groceries", "Presents", "Rent", "Salary");

        // Gifts can't come along, and a category of someone else's is none of hers.
        assertThat(details(inviteCall(carol, "accept", token(token, "\"displayName\": \"Carol\", \"categoryIds\": [%d]"
                .formatted(herGifts))))).containsExactly("CATEGORY null the family budget's category GIFTS is INCOME, "
                        + "and yours is EXPENSE: yours stays private");
        assertThat(details(inviteCall(carol, "accept", token(token, "\"displayName\": \"Carol\", \"categoryIds\": [%d]"
                .formatted(categoryId(bob, "HEALTH")))))).containsExactly(("CATEGORY null category %d is not one of "
                        + "yours").formatted(categoryId(bob, "HEALTH")));
        assertThat(get(carol, uri)).hasStatus(HttpStatus.NOT_FOUND);

        accept(carol, token, "Carol", herHealth);
        JsonNode familyCategories = ok(get(carol, uri + "/categories"));
        assertThat(find(familyCategories, "code", "GROCERIES").get("name").asText()).isEqualTo("Groceries");
        long familyHealth = find(familyCategories, "code", "HEALTH").get("id").asLong();
        assertThat(find(familyCategories, "code", "HEALTH").get("name").asText()).isEqualTo("Health");
        assertThat(familyHealth).isNotEqualTo(herHealth);

        JsonNode hers = ok(get(carol, "/api/categories"));
        assertThat(find(hers, "code", "GROCERIES").get("id").asLong()).isEqualTo(groceries);
        assertThat(find(hers, "code", "GROCERIES").get("familyLedgerName").asText()).isEqualTo("Home");
        assertThat(find(hers, "code", "HEALTH").get("id").asLong()).isEqualTo(familyHealth);
        assertThat(hers.findValuesAsText("id")).doesNotContain(String.valueOf(herGroceries), String.valueOf(herHealth));
        assertThat(hers.findValuesAsText("id")).contains(String.valueOf(herGifts), String.valueOf(familyGifts));

        // Her entries point at the family's categories now, and keep their versions.
        JsonNode foodNow = ok(get(carol, "/api/entries/" + food.get("id").asLong()));
        assertThat(foodNow.get("postings").findValuesAsText("categoryId")).contains(String.valueOf(groceries));
        assertThat(foodNow.get("version").asInt()).isEqualTo(food.get("version").asInt());
        JsonNode medicineNow = ok(get(carol, "/api/entries/" + medicine.get("id").asLong()));
        assertThat(medicineNow.get("postings").findValuesAsText("categoryId")).contains(String.valueOf(familyHealth));
        // Alice sees the family's Health, and nothing of Carol's own.
        assertThat(ok(get(alice, uri + "/categories")).findValuesAsText("code")).contains("HEALTH");
        Answers.assertNoneMention(ok(get(alice, uri + "/categories")), "Food");
        assertThat(ok(get(carol, "/api/reports/integrity"))).isEmpty();
    }

    /**
     * D-31 (decided by the PM after F5): a record dated before a claimed member's join date stays editable, and every
     * change keeps their opening balance in step (D-10). Mum paid 90.00 of groceries on 09-05 and 60.00 on 09-20, each
     * split equally among Mum, Dad and Kid; Carol takes Kid's place from 09-15, with an opening balance of +30.00 and
     * the share of 20.00.
     * <ul>
     * <li>The first amount goes to 120.00: Kid's third of it, 40.00, is the opening balance now.
     * <li>The record moves to 09-18, after the join date: Carol stays in it (F5's rule for a member who was in it
     * before taking the seat), its 40.00 is a share entry of hers, and the opening balance goes.
     * <li>It moves back to 09-08: Carol stays in it, since a claim changes nothing about who takes part (D-35, which
     * replaced F6a's drop of her): her share entry of 40.00 goes, and her opening balance is +40.00 again.
     * <li>Carol moves what Kid paid, 30.00 of rent on 09-22 with a third of it hers, to 09-10 (D-35; F6a refused it,
     * `JOINED_AFTER`): its share entry and its payment on her placeholder go, and her opening balance is 40.00 + 10.00
     * − 30.00 = +20.00. An account for it is 422 `PAYMENT` (D-32). Nobody's balance moves.
     * </ul>
     * The journal names each change of the record and never an account; the invariants and the integrity check hold
     * after each step.
     */
    @Test
    void theOpeningBalanceFollowsRecordsAcrossTheJoinDate() throws IOException {
        String later = "\"paymentLater\": true,";
        long early = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "90.00", mum, later)))
                .get("id").asLong();
        created(post(alice, uri + "/records", expense("2026-09-20", groceries, "60.00", mum, later)));
        long kidPaid = created(post(alice, uri + "/records", expense("2026-09-22", rent, "30.00", kid, "")))
                .get("id").asLong();
        accept(carol, kidsPlace("2026-09-15"), "Carol");
        long debt = accountId(carol, "FAMILY_DEBT_" + family);
        long unallocated = accountId(carol, "UNALLOCATED");
        long opening = accountId(carol, "OPENING_BALANCE");
        long placeholder = accountId(carol, "UNSPECIFIED_PAYMENTS");
        String rentShare = "FAMILY_SHARE SHARE %d:10.00:%d %d:-10.00:null".formatted(unallocated, rent, debt);
        String kidsPayment = "FAMILY_PAYMENT PAYMENT %d:-30.00:null %d:30.00:null".formatted(placeholder, debt);
        String lateShare = "FAMILY_SHARE SHARE %d:20.00:%d %d:-20.00:null".formatted(unallocated, groceries, debt);
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(
                "FAMILY_OPENING OPENING_BALANCE %d:-30.00:null %d:30.00:null".formatted(debt, opening), lateShare,
                rentShare, kidsPayment);
        assertThat(balances(carol)).containsExactly("Mum -90.00", "Dad 60.00", "Carol 30.00 you");
        everyonesIntegrity();

        ok(patch(alice, uri + "/records/" + early + "?version=0", """
                {"amount": "120.00"}"""));
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(
                "FAMILY_OPENING OPENING_BALANCE %d:-40.00:null %d:40.00:null".formatted(debt, opening), lateShare,
                rentShare, kidsPayment);
        assertThat(balances(carol)).containsExactly("Mum -110.00", "Dad 70.00", "Carol 40.00 you");
        everyonesIntegrity();

        ok(patch(alice, uri + "/records/" + early + "?version=1", """
                {"date": "2026-09-18"}"""));
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(
                "FAMILY_SHARE SHARE %d:40.00:%d %d:-40.00:null".formatted(unallocated, groceries, debt), lateShare,
                rentShare, kidsPayment);
        assertThat(balances(carol)).containsExactly("Mum -110.00", "Dad 70.00", "Carol 40.00 you");
        everyonesIntegrity();

        JsonNode back = ok(patch(alice, uri + "/records/" + early + "?version=2", """
                {"date": "2026-09-08"}"""));
        assertThat(shares(back)).containsExactly("Mum 40.00 null", "Dad 40.00 null", "Carol 40.00 null");
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(
                "FAMILY_OPENING OPENING_BALANCE %d:-40.00:null %d:40.00:null".formatted(debt, opening), lateShare,
                rentShare, kidsPayment);
        assertThat(balances(carol)).containsExactly("Mum -110.00", "Dad 70.00", "Carol 40.00 you");
        everyonesIntegrity();

        ok(patch(carol, uri + "/records/" + kidPaid + "?version=0", """
                {"date": "2026-09-10"}"""));
        assertThat(postedEntries(carol)).containsExactlyInAnyOrder(
                "FAMILY_OPENING OPENING_BALANCE %d:-20.00:null %d:20.00:null".formatted(debt, opening), lateShare);
        assertThat(balances(carol)).containsExactly("Mum -110.00", "Dad 70.00", "Carol 40.00 you");
        assertThat(details(patch(carol, uri + "/records/" + kidPaid + "?version=1", """
                {"paymentAccountId": %d}""".formatted(accountId(carol, "CASH")))))
                .allMatch(detail -> detail.startsWith("PAYMENT " + kid));
        everyonesIntegrity();

        JsonNode journal = ok(get(alice, uri + "/journal?recordId=" + early));
        assertThat(changes(journal).subList(0, 3)).containsExactly(
                "UPDATE by Mum: date 2026-09-18→2026-09-08",
                "UPDATE by Mum: date 2026-09-05→2026-09-18",
                "UPDATE by Mum: amount 90.00→120.00, share of Mum 30.00→40.00, share of Dad 30.00→40.00, "
                        + "share of Carol 30.00→40.00");
        Answers.assertNoneMention(journal, "account", "Account", "ACCOUNT");
    }

    /** Nobody's integrity check finds anything, and the invariants hold. */
    private void everyonesIntegrity() throws IOException {
        for (String user : List.of(alice, bob, carol)) {
            assertThat(ok(get(user, "/api/reports/integrity"))).as("integrity of %s", user).isEmpty();
        }
        com.example.financetracker.ledger.family.FamilyInvariants.check(jdbc, family);
    }
}
