package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.family.FamilyInvariants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Family categories in personal ledgers (D-11; ADR 0003 topic F; F4a parts 5 and 6). Alice has two groceries entries
 * of her own, then creates the family budget "Home" with her GROCERIES, which merges it: her postings move to the
 * family category and her personal one goes. Bob is a member with an account since the start date, and has
 * groceries of his own too. Their personal category lists and reports show the family category, marked with "Home",
 * and its totals are their posted shares plus what they filed there themselves.
 */
class FamilyCategoryApiTests extends LedgerApiTest {

    private static final String SEPTEMBER = "?from=2026-09-01&to=2026-09-30";

    @Autowired
    private TransactionTemplate transactions;

    private final String alice = newUser();
    private final String bob = newUser();
    private long family;
    private String uri;
    private long mum;
    private long dad;
    private long groceries;
    private long alicesGroceries;
    private List<Long> alicesEntries;

    @BeforeEach
    void aFamilyMadeOfAlicesGroceries() throws IOException {
        ok(get(bob, "/api/accounts"));
        alicesGroceries = categoryId(alice, "GROCERIES");
        alicesEntries = List.of(newExpense(alice, "2026-09-02", "12.50", null, "Market").get("id").asLong(),
                newExpense(alice, "2026-09-03", "7.50", null, null).get("id").asLong());
        newExpense(bob, "2026-09-04", "4.00", null, "Bob's own");
        JsonNode created = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", "startDate": "2026-09-01",
                 "categoryIds": [%d]}""".formatted(alicesGroceries));
        family = created.get("id").asLong();
        uri = "/api/family-ledgers/" + family;
        mum = created.get("memberId").asLong();
        dad = join(family, bob, "Dad", "MEMBER", LocalDate.of(2026, 9, 1));
        groceries = ok(get(alice, uri + "/categories")).get(0).get("id").asLong();
    }

    /** The merge at creation: her postings on her GROCERIES now use the family's, and her GROCERIES is gone. */
    @Test
    void creatingAFamilyBudgetMergesTheChosenCategories() throws IOException {
        assertThat(jdbc.sql("SELECT count(*) FROM category WHERE id = ?").param(alicesGroceries).query(Long.class)
                .single()).isZero();
        for (long entry : alicesEntries) {
            JsonNode read = ok(get(alice, "/api/entries/" + entry));
            assertThat(read.get("postings").findValuesAsText("categoryId")).contains(String.valueOf(groceries));
            assertThat(read.get("version").asInt()).isZero();
            assertThat(read.get("family").isNull()).isTrue();
        }
        JsonNode listed = find(ok(get(alice, "/api/categories")), "code", "GROCERIES");
        assertThat(listed.get("id").asLong()).isEqualTo(groceries);
        assertThat(listed.get("familyLedgerName").asText()).isEqualTo("Home");
        assertThat(ok(get(alice, "/api/reports/integrity"))).isEmpty();
        FamilyInvariants.check(jdbc, family);
        // Her entry stays hers: she changes it with the family category, as with any category of hers.
        JsonNode changed = ok(put(alice, "/api/entries/" + alicesEntries.getFirst() + "?version=0", """
                {"kind": "EXPENSE", "entryDate": "2026-09-02", "accountId": %d, "currency": "EUR", "amount": "13",
                 "categoryId": %d}""".formatted(accountId(alice, "CASH"), groceries)));
        assertThat(changed.get("version").asInt()).isOne();
    }

    /**
     * Both personal lists show the family category once, marked; Bob's own GROCERIES stays beside it. Personal
     * endpoints don't rename or archive it (409 pointing to the family budget), and a personal category can't take a
     * family category's code.
     */
    @Test
    void personalListsShowTheFamilyCategoriesMarked() throws IOException {
        JsonNode bobs = ok(get(bob, "/api/categories"));
        List<String> groceryLines = new ArrayList<>();
        bobs.forEach(c -> {
            if (c.get("code").asText().equals("GROCERIES")) {
                groceryLines.add(c.get("id").asText() + " " + c.path("familyLedgerName").asText("-"));
            }
        });
        assertThat(groceryLines).containsExactly(categoryIdOfOwn(bob) + " -", groceries + " Home");
        assertThat(find(bobs, "code", "HOUSING").has("familyLedgerId")).isFalse();

        assertThat(body(patch(bob, "/api/categories/" + groceries, """
                {"name": "Mine", "archived": true}"""), HttpStatus.CONFLICT).get("detail").asText()).isEqualTo(
                "The category GROCERIES belongs to the family budget \"Home\"; rename or archive it there.");
        assertThat(ok(get(alice, uri + "/categories")).get(0).get("name").asText()).isEqualTo("Groceries");
        body(post(alice, uri + "/categories", """
                {"code": "SCHOOL", "name": "School", "type": "EXPENSE"}"""), HttpStatus.CREATED);
        assertThat(body(post(bob, "/api/categories", """
                {"code": "SCHOOL", "name": "My school", "type": "EXPENSE"}"""), HttpStatus.CONFLICT).get("detail").asText())
                .isEqualTo("The family budget \"Home\" has a category with the code SCHOOL; use it, or choose another code.");
        // A family category Carol doesn't see is missing to her, whatever she does with it.
        String carol = newUser();
        assertThat(patch(carol, "/api/categories/" + groceries, """
                {"name": "Mine"}""")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ok(get(carol, "/api/categories")).findValuesAsText("id")).doesNotContain(String.valueOf(groceries));
    }

    /**
     * The personal cash flow counts the family category, marked with its family budget: Alice's merged entries and her
     * posted share, and Bob's posted share and an entry of his own in it, apart from his own GROCERIES. The totals are
     * exactly those, in each currency and in the base currency.
     */
    @Test
    void personalReportsCountTheFamilyCategoriesMarked() throws IOException {
        body(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "60", "payerMemberId": %d, "paymentAccountId": %d,
                 "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                   {"memberId": %d, "basisPoints": 5000}]}}""".formatted(groceries, mum, accountId(alice, "CASH"), mum,
                dad)), HttpStatus.CREATED);
        newEntry(bob, """
                {"kind": "EXPENSE", "entryDate": "2026-09-11", "accountId": %d, "currency": "EUR", "amount": "2.25",
                 "categoryId": %d, "memo": "Bob's family groceries"}""".formatted(accountId(bob, "CASH"), groceries));

        assertThat(groceryRows(ok(get(alice, "/api/reports/cash-flow" + SEPTEMBER)))).containsExactly(
                "GROCERIES Home 50.00");
        assertThat(groceryRows(ok(get(bob, "/api/reports/cash-flow" + SEPTEMBER)))).containsExactly(
                "GROCERIES - 4.00", "GROCERIES Home 32.25");
        assertThat(groceryRows(ok(get(bob, "/api/reports/cash-flow" + SEPTEMBER + "&currency=BASE")).get("rows")))
                .containsExactly("GROCERIES - 4.00", "GROCERIES Home 32.25");
        // His family total is his posted share and his own entry, read from the postings.
        assertThat(jdbc.sql("""
                SELECT sum(p.amount) FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE e.user_id = ? AND p.category_id = ?""").params(bob, groceries).query(BigDecimal.class).single())
                .isEqualByComparingTo("32.25");
        assertThat(ok(get(alice, "/api/reports/integrity"))).isEmpty();
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();
        FamilyInvariants.check(jdbc, family);
    }

    /**
     * The integrity check (D-10) reports an ACTIVE membership whose debt account doesn't show the member's family
     * balance: here two shares changed past the triggers, one cent each way.
     */
    @Test
    void theIntegrityCheckReportsADebtAccountThatDiffersFromTheFamilyBalance() throws IOException {
        long record = body(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "10", "payerMemberId": %d, "paymentLater": true}"""
                .formatted(groceries, mum)), HttpStatus.CREATED).get("id").asLong();
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();

        transactions.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE family_share SET amount = amount + CASE member_id WHEN ? THEN 0.01 ELSE -0.01 END "
                    + "WHERE record_id = ?").params(dad, record).update();
        });

        assertThat(ok(get(bob, "/api/reports/integrity"))).isEqualTo(json.readTree("""
                [{"currency": "EUR", "postingSum": "0.00", "balanceSheetGap": "0.00", "familyLedgerId": %d,
                  "familyLedgerName": "Home", "debtBalance": "5.00", "familyBalance": "5.01"}]""".formatted(family)));
        assertThat(ok(get(alice, "/api/reports/integrity")).get(0).get("familyBalance").asText()).isEqualTo("-5.01");
        String carol = newUser();
        assertThat(ok(get(carol, "/api/reports/integrity"))).isEmpty();
    }

    /**
     * The demo loads for an ACTIVE member of a family budget whose ledger is otherwise empty, and touches no family
     * data. Its categories are always personal ones: a starter category that became a family category at a merge comes
     * back as a personal one beside it, and no demo entry uses a family category.
     */
    @Test
    void theDemoLoadsForAFamilyMemberAndKeepsItsCategoriesPersonal() throws IOException {
        String dave = newUser();
        ok(get(dave, "/api/accounts"));
        JsonNode davesFamily = newFamily(dave, """
                {"name": "Dave's home", "baseCurrency": "EUR", "displayName": "Dave", "startDate": "2026-09-01",
                 "categoryIds": [%d]}""".formatted(categoryId(dave, "GROCERIES")));
        long davesGroceries = ok(get(dave, "/api/family-ledgers/" + davesFamily.get("id").asLong() + "/categories"))
                .get(0).get("id").asLong();
        // Dave is in Alice's family too, with a share posted into his ledger.
        long daveInHome = join(family, dave, "Uncle", "MEMBER", LocalDate.of(2026, 9, 1));
        body(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "9", "payerMemberId": %d, "paymentLater": true,
                 "split": {"method": "ONE_MEMBER", "memberId": %d}}""".formatted(groceries, mum, daveInHome)),
                HttpStatus.CREATED);
        Map<String, String> familyRows = familyDigest();
        long postedToDave = jdbc.sql("""
                SELECT count(*) FROM family_entry_link l JOIN journal_entry e ON e.id = l.entry_id WHERE e.user_id = ?""")
                .param(dave).query(Long.class).single();
        assertThat(postedToDave).isOne();

        ok(post(dave, "/api/demo-data", null));

        assertThat(familyDigest()).isEqualTo(familyRows);
        FamilyInvariants.check(jdbc, family);
        JsonNode categories = ok(get(dave, "/api/categories"));
        List<String> groceryLines = new ArrayList<>();
        categories.forEach(c -> {
            if (c.get("code").asText().equals("GROCERIES")) {
                groceryLines.add(c.path("familyLedgerName").asText("personal"));
            }
        });
        assertThat(groceryLines).containsExactlyInAnyOrder("personal", "Dave's home", "Home");
        List<Long> familyCategories = List.of(groceries, davesGroceries);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM posting p JOIN journal_entry e ON e.id = p.entry_id
                WHERE e.user_id = ? AND p.category_id IN (?, ?)
                  AND NOT EXISTS (SELECT FROM family_entry_link l WHERE l.entry_id = e.id)""")
                .params(dave, familyCategories.get(0), familyCategories.get(1)).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM family_entry_link l JOIN journal_entry e ON e.id = l.entry_id WHERE e.user_id = ?""")
                .param(dave).query(Long.class).single()).isOne();
        assertThat(ok(get(dave, "/api/reports/integrity"))).isEmpty();
        // Loaded once, the ledger has entries of his own: a second load is refused as before.
        assertThat(post(dave, "/api/demo-data", null)).hasStatus(HttpStatus.CONFLICT);
    }

    /**
     * "Delete all my data" for members whose personal postings use family categories, merged ones included: Alice's
     * merged entries and posted share go with her data, and Bob keeps the family and his entries in it; then Bob, the
     * last member with an account, takes the family budget with him.
     */
    @Test
    void deleteAllWorksWithPostingsOnFamilyCategoriesMergedOnesIncluded() throws IOException {
        body(post(alice, uri + "/records", """
                {"date": "2026-09-10", "categoryId": %d, "amount": "20", "payerMemberId": %d, "paymentLater": true}"""
                .formatted(groceries, mum)), HttpStatus.CREATED);
        newEntry(bob, """
                {"kind": "EXPENSE", "entryDate": "2026-09-11", "accountId": %d, "currency": "EUR", "amount": "3",
                 "categoryId": %d}""".formatted(accountId(bob, "CASH"), groceries));

        assertThat(delete(alice, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rowsOf(alice)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        FamilyInvariants.check(jdbc, family);
        assertThat(ok(get(bob, "/api/reports/integrity"))).isEmpty();
        assertThat(find(ok(get(bob, "/api/categories")), "id", String.valueOf(groceries)).get("familyLedgerName")
                .asText()).isEqualTo("Home");

        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rowsOf(bob)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(jdbc.sql("SELECT count(*) FROM category WHERE id = ?").param(groceries).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ledger WHERE id = ?").param(family).query(Long.class).single())
                .isZero();
    }

    /** The grocery rows of a cash flow as "code family total". */
    private static List<String> groceryRows(JsonNode rows) {
        List<String> lines = new ArrayList<>();
        rows.forEach(row -> {
            if (row.get("categoryCode").asText().equals("GROCERIES")) {
                lines.add("GROCERIES " + row.path("familyLedgerName").asText("-") + " " + row.get("total").asText());
            }
        });
        return lines;
    }

    private long categoryIdOfOwn(String user) {
        return jdbc.sql("SELECT id FROM category WHERE user_id = ? AND code = 'GROCERIES'").param(user)
                .query(Long.class).single();
    }

    /** Every family table's rows, as text, to compare before and after. */
    private Map<String, String> familyDigest() {
        Map<String, String> digests = new java.util.LinkedHashMap<>();
        for (String table : List.of("ledger", "ledger_member", "family_record", "family_share", "family_entry_link",
                "family_record_change")) {
            digests.put(table, jdbc.sql("SELECT md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), '')) FROM "
                    + table + " t").query(String.class).single());
        }
        digests.put("family category", jdbc.sql("""
                SELECT md5(coalesce(string_agg(c::text, '|' ORDER BY c.id), ''))
                FROM category c JOIN ledger l ON l.id = c.ledger_id WHERE l.type = 'SHARED'""")
                .query(String.class).single());
        return digests;
    }
}
