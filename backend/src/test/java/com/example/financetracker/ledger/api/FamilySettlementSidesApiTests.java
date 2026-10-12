package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import com.example.financetracker.Answers;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.family.FamilyRecordChanges;
import com.example.financetracker.ledger.family.FamilyRecordService;
import com.example.financetracker.ledger.family.FamilyRecordView;
import com.example.financetracker.ledger.family.NewSettlement;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * A settlement's side on an account that requires a counterparty (D3b, D-148), and on FX_EXCHANGE alone, one of the two
 * settlements that convert a family debt into another currency (D-71), which only the import makes. Both are the side's
 * own, as any account of theirs: no other member's answer names them.
 */
class FamilySettlementSidesApiTests extends FamilyApiTest {

    @Autowired
    private FamilyRecordService records;
    @Autowired
    private LedgerAccess access;

    private JsonNode settlementEntry(String user, long recordId) throws IOException {
        for (JsonNode entry : ok(get(user, "/api/entries?size=200")).get("content")) {
            JsonNode family = entry.get("family");
            if (!family.isNull() && family.get("link").asText().equals("SETTLEMENT")
                    && family.get("recordId").asLong() == recordId) {
                return entry;
            }
        }
        throw new AssertionError("no settlement entry for record " + recordId);
    }

    private static List<String> lines(JsonNode entry) {
        return StreamSupport.stream(entry.get("postings").spliterator(), false)
                .map(p -> p.get("accountId").asLong() + ":" + p.get("currency").asText() + ":" + p.get("amount").asText()
                        + ":" + (p.get("counterpartyId").isNull() ? "-" : p.get("counterpartyId").asText()))
                .toList();
    }

    /** D-148: Mum pays Kid from her creditor debt, whose line names the creditor; she moves it, and back. */
    @Test
    void aSettlementsSideIsOnAnAccountThatRequiresACounterpartyWithIt() throws IOException {
        long creditors = accountId(alice, "CREDITOR_DEBT");
        long loans = accountId(alice, "LOANS_ASSET");
        long bank = newCounterparty(alice, "The bank");
        JsonNode settled = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "50", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d, "paymentCounterpartyId": %d}""".formatted(mum, kid, creditors, bank)));
        long id = settled.get("id").asLong();
        assertThat(settled.get("yourPayment").get("accountId").asLong()).isEqualTo(creditors);
        assertThat(settled.get("yourPayment").get("counterpartyId").asLong()).isEqualTo(bank);
        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(lines(settlementEntry(alice, id))).containsExactly(
                creditors + ":EUR:-50.00:" + bank, debt + ":EUR:50.00:-");
        // Bob reads the settlement, never her account or the creditor.
        JsonNode bobsView = ok(get(bob, uri + "/records/" + id));
        assertThat(bobsView.has("yourPayment")).isFalse();
        Answers.assertNoneMention(bobsView, "The bank", "counterparty");

        // A counterparty without the account it needs, or with one that takes none, is refused.
        assertThat(details(patch(alice, uri + "/records/%d?version=0".formatted(id), """
                {"paymentCounterpartyId": %d}""".formatted(bank)))).containsExactly(
                "COUNTERPARTY %d name the counterparty with the account of your own side that requires it".formatted(mum));
        assertThat(details(patch(alice, uri + "/records/%d?version=0".formatted(id), """
                {"paymentAccountId": %d}""".formatted(loans)))).containsExactly(
                "COUNTERPARTY %d the account LOANS_ASSET requires a counterparty: name who you owe or who owes you"
                        .formatted(mum));
        // Moved to cash, the counterparty goes; moved back to a loan, with its counterparty.
        JsonNode cash = ok(patch(alice, uri + "/records/%d?version=0".formatted(id), """
                {"paymentAccountId": %d}""".formatted(accountId(alice, "CASH"))));
        assertThat(cash.get("yourPayment").has("counterpartyId")).isFalse();
        assertThat(cash.get("version").asInt()).isZero();
        ok(patch(alice, uri + "/records/%d?version=0".formatted(id), """
                {"paymentAccountId": %d, "paymentCounterpartyId": %d}""".formatted(loans, bank)));
        assertThat(lines(settlementEntry(alice, id))).containsExactly(
                loans + ":EUR:-50.00:" + bank, debt + ":EUR:50.00:-");
        // A new amount keeps the account and its counterparty.
        ok(patch(alice, uri + "/records/%d?version=0".formatted(id), """
                {"amount": "55"}"""));
        assertThat(lines(settlementEntry(alice, id))).containsExactly(
                loans + ":EUR:-55.00:" + bank, debt + ":EUR:55.00:-");
        assertThat(delete(alice, uri + "/records/%d?version=1".formatted(id))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(postedEntries(alice)).isEmpty();
    }

    /** D-148 for the other side: Dad puts his part of Mum's settlement on his loans given, with his counterparty. */
    @Test
    void theOtherSidePutsItsPartOnAnAccountThatRequiresACounterparty() throws IOException {
        long bobsLoans = accountId(bob, "LOANS_ASSET");
        long friend = newCounterparty(bob, "A friend");
        long id = created(post(alice, uri + "/settlements", """
                {"date": "2026-09-12", "amount": "20", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, dad, accountId(alice, "CASH")))).get("id").asLong();
        assertThat(details(patch(bob, uri + "/records/%d?version=0".formatted(id), """
                {"paymentAccountId": %d}""".formatted(bobsLoans)))).containsExactly(
                "COUNTERPARTY %d the account LOANS_ASSET requires a counterparty: name who you owe or who owes you"
                        .formatted(dad));
        JsonNode moved = ok(patch(bob, uri + "/records/%d?version=0".formatted(id), """
                {"paymentAccountId": %d, "paymentCounterpartyId": %d}""".formatted(bobsLoans, friend)));
        assertThat(moved.get("yourPayment").get("counterpartyId").asLong()).isEqualTo(friend);
        long bobsDebt = accountId(bob, "FAMILY_DEBT_" + family);
        assertThat(lines(settlementEntry(bob, id))).containsExactly(
                bobsLoans + ":EUR:20.00:" + friend, bobsDebt + ":EUR:-20.00:-");
        Answers.assertNoneMention(ok(get(alice, uri + "/records/" + id)), "A friend");
    }

    /**
     * D-71: a conversion of Mum's family debt from roubles into euros, as two settlements through her FX_EXCHANGE, which
     * the import records through the service; FX_EXCHANGE keeps the difference, as for an exchange between accounts.
     * A change of the amount posts the side again on FX_EXCHANGE, and no endpoint takes FX_EXCHANGE as an account.
     */
    @Test
    void aConversionOfTheFamilyDebtIsTwoSettlementsThroughFxExchange() throws IOException {
        long fx = accountId(alice, "FX_EXCHANGE");
        var familyScope = access.member(alice, family);
        var personal = access.personal(alice);
        FamilyRecordView roubles = records.settle(familyScope, personal, new NewSettlement(LocalDate.of(2026, 9, 15),
                new BigDecimal("1000"), mum, kid, null, fx, false, "RUB", null, null, null, true));
        FamilyRecordView euros = records.settle(familyScope, personal, new NewSettlement(LocalDate.of(2026, 9, 15),
                new BigDecimal("12"), kid, mum, null, fx, false, "EUR", null, null, null, true));
        long debt = accountId(alice, "FAMILY_DEBT_" + family);
        assertThat(lines(settlementEntry(alice, roubles.id()))).containsExactly(
                fx + ":RUB:-1000.00:-", debt + ":RUB:1000.00:-");
        assertThat(lines(settlementEntry(alice, euros.id()))).containsExactly(
                fx + ":EUR:12.00:-", debt + ":EUR:-12.00:-");
        JsonNode view = ok(get(alice, uri + "/records/" + roubles.id()));
        assertThat(view.get("yourPayment").get("accountId").asLong()).isEqualTo(fx);
        assertThat(view.get("yourPayment").get("later").asBoolean()).isFalse();

        // Changed by its recorder, the side stays on FX_EXCHANGE with the new amount.
        records.update(familyScope, personal, roubles.id(), roubles.version(), new FamilyRecordChanges(null, false, null,
                null, null, new BigDecimal("1100"), null, null, false, false, null, null, null, null));
        assertThat(lines(settlementEntry(alice, roubles.id()))).containsExactly(
                fx + ":RUB:-1100.00:-", debt + ":RUB:1100.00:-");

        // The API takes no FX_EXCHANGE: a settlement's side is on an account of the member's own money or credit.
        assertThat(details(post(alice, uri + "/settlements", """
                {"date": "2026-09-16", "amount": "5", "payerMemberId": %d, "payeeMemberId": %d,
                 "paymentAccountId": %d}""".formatted(mum, kid, fx)))).containsExactly(
                "PAYMENT %d the account FX_EXCHANGE can't take a settlement: use an account of your own money or "
                        .formatted(mum) + "credit, or specify it later");
        assertThat(delete(alice, uri + "/records/%d?version=1".formatted(roubles.id())))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(alice, uri + "/records/%d?version=0".formatted(euros.id())))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(postedEntries(alice)).isEmpty();
    }

    /** Only a conversion goes through FX_EXCHANGE, and only on FX_EXCHANGE. */
    @Test
    void throughExchangeNamesFxExchangeAndNothingElse() throws IOException {
        var familyScope = access.member(alice, family);
        var personal = access.personal(alice);
        long cash = accountId(alice, "CASH");
        var refused = org.assertj.core.api.Assertions.catchThrowableOfType(
                com.example.financetracker.ledger.RuleViolationException.class,
                () -> records.settle(familyScope, personal, new NewSettlement(LocalDate.of(2026, 9, 15),
                        new BigDecimal("10"), mum, kid, null, cash, false, "EUR", null, null, null, true)));
        assertThat(refused.details()).extracting(v -> v.code() + " " + v.message())
                .containsExactly("PAYMENT a conversion's settlement goes through FX_EXCHANGE, not CASH");
        var later = org.assertj.core.api.Assertions.catchThrowableOfType(
                com.example.financetracker.ledger.RuleViolationException.class,
                () -> records.settle(familyScope, personal, new NewSettlement(LocalDate.of(2026, 9, 15),
                        new BigDecimal("10"), mum, kid, null, null, true, "EUR", null, null, null, true)));
        assertThat(later.details()).extracting(v -> v.code() + " " + v.message()).containsExactly(
                "COUNTERPARTY a counterparty or FX_EXCHANGE goes only with an account of your own side of the settlement");
        assertThat(postedEntries(alice)).isEmpty();
    }
}
