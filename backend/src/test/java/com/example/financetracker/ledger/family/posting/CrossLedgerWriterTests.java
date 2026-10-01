package com.example.financetracker.ledger.family.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.family.posting.PostedEntry.Line;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cross-ledger writer (D-8; ADR 0003, topic E) refuses every entry that D-8 doesn't allow, before the database
 * would: whatever the posting service's factories might build by mistake, a member's cards, other accounts and
 * personal categories stay out of reach. Bob acts in family ledger "Home", which he shares with Alice; nothing he
 * gets the writer to try changes a row of hers.
 */
class CrossLedgerWriterTests extends IntegrationTest {

    @Autowired
    private CrossLedgerWriter writer;

    @Autowired
    private LedgerAccess access;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private FamilyPostingService posting;

    private final String alice = UUID.randomUUID().toString();
    private final String bob = UUID.randomUUID().toString();
    private long family;
    private long alicesMembership;
    private long record;
    /** A record entirely on Bob, so that Alice has no share of it. */
    private long bobsOwn;

    @BeforeEach
    void aFamilyWithARecord() throws IOException {
        call(bob, HttpMethod.GET, "/api/accounts", null);
        JsonNode created = call(alice, HttpMethod.POST, "/api/family-ledgers", """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-09-01"}""");
        family = created.get("id").asLong();
        alicesMembership = created.get("memberId").asLong();
        long bobs = jdbc.sql("""
                INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date)
                VALUES (?, 'SHARED', ?, 'Dad', 'MEMBER', 'ACTIVE', DATE '2026-09-01') RETURNING id""")
                .params(family, bob).query(Long.class).single();
        long category = call(alice, HttpMethod.POST, "/api/family-ledgers/%d/categories".formatted(family), """
                {"code": "GROCERIES", "name": "Groceries", "type": "EXPENSE"}""").get("id").asLong();
        record = call(bob, HttpMethod.POST, "/api/family-ledgers/%d/records".formatted(family), """
                {"date": "2026-09-10", "categoryId": %d, "amount": "20", "payerMemberId": %d, "paymentLater": true}"""
                .formatted(category, bobs)).get("id").asLong();
        bobsOwn = call(bob, HttpMethod.POST, "/api/family-ledgers/%d/records".formatted(family), """
                {"date": "2026-09-11", "categoryId": %d, "amount": "5", "payerMemberId": %d, "paymentLater": true,
                 "split": {"method": "ONE_MEMBER", "memberId": %d}}""".formatted(category, bobs, bobs))
                .get("id").asLong();
    }

    @Test
    void theWriterRefusesWhatD8DoesntAllow() throws IOException {
        LedgerScope bobInHome = access.member(bob, family);
        long alicesCash = accountOf(alice, "CASH");
        long alicesDebt = accountOf(alice, "FAMILY_DEBT_" + family);
        long alicesUnallocated = accountOf(alice, "UNALLOCATED");
        long alicesHousing = jdbc.sql("SELECT id FROM category WHERE user_id = ? AND code = 'HOUSING'").param(alice)
                .query(Long.class).single();
        long familyCategory = jdbc.sql("SELECT id FROM category WHERE ledger_id = ?").param(family)
                .query(Long.class).single();
        String alicesRows = alicesRows();

        // One of Alice's own accounts, her cash, in a share of hers.
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.SHARE, EntryKind.FAMILY_SHARE, true,
                LocalDate.of(2026, 9, 10), List.of(line(alicesCash, "10.00", null), line(alicesDebt, "-10.00", null)), null),
                "account %d is not one it may post a SHARE to".formatted(alicesCash));
        // Her cash as the account of "her" payment, which only she can make.
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.PAYMENT, EntryKind.FAMILY_PAYMENT, false,
                LocalDate.of(2026, 9, 10), List.of(line(alicesCash, "-10.00", null), line(alicesDebt, "10.00", null)), null),
                "only a payment with the payer's own account is not the family budget's");
        // UNALLOCATED with one of her personal categories.
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.SHARE, EntryKind.FAMILY_SHARE, true,
                LocalDate.of(2026, 9, 10), List.of(line(alicesUnallocated, "10.00", alicesHousing),
                        line(alicesDebt, "-10.00", null)), null),
                "account %d is not one it may post a SHARE to".formatted(alicesUnallocated));
        // A kind that isn't the link's, and an entry that doesn't touch the debt account.
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.SHARE, EntryKind.FAMILY_PAYMENT, true,
                LocalDate.of(2026, 9, 10), List.of(line(alicesUnallocated, "10.00", familyCategory),
                        line(alicesDebt, "-10.00", null)), null), "a SHARE link takes an entry of kind FAMILY_SHARE");
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.SHARE, EntryKind.FAMILY_SHARE, true,
                LocalDate.of(2026, 9, 10), List.of(line(alicesUnallocated, "10.00", familyCategory),
                        line(alicesUnallocated, "-10.00", familyCategory)), null),
                "it posts nothing to the member's debt account");
        // A record of another family ledger.
        long elsewhere = call(alice, HttpMethod.POST, "/api/family-ledgers", """
                {"name": "Elsewhere", "baseCurrency": "EUR", "displayName": "Mum"}""").get("id").asLong();
        refused(bobInHome, new PostedEntry(alicesMembership, record + 1_000_000, LinkType.SHARE,
                EntryKind.FAMILY_SHARE, true, LocalDate.of(2026, 9, 10), List.of(line(alicesUnallocated, "10.00",
                        familyCategory), line(alicesDebt, "-10.00", null)), null),
                "record %d is not of family ledger %d".formatted(record + 1_000_000, family));
        // A note in her ledger: only the payer's own payment takes one, written by the payer (F4c).
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.PAYMENT, EntryKind.FAMILY_PAYMENT, false,
                LocalDate.of(2026, 9, 10), List.of(line(alicesCash, "-10.00", null), line(alicesDebt, "10.00", null)),
                "Bob's words"),
                "only the payer's own payment takes a note, their own");
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.SHARE, EntryKind.FAMILY_SHARE, true,
                LocalDate.of(2026, 9, 10), List.of(line(alicesUnallocated, "10.00", familyCategory),
                        line(alicesDebt, "-10.00", null)), "Bob's words"),
                "only the payer's own payment takes a note, their own");
        assertThat(elsewhere).isPositive();
        // Her cash as her side of a settlement, which only she puts on an account of hers (F4d, D-24), whether the
        // family budget or she would own it; and a note on a side, which no settlement takes.
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.SETTLEMENT,
                EntryKind.FAMILY_SETTLEMENT, false, LocalDate.of(2026, 9, 10), List.of(line(alicesCash, "10.00", null),
                        line(alicesDebt, "-10.00", null)), null),
                "only a payment with the payer's own account is not the family budget's");
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.SETTLEMENT,
                EntryKind.FAMILY_SETTLEMENT, true, LocalDate.of(2026, 9, 10), List.of(line(alicesCash, "10.00", null),
                        line(alicesDebt, "-10.00", null)), null),
                "account %d is not one it may post a SETTLEMENT to".formatted(alicesCash));
        refused(bobInHome, new PostedEntry(alicesMembership, record, LinkType.SETTLEMENT,
                EntryKind.FAMILY_SETTLEMENT, true, LocalDate.of(2026, 9, 10), List.of(line(alicesCash, "10.00", null),
                        line(alicesDebt, "-10.00", null)), "Bob's words"),
                "only the payer's own payment takes a note, their own");

        assertThat(alicesRows()).isEqualTo(alicesRows);
        // What D-8 allows goes through: a share on UNALLOCATED with the family's category and her debt account.
        transaction.executeWithoutResult(status -> {
            writer.write(bobInHome, new PostedEntry(alicesMembership, bobsOwn, LinkType.SHARE,
                    EntryKind.FAMILY_SHARE, true, LocalDate.of(2026, 9, 11), List.of(
                    line(alicesUnallocated, "1.00", familyCategory), line(alicesDebt, "-1.00", null)), null));
            status.setRollbackOnly();
        });
        assertThat(alicesRows()).isEqualTo(alicesRows);
    }

    /**
     * The settlement lock (D-28) at the writer: once Alice has put her side of Bob's settlement on her cash, nothing
     * Bob does reaches it. The writer refuses to replace or delete it as his, and the posting service refuses to
     * re-post a settlement whose amount or date changed past the record service's lock, or that was deleted; her rows
     * stay as they were.
     */
    @Test
    void anotherMembersSideOnTheirOwnAccountIsBeyondReach() throws IOException {
        long bobs = jdbc.sql("SELECT id FROM ledger_member WHERE ledger_id = ? AND user_sub = ?").params(family, bob)
                .query(Long.class).single();
        JsonNode settled = call(bob, HttpMethod.POST, "/api/family-ledgers/%d/settlements".formatted(family), """
                {"date": "2026-09-12", "amount": "10", "payerMemberId": %d, "payeeMemberId": %d, "paymentLater": true}"""
                .formatted(bobs, alicesMembership));
        long settlement = settled.get("id").asLong();
        String path = "/api/family-ledgers/%d/records/%d".formatted(family, settlement);
        long alicesCash = accountOf(alice, "CASH");
        call(alice, HttpMethod.PATCH, path + "?version=0", """
                {"paymentAccountId": %d}""".formatted(alicesCash));
        LedgerScope bobInHome = access.member(bob, family);
        CrossLedgerWriter.Link hers = writer.links(bobInHome, settlement).stream()
                .filter(link -> link.memberId() == alicesMembership).findFirst().orElseThrow();
        assertThat(hers.systemOwned()).isFalse();
        String alicesRows = alicesRows();
        String own = "it is member %d's own, on an account of theirs, and member %d acts".formatted(alicesMembership,
                bobs);

        // The writer itself: neither a replacement nor a deletion of her side, as Bob.
        long alicesDebt = accountOf(alice, "FAMILY_DEBT_" + family);
        long alicesPlaceholder = accountOf(alice, "UNSPECIFIED_PAYMENTS");
        PostedEntry back = new PostedEntry(alicesMembership, settlement, LinkType.SETTLEMENT,
                EntryKind.FAMILY_SETTLEMENT, true, LocalDate.of(2026, 9, 12), List.of(
                line(alicesPlaceholder, "10.00", null), line(alicesDebt, "-10.00", null)), null);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> writer.replace(bobInHome, hers, back)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(own);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> writer.delete(bobInHome, hers)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(own);

        // The posting service: a new amount, a new date or a deletion that got past the lock would move her side.
        String wouldChange = "member %d's side on an account of theirs would change through member %d's change"
                .formatted(alicesMembership, bobs);
        for (String change : List.of("base_amount = 12, original_amount = 12", "record_date = DATE '2026-09-13'",
                "deleted_at = now(), deleted_by_member_id = updated_by_member_id")) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("UPDATE family_record SET " + change + " WHERE id = ?").param(settlement).update();
                posting.post(bobInHome, settlement, new FamilyPostingService.Unchanged());
            })).as(change).isInstanceOf(IllegalStateException.class).hasMessageContaining(wouldChange);
        }
        assertThat(alicesRows()).isEqualTo(alicesRows);

        // Through the API, the lock answers first, and her side stays on her cash.
        var answer = mvc.method(HttpMethod.PATCH).uri(path + "?version=0").with(member(bob))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"amount": "12"}""").exchange();
        assertThat(answer.getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.method(HttpMethod.DELETE).uri(path + "?version=0").with(member(bob)).exchange()
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(alicesRows()).isEqualTo(alicesRows);
    }

    private void refused(LedgerScope scope, PostedEntry entry, String why) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> writer.write(scope, entry)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The family posting refuses an entry for member %d: %s"
                        .formatted(entry.memberId(), why));
    }

    private static Line line(long account, String amount, Long category) {
        return new Line(account, "EUR", new BigDecimal(amount), category);
    }

    private long accountOf(String user, String code) {
        return jdbc.sql("SELECT id FROM account WHERE user_id = ? AND code = ?").params(user, code)
                .query(Long.class).single();
    }

    /** Every row of Alice's personal ledger, as text: accounts, categories, entries and their postings. */
    private String alicesRows() {
        return jdbc.sql("""
                SELECT (SELECT string_agg(a::text, '|' ORDER BY a.id) FROM account a WHERE a.user_id = :sub)
                    || (SELECT string_agg(c::text, '|' ORDER BY c.id) FROM category c WHERE c.user_id = :sub)
                    || coalesce((SELECT string_agg(e::text, '|' ORDER BY e.id) FROM journal_entry e
                                 WHERE e.user_id = :sub), '')
                    || coalesce((SELECT string_agg(p::text, '|' ORDER BY p.id) FROM posting p
                                 JOIN journal_entry e ON e.id = p.entry_id WHERE e.user_id = :sub), '')""")
                .param("sub", alice).query(String.class).single();
    }

    private JsonNode call(String user, HttpMethod method, String uri, String body) throws IOException {
        var request = mvc.method(method).uri(uri).with(member(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        var result = request.exchange();
        assertThat(result.getResponse().getStatus()).as("%s %s", method, uri).isBetween(200, 299);
        return read(result, JsonNode.class);
    }
}
