package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * UX-9, D-479 to D-483: a member lets the other members of a family budget see the payees of their family payments,
 * each for their own, in each budget separately, off by default. The others then get the payee's name
 * ({@code paymentPayee}) in the records list and on the record, read when asked, and nothing else of the payer's side:
 * never the account, the account line's counterparty, the note or the paying amount, nothing of a settlement, and not
 * in the journal, the report, balances, members, their own entries, export or similar entries.
 */
class FamilyPayeeApiTests extends FamilyApiTest {

    private static final String SHOP = "Corner shop UX9";
    private static final String NOTE = "Private note UX9";
    private static final String CREDITOR = "Creditor UX9";

    private long current;
    private long shop;
    private long expense;

    @BeforeEach
    void mumPaysWithAPayee() throws IOException {
        current = accountId(alice, "CURRENT_ACCOUNT");
        shop = newCounterparty(alice, SHOP);
        expense = created(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "30.00", "currency": "EUR", "payerMemberId": %d,
                 "paymentAccountId": %d, "accountCurrency": "USD", "accountAmount": "33.33", "payeeId": %d}"""
                .formatted(groceries, mum, current, shop))).get("id").asLong();
        // Her private note on her own payment entry.
        JsonNode payment = paymentEntry(alice, expense);
        ok(patch(alice, "/api/entries/%d/family-payment?version=%d".formatted(payment.get("id").asLong(),
                payment.get("version").asInt()), "{\"memo\": \"%s\"}".formatted(NOTE)));
    }

    private JsonNode paymentEntry(String user, long recordId) throws IOException {
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            JsonNode family = entry.get("family");
            if (!family.isNull() && family.get("link").asText().equals("PAYMENT")
                    && family.get("recordId").asLong() == recordId) {
                return entry;
            }
        }
        throw new AssertionError("no payment entry for record " + recordId);
    }

    private JsonNode record(String user, long id) throws IOException {
        return ok(get(user, uri + "/records/" + id));
    }

    private JsonNode listed(String user, long id) throws IOException {
        return find(ok(get(user, uri + "/records?size=200")).get("content"), "id", String.valueOf(id));
    }

    private JsonNode showPayees(String user, long ledger, boolean on) throws IOException {
        return ok(patch(user, "/api/family-ledgers/" + ledger + "/me", "{\"showsPayees\": %s}".formatted(on)));
    }

    private boolean stored(long memberId) {
        return jdbc.sql("SELECT shows_payees FROM ledger_member WHERE id = ?").param(memberId).query(Boolean.class)
                .single();
    }

    /**
     * Everything Bob reads that D-481 keeps the payee out of, always. Since UX-11 (D-523) his own entries name it only
     * as their record's payee ({@code family.recordPayee}), and only while it is shown: who sees it is unchanged.
     */
    private void bobNeverSeesItElsewhere() throws IOException {
        for (String path : List.of(uri + "/journal", uri + "/balances", uri + "/members", uri,
                uri + "/report?from=2026-09-01&to=2026-12-31",
                "/api/export/summary", "/api/entries/matches?type=EXPENSE&categoryId=" + groceries,
                "/api/family-ledgers")) {
            Answers.assertNoneMention(ok(get(bob, path)), SHOP);
        }
        JsonNode entries = ok(get(bob, "/api/entries?size=200"));
        entries.get("content").forEach(entry -> {
            if (entry.get("family") instanceof com.fasterxml.jackson.databind.node.ObjectNode family) {
                family.remove("recordPayee");
            }
        });
        Answers.assertNoneMention(entries, SHOP);
        assertThat(Answers.csv(get(bob, "/api/export/transactions")).lines().stream().flatMap(List::stream))
                .noneMatch(value -> value.contains(SHOP));
    }

    /** The payer's own side, which no other member reads, whatever the switch. */
    private static void holdsNothingOfHerSide(JsonNode answer) {
        assertThat(answer.has("yourPayment")).isFalse();
        Answers.assertNoneMention(answer, NOTE, "Current account", CREDITOR);
        assertThat(Answers.numbers(answer)).doesNotContain("33.33");
    }

    /** Off by default: only the payer reads her payee, in her own answers. */
    @Test
    void offByDefaultOnlyThePayerSeesHerPayee() throws IOException {
        assertThat(ok(get(alice, uri)).get("showsPayees").asBoolean()).isFalse();
        assertThat(ok(get(bob, uri)).get("showsPayees").asBoolean()).isFalse();
        for (JsonNode member : ok(get(alice, uri + "/members"))) {
            assertThat(member.get("showsPayees").asBoolean()).isFalse();
        }
        assertThat(record(alice, expense).get("paymentPayee").asText()).isEqualTo(SHOP);
        assertThat(listed(alice, expense).get("paymentPayee").asText()).isEqualTo(SHOP);

        assertThat(record(bob, expense).has("paymentPayee")).isFalse();
        assertThat(listed(bob, expense).has("paymentPayee")).isFalse();
        Answers.assertNoneMention(ok(get(bob, uri + "/records?size=200")), SHOP);
        Answers.assertNoneMention(record(bob, expense), SHOP);
        holdsNothingOfHerSide(record(bob, expense));
        bobNeverSeesItElsewhere();
        // Hidden: not even as his share's record's payee (UX-11, D-523).
        Answers.assertNoneMention(ok(get(bob, "/api/entries?size=200")), SHOP);
    }

    /**
     * Switched on: Dad reads her payees, past records included, an income's receipt and a refund too, a renamed payee by
     * its new name, a removed one not at all, and nothing else of her side; never a settlement's. Off again hides them
     * all at once.
     */
    @Test
    void switchedOnTheOtherMembersReadThePayeesNameOnly() throws IOException {
        long creditors = accountId(alice, "CREDITOR_DEBT");
        long creditor = newCounterparty(alice, CREDITOR);
        long refund = created(post(alice, uri + "/records", """
                {"date": "2026-09-11", "categoryId": %d, "amount": "4.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "refund": true, "payeeId": %d}""".formatted(groceries, mum, current, shop))).get("id").asLong();
        long income = created(post(alice, uri + "/records", """
                {"type": "INCOME", "date": "2026-09-12", "categoryId": %d, "amount": "50.00", "payerMemberId": %d,
                 "paymentAccountId": %d, "payeeId": %d}""".formatted(salary, mum, current, shop))).get("id").asLong();
        long onCredit = created(post(alice, uri + "/records", """
                {"date": "2026-09-13", "categoryId": %d, "amount": "8.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "paymentCounterpartyId": %d}""".formatted(rent, mum, creditors, creditor))).get("id").asLong();
        long settlement = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-14", "amount": "5.00", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, current))).get("id").asLong();

        JsonNode mine = showPayees(alice, family, true);
        assertThat(mine.get("showsPayees").asBoolean()).isTrue();
        assertThat(mine.get("memberId").asLong()).isEqualTo(mum);
        assertThat(stored(mum)).isTrue();
        assertThat(stored(dad)).isFalse();
        assertThat(find(ok(get(bob, uri + "/members")), "id", String.valueOf(mum)).get("showsPayees").asBoolean())
                .isTrue();
        assertThat(ok(get(bob, uri)).get("showsPayees").asBoolean()).isFalse();

        for (long id : List.of(expense, refund, income)) {
            assertThat(record(bob, id).get("paymentPayee").asText()).as("record %d", id).isEqualTo(SHOP);
            assertThat(listed(bob, id).get("paymentPayee").asText()).as("listed %d", id).isEqualTo(SHOP);
            holdsNothingOfHerSide(record(bob, id));
            holdsNothingOfHerSide(listed(bob, id));
        }
        // No payee on the entry: nothing; the account line's counterparty is never a payee.
        assertThat(record(bob, onCredit).has("paymentPayee")).isFalse();
        holdsNothingOfHerSide(record(bob, onCredit));
        // A settlement never.
        assertThat(record(bob, settlement).has("paymentPayee")).isFalse();
        assertThat(record(alice, settlement).has("paymentPayee")).isFalse();
        bobNeverSeesItElsewhere();

        // Read when shown: renamed, the new name; removed from the entry, nothing.
        ok(patch(alice, "/api/counterparties/" + shop, "{\"name\": \"%s\"}".formatted(SHOP + " renamed")));
        assertThat(record(bob, expense).get("paymentPayee").asText()).isEqualTo(SHOP + " renamed");
        int version = record(alice, income).get("version").asInt();
        ok(patch(alice, uri + "/records/%d?version=%d".formatted(income, version), "{\"payeeId\": null}"));
        assertThat(record(bob, income).has("paymentPayee")).isFalse();

        // Off again: all hidden at once, the payer still reads hers.
        assertThat(showPayees(alice, family, false).get("showsPayees").asBoolean()).isFalse();
        for (long id : List.of(expense, refund)) {
            assertThat(record(bob, id).has("paymentPayee")).isFalse();
            assertThat(listed(bob, id).has("paymentPayee")).isFalse();
            assertThat(record(alice, id).get("paymentPayee").asText()).isEqualTo(SHOP + " renamed");
        }
    }

    /** Each budget on its own: switched on in one, nobody of the other sees her payees there. */
    @Test
    void aMemberOfAnotherBudgetNeverSeesThem() throws IOException {
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        JsonNode other = newFamily(alice, """
                {"name": "Trips", "baseCurrency": "EUR", "displayName": "Alice"}""");
        long trips = other.get("id").asLong();
        join(trips, carol, "Carol", "MEMBER", utcToday());
        long travel = created(post(alice, "/api/family-ledgers/" + trips + "/categories", """
                {"code": "TRAVEL", "name": "Travel", "type": "EXPENSE"}""")).get("id").asLong();
        long trip = created(post(alice, "/api/family-ledgers/" + trips + "/records", """
                {"date": "%s", "categoryId": %d, "amount": "12.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "payeeId": %d}""".formatted(today(), travel, other.get("memberId").asLong(), current, shop)))
                .get("id").asLong();

        showPayees(alice, family, true);
        assertThat(ok(get(carol, "/api/family-ledgers/" + trips + "/records/" + trip)).has("paymentPayee")).isFalse();
        Answers.assertNoneMention(ok(get(carol, "/api/family-ledgers/" + trips + "/records")), SHOP);
        // Carol reaches nothing of Home, Bob nothing of Trips.
        assertThat(get(carol, uri + "/records/" + expense)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(bob, "/api/family-ledgers/" + trips + "/records/" + trip)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(record(bob, expense).get("paymentPayee").asText()).isEqualTo(SHOP);

        showPayees(alice, trips, true);
        assertThat(ok(get(carol, "/api/family-ledgers/" + trips + "/records/" + trip)).get("paymentPayee").asText())
                .isEqualTo(SHOP);
        showPayees(alice, family, false);
        assertThat(record(bob, expense).has("paymentPayee")).isFalse();
        assertThat(ok(get(carol, "/api/family-ledgers/" + trips + "/records/" + trip)).get("paymentPayee").asText())
                .isEqualTo(SHOP);
    }

    /**
     * Only the member themself switches it, through a path that names no member: an owner can't for anyone else, a
     * member without an account has none, someone outside the budget gets the missing ledger's answer. Leaving,
     * removal and "Delete all my data" turn it off, and the database refuses it for anyone but an ACTIVE member with an
     * account.
     */
    @Test
    void onlyTheMemberThemselfSwitchesItAndLeavingTurnsItOff() throws IOException {
        // Dad's own payee, which Mum sees only by his choice.
        long bobsShop = newCounterparty(bob, "Bob's shop UX9");
        long dads = created(post(bob, uri + "/records", """
                {"date": "2026-09-15", "categoryId": %d, "amount": "9.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "payeeId": %d}""".formatted(groceries, dad, accountId(bob, "CURRENT_ACCOUNT"), bobsShop)))
                .get("id").asLong();
        assertThat(record(alice, dads).has("paymentPayee")).isFalse();

        // The owner can't name Dad's membership: PATCH /members/{id} is a rename, and its body has no such field.
        assertThat(patch(alice, uri + "/members/" + dad, "{\"showsPayees\": true}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(stored(dad)).isFalse();
        // Mum's own switch is hers alone.
        showPayees(alice, family, true);
        assertThat(stored(dad)).isFalse();
        assertThat(record(alice, dads).has("paymentPayee")).isFalse();
        // Someone outside, and a missing body field.
        String carol = newUser();
        assertThat(patch(carol, uri + "/me", "{\"showsPayees\": true}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(patch(bob, uri + "/me", "{}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(stored(dad)).isFalse();

        assertThat(showPayees(bob, family, true).get("showsPayees").asBoolean()).isTrue();
        assertThat(stored(dad)).isTrue();
        assertThat(record(alice, dads).get("paymentPayee").asText()).isEqualTo("Bob's shop UX9");

        // A member without an account (Kid) can't have it on, whoever writes the row.
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_member SET shows_payees = TRUE WHERE id = ?").param(kid)
                .update()).hasMessageContaining("ledger_member_shows_payees_check");

        // Dad leaves: off, and his payee no longer shown (his links are detached).
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(stored(dad)).isFalse();
        assertThat(record(alice, dads).has("paymentPayee")).isFalse();
        assertThat(patch(bob, uri + "/me", "{\"showsPayees\": true}")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void removalAndDeleteAllTurnItOff() throws IOException {
        showPayees(bob, family, true);
        assertThat(delete(alice, uri + "/members/" + dad)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(stored(dad)).isFalse();

        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        long carolInHome = join(family, carol, "Carol", "MEMBER", LocalDate.of(2026, 9, 1));
        showPayees(carol, family, true);
        assertThat(stored(carolInHome)).isTrue();
        assertThat(delete(carol, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(stored(carolInHome)).isFalse();
        // The rows of the old code stay valid: a LEFT or FORMER row with it on is turned off by the trigger.
        jdbc.sql("UPDATE ledger_member SET status = 'ACTIVE', left_date = NULL WHERE id = ?").param(dad).update();
        jdbc.sql("UPDATE ledger_member SET shows_payees = TRUE WHERE id = ?").param(dad).update();
        jdbc.sql("UPDATE ledger_member SET status = 'LEFT', left_date = DATE '2026-10-01' WHERE id = ?").param(dad)
                .update();
        assertThat(stored(dad)).isFalse();
    }

    /** The export of both members' ledgers is the same, byte for byte, with the switch off and on. */
    @Test
    void theExportIsTheSameWithTheSwitchOffAndOn() throws Exception {
        created(post(bob, uri + "/records", """
                {"date": "2026-09-16", "categoryId": %d, "amount": "7.00", "payerMemberId": %d, "paymentAccountId": %d,
                 "payeeId": %d}""".formatted(groceries, dad, accountId(bob, "CURRENT_ACCOUNT"),
                newCounterparty(bob, "Bob's bakery UX9"))));
        byte[] alicesOff = Answers.download(get(alice, "/api/export/transactions"));
        byte[] bobsOff = Answers.download(get(bob, "/api/export/transactions"));
        showPayees(alice, family, true);
        showPayees(bob, family, true);
        assertThat(Answers.download(get(alice, "/api/export/transactions"))).isEqualTo(alicesOff);
        assertThat(Answers.download(get(bob, "/api/export/transactions"))).isEqualTo(bobsOff);
        assertThat(Answers.csv(get(alice, "/api/export/transactions")).lines()).hasSizeGreaterThan(1);
        assertThat(Answers.csv(get(bob, "/api/export/transactions")).lines().stream().flatMap(List::stream))
                .noneMatch(value -> value.contains(SHOP));
    }
}
