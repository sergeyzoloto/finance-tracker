package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The family ledger endpoints (F3a; ADR 0003, topics B, C, F and I): what owners and members may do, members without
 * an account, the split rule and the family categories. Bob, a member with an account, is written with plain SQL,
 * since no code adds one before F5. {@link DataIsolationApiTests} checks users outside a family ledger.
 */
class FamilyLedgerApiTests extends LedgerApiTest {

    /** What a member who isn't an owner gets for an owner's action (D-15): 409, not 403. */
    private static final String OWNERS_ONLY = "Only an owner of the family budget can do this: owners manage its "
            + "settings, split rule and members, and rename, archive and delete its categories.";

    private final String alice = newUser();
    private final String bob = newUser();

    private long family;
    private long alicesMembership;
    private String uri;

    @BeforeEach
    void aliceCreatesAFamilyLedger() throws IOException {
        JsonNode created = newFamily(alice, """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna",
                 "categoryIds": [%d, %d]}""".formatted(categoryId(alice, "GROCERIES"), categoryId(alice, "SALARY")));
        family = created.get("id").asLong();
        alicesMembership = created.get("memberId").asLong();
        uri = "/api/family-ledgers/" + family;
    }

    @Test
    void theCreatorOwnsTheNewLedgerWithCopiesOfTheCategoriesTheyChose() throws IOException {
        JsonNode ledger = ok(get(alice, uri));
        assertThat(ledger.get("name").asText()).isEqualTo("Home");
        assertThat(ledger.get("baseCurrency").asText()).isEqualTo("EUR");
        assertThat(ledger.get("splitRule").asText()).isEqualTo("EQUAL");
        assertThat(ledger.get("role").asText()).isEqualTo("OWNER");
        assertThat(ledger.get("createdAt").isTextual()).isTrue();

        JsonNode members = ok(get(alice, uri + "/members"));
        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("id").asLong()).isEqualTo(alicesMembership);
        assertThat(members.get(0).get("displayName").asText()).isEqualTo("Anna");
        assertThat(members.get(0).get("role").asText()).isEqualTo("OWNER");
        assertThat(members.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(members.get(0).get("joinDate").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(members.get(0).get("hasAccount").asBoolean()).isTrue();
        assertThat(members.get(0).get("share").isNull()).isTrue();

        // Copies of code, name and type, with ids of their own; her personal categories stay as they are.
        JsonNode categories = ok(get(alice, uri + "/categories"));
        assertThat(categories.findValuesAsText("code")).containsExactly("GROCERIES", "SALARY");
        assertThat(categories.findValuesAsText("type")).containsExactly("EXPENSE", "INCOME");
        JsonNode personal = ok(get(alice, "/api/categories"));
        assertThat(personal.findValuesAsText("id")).doesNotContainAnyElementsOf(categories.findValuesAsText("id"));
        assertThat(personal.findValuesAsText("code")).contains("GROCERIES", "SALARY");
        assertThat(jdbc.sql("SELECT count(*) FROM category WHERE ledger_id = ? AND user_id IS NULL").param(family)
                .query(Long.class).single()).isEqualTo(2);

        // A second one, and the switcher's list by name, each with her role.
        long second = newFamily(alice, """
                {"name": "Allotment", "baseCurrency": "GBP", "displayName": "Anna", "splitRule": "CUSTOM"}""")
                .get("id").asLong();
        JsonNode list = ok(get(alice, "/api/family-ledgers"));
        assertThat(list.findValuesAsText("id")).containsExactly(String.valueOf(second), String.valueOf(family));
        assertThat(list.findValuesAsText("role")).containsExactly("OWNER", "OWNER");
        assertThat(ok(get(alice, "/api/family-ledgers/" + second + "/members")).get(0).get("share").asInt())
                .isEqualTo(10_000);
        assertThat(ok(get(bob, "/api/family-ledgers"))).isEmpty();
    }

    @Test
    void aNewLedgerIsCheckedBeforeAnythingIsWritten() throws IOException {
        long bobsGroceries = categoryId(bob, "GROCERIES");
        long ledgersBefore = jdbc.sql("SELECT count(*) FROM ledger").query(Long.class).single();

        assertThat(post(alice, "/api/family-ledgers", """
                {"name": " ", "baseCurrency": "EURO", "displayName": ""}""")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(post(alice, "/api/family-ledgers", """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna", "splitRule": "HALVES"}"""),
                HttpStatus.BAD_REQUEST).get("errors").findValuesAsText("field")).containsExactly("splitRule");
        // Another user's category answers like one that doesn't exist.
        JsonNode notFound = body(post(alice, "/api/family-ledgers", """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Anna", "categoryIds": [%d]}"""
                .formatted(bobsGroceries)), HttpStatus.NOT_FOUND);
        assertThat(notFound.get("detail").asText()).isEqualTo("Category %d not found.".formatted(bobsGroceries));

        assertThat(jdbc.sql("SELECT count(*) FROM ledger").query(Long.class).single()).isEqualTo(ledgersBefore);
    }

    @Test
    void ownersRenameTheLedgerAndChangeItsBaseCurrency() throws IOException {
        JsonNode renamed = ok(patch(alice, uri, """
                {"name": " Our home ", "baseCurrency": "USD"}"""));
        assertThat(renamed.get("name").asText()).isEqualTo("Our home");
        assertThat(renamed.get("baseCurrency").asText()).isEqualTo("USD");
        assertThat(ok(patch(alice, uri, "{}")).get("name").asText()).isEqualTo("Our home");
        assertThat(body(patch(alice, uri, """
                {"name": " ", "baseCurrency": "usd"}"""), HttpStatus.BAD_REQUEST).get("errors")
                .findValuesAsText("field")).containsExactlyInAnyOrder("name", "baseCurrency");
        // Her personal base currency is another matter.
        assertThat(ok(get(alice, "/api/settings")).get("baseCurrency").asText()).isEqualTo("EUR");
    }

    @Test
    void ownersManageMembersWithoutAnAccount() throws IOException {
        JsonNode kid = body(post(alice, uri + "/members", """
                {"displayName": " Kid "}"""), HttpStatus.CREATED);
        assertThat(kid.get("displayName").asText()).isEqualTo("Kid");
        assertThat(kid.get("role").asText()).isEqualTo("MEMBER");
        assertThat(kid.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(kid.get("hasAccount").asBoolean()).isFalse();
        assertThat(kid.get("share").isNull()).isTrue();
        long kidId = kid.get("id").asLong();
        String kidUri = uri + "/members/" + kidId;

        // Display names are one per person, whatever their case.
        assertThat(detail(post(alice, uri + "/members", """
                {"displayName": "KID"}"""), HttpStatus.CONFLICT))
                .isEqualTo("The family budget has a member named KID already.");
        assertThat(detail(patch(alice, kidUri, """
                {"displayName": "anna"}"""), HttpStatus.CONFLICT))
                .isEqualTo("The family budget has a member named anna already.");
        assertThat(ok(patch(alice, kidUri, """
                {"displayName": "kid"}""")).get("displayName").asText()).isEqualTo("kid");
        // A member with an account chooses their own name, and isn't removed here.
        String alicesUri = uri + "/members/" + alicesMembership;
        assertThat(detail(patch(alice, alicesUri, """
                {"displayName": "Mum"}"""), HttpStatus.CONFLICT)).isEqualTo("Anna has an account: they choose their own name.");
        assertThat(detail(delete(alice, alicesUri), HttpStatus.CONFLICT))
                .isEqualTo("Anna has an account: only members without an account can be removed so far.");
        // A member of no ledger of hers, or of another one of hers, is missing.
        long other = newFamily(alice, """
                {"name": "Allotment", "baseCurrency": "EUR", "displayName": "Anna"}""").get("id").asLong();
        long neighbour = body(post(alice, "/api/family-ledgers/" + other + "/members", """
                {"displayName": "Neighbour"}"""), HttpStatus.CREATED).get("id").asLong();
        assertThat(detail(patch(alice, uri + "/members/" + neighbour, """
                {"displayName": "Mine"}"""), HttpStatus.NOT_FOUND)).isEqualTo("Member %d not found.".formatted(neighbour));
        assertThat(detail(delete(alice, uri + "/members/" + neighbour), HttpStatus.NOT_FOUND))
                .isEqualTo("Member %d not found.".formatted(neighbour));

        assertThat(delete(alice, kidUri)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("displayName")).containsExactly("Anna");
        assertThat(ok(get(alice, "/api/family-ledgers/" + other + "/members")).findValuesAsText("displayName"))
                .containsExactly("Anna", "Neighbour");
    }

    /** Any ACTIVE member with an account changes the name the others see, and only their own (D-3). */
    @Test
    void aMemberWithAnAccountChangesTheirOwnDisplayName() throws IOException {
        long bobsMembership = join(family, bob, "Ben", "MEMBER", LocalDate.now());
        long kid = memberId(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""));
        String me = uri + "/members/me";

        JsonNode renamed = ok(patch(bob, me, """
                {"displayName": " Dad "}"""));
        assertThat(renamed.get("id").asLong()).isEqualTo(bobsMembership);
        assertThat(renamed.get("displayName").asText()).isEqualTo("Dad");
        assertThat(renamed.get("role").asText()).isEqualTo("MEMBER");
        assertThat(renamed.get("hasAccount").asBoolean()).isTrue();
        // An owner too, and a change of case of one's own name is no clash.
        assertThat(ok(patch(alice, me, """
                {"displayName": "ANNA"}""")).get("id").asLong()).isEqualTo(alicesMembership);
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("displayName")).containsExactly("ANNA", "Dad", "Kid");

        // The same rules as for any display name: not blank, and one per person, whatever the case.
        assertThat(body(patch(bob, me, """
                {"displayName": "  "}"""), HttpStatus.BAD_REQUEST).get("errors").findValuesAsText("field"))
                .containsExactly("displayName");
        assertThat(detail(patch(bob, me, """
                {"displayName": "kid"}"""), HttpStatus.CONFLICT)).isEqualTo("The family budget has a member named kid already.");
        assertThat(detail(patch(bob, me, """
                {"displayName": "anna"}"""), HttpStatus.CONFLICT)).isEqualTo("The family budget has a member named anna already.");
        assertThat(ok(get(bob, uri + "/members")).findValuesAsText("displayName")).containsExactly("ANNA", "Dad", "Kid");
        assertThat(jdbc.sql("SELECT display_name FROM ledger_member WHERE id = ?").param(kid).query(String.class)
                .single()).isEqualTo("Kid");

        // A member who left has no way in.
        jdbc.sql("UPDATE ledger_member SET status = 'LEFT', left_date = current_date WHERE id = ?")
                .param(bobsMembership).update();
        assertThat(detail(patch(bob, me, """
                {"displayName": "Dad again"}"""), HttpStatus.NOT_FOUND)).isEqualTo("Family budget %d not found.".formatted(family));
        assertThat(jdbc.sql("SELECT display_name FROM ledger_member WHERE id = ?").param(bobsMembership)
                .query(String.class).single()).isEqualTo("Dad");
    }

    @Test
    void theCustomSplitRuleCoversTheActiveMembersAndSumsTo10000() throws IOException {
        long kid = memberId(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""));
        String custom = """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": %d}, {"memberId": %d, "share": %d}]}""";

        JsonNode members = ok(put(alice, uri + "/split-rule", custom.formatted(alicesMembership, 6667, kid, 3333)));
        assertThat(members.findValuesAsText("share")).containsExactly("6667", "3333");
        assertThat(ok(get(alice, uri)).get("splitRule").asText()).isEqualTo("CUSTOM");
        // A member added under a custom rule gets 0 until an owner changes it.
        long baby = memberId(post(alice, uri + "/members", """
                {"displayName": "Baby"}"""));
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("share")).containsExactly("6667", "3333", "0");

        assertThat(violations(put(alice, uri + "/split-rule", custom.formatted(alicesMembership, 6000, kid, 3000))))
                .containsExactly("Baby has no share", "the shares sum to 90.00 %, not 100.00 %");
        // The same violations with a code, and the member each is about, for the interface to place them.
        assertThat(body(put(alice, uri + "/split-rule", custom.formatted(alicesMembership, 6000, kid, 3000)),
                HttpStatus.UNPROCESSABLE_ENTITY).get("violationDetails")).isEqualTo(json.readTree("""
                [{"code": "NO_SHARE", "memberId": %d, "message": "Baby has no share"},
                 {"code": "SUM_NOT_WHOLE", "memberId": null, "message": "the shares sum to 90.00 %%, not 100.00 %%"}]"""
                .formatted(baby)));
        assertThat(violations(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 10000}, {"memberId": %d, "share": 0},
                 {"memberId": %d, "share": 0}, {"memberId": 9000000000, "share": 0}]}"""
                .formatted(alicesMembership, kid, baby)))).containsExactly(
                        "Member 9000000000 is not an active member of the family budget");
        assertThat(violations(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 5000}, {"memberId": %d, "share": 5000}]}"""
                .formatted(kid, kid)))).containsExactly("Member %d has more than one share".formatted(kid));
        assertThat(violations(put(alice, uri + "/split-rule", """
                {"rule": "EQUAL", "shares": [{"memberId": %d, "share": 10000}]}""".formatted(alicesMembership))))
                .containsExactly("an equal split takes no shares");
        assertThat(body(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 10001}, {"memberId": %d, "share": -1}]}"""
                .formatted(alicesMembership, kid)), HttpStatus.BAD_REQUEST).get("errors").findValuesAsText("field"))
                .containsExactly("shares[0].share", "shares[1].share");
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("share")).containsExactly("6667", "3333", "0");

        // A member with a share of the custom rule stays until an owner gives it to others.
        assertThat(detail(delete(alice, uri + "/members/" + kid), HttpStatus.CONFLICT)).isEqualTo(
                "Kid has a share of 33.33 % in the custom split rule; change the rule to give them 0 first.");
        assertThat(delete(alice, uri + "/members/" + baby)).hasStatus(HttpStatus.NO_CONTENT);
        ok(put(alice, uri + "/split-rule", """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 10000}, {"memberId": %d, "share": 0}]}"""
                .formatted(alicesMembership, kid)));
        assertThat(delete(alice, uri + "/members/" + kid)).hasStatus(HttpStatus.NO_CONTENT);

        // Equal shares follow the members by themselves.
        assertThat(ok(put(alice, uri + "/split-rule", """
                {"rule": "EQUAL"}""")).findValuesAsText("share")).containsExactly("null");
        assertThat(ok(get(alice, uri)).get("splitRule").asText()).isEqualTo("EQUAL");
    }

    @Test
    void anyMemberAddsFamilyCategoriesAndOwnersManageThem() throws IOException {
        join(family, bob, "Ben", "MEMBER", LocalDate.now());
        JsonNode bobs = body(post(bob, uri + "/categories", """
                {"code": "HOLIDAYS", "name": "Holidays", "type": "EXPENSE"}"""), HttpStatus.CREATED);
        long holidays = bobs.get("id").asLong();
        String holidaysUri = uri + "/categories/" + holidays;
        assertThat(jdbc.sql("SELECT user_id IS NULL FROM category WHERE id = ?").param(holidays).query(Boolean.class)
                .single()).isTrue();
        assertThat(ok(get(alice, uri + "/categories")).findValuesAsText("code"))
                .containsExactly("GROCERIES", "HOLIDAYS", "SALARY");
        // Not in anybody's personal list before F4a.
        assertThat(ok(get(bob, "/api/categories")).findValuesAsText("code")).doesNotContain("HOLIDAYS");

        assertThat(detail(post(alice, uri + "/categories", """
                {"code": "HOLIDAYS", "name": "Trips", "type": "EXPENSE"}"""), HttpStatus.CONFLICT))
                .isEqualTo("The family budget has a category with the code HOLIDAYS already.");
        assertThat(body(post(bob, uri + "/categories", """
                {"code": "holidays", "name": "Trips", "type": "EXPENSE"}"""), HttpStatus.BAD_REQUEST).get("errors")
                .findValuesAsText("field")).containsExactly("code");
        // The same code as a personal category of hers is no clash.
        assertThat(post(alice, "/api/categories", """
                {"code": "HOLIDAYS", "name": "My holidays", "type": "EXPENSE"}""")).hasStatus(HttpStatus.CREATED);

        // Renaming, archiving and deleting are the owners'.
        for (MvcTestResult refused : List.of(patch(bob, holidaysUri, """
                {"name": "Trips"}"""), patch(bob, holidaysUri, """
                {"archived": true}"""), delete(bob, holidaysUri))) {
            assertThat(detail(refused, HttpStatus.CONFLICT)).isEqualTo(OWNERS_ONLY);
        }
        JsonNode renamed = ok(patch(alice, holidaysUri, """
                {"name": "Trips", "archived": true}"""));
        assertThat(renamed.get("name").asText()).isEqualTo("Trips");
        assertThat(renamed.get("archived").asBoolean()).isTrue();
        assertThat(detail(patch(alice, holidaysUri, """
                {"type": "INCOME"}"""), HttpStatus.CONFLICT))
                .isEqualTo("The category HOLIDAYS is EXPENSE, and a category's type can't be changed.");
        assertThat(delete(alice, holidaysUri)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(detail(delete(alice, holidaysUri), HttpStatus.NOT_FOUND))
                .isEqualTo("Category %d not found.".formatted(holidays));
        // A personal category can't be reached through a family ledger.
        long alicesOwn = categoryId(alice, "GROCERIES");
        assertThat(detail(delete(alice, uri + "/categories/" + alicesOwn), HttpStatus.NOT_FOUND))
                .isEqualTo("Category %d not found.".formatted(alicesOwn));
        assertThat(ok(get(alice, "/api/categories")).findValuesAsText("code")).contains("GROCERIES");
    }

    /** Every owner's action answers 409 to a member, and changes nothing (D-15); reading and adding categories don't. */
    @Test
    void aMemberWhoIsNotAnOwnerGets409ForEveryOwnersAction() throws IOException {
        join(family, bob, "Ben", "MEMBER", LocalDate.now());
        long kid = memberId(post(alice, uri + "/members", """
                {"displayName": "Kid"}"""));
        long groceries = find(ok(get(alice, uri + "/categories")), "code", "GROCERIES").get("id").asLong();
        String before = state();

        for (MvcTestResult refused : List.of(
                patch(bob, uri, """
                        {"name": "Bob's now"}"""),
                put(bob, uri + "/split-rule", """
                        {"rule": "EQUAL"}"""),
                post(bob, uri + "/members", """
                        {"displayName": "Bob's friend"}"""),
                patch(bob, uri + "/members/" + kid, """
                        {"displayName": "Bob's kid"}"""),
                delete(bob, uri + "/members/" + kid),
                patch(bob, uri + "/categories/" + groceries, """
                        {"name": "Food"}"""),
                delete(bob, uri + "/categories/" + groceries))) {
            assertThat(detail(refused, HttpStatus.CONFLICT)).as(refused.getRequest().getMethod() + " "
                    + refused.getRequest().getRequestURI()).isEqualTo(OWNERS_ONLY);
        }
        assertThat(state()).isEqualTo(before);

        JsonNode ledger = ok(get(bob, uri));
        assertThat(ledger.get("role").asText()).isEqualTo("MEMBER");
        assertThat(ok(get(bob, uri + "/members")).findValuesAsText("displayName")).containsExactly("Anna", "Ben", "Kid");
        assertThat(ok(get(bob, "/api/family-ledgers")).findValuesAsText("id")).containsExactly(String.valueOf(family));
    }

    /** The ledger's rows, to compare before and after. */
    private String state() {
        return jdbc.sql("""
                SELECT (SELECT string_agg(l::text, '|') FROM ledger l WHERE l.id = :family)
                    || (SELECT string_agg(m::text, '|' ORDER BY m.id) FROM ledger_member m WHERE m.ledger_id = :family)
                    || (SELECT string_agg(c::text, '|' ORDER BY c.id) FROM category c WHERE c.ledger_id = :family)""")
                .param("family", family).query(String.class).single();
    }

    private long memberId(MvcTestResult created) throws IOException {
        return body(created, HttpStatus.CREATED).get("id").asLong();
    }

    private String detail(MvcTestResult result, HttpStatus status) throws IOException {
        return body(result, status).get("detail").asText();
    }

    private List<String> violations(MvcTestResult result) throws IOException {
        List<String> violations = new ArrayList<>();
        body(result, HttpStatus.UNPROCESSABLE_ENTITY).get("violations").forEach(v -> violations.add(v.asText()));
        return violations;
    }
}
