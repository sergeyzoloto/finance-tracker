package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Family records (F4a; ADR 0003, topics D, E and H): creating, listing, reading, changing and deleting family
 * expenses, who may do which, the posted entries in the members' personal ledgers, the balances and the journal, in
 * the family of {@link FamilyApiTest}.
 */
class FamilyRecordApiTests extends FamilyApiTest {

    /**
     * D-7's example: Alice pays 100 with her current account, split 50/50 with Bob. Her ledger gets the payment (the
     * account −100, her debt +100) and her share (UNALLOCATED +50 with the family category, her debt −50); his, his
     * share. The debts show −50 for her and +50 for him, as their family balances do.
     */
    @Test
    void anExpenseIsPostedAsD7Says() throws IOException {
        long current = accountId(alice, "CURRENT_ACCOUNT");
        JsonNode record = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "100", "comment": "Weekly shop",
                 "payerMemberId": %d, "paymentAccountId": %d, "split": {"method": "PERCENT", "shares": [
                   {"memberId": %d, "basisPoints": 5000}, {"memberId": %d, "basisPoints": 5000}]}}"""
                .formatted(groceries, mum, current, mum, dad)));

        assertThat(record.get("amount").asText()).isEqualTo("100.00");
        assertThat(record.get("currency").asText()).isEqualTo("EUR");
        assertThat(record.get("category").get("name").asText()).isEqualTo("Groceries");
        assertThat(record.get("payer").get("displayName").asText()).isEqualTo("Mum");
        assertThat(record.get("splitMethod").asText()).isEqualTo("PERCENT");
        assertThat(shares(record)).containsExactly("Mum 50.00 5000", "Dad 50.00 5000");
        assertThat(record.get("author").get("displayName").asText()).isEqualTo("Mum");
        assertThat(record.get("version").asInt()).isZero();
        assertThat(record.get("frozen").asBoolean()).isFalse();
        assertThat(record.get("canEdit").asBoolean()).isTrue();
        assertThat(record.get("canDelete").asBoolean()).isTrue();

        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        JsonNode alicesAccount = find(ok(get(alice, "/api/accounts")), "code", "FAMILY_DEBT_" + family);
        assertThat(alicesAccount.get("name").asText()).isEqualTo("Debt to family budget: Home");
        assertThat(alicesAccount.get("type").asText()).isEqualTo("LIABILITY");
        assertThat(alicesAccount.get("system").asBoolean()).isTrue();
        assertThat(postedEntries(alice)).containsExactly(
                "FAMILY_SHARE SHARE %d:50.00:%d %d:-50.00:null".formatted(accountId(alice, "UNALLOCATED"), groceries,
                        debt),
                "FAMILY_PAYMENT PAYMENT %d:-100.00:null %d:100.00:null".formatted(current, debt));
        assertThat(postedEntries(bob)).containsExactly("FAMILY_SHARE SHARE %d:50.00:%d %d:-50.00:null".formatted(
                accountId(bob, "UNALLOCATED"), groceries, accountId(bob, "FAMILY_DEBT_" + family)));
        JsonNode posted = ok(get(bob, "/api/entries")).get("content").get(0);
        assertThat(posted.get("family")).isEqualTo(json.readTree("""
                {"ledgerId": %d, "ledgerName": "Home", "recordId": %d, "link": "SHARE", "readOnly": true,
                 "recordType": "EXPENSE"}"""
                .formatted(family, record.get("id").asLong())));
        assertThat(posted.get("memo").isNull()).isTrue();

        assertThat(balances(alice)).containsExactly("Mum -50.00 you", "Dad 50.00", "Kid 0.00");
        assertThat(balances(bob)).containsExactly("Mum -50.00", "Dad 50.00 you", "Kid 0.00");
        assertThat(find(ok(get(alice, "/api/reports/balances?asOf=2026-09-30")), "accountCode", "FAMILY_DEBT_" + family)
                .get("balance").asText()).isEqualTo("-50.00");
        assertThat(find(ok(get(bob, "/api/reports/balances?asOf=2026-09-30")), "accountCode", "FAMILY_DEBT_" + family)
                .get("balance").asText()).isEqualTo("50.00");
    }

    /** D-12: 10.01 split 50/50 gives the payer 5.01. The payer specifies the account later (D-14). */
    @Test
    void tenOhOneSplitFiftyFiftyGivesThePayer501() throws IOException {
        String bobPaysLater = """
                {"date": "2026-09-12", "categoryId": %d, "amount": "10.01", "payerMemberId": %d, "paymentLater": true,
                 "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                   {"memberId": %d, "basisPoints": 5000}]}}""".formatted(groceries, dad, mum, dad);
        JsonNode record = created(post(bob, uri + "/records", bobPaysLater));
        created(post(bob, uri + "/records", bobPaysLater));

        assertThat(shares(record)).containsExactly("Mum 5.00 5000", "Dad 5.01 5000");
        JsonNode placeholder = find(ok(get(bob, "/api/accounts")), "code", "UNSPECIFIED_PAYMENTS");
        assertThat(placeholder.get("name").asText()).isEqualTo("Payments without a specified account");
        assertThat(placeholder.get("type").asText()).isEqualTo("ASSET");
        assertThat(placeholder.get("system").asBoolean()).isTrue();
        assertThat(ok(get(bob, "/api/accounts")).findValuesAsText("code")).containsOnlyOnce("UNSPECIFIED_PAYMENTS");
        assertThat(postedEntries(bob)).contains("FAMILY_PAYMENT PAYMENT %d:-10.01:null %d:10.01:null".formatted(
                placeholder.get("id").asLong(), accountId(bob, "FAMILY_DEBT_" + family)));
        // Nothing of the payment reached Alice: no placeholder of hers.
        assertThat(ok(get(alice, "/api/accounts")).findValuesAsText("code")).doesNotContain("UNSPECIFIED_PAYMENTS");
        assertThat(balances(bob)).containsExactly("Mum 10.00", "Dad -10.00 you", "Kid 0.00");
        // Equal shares among three: 3.33 each, and the remainder of 0.02 to the payer.
        JsonNode equal = created(post(alice, uri + "/records", expense("2026-09-13", groceries, "10.01", kid, "")));
        assertThat(shares(equal)).containsExactly("Mum 3.33 null", "Dad 3.33 null", "Kid 3.35 null");
    }

    @Test
    void postedEntriesAreReadOnlyInThePersonalLedger() throws IOException {
        created(post(alice, uri + "/records", expense("2026-09-10", groceries, "30", mum,
                "\"paymentAccountId\": %d,".formatted(accountId(alice, "CASH")))));
        JsonNode[] entries = elements(ok(get(alice, "/api/entries")).get("content"));
        JsonNode share = entries[0].get("kind").asText().equals("FAMILY_SHARE") ? entries[0] : entries[1];
        JsonNode payment = share == entries[0] ? entries[1] : entries[0];
        String command = """
                {"kind": "EXPENSE", "entryDate": "2026-09-10", "accountId": %d, "currency": "EUR", "amount": "1",
                 "categoryId": %d}""".formatted(accountId(alice, "CASH"), categoryId(alice, "HOUSING"));

        assertThat(detail(put(alice, "/api/entries/%d?version=0".formatted(share.get("id").asLong()), command),
                HttpStatus.CONFLICT)).isEqualTo("Entry %d was posted from the family budget \"Home\"; change it there."
                .formatted(share.get("id").asLong()));
        assertThat(detail(delete(alice, "/api/entries/%d?version=0".formatted(share.get("id").asLong())),
                HttpStatus.CONFLICT)).startsWith("Entry %d was posted".formatted(share.get("id").asLong()));
        // The payment changes as a payment, or through the expense; deleting it deletes the expense (F4c).
        assertThat(detail(put(alice, "/api/entries/%d?version=0".formatted(payment.get("id").asLong()), command),
                HttpStatus.CONFLICT)).isEqualTo(("Entry %d is your payment for an expense of the family budget \"Home\"; "
                + "change or delete the expense there.").formatted(payment.get("id").asLong()));
        assertThat(ok(get(alice, "/api/entries/" + share.get("id").asLong())).get("family").get("readOnly").asBoolean())
                .isTrue();

        // Only the family budget posts to a debt account, and it can't be renamed or archived.
        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(violationsOf(post(alice, "/api/entries", """
                {"kind": "TRANSFER", "entryDate": "2026-09-10", "fromAccountId": %d, "toAccountId": %d,
                 "currency": "EUR", "amount": "5"}""".formatted(accountId(alice, "CASH"), debt)))).containsExactly(
                "posting 2 (FAMILY_DEBT_%d): the account is a family budget's debt account, which only the family budget "
                        .formatted(family) + "posts to");
        assertThat(patch(alice, "/api/accounts/" + debt, """
                {"name": "Mine"}""")).hasStatus(HttpStatus.CONFLICT);
        assertThat(patch(alice, "/api/accounts/" + accountId(alice, "UNALLOCATED"), """
                {"archived": true}""")).hasStatus(HttpStatus.CONFLICT);
    }

    /**
     * Newest first, one page at a time; the author and the owners change a record's family fields; the payer with an
     * account deletes, or for a payer without one the author or an owner; a deleted record is gone with its entries.
     */
    @Test
    void recordsAreListedReadChangedAndDeletedByWhomD14Says() throws IOException {
        long first = created(post(alice, uri + "/records", expense("2026-09-05", groceries, "12", mum,
                "\"paymentAccountId\": %d,".formatted(accountId(alice, "CASH"))))).get("id").asLong();
        long kidsByAlice = created(post(alice, uri + "/records", expense("2026-09-20", rent, "40", kid, "",
                "{\"method\": \"ONE_MEMBER\", \"memberId\": %d}".formatted(dad)))).get("id").asLong();
        JsonNode kidsByBob = created(post(bob, uri + "/records", expense("2026-09-20", groceries, "9", kid, "",
                """
                {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "4"}, {"memberId": %d, "amount": "5"}]}"""
                        .formatted(mum, dad))));
        long third = kidsByBob.get("id").asLong();
        assertThat(shares(kidsByBob)).containsExactly("Mum 4.00 null", "Dad 5.00 null");

        JsonNode page = ok(get(bob, uri + "/records?size=2"));
        assertThat(ids(page.get("content"))).containsExactly(third, kidsByAlice);
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(ok(get(bob, uri + "/records?page=1&size=2")).get("content"))).containsExactly(first);
        JsonNode firstAsBob = ok(get(bob, uri + "/records/" + first));
        assertThat(firstAsBob.get("canEdit").asBoolean()).isFalse();
        assertThat(firstAsBob.get("canDelete").asBoolean()).isFalse();

        // Bob neither wrote the first record nor owns the ledger.
        assertThat(detail(patch(bob, uri + "/records/" + first + "?version=0", """
                {"comment": "Mine"}"""), HttpStatus.CONFLICT))
                .isEqualTo("Only the expense's author or an owner of the family budget can change its category, split or "
                        + "comment.");
        // An owner changes a record of Bob's: the category, the comment and the split.
        JsonNode changed = ok(patch(alice, uri + "/records/" + third + "?version=0", """
                {"categoryId": %d, "comment": "Kid's school trip", "split": {"method": "ONE_MEMBER", "memberId": %d}}"""
                .formatted(rent, mum)));
        assertThat(changed.get("category").get("code").asText()).isEqualTo("RENT");
        assertThat(changed.get("comment").asText()).isEqualTo("Kid's school trip");
        assertThat(shares(changed)).containsExactly("Mum 9.00 null");
        assertThat(changed.get("updatedBy").get("displayName").asText()).isEqualTo("Mum");
        assertThat(changed.get("author").get("displayName").asText()).isEqualTo("Dad");
        assertThat(changed.get("version").asInt()).isOne();
        assertThat(postedEntries(bob)).hasSize(2);
        assertThat(detail(patch(alice, uri + "/records/" + third + "?version=0", """
                {"comment": "Again"}"""), HttpStatus.CONFLICT))
                .isEqualTo("Expense %d has changed since version 0. Reload it and try again.".formatted(third));
        // The same values again change nothing: no new version, no new entry versions.
        List<String> alicesEntries = entryVersions(alice);
        assertThat(ok(patch(alice, uri + "/records/" + third + "?version=1", """
                {"categoryId": %d, "comment": "Kid's school trip", "split": {"method": "ONE_MEMBER", "memberId": %d}}"""
                .formatted(rent, mum))).get("version").asInt()).isOne();
        assertThat(entryVersions(alice)).isEqualTo(alicesEntries);

        // The payer with an account deletes; for Kid, the author or an owner.
        assertThat(detail(delete(bob, uri + "/records/" + first + "?version=0"), HttpStatus.CONFLICT))
                .isEqualTo("Only Mum, who paid it, can delete this expense.");
        assertThat(detail(delete(bob, uri + "/records/" + kidsByAlice + "?version=0"), HttpStatus.CONFLICT))
                .isEqualTo("Only the expense's author or an owner of the family budget can delete it.");
        assertThat(delete(bob, uri + "/records/" + third + "?version=1")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(detail(get(bob, uri + "/records/" + third), HttpStatus.NOT_FOUND))
                .isEqualTo("Expense %d not found.".formatted(third));
        assertThat(delete(alice, uri + "/records/" + kidsByAlice + "?version=0")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(postedEntries(bob)).hasSize(1);
        assertThat(delete(alice, uri + "/records/" + first + "?version=0")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(postedEntries(alice)).isEmpty();
        assertThat(postedEntries(bob)).isEmpty();
        assertThat(ok(get(alice, uri + "/records")).get("totalElements").asInt()).isZero();
        assertThat(balances(alice)).containsExactly("Mum 0.00 you", "Dad 0.00", "Kid 0.00");
        assertThat(delete(alice, uri + "/records/" + first + "?version=1")).hasStatus(HttpStatus.NOT_FOUND);
    }

    /** Every rule of a new record that it breaks, each with its code and the member it is about. */
    @Test
    void aRecordThatBreaksTheRulesIsRefusedWithEveryViolation() throws IOException {
        long cash = accountId(alice, "CASH");
        String withCash = "\"paymentAccountId\": %d,".formatted(cash);
        long alicesOwnCategory = categoryId(alice, "HOUSING");
        long bobsCash = accountId(bob, "CASH");
        long archived = body(post(alice, uri + "/categories", """
                {"code": "OLD", "name": "Old", "type": "EXPENSE"}"""), HttpStatus.CREATED).get("id").asLong();
        ok(patch(alice, uri + "/categories/" + archived, """
                {"archived": true}"""));

        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", alicesOwnCategory, "0", mum, withCash))))
                .containsExactly("CATEGORY null category %d is not a category of the family budget"
                        .formatted(alicesOwnCategory), "AMOUNT null the amount must be above 0");
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", salary, "1.005", mum, withCash))))
                .containsExactly("CATEGORY null the category SALARY is INCOME, and an expense needs an EXPENSE category",
                        "AMOUNT null the amount 1.005 has more decimals than EUR has (2)");
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", archived, "1", mum, withCash))))
                .containsExactly("CATEGORY null the category OLD is archived");
        // A member with an account names only themselves as payer, and says how they paid.
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "1", dad, ""))))
                .containsExactly("PAYER %d Dad has an account: only they can record what they paid; name yourself, or a "
                        .formatted(dad) + "member without an account");
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "1", mum, ""))))
                .containsExactly("PAYMENT %d name the account you paid with, or specify it later".formatted(mum));
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "1", mum,
                withCash + "\"paymentLater\": true,")))).containsExactly(
                "PAYMENT %d name the account you paid with, or specify it later, not both".formatted(mum));
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "1", kid, withCash))))
                .containsExactly("PAYMENT %d Kid has no account, so there is no account of theirs to pay with"
                        .formatted(kid));
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "1", 9_000_000_000L, ""))))
                .containsExactly("PAYER 9000000000 Member 9000000000 is not an active member of the family budget");
        // Another user's account reads as a missing one; an equity account pays nothing; a loan account pays only with
        // its counterparty (D-80).
        MvcTestResult bobsAccount = post(alice, uri + "/records", expense("2026-09-10", groceries, "1", mum,
                "\"paymentAccountId\": %d,".formatted(bobsCash)));
        assertThat(details(bobsAccount)).containsExactly("PAYMENT %d account %d does not exist".formatted(mum, bobsCash));
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "1", mum,
                "\"paymentAccountId\": %d,".formatted(accountId(alice, "UNALLOCATED")))))).containsExactly(
                "PAYMENT %d the account UNALLOCATED can't pay a family expense: pay with an account of your own money "
                        .formatted(mum) + "or credit, or specify it later");
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "1", mum,
                "\"paymentAccountId\": %d,".formatted(accountId(alice, "LOANS_ASSET")))))).containsExactly(
                "COUNTERPARTY %d the account LOANS_ASSET requires a counterparty: name who you owe or who owes you"
                        .formatted(mum));

        // Shares: to ACTIVE members, once each, adding up.
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "10", kid, "", """
                {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 6000},
                 {"memberId": %d, "basisPoints": 3000}, {"memberId": 9000000000, "basisPoints": 0}]}"""
                .formatted(mum, dad))))).containsExactly(
                "NOT_ACTIVE_MEMBER 9000000000 Member 9000000000 is not an active member of the family budget",
                "SUM_NOT_WHOLE null the shares sum to 90.00 %, not 100.00 %");
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "10", kid, "", """
                {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "6"}, {"memberId": %d, "amount": "3"},
                 {"memberId": %d, "amount": "1"}, {"memberId": %d, "amount": "0.001"}]}"""
                .formatted(mum, dad, kid, kid))))).containsExactly("DUPLICATE_SHARE %d Member %d has more than one share"
                .formatted(kid, kid));
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "10", kid, "", """
                {"method": "AMOUNT", "shares": [{"memberId": %d, "amount": "6"}, {"memberId": %d, "amount": "3"}]}"""
                .formatted(mum, dad))))).containsExactly("AMOUNTS_DONT_ADD_UP null the shares sum to 9.00, not to the "
                + "amount 10.00");
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "10", kid, "", """
                {"method": "ONE_MEMBER"}""")))).containsExactly("SHARE null name the member the whole amount is on");
        // A member with an account shares records from their join date on (D-7).
        String late = newUser();
        ok(get(late, "/api/accounts"));
        long grandpa = join(family, late, "Grandpa", "MEMBER", LocalDate.of(2026, 9, 15));
        assertThat(details(post(alice, uri + "/records", expense("2026-09-10", groceries, "10", kid, "", """
                {"method": "ONE_MEMBER", "memberId": %d}""".formatted(grandpa))))).containsExactly(
                "JOINED_AFTER %d Grandpa joined on 2026-09-15, after the expense's date 2026-09-10".formatted(grandpa));
        // Under the equal rule he isn't among the members of an earlier record.
        assertThat(shares(created(post(alice, uri + "/records", expense("2026-09-10", groceries, "10", kid, "")))))
                .containsExactly("Mum 3.33 null", "Dad 3.33 null", "Kid 3.34 null");
        assertThat(ok(get(alice, uri + "/records")).get("totalElements").asInt()).isOne();
    }

    /**
     * 409 for what the ledger's state rules out: a date before its start (D-27), also as a change, removing a member who
     * shares records, deleting a category that anything uses, and
     * a record that involves a member who deleted their data (D-19, D-20).
     */
    @Test
    void whatTheStateRulesOutIsAConflict() throws IOException {
        assertThat(detail(post(alice, uri + "/records", expense("2026-08-31", groceries, "10", kid, "")),
                HttpStatus.CONFLICT)).isEqualTo("The family budget starts on 2026-09-01, and an expense can't be dated "
                + "before its start date.");
        assertThat(ok(get(alice, uri)).get("startDate").asText()).isEqualTo("2026-09-01");
        assertThat(ok(patch(alice, uri, """
                {"baseCurrency": "USD"}""")).get("baseCurrency").asText()).isEqualTo("USD");
        ok(patch(alice, uri, """
                {"baseCurrency": "EUR"}"""));

        JsonNode kids = created(post(alice, uri + "/records", expense("2026-09-10", groceries, "10", kid, "")));
        String record = uri + "/records/" + kids.get("id").asLong() + "?version=0";
        assertThat(detail(patch(alice, record, """
                {"date": "2026-08-31"}"""), HttpStatus.CONFLICT)).isEqualTo("The family budget starts on 2026-09-01, "
                + "and an expense can't be dated before its start date.");
        // The main currency changes with records since F8a (D-45): each record keeps its own.
        ok(patch(alice, uri, """
                {"baseCurrency": "USD"}"""));
        assertThat(ok(get(alice, record.replace("?version=0", ""))).get("currency").asText()).isEqualTo("EUR");
        assertThat(ok(patch(alice, uri, """
                {"baseCurrency": "EUR", "name": "Our home"}""")).get("name").asText()).isEqualTo("Our home");
        assertThat(detail(delete(alice, uri + "/categories/" + groceries), HttpStatus.CONFLICT))
                .isEqualTo("The category GROCERIES is in use by family records or entries; archive it instead.");
        // Deleted, the record still names the category and the member.
        assertThat(delete(alice, record)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(alice, uri + "/categories/" + groceries)).hasStatus(HttpStatus.CONFLICT);
        assertThat(delete(alice, uri + "/categories/" + rent)).hasStatus(HttpStatus.NO_CONTENT);

        // Bob shares a record, then deletes all his data: it is frozen, and one without him isn't.
        long withBob = created(post(alice, uri + "/records", expense("2026-09-12", groceries, "20", mum,
                "\"paymentAccountId\": %d,".formatted(accountId(alice, "CASH"))))).get("id").asLong();
        long withoutBob = created(post(alice, uri + "/records", expense("2026-09-12", groceries, "8", kid, "",
                "{\"method\": \"ONE_MEMBER\", \"memberId\": %d}".formatted(mum)))).get("id").asLong();
        List<String> before = balances(alice);
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);

        JsonNode frozen = ok(get(alice, uri + "/records/" + withBob));
        assertThat(frozen.get("frozen").asBoolean()).isTrue();
        assertThat(frozen.get("canEdit").asBoolean()).isFalse();
        assertThat(shares(frozen)).containsExactly("Mum 6.68 null", "Former member 6.66 null", "Kid 6.66 null");
        String frozenMessage = "The expense is frozen: a member it involves has left the family budget or deleted "
                + "their data, so nobody can change it.";
        assertThat(detail(patch(alice, uri + "/records/" + withBob + "?version=0", """
                {"comment": "Still?"}"""), HttpStatus.CONFLICT)).isEqualTo(frozenMessage);
        assertThat(detail(delete(alice, uri + "/records/" + withBob + "?version=0"), HttpStatus.CONFLICT))
                .isEqualTo(frozenMessage);
        assertThat(ok(patch(alice, uri + "/records/" + withoutBob + "?version=0", """
                {"comment": "Fine"}""")).get("comment").asText()).isEqualTo("Fine");
        // The balances stay as they were, Bob's as a former member's.
        assertThat(balances(alice)).isEqualTo(before.stream().map(b -> b.replace("Dad", "Former member")).toList());
        // Nobody names a former member in a new record.
        assertThat(details(post(alice, uri + "/records", expense("2026-09-12", groceries, "8", kid, "",
                "{\"method\": \"ONE_MEMBER\", \"memberId\": %d}".formatted(dad))))).containsExactly(
                "NOT_ACTIVE_MEMBER %d Member %d is not an active member of the family budget".formatted(dad, dad));
    }

    /**
     * D-27: a start date today by default, earlier allowed, never later; it is the creator's join date. Today is the
     * api's, UTC's for a user with no zone (D-101), not the test JVM's (Pacific/Kiritimati: a day ahead from 10:00 UTC on).
     */
    @Test
    void theStartDateIsTheCreatorsJoinDateAndNeverInTheFuture() throws IOException {
        String tomorrow = utcToday().plusDays(1).toString();
        JsonNode future = body(post(alice, "/api/family-ledgers", """
                {"name": "Later", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "%s"}""".formatted(tomorrow)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(future.get("violationDetails").get(0).get("code").asText()).isEqualTo("START_DATE");
        JsonNode today = newFamily(alice, """
                {"name": "Today", "baseCurrency": "EUR", "displayName": "Mum"}""");
        assertThat(today.get("startDate").asText()).isEqualTo(utcToday().toString());
        assertThat(ok(get(alice, uri + "/members")).get(0).get("joinDate").asText()).isEqualTo("2026-09-01");
        assertThat(ok(get(alice, "/api/family-ledgers/" + today.get("id").asLong() + "/members")).get(0)
                .get("joinDate").asText()).isEqualTo(utcToday().toString());
    }

    /**
     * The journal (D-16, topic H): the creation, each change of family fields with the old and new values, and the
     * deletion, newest first, with members and categories named as they are now: a member who deleted their data reads
     * "Former member", and their comments are gone (D-20).
     */
    @Test
    void theJournalShowsWhoChangedWhatAndWhen() throws IOException {
        JsonNode created = created(post(bob, uri + "/records", """
                {"date": "2026-09-14", "categoryId": %d, "amount": "20", "comment": "Dad's note",
                 "payerMemberId": %d, "paymentLater": true}""".formatted(groceries, dad)));
        long id = created.get("id").asLong();
        ok(patch(alice, uri + "/records/" + id + "?version=0", """
                {"categoryId": %d, "comment": "Mum's note", "split": {"method": "ONE_MEMBER", "memberId": %d}}"""
                .formatted(rent, kid)));
        ok(patch(bob, uri + "/records/" + id + "?version=1", """
                {"comment": null}"""));

        JsonNode journal = ok(get(bob, uri + "/journal"));
        assertThat(journal.get("totalElements").asInt()).isEqualTo(3);
        assertThat(changes(journal)).containsExactly(
                "UPDATE by Dad: comment Mum's note→null",
                "UPDATE by Mum: category Groceries→Rent, splitMethod EQUAL→ONE_MEMBER, share of Mum 6.66→null, "
                        + "share of Dad 6.68→null, share of Kid 6.66→20.00, comment Dad's note→Mum's note",
                "CREATE by Dad: date null→2026-09-14, category null→Groceries, amount null→20.00, payer null→Dad, "
                        + "splitMethod null→EQUAL, share of Mum null→6.66, share of Dad null→6.68, "
                        + "share of Kid null→6.66, comment null→Dad's note");
        assertThat(ok(get(bob, uri + "/journal?recordId=" + id + "&size=1")).get("content")).hasSize(1);
        // Each change names its record as it is now, so that a sentence reads "… of Rent, 14 Sep" (F4b).
        assertThat(summaries(journal)).containsOnly("2026-09-14 Rent 20.00 live");
        // The private side of the payment is never journaled (D-16).
        Answers.assertNoneMention(journal, "UNSPECIFIED", "account", "Account");

        // Only Bob, who paid it, deletes it.
        assertThat(delete(alice, uri + "/records/" + id + "?version=2")).hasStatus(HttpStatus.CONFLICT);
        assertThat(delete(bob, uri + "/records/" + id + "?version=2")).hasStatus(HttpStatus.NO_CONTENT);
        // A deleted record is named all the same: the journal is where it still shows.
        assertThat(summaries(ok(get(alice, uri + "/journal")))).containsExactly("2026-09-14 Rent 20.00 deleted",
                "2026-09-14 Rent 20.00 deleted", "2026-09-14 Rent 20.00 deleted", "2026-09-14 Rent 20.00 deleted");
        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(changes(ok(get(alice, uri + "/journal")))).containsExactly(
                "DELETE by Former member: ",
                "UPDATE by Former member: comment Mum's note→null",
                "UPDATE by Mum: category Groceries→Rent, splitMethod EQUAL→ONE_MEMBER, share of Mum 6.66→null, "
                        + "share of Former member 6.68→null, share of Kid 6.66→20.00, comment null→Mum's note",
                "CREATE by Former member: date null→2026-09-14, category null→Groceries, amount null→20.00, "
                        + "payer null→Former member, splitMethod null→EQUAL, share of Mum null→6.66, "
                        + "share of Former member null→6.68, share of Kid null→6.66, comment null→null");
    }
}
