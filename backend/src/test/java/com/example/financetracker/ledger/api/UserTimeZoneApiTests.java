package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.StreamSupport;

import com.example.financetracker.TestClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * D-100, D-101: each user has a time zone, and D-53's today is the date there; UTC's while none is set. The clock is
 * set to instants at which the dates of the zones differ, and every "today" of the api is read for users in each zone:
 * {@code /api/me}, the reports' default day, the rates that apply today, a family budget's start date, a new member's
 * and a claim's join date, a member's leaving date, the "≈" totals and the demo's last day. In a family budget the
 * acting member's zone counts. The class extends {@link FamilyApiTest} for its invite helpers; every test uses users
 * and budgets of its own.
 */
class UserTimeZoneApiTests extends FamilyApiTest {

    private static final String UTC = "UTC";
    private static final String AMSTERDAM = "Europe/Amsterdam";
    private static final String LOS_ANGELES = "America/Los_Angeles";
    private static final String PLUS_14 = "Pacific/Kiritimati";
    private static final String MINUS_12 = "Etc/GMT+12";

    @Autowired
    private TestClock clock;

    @AfterEach
    void reset() {
        clock.reset();
    }

    /** A zone, or none, at an instant, and the date there by hand (for the unset zone, the UTC date). */
    private record Case(String zone, String instant, String today) {
    }

    private static final List<Case> CASES = List.of(
            // 23:30 UTC on 6 October: Amsterdam (UTC+2) and UTC+14 are on the 7th, the rest on the 6th.
            new Case(null, "2026-10-06T23:30:00Z", "2026-10-06"),
            new Case(UTC, "2026-10-06T23:30:00Z", "2026-10-06"),
            new Case(AMSTERDAM, "2026-10-06T23:30:00Z", "2026-10-07"),
            new Case(LOS_ANGELES, "2026-10-06T23:30:00Z", "2026-10-06"),
            new Case(PLUS_14, "2026-10-06T23:30:00Z", "2026-10-07"),
            new Case(MINUS_12, "2026-10-06T23:30:00Z", "2026-10-06"),
            // 03:00 UTC on the 7th: Los Angeles (UTC-7) and UTC-12 are still on the 6th.
            new Case(null, "2026-10-07T03:00:00Z", "2026-10-07"),
            new Case(UTC, "2026-10-07T03:00:00Z", "2026-10-07"),
            new Case(AMSTERDAM, "2026-10-07T03:00:00Z", "2026-10-07"),
            new Case(LOS_ANGELES, "2026-10-07T03:00:00Z", "2026-10-06"),
            new Case(PLUS_14, "2026-10-07T03:00:00Z", "2026-10-07"),
            new Case(MINUS_12, "2026-10-07T03:00:00Z", "2026-10-06"));

    private void at(String instant) {
        clock.set(Instant.parse(instant), ZoneOffset.UTC);
    }

    private JsonNode setZone(String user, String zone) throws IOException {
        return ok(put(user, "/api/settings/time-zone", "{\"timeZone\": \"%s\"}".formatted(zone)));
    }

    /** F8c step 1's finding: with no zone set, the api's today is UTC's, which a browser west of UTC is behind. */
    @Test
    void withNoZoneAWesternBrowsersOwnTodayIsBeforeTheStartDateItLeftOut() throws IOException {
        String user = newUser();
        at("2026-10-07T03:00:00Z");
        JsonNode created = newFamily(user, familyRequest(user, ""));
        assertThat(created.get("startDate").asText()).isEqualTo("2026-10-07");
        assertThat(detail(post(user, "/api/family-ledgers/" + created.get("id").asLong() + "/records",
                record(user, created, "2026-10-06")), HttpStatus.CONFLICT))
                .isEqualTo("The family budget starts on 2026-10-07, and an expense can't be dated before its start date.");
    }

    /**
     * F8c step 1, fixed: the browser's zone is saved on the first load, so the same evening in Los Angeles is the 6th
     * for the api too; a budget whose start date was left out starts on the 6th, and a record of that day is accepted.
     */
    @Test
    void aBrowserWestOfUtcCanRecordOnItsOwnToday() throws IOException {
        String user = newUser();
        at("2026-10-07T03:00:00Z");
        JsonNode me = ok(get(user, "/api/me"));
        assertThat(me.get("timeZone").isNull()).isTrue();
        assertThat(me.get("today").asText()).isEqualTo("2026-10-07");

        JsonNode saved = setZone(user, LOS_ANGELES);
        assertThat(saved.get("timeZone").asText()).isEqualTo(LOS_ANGELES);
        assertThat(saved.get("today").asText()).isEqualTo("2026-10-06");
        me = ok(get(user, "/api/me"));
        assertThat(me.get("timeZone").asText()).isEqualTo(LOS_ANGELES);
        assertThat(me.get("today").asText()).isEqualTo("2026-10-06");

        JsonNode created = newFamily(user, familyRequest(user, ""));
        assertThat(created.get("startDate").asText()).isEqualTo("2026-10-06");
        created(post(user, "/api/family-ledgers/" + created.get("id").asLong() + "/records",
                record(user, created, "2026-10-06")));
    }

    @Test
    void everyDefaultDateIsTheUsersToday() throws IOException {
        for (Case c : CASES) {
            String user = newUser();
            String where = c.zone() + " at " + c.instant();
            at(c.instant());
            if (c.zone() != null) {
                assertThat(setZone(user, c.zone()).get("today").asText()).as(where + ": the answer's today")
                        .isEqualTo(c.today());
            }
            LocalDate today = LocalDate.parse(c.today());

            JsonNode me = ok(get(user, "/api/me"));
            assertThat(me.get("today").asText()).as(where + ": /api/me's today").isEqualTo(c.today());
            assertThat(me.get("timeZone").isNull()).as(where + ": /api/me's zone").isEqualTo(c.zone() == null);

            // The reports' default day: an entry of today is in the balances, one of tomorrow isn't (before asOf).
            long cash = accountId(user, "CASH");
            long groceries = categoryId(user, "GROCERIES");
            for (LocalDate day : List.of(today, today.plusDays(1))) {
                newEntry(user, """
                        {"kind": "EXPENSE", "entryDate": "%s", "accountId": %d, "currency": "EUR", "amount": "10",
                         "categoryId": %d}""".formatted(day, cash, groceries));
            }
            assertThat(cashBalance(ok(get(user, "/api/reports/balances")))).as(where + ": balances")
                    .isEqualTo("-10.00");
            assertThat(cashBalance(ok(get(user, "/api/reports/balances?currency=BASE")))).as(where + ": in base")
                    .isEqualTo("-10.00");
            assertThat(ok(get(user, "/api/reports/net-worth?asOf=" + today)).findValuesAsText("netWorth"))
                    .as(where + ": net worth").isEqualTo(ok(get(user, "/api/reports/net-worth")).findValuesAsText(
                            "netWorth"));

            // The rates page: a rate of today applies, one of tomorrow doesn't yet.
            ok(post(user, "/api/rates/manual", """
                    {"date": "%s", "base": "EUR", "quote": "RUB", "rate": "95.50"}""".formatted(today)));
            ok(post(user, "/api/rates/manual", """
                    {"date": "%s", "base": "EUR", "quote": "KZT", "rate": "550"}""".formatted(today.plusDays(1))));
            JsonNode latest = ok(get(user, "/api/rates")).get("latest");
            assertThat(applies(latest, "RUB")).as(where + ": today's rate applies").isTrue();
            assertThat(applies(latest, "KZT")).as(where + ": tomorrow's rate doesn't").isFalse();

            // A family budget: its start date, a new member's join date, a claim's, and what is in the future.
            JsonNode created = newFamily(user, familyRequest(user, ""));
            long family = created.get("id").asLong();
            String uri = "/api/family-ledgers/" + family;
            assertThat(created.get("startDate").asText()).as(where + ": start date").isEqualTo(c.today());
            JsonNode future = body(post(user, "/api/family-ledgers", familyRequest(user, "\"startDate\": \"%s\","
                    .formatted(today.plusDays(1)))), HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(future.get("violationDetails").get(0).get("code").asText()).as(where).isEqualTo("START_DATE");
            long kid = body(post(user, uri + "/members", "{\"displayName\": \"Kid\"}"), HttpStatus.CREATED)
                    .get("id").asLong();
            assertThat(find(ok(get(user, uri + "/members")), "id", String.valueOf(kid)).get("joinDate").asText())
                    .as(where + ": new member").isEqualTo(c.today());
            assertThat(created(post(user, uri + "/invites", """
                    {"kind": "CLAIM", "seatMemberId": %d}""".formatted(kid))).get("joinDate").asText())
                    .as(where + ": claim").isEqualTo(c.today());
            body(post(user, uri + "/invites", """
                    {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "%s"}""".formatted(kid, today.plusDays(1))),
                    HttpStatus.UNPROCESSABLE_ENTITY);
            created(post(user, uri + "/invites", """
                    {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "%s"}""".formatted(kid, today)));
        }
    }

    /** The demo's last day is the user's today. */
    @Test
    void theDemoEndsOnTheUsersToday() throws IOException {
        at("2026-10-06T23:30:00Z");
        for (String[] c : new String[][] {{LOS_ANGELES, "2026-10-06"}, {PLUS_14, "2026-10-07"}, {null, "2026-10-06"}}) {
            String user = newUser();
            if (c[0] != null) {
                setZone(user, c[0]);
            }
            JsonNode demo = ok(post(user, "/api/demo-data", null));
            assertThat(demo.get("to").asText()).as(String.valueOf(c[0])).isEqualTo(c[1]);
            JsonNode newest = ok(get(user, "/api/entries?size=1")).get("content").get(0);
            assertThat(LocalDate.parse(newest.get("entryDate").asText())).isBeforeOrEqualTo(LocalDate.parse(c[1]));
            // The demo's family budget starts in the user's zone too, six months back from its last day.
            assertThat(ok(get(user, "/api/family-ledgers/" + demo.get("familyLedgerId").asLong())).get("startDate")
                    .asText()).startsWith("2026-04-0");
        }
    }

    /**
     * Two members, two zones, one instant: 03:00 UTC on 7 October is the 7th for Alice at UTC+14 and the evening of
     * the 6th for Bob in Los Angeles. Each action takes its actor's date: Alice starts the budget on the 7th, Bob
     * joins on the 6th, and a record of the 6th is before the start date, whoever makes it (D-27).
     */
    @Test
    void membersInDifferentZonesEachActOnTheirOwnToday() throws IOException {
        String alice = newUser();
        String bob = newUser();
        ok(get(bob, "/api/accounts"));
        setZone(alice, PLUS_14);
        setZone(bob, LOS_ANGELES);
        at("2026-10-07T03:00:00Z");
        JsonNode created = newFamily(alice, familyRequest(alice, ""));
        long family = created.get("id").asLong();
        String uri = "/api/family-ledgers/" + family;
        long mum = created.get("memberId").asLong();
        assertThat(created.get("startDate").asText()).isEqualTo("2026-10-07");

        String token = inviteTo(alice, uri, "{\"kind\": \"NEW_MEMBER\"}");
        JsonNode joined = accept(bob, token, "Bob");
        assertThat(joined.get("id").asLong()).isEqualTo(family);
        JsonNode members = ok(get(alice, uri + "/members"));
        assertThat(find(members, "displayName", "Mum").get("joinDate").asText()).isEqualTo("2026-10-07");
        assertThat(find(members, "displayName", "Bob").get("joinDate").asText()).isEqualTo("2026-10-06");

        long groceries = find(ok(get(alice, uri + "/categories")), "code", "GROCERIES").get("id").asLong();
        long bobsId = find(members, "displayName", "Bob").get("id").asLong();
        String request = """
                {"date": "%s", "categoryId": %d, "amount": "10.00", "payerMemberId": %d, "paymentLater": true}""";
        assertThat(post(bob, uri + "/records", request.formatted("2026-10-06", groceries, bobsId)))
                .hasStatus(HttpStatus.CONFLICT);
        created(post(bob, uri + "/records", request.formatted("2026-10-07", groceries, bobsId)));
        created(post(alice, uri + "/records", request.formatted("2026-10-07", groceries, mum)));

        // The balances' "≈" total is as of the reader's own today.
        assertThat(ok(get(alice, uri + "/balances")).get("total").get("asOf").asText()).isEqualTo("2026-10-07");
        assertThat(ok(get(bob, uri + "/balances")).get("total").get("asOf").asText()).isEqualTo("2026-10-06");
        // A new invite for Bob's place, and the leaving date: whoever acts.
        long kid = body(post(alice, uri + "/members", "{\"displayName\": \"Kid\"}"), HttpStatus.CREATED)
                .get("id").asLong();
        assertThat(created(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d}""".formatted(kid))).get("joinDate").asText())
                .isEqualTo("2026-10-07");
        // Bob leaves on his own 6th; Carol, whom Alice then removes, on Alice's 7th.
        String carol = newUser();
        ok(get(carol, "/api/accounts"));
        setZone(carol, MINUS_12);
        long carolId = join(family, carol, "Carol", "MEMBER", LocalDate.of(2026, 10, 7));
        assertThat(delete(alice, uri + "/members/" + carolId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(bob, uri + "/members/me")).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode after = ok(get(alice, uri + "/members"));
        assertThat(find(after, "id", String.valueOf(carolId)).get("leftDate").asText()).isEqualTo("2026-10-07");
        assertThat(find(after, "id", String.valueOf(bobsId)).get("leftDate").asText()).isEqualTo("2026-10-06");
    }

    /** At 23:30 UTC on 31 October, a reader in Amsterdam is in November, and one in Los Angeles in October. */
    @Test
    void theTotalsAreAsOfTheReadersToday() throws IOException {
        String alice = newUser();
        String bob = newUser();
        ok(get(bob, "/api/accounts"));
        setZone(alice, AMSTERDAM);
        setZone(bob, LOS_ANGELES);
        at("2026-10-31T23:30:00Z");
        for (String[] rate : new String[][] {{"2026-10-31", "1.10"}, {"2026-11-01", "2.00"}}) {
            for (String user : new String[] {alice, bob}) {
                ok(post(user, "/api/rates/manual", """
                        {"date": "%s", "base": "EUR", "quote": "USD", "rate": "%s"}""".formatted(rate[0], rate[1])));
            }
        }
        JsonNode created = newFamily(alice, familyRequest(alice, "\"startDate\": \"2026-10-01\","));
        String uri = "/api/family-ledgers/" + created.get("id").asLong();
        long mum = created.get("memberId").asLong();
        accept(bob, inviteTo(alice, uri, "{\"kind\": \"NEW_MEMBER\"}"), "Bob");
        long groceries = find(ok(get(alice, uri + "/categories")), "code", "GROCERIES").get("id").asLong();
        created(post(alice, uri + "/records", """
                {"date": "2026-10-31", "categoryId": %d, "amount": "110.00", "currency": "USD", "payerMemberId": %d,
                 "paymentLater": true, "split": {"method": "ONE_MEMBER", "memberId": %d}}"""
                .formatted(groceries, mum, find(ok(get(alice, uri + "/members")), "displayName", "Bob").get("id")
                        .asLong())));

        JsonNode amsterdam = ok(get(alice, uri + "/balances")).get("total");
        assertThat(amsterdam.get("asOf").asText()).isEqualTo("2026-11-01");
        assertThat(StreamSupport.stream(amsterdam.get("rates").spliterator(), false).map(r -> r.get("date").asText()
                + " " + r.get("perEuro").asText()).toList()).containsExactly("2026-11-01 2.00");
        JsonNode losAngeles = ok(get(bob, uri + "/balances")).get("total");
        assertThat(losAngeles.get("asOf").asText()).isEqualTo("2026-10-31");
        assertThat(StreamSupport.stream(losAngeles.get("rates").spliterator(), false).map(r -> r.get("date").asText()
                + " " + r.get("perEuro").asText()).toList()).containsExactly("2026-10-31 1.10");
    }

    @Test
    void anInvalidZoneIsRefusedWith422AndChangesNothing() throws IOException {
        String user = newUser();
        setZone(user, AMSTERDAM);
        for (String invalid : List.of("Mars/Olympus_Mons", "+02:00", "UTC+2", "GMT+2", "Europe/amsterdam", "../etc/passwd",
                " Europe/Amsterdam", "Europe/Amsterdam ", "Z", "CEST", "Europe/")) {
            JsonNode problem = body(put(user, "/api/settings/time-zone", "{\"timeZone\": \"%s\"}".formatted(invalid)),
                    HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(problem.get("violations").get(0).asText()).as(invalid).contains("is not a time zone");
            assertThat(ok(get(user, "/api/me")).get("timeZone").asText()).as(invalid).isEqualTo(AMSTERDAM);
        }
        // A missing or blank id, or one too long for any zone, is a malformed request.
        for (String malformed : List.of("{}", "{\"timeZone\": null}", "{\"timeZone\": \"\"}", "{\"timeZone\": \"  \"}",
                "{\"timeZone\": \"%s\"}".formatted("x".repeat(65)))) {
            assertThat(put(user, "/api/settings/time-zone", malformed).getResponse().getStatus()).as(malformed)
                    .isEqualTo(400);
        }
        assertThat(ok(get(user, "/api/me")).get("timeZone").asText()).isEqualTo(AMSTERDAM);
        // A valid id other than the browser's is accepted: the user's choice.
        assertThat(setZone(user, "Asia/Kolkata").get("timeZone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(setZone(user, "UTC").get("timeZone").asText()).isEqualTo("UTC");
    }

    /**
     * D-103: a zone is accepted if and only if it is in {@code ZoneId.getAvailableZoneIds()}, case-sensitive; the list
     * the frontend offers from is that set, and every id in it is accepted.
     */
    @Test
    void aZoneIsAcceptedIfAndOnlyIfJavaKnowsItsId() throws IOException {
        String user = newUser();
        List<String> listed = StreamSupport.stream(ok(get(user, "/api/settings/time-zones")).spliterator(), false)
                .map(JsonNode::asText).toList();
        assertThat(listed).containsExactlyInAnyOrderElementsOf(ZoneId.getAvailableZoneIds())
                .contains("UTC", "Etc/UTC", "Etc/GMT+5", "Asia/Calcutta", "Asia/Kolkata", "Europe/Amsterdam")
                .doesNotContain("UTC+2", "GMT+2", "Europe/amsterdam", "Z", "CEST");
        for (String id : List.of("UTC", "Etc/UTC", "Etc/GMT+5", "Asia/Calcutta", "Europe/Amsterdam")) {
            assertThat(setZone(user, id).get("timeZone").asText()).isEqualTo(id);
            assertThat(ok(get(user, "/api/me")).get("timeZone").asText()).isEqualTo(id);
        }
        // Every listed id is accepted, with the date in it.
        for (String id : listed) {
            assertThat(put(user, "/api/settings/time-zone", "{\"timeZone\": \"%s\"}".formatted(id)).getResponse().getStatus())
                    .as(id).isEqualTo(200);
        }
        // Nothing else is: the same id in another case, an offset, an abbreviation.
        for (String id : List.of("utc", "etc/utc", "europe/amsterdam", "EUROPE/AMSTERDAM", "UTC+0", "UTC+02:00", "+02:00",
                "GMT+5", "CET ", "EST5EDT2")) {
            assertThat(put(user, "/api/settings/time-zone", "{\"timeZone\": \"%s\"}".formatted(id)).getResponse().getStatus())
                    .as(id).isEqualTo(422);
        }
    }

    /**
     * F8c-fix, choice 6: what the app can't run without is the token's subject (the api's own check) and, in
     * {@code /api/me}'s answer, today's date. An account registered with its email only, with no name, no
     * preferred_username and no given or family name, is a user like any other: {@code name} is null, nothing fails,
     * and its zone, null until saved, is set and read back.
     */
    @Test
    void anAccountWithNoNameAtAllWorks() throws IOException {
        String sub = newUser();
        var emailOnly = jwt().jwt(token -> token.subject(sub).claim("email", sub + "@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
        JsonNode me = body(mvc.get().uri("/api/me").with(emailOnly).exchange(), HttpStatus.OK);
        assertThat(me.get("name").isNull()).isTrue();
        assertThat(me.get("email").asText()).isEqualTo(sub + "@example.com");
        assertThat(me.get("timeZone").isNull()).isTrue();
        assertThat(me.get("today").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(me.get("features").get("familyLedgers").asBoolean()).isTrue();

        // It is provisioned and served like anyone: reference data, the zone, the demo with its family budget.
        body(mvc.get().uri("/api/accounts").with(emailOnly).exchange(), HttpStatus.OK);
        JsonNode saved = body(mvc.put().uri("/api/settings/time-zone").with(emailOnly)
                .contentType(MediaType.APPLICATION_JSON).content("{\"timeZone\": \"UTC\"}").exchange(), HttpStatus.OK);
        assertThat(saved.get("timeZone").asText()).isEqualTo("UTC");
        me = body(mvc.get().uri("/api/me").with(emailOnly).exchange(), HttpStatus.OK);
        assertThat(me.get("timeZone").asText()).isEqualTo("UTC");
        assertThat(me.get("name").isNull()).isTrue();
        JsonNode demo = body(mvc.post().uri("/api/demo-data").with(emailOnly).exchange(), HttpStatus.OK);
        assertThat(demo.get("familyLedgerId").isNumber()).isTrue();
    }

    /** Nothing but the zone's own endpoint writes it: the settings page's PUT and the demo leave it as it is. */
    @Test
    void theSettingsPutAndTheDemoLeaveTheZoneAlone() throws IOException {
        String user = newUser();
        setZone(user, LOS_ANGELES);
        ok(put(user, "/api/settings", """
                {"baseCurrency": "USD", "defaultShareRatio": "0.40"}"""));
        assertThat(ok(get(user, "/api/me")).get("timeZone").asText()).isEqualTo(LOS_ANGELES);
        ok(post(user, "/api/demo-data", null));
        assertThat(ok(get(user, "/api/me")).get("timeZone").asText()).isEqualTo(LOS_ANGELES);
    }

    /** "Delete all my data" removes the setting with the rest; the next load sets it again. */
    @Test
    void deletingAllMyDataRemovesTheZone() throws IOException {
        String user = newUser();
        at("2026-10-07T03:00:00Z");
        setZone(user, LOS_ANGELES);
        assertThat(ok(get(user, "/api/me")).get("today").asText()).isEqualTo("2026-10-06");
        assertThat(delete(user, "/api/me/data")).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode me = ok(get(user, "/api/me"));
        assertThat(me.get("timeZone").isNull()).isTrue();
        assertThat(me.get("today").asText()).isEqualTo("2026-10-07");
        assertThat(jdbc.sql("SELECT count(*) FROM user_settings WHERE user_id = ?").param(user).query(Long.class)
                .single()).isZero();
        assertThat(setZone(user, LOS_ANGELES).get("today").asText()).isEqualTo("2026-10-06");
    }

    /** Bob's zone is his own: Alice's never reaches him, and his PUT changes nobody's but his. */
    @Test
    void aZoneIsTheUsersOwn() throws IOException {
        String alice = newUser();
        String bob = newUser();
        setZone(alice, PLUS_14);
        assertThat(ok(get(bob, "/api/me")).get("timeZone").isNull()).isTrue();
        setZone(bob, LOS_ANGELES);
        assertThat(ok(get(alice, "/api/me")).get("timeZone").asText()).isEqualTo(PLUS_14);
        assertThat(ok(get(bob, "/api/me")).get("timeZone").asText()).isEqualTo(LOS_ANGELES);
        assertThat(request(HttpMethod.PUT, "/api/settings/time-zone", null, "{\"timeZone\": \"UTC\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** With the system clock, an unset user's today is the UTC date, not the JVM's (Pacific/Kiritimati in the tests). */
    @Test
    void withTheSystemClockAnUnsetUsersTodayIsTheUtcDate() throws IOException {
        String user = newUser();
        for (int attempt = 0; attempt < 3; attempt++) {
            LocalDate before = utcToday();
            String today = ok(get(user, "/api/me")).get("today").asText();
            if (before.equals(utcToday())) {
                assertThat(today).isEqualTo(before.toString());
                return;
            }
        }
        throw new AssertionError("The date turned over in each of three attempts");
    }

    /** The token of a new invite of the owner's to the budget. */
    private String inviteTo(String owner, String budget, String request) throws IOException {
        String link = created(post(owner, budget + "/invites", request)).get("link").asText();
        return link.substring(link.indexOf('#') + 1);
    }

    /** A new budget of the user's, which brings their Groceries; the rest of the request after the name. */
    private String familyRequest(String user, String startDate) throws IOException {
        return """
                {"name": "Home", "baseCurrency": "EUR", "displayName": "Mum", %s "categoryIds": [%d]}""".formatted(
                startDate, categoryId(user, "GROCERIES"));
    }

    /** A record of the date by the budget's creator, in the family's Groceries. */
    private String record(String user, JsonNode family, String date) throws IOException {
        String uri = "/api/family-ledgers/" + family.get("id").asLong();
        long category = find(ok(get(user, uri + "/categories")), "code", "GROCERIES").get("id").asLong();
        return """
                {"date": "%s", "categoryId": %d, "amount": "10.00", "payerMemberId": %d, "paymentLater": true}"""
                .formatted(date, category, family.get("memberId").asLong());
    }

    private static boolean applies(JsonNode latest, String currency) {
        return find(latest, "currency", currency).get("applies").asBoolean();
    }

    private static String cashBalance(JsonNode balances) {
        return find(balances, "accountCode", "CASH").get("balance").asText();
    }
}
