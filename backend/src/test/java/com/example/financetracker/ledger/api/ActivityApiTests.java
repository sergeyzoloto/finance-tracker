package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * HIST-2 step 3 (D-999, D-1076, D-1079, D-1098 to D-1100): an event for each change of a setting, a manual rate, a
 * family budget's settings, a membership and an invite, written in the change's own transaction; the activity list's
 * scope, paging and erasure.
 */
class ActivityApiTests extends FamilyApiTest {

    private final String carol = newUser();

    /** The user's activity list, every page of 200. */
    private List<JsonNode> activity(String user) throws IOException {
        return elementsOf(ok(get(user, "/api/activity?size=200")).get("content"));
    }

    private static List<JsonNode> elementsOf(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private static List<JsonNode> ofKind(List<JsonNode> events, String kind) {
        return events.stream().filter(e -> e.get("kind").asText().equals(kind)).toList();
    }

    private long count(String sub, String kind) {
        return jdbc.sql("SELECT count(*) FROM activity_event WHERE actor_sub = ? AND kind = ?").param(sub).param(kind)
                .query(Long.class).single();
    }

    @Test
    void aSettingOrAManualRateWritesOneEventPerChangeWithOldAndNewAndNoneForAnUnchangedSave() throws IOException {
        ok(put(alice, "/api/settings/date-format", """
                {"dateFormat": "DD.MM.YYYY"}"""));
        ok(put(alice, "/api/settings/date-format", """
                {"dateFormat": "DD.MM.YYYY"}"""));
        ok(put(alice, "/api/settings", """
                {"baseCurrency": "EUR", "defaultShareRatio": "0.5"}"""));
        ok(put(alice, "/api/settings", """
                {"baseCurrency": "USD", "defaultShareRatio": "0.50"}"""));
        List<JsonNode> settings = ofKind(activity(alice), "SETTINGS_CHANGED");
        assertThat(settings).hasSize(2);
        JsonNode currency = settings.get(0).get("details").get("changes");
        assertThat(currency).hasSize(1);
        assertThat(currency.get(0).get("field").asText()).isEqualTo("baseCurrency");
        assertThat(currency.get(0).get("old").asText()).isEqualTo("EUR");
        assertThat(currency.get(0).get("new").asText()).isEqualTo("USD");
        JsonNode format = settings.get(1).get("details").get("changes").get(0);
        assertThat(format.get("field").asText()).isEqualTo("dateFormat");
        assertThat(format.get("old").isNull()).isTrue();
        assertThat(format.get("new").asText()).isEqualTo("DD.MM.YYYY");
        assertThat(settings.get(0).get("ledgerId").isNull()).isTrue();
        assertThat(settings.get(0).get("actor").get("kind").asText()).isEqualTo("USER");

        String rate = """
                {"date": "2026-09-10", "base": "EUR", "quote": "USD", "rate": "%s"}""";
        ok(post(alice, "/api/rates/manual", rate.formatted("1.1")));
        ok(post(alice, "/api/rates/manual", rate.formatted("1.10")));
        ok(post(alice, "/api/rates/manual", rate.formatted("1.2")));
        assertThat(delete(alice, "/api/rates/manual?date=2026-09-10&currency=USD")).hasStatus(HttpStatus.NO_CONTENT);
        List<JsonNode> events = activity(alice);
        assertThat(ofKind(events, "MANUAL_RATE_SET")).hasSize(1);
        JsonNode changed = ofKind(events, "MANUAL_RATE_CHANGED").get(0).get("details");
        assertThat(ofKind(events, "MANUAL_RATE_CHANGED")).hasSize(1);
        assertThat(new BigDecimal(changed.get("old").asText())).isEqualByComparingTo("1.1");
        assertThat(new BigDecimal(changed.get("new").asText())).isEqualByComparingTo("1.2");
        assertThat(changed.get("currency").asText()).isEqualTo("USD");
        JsonNode removed = ofKind(events, "MANUAL_RATE_REMOVED").get(0).get("details");
        assertThat(new BigDecimal(removed.get("old").asText())).isEqualByComparingTo("1.2");
        assertThat(removed.get("new").isNull()).isTrue();
    }

    @Test
    void aFamilyBudgetsSettingsMembershipsAndInvitesAreEventsOfItsLedger() throws IOException {
        ok(patch(alice, uri, """
                {"name": "Home"}"""));
        ok(patch(alice, uri, """
                {"name": "House"}"""));
        String token = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        accept(carol, token, "Cat");
        long revoked = created(post(alice, uri + "/invites", """
                {"kind": "NEW_MEMBER"}""")).get("id").asLong();
        assertThat(delete(alice, uri + "/invites/" + revoked)).hasStatus(HttpStatus.NO_CONTENT);
        String sam = newUser();
        accept(sam, kidsPlace(today().toString()), "Sam");

        List<JsonNode> events = activity(bob);
        JsonNode settings = ofKind(events, "LEDGER_SETTINGS_CHANGED").get(0);
        assertThat(ofKind(events, "LEDGER_SETTINGS_CHANGED")).hasSize(1);
        assertThat(settings.get("ledgerId").asLong()).isEqualTo(family);
        assertThat(settings.get("actor").get("memberId").asLong()).isEqualTo(mum);
        assertThat(settings.get("actor").get("name").asText()).isEqualTo("Mum");
        assertThat(settings.get("details").get("changes").get(0).get("new").asText()).isEqualTo("House");
        assertThat(ofKind(events, "INVITE_CREATED")).hasSize(3);
        assertThat(ofKind(events, "INVITE_REVOKED").get(0).get("details").get("inviteId").asLong()).isEqualTo(revoked);
        JsonNode joined = ofKind(events, "MEMBER_JOINED").get(0);
        assertThat(joined.get("actor").get("name").asText()).isEqualTo("Cat");
        assertThat(joined.get("subject").get("name").asText()).isEqualTo("Cat");
        JsonNode claimed = ofKind(events, "SEAT_CLAIMED").get(0);
        assertThat(claimed.get("subject").get("memberId").asLong()).isEqualTo(kid);
        assertThat(claimed.get("actor").get("name").asText()).isEqualTo("Sam");
        // Never the token.
        assertThat(events.toString()).doesNotContain(token);

        // The split rule: one event for the change, and a reset when a member with a share leaves.
        long cat = joined.get("subject").get("memberId").asLong();
        String shares = """
                {"rule": "CUSTOM", "shares": [{"memberId": %d, "share": 4000}, {"memberId": %d, "share": 3000},
                 {"memberId": %d, "share": 2000}, {"memberId": %d, "share": 1000}]}""".formatted(mum, dad, kid, cat);
        ok(put(alice, uri + "/split-rule", shares));
        ok(put(alice, uri + "/split-rule", shares));
        assertThat(delete(alice, uri + "/members/" + dad)).hasStatus(HttpStatus.NO_CONTENT);
        events = activity(alice);
        assertThat(ofKind(events, "LEDGER_SETTINGS_CHANGED")).hasSize(2);
        assertThat(ofKind(events, "LEDGER_SETTINGS_CHANGED").get(0).get("details").get("changes").get(1)
                .get("new").asText()).isEqualTo("CUSTOM");
        JsonNode reset = ofKind(events, "SPLIT_RULE_RESET").get(0);
        assertThat(ofKind(events, "SPLIT_RULE_RESET")).hasSize(1);
        assertThat(reset.get("actor").get("kind").asText()).isEqualTo("SYSTEM");
        assertThat(reset.get("actor").get("name").isNull()).isTrue();
        assertThat(reset.get("subject").get("memberId").asLong()).isEqualTo(dad);
        // The journal keeps its own row of it (D-1099).
        assertThat(jdbc.sql("SELECT count(*) FROM family_record_change WHERE ledger_id = ? AND action = "
                + "'SPLIT_RULE_RESET'").param(family).query(Long.class).single()).isEqualTo(1);
        JsonNode left = ofKind(events, "MEMBER_LEFT").get(0);
        assertThat(left.get("details").get("removed").asBoolean()).isTrue();
        assertThat(left.get("subject").get("name").asText()).isEqualTo("Dad");
    }

    @Test
    void aFailingEventRollsItsChangeBackAndAFailingChangeWritesNoEvent() throws IOException {
        String name = "test_refuse_" + Math.abs(alice.hashCode());
        jdbc.sql("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.actor_sub = '%s' THEN RAISE EXCEPTION 'refused'; END IF;
                    RETURN NEW;
                END $$""".formatted(name, alice)).update();
        jdbc.sql("CREATE TRIGGER %s BEFORE INSERT ON activity_event FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(name, name)).update();
        try {
            assertThat(put(alice, "/api/settings/date-format", """
                    {"dateFormat": "YYYY-MM-DD"}""")).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(patch(alice, uri, """
                    {"name": "Refused"}""")).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            jdbc.sql("DROP TRIGGER %s ON activity_event".formatted(name)).update();
            jdbc.sql("DROP FUNCTION %s()".formatted(name)).update();
        }
        assertThat(jdbc.sql("SELECT date_format IS NULL FROM user_settings WHERE user_id = ?").param(alice)
                .query(Boolean.class).single()).isTrue();
        assertThat(ok(get(alice, uri)).get("name").asText()).isEqualTo("Home");

        // A change that fails writes no event: Bob is no owner, and Alice has no account 0.
        assertThat(patch(bob, uri, """
                {"name": "Bob's"}""").getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(put(alice, "/api/settings", """
                {"baseCurrency": "USD", "sharedAccountId": 999999999, "defaultShareRatio": "0.5"}"""))
                .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(count(alice, "SETTINGS_CHANGED") + count(alice, "LEDGER_SETTINGS_CHANGED")
                + count(bob, "LEDGER_SETTINGS_CHANGED")).isZero();
    }

    @Test
    void theListShowsTheCallersOwnEventsAndThoseOfTheirActiveFamiliesOnlyInAStableOrder() throws IOException {
        ok(put(carol, "/api/settings/date-format", """
                {"dateFormat": "DD/MM/YYYY"}"""));
        for (int i = 0; i < 5; i++) {
            ok(patch(alice, uri, """
                    {"name": "Home %d"}""".formatted(i)));
        }
        ok(put(bob, "/api/settings/time-zone", """
                {"timeZone": "UTC"}"""));
        List<JsonNode> bobs = activity(bob);
        assertThat(bobs).extracting(e -> e.get("kind").asText()).containsOnly("LEDGER_SETTINGS_CHANGED",
                "SETTINGS_CHANGED");
        assertThat(ofKind(bobs, "LEDGER_SETTINGS_CHANGED")).hasSize(5);
        assertThat(ofKind(bobs, "SETTINGS_CHANGED")).hasSize(1);
        // Carol is in no family: her own event alone.
        assertThat(activity(carol)).hasSize(1).first().satisfies(e -> assertThat(e.get("kind").asText())
                .isEqualTo("SETTINGS_CHANGED"));

        // Paging: newest first, and the pages put together are the whole list.
        List<Long> all = bobs.stream().map(e -> e.get("id").asLong()).toList();
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode answer = ok(get(bob, "/api/activity?size=2&page=" + page));
            assertThat(answer.get("total").asLong()).isEqualTo(6);
            assertThat(answer.get("totalPages").asInt()).isEqualTo(3);
            answer.get("content").forEach(e -> paged.add(e.get("id").asLong()));
        }
        assertThat(paged).isEqualTo(all);
        assertThat(ok(get(bob, "/api/activity")).get("size").asInt()).isEqualTo(50);
        assertThat(get(bob, "/api/activity?size=201")).hasStatus(HttpStatus.BAD_REQUEST);

        // A member who left sees their own events, and no more of the family's.
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        ok(patch(alice, uri, """
                {"name": "After"}"""));
        List<JsonNode> after = activity(bob);
        assertThat(ofKind(after, "LEDGER_SETTINGS_CHANGED")).isEmpty();
        assertThat(ofKind(after, "MEMBER_LEFT")).hasSize(1);
        assertThat(ofKind(after, "SETTINGS_CHANGED")).hasSize(1);
    }

    @Test
    void erasureDeletesTheLeaversEventsAndKeepsTheOthersAboutThemAsAFormerMember() throws IOException {
        ok(post(alice, uri + "/members/" + dad + "/owner", null));
        ok(patch(bob, uri, """
                {"name": "Bob's home"}"""));
        ok(put(bob, "/api/settings/date-format", """
                {"dateFormat": "DD/MM/YYYY"}"""));
        assertThat(delete(alice, uri + "/members/" + dad)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(ofKind(activity(alice), "LEDGER_SETTINGS_CHANGED")).hasSize(1);

        assertThat(delete(bob, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT count(*) FROM activity_event WHERE actor_sub = ?").param(bob)
                .query(Long.class).single()).isZero();
        List<JsonNode> alices = activity(alice);
        assertThat(ofKind(alices, "LEDGER_SETTINGS_CHANGED")).isEmpty();
        JsonNode left = ofKind(alices, "MEMBER_LEFT").get(0);
        assertThat(left.get("subject").get("memberId").asLong()).isEqualTo(dad);
        assertThat(left.get("subject").get("name").asText()).isEqualTo("Former member");

        // Alice's own delete-all takes her rows too.
        assertThat(delete(alice, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.sql("SELECT count(*) FROM activity_event WHERE actor_sub = ?").param(alice)
                .query(Long.class).single()).isZero();
    }
}
