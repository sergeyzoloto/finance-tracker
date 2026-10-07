package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Settlements (F4d; D2, D-24; ADR 0003, topic E) in the family of {@link FamilyApiTest}: who records one, how both sides
 * are posted, how the other side puts its part on an account, who changes and deletes one, frozen and stale ones, the
 * journal, and each side's account for that side's eyes only. The invariants hold after every test.
 */
class FamilySettlementApiTests extends FamilyApiTest {

    /**
     * Alice paid 72.40, half Bob's, so Bob owes her 36.20. Bob pays it from his cash: his side goes from his cash, hers
     * to her "Payments without a specified account" (D-24), and both balances are zero. She puts her side on her current
     * account, through her entry and through the settlement, which changes nothing the family sees. Only Bob, who
     * recorded it, changes its amount and date, and deletes it; while her side is on her account, neither (D-28): she
     * moves it back to "Specify later" to let him, since only she changes her own account (D-8).
     */
    @Test
    void aSettlementPostsBothSidesAndTheOtherSidePutsItsPartOnAnAccount() throws IOException {
        long alicesCurrent = accountId(alice, "CURRENT_ACCOUNT");
        long bobsCash = accountId(bob, "CASH");
        created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "72.40", "payerMemberId": %d, "paymentAccountId": %d,
                 "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                   {"memberId": %d, "basisPoints": 5000}]}}""".formatted(groceries, mum, alicesCurrent, mum, dad)));
        assertThat(balances(bob)).containsExactly("Mum -36.20", "Dad 36.20 you", "Kid 0.00");

        JsonNode settled = created(post(bob, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "36.20", "payerMemberId": %d, "payeeMemberId": %d,
                 "comment": "Groceries", "paymentAccountId": %d}""".formatted(dad, mum, bobsCash)));
        long id = settled.get("id").asLong();
        String path = uri + "/records/" + id;
        assertThat(settled.get("type").asText()).isEqualTo("SETTLEMENT");
        assertThat(settled.get("category").isNull()).isTrue();
        assertThat(settled.get("splitMethod").isNull()).isTrue();
        assertThat(settled.get("shares")).isEmpty();
        assertThat(settled.get("payer").get("displayName").asText()).isEqualTo("Dad");
        assertThat(settled.get("payee").get("displayName").asText()).isEqualTo("Mum");
        assertThat(settled.get("amount").asText()).isEqualTo("36.20");
        assertThat(settled.get("comment").asText()).isEqualTo("Groceries");
        long bobsEntry = settled.get("yourPayment").get("entryId").asLong();
        assertThat(settled.get("yourPayment")).isEqualTo(json.readTree("""
                {"entryId": %d, "accountId": %d, "accountName": "Cash", "later": false, "amount": "36.20",
                 "currency": "EUR"}""".formatted(bobsEntry,
                bobsCash)));
        assertThat(List.of(settled.get("canEdit").asBoolean(), settled.get("canEditPayment").asBoolean(),
                settled.get("canDelete").asBoolean())).containsOnly(true);
        assertThat(balances(alice)).containsExactly("Mum 0.00 you", "Dad 0.00", "Kid 0.00");

        long bobsDebt = accountId(bob, "FAMILY_DEBT_" + family);
        assertThat(postedEntries(bob)).endsWith("FAMILY_SETTLEMENT SETTLEMENT %d:-36.20:null %d:36.20:null"
                .formatted(bobsCash, bobsDebt));
        long alicesDebt = accountId(alice, "FAMILY_DEBT_" + family);
        long alicesPlaceholder = accountId(alice, "UNSPECIFIED_PAYMENTS");
        assertThat(postedEntries(alice)).endsWith("FAMILY_SETTLEMENT SETTLEMENT %d:36.20:null %d:-36.20:null"
                .formatted(alicesPlaceholder, alicesDebt));

        // Alice's side: on "Specify later", for her eyes only; Bob's account is his.
        JsonNode asAlice = ok(get(alice, path));
        long alicesEntry = asAlice.get("yourPayment").get("entryId").asLong();
        assertThat(asAlice.get("yourPayment")).isEqualTo(json.readTree("""
                {"entryId": %d, "accountId": null, "accountName": null, "later": true, "amount": "36.20",
                 "currency": "EUR"}""".formatted(alicesEntry)));
        Answers.assertNoneMention(asAlice, "Cash");
        assertThat(asAlice.findValuesAsText("accountId")).doesNotContain(String.valueOf(bobsCash));
        assertThat(List.of(asAlice.get("canEdit").asBoolean(), asAlice.get("canEditPayment").asBoolean(),
                asAlice.get("canDelete").asBoolean())).containsOnly(false);
        JsonNode herEntry = ok(get(alice, "/api/entries/" + alicesEntry));
        assertThat(herEntry.get("kind").asText()).isEqualTo("FAMILY_SETTLEMENT");
        assertThat(herEntry.get("family").get("link").asText()).isEqualTo("SETTLEMENT");
        assertThat(herEntry.get("family").get("recordId").asLong()).isEqualTo(id);

        // She puts it on her current account through her entry, then back to "Specify later" and onto it again
        // through the settlement: the family sees no change, and the journal says nothing.
        JsonNode moved = ok(patch(alice, "/api/entries/%d/family-payment?version=0".formatted(alicesEntry), """
                {"accountId": %d}""".formatted(alicesCurrent)));
        assertThat(moved.get("postings").findValuesAsText("accountId"))
                .containsExactly(String.valueOf(alicesCurrent), String.valueOf(alicesDebt));
        assertThat(ok(patch(alice, path + "?version=0", """
                {"paymentLater": true}""")).get("yourPayment").get("later").asBoolean()).isTrue();
        JsonNode onAccount = ok(patch(alice, path + "?version=0", """
                {"paymentAccountId": %d}""".formatted(alicesCurrent)));
        assertThat(onAccount.get("yourPayment").get("accountName").asText()).isEqualTo("Current account");
        assertThat(onAccount.get("version").asInt()).isZero();
        assertThat(postedEntries(alice)).endsWith("FAMILY_SETTLEMENT SETTLEMENT %d:36.20:null %d:-36.20:null"
                .formatted(alicesCurrent, alicesDebt));
        assertThat(ok(get(bob, path)).get("yourPayment").get("accountId").asLong()).isEqualTo(bobsCash);
        assertThat(changes(ok(get(bob, uri + "/journal?recordId=" + id)))).containsExactly(
                "CREATE by Dad: date null→2026-09-12, amount null→36.20, payer null→Dad, payee null→Mum, "
                        + "comment null→Groceries");

        // Its date, amount and comment are Bob's, and so is deleting it; not through her entry either.
        String onlyBob = "Only Dad, who recorded it, can change the settlement's date, amount or comment.";
        assertThat(detail(patch(alice, path + "?version=0", """
                {"amount": "30"}"""), HttpStatus.CONFLICT)).isEqualTo(onlyBob);
        assertThat(detail(patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(alicesEntry,
                ok(get(alice, "/api/entries/" + alicesEntry)).get("version").asInt()), """
                {"amount": "30"}"""), HttpStatus.CONFLICT)).isEqualTo(onlyBob);
        assertThat(detail(delete(alice, path + "?version=0"), HttpStatus.CONFLICT))
                .isEqualTo("Only Dad, who recorded it, can delete this settlement.");
        assertThat(detail(put(alice, "/api/entries/%d?version=0".formatted(alicesEntry), """
                {"kind": "EXPENSE", "entryDate": "2026-09-12", "accountId": %d, "categoryId": %d, "amount": "1",
                 "currency": "EUR"}""".formatted(alicesCurrent, categoryId(alice, "EATING_OUT"))),
                HttpStatus.CONFLICT)).isEqualTo("Entry %d is your side of a settlement in the family budget \"Home\"; "
                        .formatted(alicesEntry) + "change it there.");

        // The lock (D-28): her side is on her account, so Bob changes neither the date nor the amount, nor deletes it.
        // His comment still changes, and her side stays as it is.
        String locked = "Mum has put their side of this settlement on an account of theirs, so %s; Mum can move it "
                + "back to \"Specify later\" to allow it.";
        JsonNode asBob = ok(get(bob, path));
        assertThat(asBob.get("lockedBy").get("displayName").asText()).isEqualTo("Mum");
        assertThat(List.of(asBob.get("canEdit").asBoolean(), asBob.get("canEditPayment").asBoolean(),
                asBob.get("canDelete").asBoolean())).containsExactly(true, false, false);
        assertThat(ok(get(alice, path)).has("lockedBy")).isFalse();
        assertThat(detail(patch(bob, path + "?version=0", """
                {"amount": "40", "date": "2026-09-13"}"""), HttpStatus.CONFLICT))
                .isEqualTo(locked.formatted("its date and amount can't change"));
        assertThat(detail(patch(bob, path + "?version=0", """
                {"date": "2026-09-13"}"""), HttpStatus.CONFLICT))
                .isEqualTo(locked.formatted("its date and amount can't change"));
        assertThat(detail(patch(bob, "/api/entries/%d/family-payment?version=0".formatted(bobsEntry), """
                {"amount": "40"}"""), HttpStatus.CONFLICT)).isEqualTo(locked.formatted("its date and amount can't change"));
        assertThat(detail(delete(bob, path + "?version=0"), HttpStatus.CONFLICT))
                .isEqualTo(locked.formatted("it can't be deleted"));
        assertThat(detail(delete(bob, "/api/entries/%d?version=0".formatted(bobsEntry)), HttpStatus.CONFLICT))
                .isEqualTo(locked.formatted("it can't be deleted"));
        List<String> alicesBefore = postedEntries(alice);
        JsonNode commented = ok(patch(bob, path + "?version=0", """
                {"comment": "Groceries, Sep"}"""));
        assertThat(commented.get("version").asInt()).isOne();
        assertThat(postedEntries(alice)).isEqualTo(alicesBefore);

        // She moves her side back to "Specify later": the lock is gone.
        assertThat(ok(patch(alice, path + "?version=1", """
                {"paymentLater": true}""")).get("yourPayment").get("later").asBoolean()).isTrue();
        assertThat(ok(get(bob, path)).has("lockedBy")).isFalse();
        assertThat(ok(get(bob, path)).get("canEditPayment").asBoolean()).isTrue();

        // Bob pays 40 instead: 3.80 more than he owed, so the balances flip. Alice's side follows on her placeholder.
        JsonNode more = ok(patch(bob, path + "?version=1", """
                {"amount": "40", "date": "2026-09-13"}"""));
        assertThat(more.get("version").asInt()).isEqualTo(2);
        assertThat(more.get("yourPayment").get("accountId").asLong()).isEqualTo(bobsCash);
        assertThat(balances(alice)).containsExactly("Mum 3.80 you", "Dad -3.80", "Kid 0.00");
        assertThat(postedEntries(bob)).endsWith("FAMILY_SETTLEMENT SETTLEMENT %d:-40.00:null %d:40.00:null"
                .formatted(bobsCash, bobsDebt));
        assertThat(postedEntries(alice)).endsWith("FAMILY_SETTLEMENT SETTLEMENT %d:40.00:null %d:-40.00:null"
                .formatted(alicesPlaceholder, alicesDebt));
        assertThat(ok(get(alice, path)).get("yourPayment").get("later").asBoolean()).isTrue();
        assertThat(ok(get(alice, "/api/entries/" + alicesEntry)).get("entryDate").asText()).isEqualTo("2026-09-13");
        assertThat(changes(ok(get(alice, uri + "/journal?recordId=" + id))).getFirst())
                .isEqualTo("UPDATE by Dad: date 2026-09-12→2026-09-13, amount 36.20→40.00");

        // She puts it on her account again, and Bob can't delete it; back on "Specify later", he deletes it: both sides
        // go, and the balances are what they were before it.
        ok(patch(alice, path + "?version=2", """
                {"paymentAccountId": %d}""".formatted(alicesCurrent)));
        assertThat(detail(delete(bob, path + "?version=2"), HttpStatus.CONFLICT))
                .isEqualTo(locked.formatted("it can't be deleted"));
        ok(patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(alicesEntry,
                ok(get(alice, "/api/entries/" + alicesEntry)).get("version").asInt()), """
                {"later": true}"""));
        assertThat(delete(bob, path + "?version=2")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(balances(alice)).containsExactly("Mum -36.20 you", "Dad 36.20", "Kid 0.00");
        assertThat(postedEntries(alice)).noneMatch(entry -> entry.startsWith("FAMILY_SETTLEMENT"));
        assertThat(postedEntries(bob)).noneMatch(entry -> entry.startsWith("FAMILY_SETTLEMENT"));
        assertThat(detail(get(bob, path), HttpStatus.NOT_FOUND)).isEqualTo("Settlement %d not found.".formatted(id));
        JsonNode journal = ok(get(alice, uri + "/journal?recordId=" + id));
        assertThat(changes(journal).getFirst()).isEqualTo("DELETE by Dad: ");
        assertThat(journal.get("content").get(0).get("record").get("type").asText()).isEqualTo("SETTLEMENT");
        assertThat(journal.get("content").get(0).get("record").get("category").isNull()).isTrue();
        assertThat(journal.get("content").get(0).get("record").get("deleted").asBoolean()).isTrue();
    }

    /**
     * Who records a settlement: a member with an account who pays or receives, with their own side's account or
     * "Specify later"; an owner, between two members without an account, without one. Anyone else is refused, as is
     * every broken rule.
     */
    @Test
    void whoRecordsASettlementAndItsRules() throws IOException {
        long gran = body(post(alice, uri + "/members", """
                {"displayName": "Gran"}"""), HttpStatus.CREATED).get("id").asLong();
        String settlement = """
                {"date": "%s", "amount": "%s", "payerMemberId": %d, "payeeMemberId": %d%s}""";

        // Alice receives from Kid, without an account: only her side is posted.
        JsonNode fromKid = created(post(alice, uri + "/settlements", settlement.formatted("2026-09-05", "10", kid, mum,
                ", \"paymentLater\": true")));
        assertThat(fromKid.get("yourPayment").get("later").asBoolean()).isTrue();
        assertThat(balances(alice)).containsExactly("Mum 10.00 you", "Dad 0.00", "Kid -10.00", "Gran 0.00");
        assertThat(postedEntries(alice)).containsExactly("FAMILY_SETTLEMENT SETTLEMENT %d:10.00:null %d:-10.00:null"
                .formatted(accountId(alice, "UNSPECIFIED_PAYMENTS"), accountId(alice, "FAMILY_DEBT_" + family)));
        assertThat(postedEntries(bob)).isEmpty();

        // Between two members without an account: an owner's, with no account of anyone's; Bob, a member, can't.
        assertThat(detail(post(bob, uri + "/settlements", settlement.formatted("2026-09-06", "5", kid, gran, "")),
                HttpStatus.CONFLICT)).isEqualTo("Only an owner of the family budget records a settlement between two "
                        + "members without an account.");
        JsonNode between = created(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "5", kid, gran,
                "")));
        assertThat(between.has("yourPayment")).isFalse();
        assertThat(between.get("canEdit").asBoolean()).isTrue();
        JsonNode asBob = ok(get(bob, uri + "/records/" + between.get("id").asLong()));
        assertThat(List.of(asBob.get("canEdit").asBoolean(), asBob.get("canDelete").asBoolean())).containsOnly(false);
        assertThat(detail(patch(bob, uri + "/records/" + between.get("id").asLong() + "?version=0", """
                {"comment": "Mine"}"""), HttpStatus.CONFLICT)).isEqualTo("Only the settlement's author or an owner of "
                        + "the family budget can change the settlement's date, amount or comment.");
        assertThat(balances(alice)).containsExactly("Mum 10.00 you", "Dad 0.00", "Kid -15.00", "Gran 5.00");
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "5", kid, gran,
                ", \"paymentLater\": true")))).containsExactly("PAYMENT %d you neither pay nor receive this "
                        .formatted(mum) + "settlement, so no account of yours is in it");

        // Bob has an account: only he records what he pays or receives.
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "5", dad, kid,
                ", \"paymentLater\": true")))).containsExactly("PAYER %d Dad has an account: only they record a "
                        .formatted(dad) + "settlement they pay or receive");
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "5", kid, dad, ""))))
                .containsExactly("PAYEE %d Dad has an account: only they record a settlement they pay or receive"
                        .formatted(dad));

        // The rules of the fields, each with its code.
        long bobsCash = accountId(bob, "CASH");
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "5.001", mum, mum,
                ", \"paymentAccountId\": %d".formatted(bobsCash))))).containsExactly(
                "AMOUNT null the amount 5.001 has more decimals than EUR has (2)",
                "PAYEE %d Mum can't settle with themselves; name who received it".formatted(mum));
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "0", mum, 9_000_000L,
                "")))).containsExactly("AMOUNT null the amount must be above 0",
                "PAYEE 9000000 Member 9000000 is not an active member of the family budget");
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "5", mum, dad, ""))))
                .containsExactly("PAYMENT %d name the account your side of it went from or into, or specify it later"
                        .formatted(mum));
        // Another user's account reads like a missing one (rule 11).
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "5", mum, dad,
                ", \"paymentAccountId\": %d".formatted(bobsCash))))).containsExactly(
                "PAYMENT %d account %d does not exist".formatted(mum, bobsCash));
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-06", "5", mum, dad,
                ", \"paymentAccountId\": %d".formatted(accountId(alice, "FAMILY_DEBT_" + family)))))).containsExactly(
                "PAYMENT %d the account FAMILY_DEBT_%d can't take a settlement: use an account of your own money or "
                        .formatted(mum, family) + "credit, or specify it later");
        assertThat(detail(post(alice, uri + "/settlements", settlement.formatted("2026-08-31", "5", mum, dad,
                ", \"paymentLater\": true")), HttpStatus.CONFLICT)).isEqualTo("The family budget starts on 2026-09-01, "
                        + "and a settlement can't be dated before its start date.");
        // A side with an account who joined after the date: nothing of theirs would be posted (D-7).
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        long grandpa = join(family, carol, "Grandpa", "MEMBER", LocalDate.of(2026, 9, 15));
        assertThat(details(post(alice, uri + "/settlements", settlement.formatted("2026-09-10", "5", mum, grandpa,
                ", \"paymentLater\": true")))).containsExactly(
                "JOINED_AFTER %d Grandpa joined on 2026-09-15, after the settlement's date 2026-09-10".formatted(grandpa));
        assertThat(balances(alice)).containsExactly("Mum 10.00 you", "Dad 0.00", "Grandpa 0.00", "Kid -15.00",
                "Gran 5.00");
    }

    /**
     * A settlement's fields: no category, split, other payer or note (422); a stale version, a date before the start
     * and a settlement of a member who deleted their data (frozen) are 409. The recorder deletes it through their own
     * entry too; the other side can't.
     */
    @Test
    void aSettlementsChangesStaleFrozenAndItsEntries() throws IOException {
        long bobsCash = accountId(bob, "CASH");
        JsonNode settled = created(post(bob, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "20", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(dad, mum, bobsCash)));
        long id = settled.get("id").asLong();
        String path = uri + "/records/" + id;
        assertThat(details(patch(bob, path + "?version=0", """
                {"categoryId": %d, "split": {"method": "RULE"}, "payerMemberId": %d}""".formatted(groceries, kid))))
                .containsExactly("CATEGORY null a settlement has no category", "SHARE null a settlement has no shares",
                        "PAYER %d who paid and who received a settlement don't change: delete it and record it again"
                                .formatted(kid));
        long bobsEntry = settled.get("yourPayment").get("entryId").asLong();
        assertThat(details(patch(bob, "/api/entries/%d/family-payment?version=0".formatted(bobsEntry), """
                {"memo": "Mine"}"""))).containsExactly("PAYMENT %d a settlement takes no private note".formatted(dad));
        assertThat(detail(patch(bob, path + "?version=0", """
                {"date": "2026-08-30"}"""), HttpStatus.CONFLICT)).isEqualTo("The family budget starts on 2026-09-01, "
                        + "and a settlement can't be dated before its start date.");
        assertThat(ok(patch(bob, path + "?version=0", """
                {"comment": "Cash in the jar"}""")).get("version").asInt()).isOne();
        assertThat(detail(patch(bob, path + "?version=0", """
                {"comment": "Again"}"""), HttpStatus.CONFLICT))
                .isEqualTo("Settlement %d has changed since version 0. Reload it and try again.".formatted(id));
        // An account alone leaves the version as it is; a third member's account is nobody's side.
        assertThat(ok(patch(bob, path + "?version=1", """
                {"paymentLater": true}""")).get("version").asInt()).isOne();

        // The other side's entry doesn't delete it; the recorder's does.
        long alicesEntry = ok(get(alice, path)).get("yourPayment").get("entryId").asLong();
        int alicesVersion = ok(get(alice, "/api/entries/" + alicesEntry)).get("version").asInt();
        assertThat(detail(delete(alice, "/api/entries/%d?version=%d".formatted(alicesEntry, alicesVersion)),
                HttpStatus.CONFLICT)).isEqualTo("Only Dad, who recorded it, can delete this settlement.");
        int bobsVersion = ok(get(bob, "/api/entries/" + bobsEntry)).get("version").asInt();
        assertThat(delete(bob, "/api/entries/%d?version=%d".formatted(bobsEntry, bobsVersion)))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(alice, path)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(alice, "/api/entries/" + alicesEntry)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(balances(alice)).containsExactly("Mum 0.00 you", "Dad 0.00", "Kid 0.00");

        // Alice settles with Bob; Bob deletes all his data: the settlement is frozen for everyone.
        JsonNode withBob = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-14", "amount": "7", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentLater": true}""".formatted(mum, dad)));
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        String frozen = "The settlement is frozen: a member it involves has left the family budget or deleted their "
                + "data, so nobody can change it.";
        String withBobsPath = uri + "/records/" + withBob.get("id").asLong();
        JsonNode read = ok(get(alice, withBobsPath));
        assertThat(read.get("frozen").asBoolean()).isTrue();
        assertThat(read.get("payee").get("displayName").asText()).isEqualTo("Former member");
        assertThat(List.of(read.get("canEdit").asBoolean(), read.get("canDelete").asBoolean())).containsOnly(false);
        assertThat(detail(patch(alice, withBobsPath + "?version=0", """
                {"amount": "8"}"""), HttpStatus.CONFLICT)).isEqualTo(frozen);
        assertThat(detail(delete(alice, withBobsPath + "?version=0"), HttpStatus.CONFLICT)).isEqualTo(frozen);
        assertThat(balances(alice)).containsExactly("Mum -7.00 you", "Former member 7.00", "Kid 0.00");
    }

    /**
     * The lock (D-28) follows the other side's part only: the recorder's own side on their account doesn't lock it, a
     * recorder who receives is locked as one who pays, and a side without an account, or a settlement between two
     * members without an account, never locks anything.
     */
    @Test
    void theLockFollowsTheOtherSidesPartOnly() throws IOException {
        long alicesCurrent = accountId(alice, "CURRENT_ACCOUNT");
        long bobsCash = accountId(bob, "CASH");
        // Alice receives 30 from Bob, into her current account: her own side doesn't lock it.
        JsonNode received = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "30", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(dad, mum, alicesCurrent)));
        String path = uri + "/records/" + received.get("id").asLong();
        assertThat(received.has("lockedBy")).isFalse();
        assertThat(ok(patch(alice, path + "?version=0", """
                {"amount": "31"}""")).get("version").asInt()).isOne();

        // Bob, who paid, puts his part on his cash: now Alice, who received, is locked.
        ok(patch(bob, path + "?version=1", """
                {"paymentAccountId": %d}""".formatted(bobsCash)));
        JsonNode asAlice = ok(get(alice, path));
        assertThat(asAlice.get("lockedBy").get("memberId").asLong()).isEqualTo(dad);
        assertThat(List.of(asAlice.get("canEditPayment").asBoolean(), asAlice.get("canDelete").asBoolean()))
                .containsOnly(false);
        assertThat(ok(get(bob, path)).has("lockedBy")).isFalse();
        assertThat(detail(patch(alice, path + "?version=1", """
                {"amount": "32"}"""), HttpStatus.CONFLICT)).isEqualTo("Dad has put their side of this settlement on an "
                        + "account of theirs, so its date and amount can't change; Dad can move it back to \"Specify "
                        + "later\" to allow it.");
        // The same date and amount aren't a change, and her own account still moves.
        assertThat(ok(patch(alice, path + "?version=1", """
                {"amount": "31.00", "date": "2026-09-12", "paymentLater": true}""")).get("version").asInt()).isOne();
        assertThat(balances(alice)).containsExactly("Mum 31.00 you", "Dad -31.00", "Kid 0.00");

        // With Kid, who has no account, nothing locks it; nor between Kid and Gran, an owner's.
        JsonNode withKid = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-13", "amount": "4", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(kid, mum, alicesCurrent)));
        String kidsPath = uri + "/records/" + withKid.get("id").asLong();
        assertThat(ok(patch(alice, kidsPath + "?version=0", """
                {"amount": "5"}""")).has("lockedBy")).isFalse();
        assertThat(delete(alice, kidsPath + "?version=1")).hasStatus(HttpStatus.NO_CONTENT);
        long gran = body(post(alice, uri + "/members", """
                {"displayName": "Gran"}"""), HttpStatus.CREATED).get("id").asLong();
        JsonNode between = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-13", "amount": "3", "payerMemberId": %d, "payeeMemberId": %d}""".formatted(kid,
                gran)));
        assertThat(delete(alice, uri + "/records/" + between.get("id").asLong() + "?version=0"))
                .hasStatus(HttpStatus.NO_CONTENT);
    }
}
