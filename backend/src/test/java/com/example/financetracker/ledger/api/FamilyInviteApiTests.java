package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Invites (F5; B2, B3, B4; D-17; ADR 0003, topic G) in the family of {@link FamilyApiTest}: the owners' side, the
 * token's lifecycle and secrecy, the one answer for a token that lets nobody in, who can't accept a valid one, a race
 * for one seat, the rate limit, and the logs. What a claim posts is FamilyClaimApiTests'; the isolation of every
 * endpoint is DataIsolationApiTests'. The invariants hold after every test.
 */
@ExtendWith(OutputCaptureExtension.class)
class FamilyInviteApiTests extends FamilyApiTest {

    private static final String INVALID = "This invite is not valid. Ask for a new one.";
    private static final String OWNERS_ONLY = "Only an owner of the family budget can do this: owners manage its "
            + "settings, split rule and members, and rename, archive and delete its categories.";

    private final String carol = newUser();
    private final String dave = newUser();

    @Test
    void anOwnerCreatesListsAndRevokesInvites() throws IOException {
        JsonNode created = created(post(alice, uri + "/invites", """
                {"kind": "NEW_MEMBER"}"""));
        String link = created.get("link").asText();
        assertThat(link).matches("https://app\\.finance-nl\\.com/invite#[A-Za-z0-9_-]{43}");
        assertThat(created.get("kind").asText()).isEqualTo("NEW_MEMBER");
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("createdBy").get("displayName").asText()).isEqualTo("Mum");
        assertThat(created.has("seat")).isFalse();
        assertThat(created.has("joinDate")).isFalse();
        assertThat(Duration.between(Instant.parse(created.get("createdAt").asText()),
                Instant.parse(created.get("expiresAt").asText()))).isEqualTo(Duration.ofHours(72));

        JsonNode claim = created(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "2026-09-10", "lifetimeHours": 1}""".formatted(kid)));
        assertThat(claim.get("kind").asText()).isEqualTo("CLAIM");
        assertThat(claim.get("seat").get("displayName").asText()).isEqualTo("Kid");
        assertThat(claim.get("joinDate").asText()).isEqualTo("2026-09-10");
        assertThat(Duration.between(Instant.parse(claim.get("createdAt").asText()),
                Instant.parse(claim.get("expiresAt").asText()))).isEqualTo(Duration.ofHours(1));

        // The list: newest first, with no link and no token.
        JsonNode list = ok(get(alice, uri + "/invites"));
        assertThat(ids(list)).containsExactly(claim.get("id").asLong(), created.get("id").asLong());
        assertThat(list.findValues("link")).isEmpty();
        assertThat(list.toString()).doesNotContain(link.substring(link.indexOf('#') + 1));

        long id = created.get("id").asLong();
        assertThat(delete(alice, uri + "/invites/" + id)).hasStatus(HttpStatus.NO_CONTENT);
        JsonNode revoked = find(ok(get(alice, uri + "/invites")), "status", "REVOKED");
        assertThat(revoked.get("id").asLong()).isEqualTo(id);
        assertThat(revoked.has("revokedAt")).isTrue();
        assertThat(detail(delete(alice, uri + "/invites/" + id), HttpStatus.CONFLICT))
                .isEqualTo("Only a pending invite can be revoked, and this one is revoked.");
        assertThat(detail(delete(alice, uri + "/invites/9000000000"), HttpStatus.NOT_FOUND))
                .isEqualTo("Invite 9000000000 not found.");

        // Owners only (D-15): Dad is a member.
        assertThat(detail(post(bob, uri + "/invites", """
                {"kind": "NEW_MEMBER"}"""), HttpStatus.CONFLICT)).isEqualTo(OWNERS_ONLY);
        assertThat(detail(get(bob, uri + "/invites"), HttpStatus.CONFLICT)).isEqualTo(OWNERS_ONLY);
        assertThat(detail(delete(bob, uri + "/invites/" + claim.get("id").asLong()), HttpStatus.CONFLICT))
                .isEqualTo(OWNERS_ONLY);
        assertThat(find(ok(get(alice, uri + "/invites")), "id", claim.get("id").asText()).get("status").asText())
                .isEqualTo("PENDING");
    }

    @Test
    void anInviteFitsItsKindAndTheFamilyBudget() throws IOException {
        for (String request : List.of("""
                {"kind": "CLAIM"}""", """
                {"kind": "NEW_MEMBER", "seatMemberId": %d}""".formatted(kid), """
                {"kind": "NEW_MEMBER", "lifetimeHours": 0}""", """
                {"kind": "NEW_MEMBER", "lifetimeHours": 169}""", """
                {}""")) {
            assertThat(post(alice, uri + "/invites", request)).as(request).hasStatus(HttpStatus.BAD_REQUEST);
        }
        assertThat(detail(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "2026-09-10"}""".formatted(dad)),
                HttpStatus.CONFLICT)).isEqualTo("Dad has an account already, so nobody takes their place.");
        assertThat(detail(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": 9000000000, "joinDate": "2026-09-10"}"""), HttpStatus.NOT_FOUND))
                .isEqualTo("Member 9000000000 not found.");
        // A claim without a join date takes today's, the server's, as a start date does (F6a).
        JsonNode today = created(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d}""".formatted(kid)));
        assertThat(today.get("joinDate").asText()).isEqualTo(today().toString());
        assertThat(delete(alice, uri + "/invites/" + today.get("id").asLong())).hasStatus(HttpStatus.NO_CONTENT);
        String notBetween = "JOIN_DATE %d the join date %s is not between the family budget's start date 2026-09-01 "
                + "and today, " + today();
        assertThat(details(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "2026-08-31"}""".formatted(kid))))
                .containsExactly(notBetween.formatted(kid, "2026-08-31"));
        LocalDate tomorrow = today().plusDays(1);
        assertThat(details(post(alice, uri + "/invites", """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "%s"}""".formatted(kid, tomorrow))))
                .containsExactly(notBetween.formatted(kid, tomorrow));
        assertThat(details(post(alice, uri + "/invites", """
                {"kind": "NEW_MEMBER", "joinDate": "2026-09-10"}"""))).containsExactly("JOIN_DATE null a new member "
                        + "joins on the day they accept; only taking the place of a member without an account takes a "
                        + "join date");

        // At most 20 pending at once; a revoked one makes room.
        for (int i = 0; i < 20; i++) {
            newInvite(alice, """
                    {"kind": "NEW_MEMBER"}""");
        }
        assertThat(detail(post(alice, uri + "/invites", """
                {"kind": "NEW_MEMBER"}"""), HttpStatus.CONFLICT)).isEqualTo("The family budget has 20 pending "
                        + "invites, the most it can have; revoke one first.");
        assertThat(delete(alice, uri + "/invites/" + ok(get(alice, uri + "/invites")).get(0).get("id").asLong()))
                .hasStatus(HttpStatus.NO_CONTENT);
        newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
    }

    /** Only the token's SHA-256 is stored, and the creation's answer is the only one that holds the token. */
    @Test
    void onlyTheHashIsStoredAndOnlyTheCreationAnswersTheToken() throws IOException, NoSuchAlgorithmException {
        List<String> answers = new ArrayList<>();
        MvcTestResult creation = post(alice, uri + "/invites", """
                {"kind": "NEW_MEMBER"}""");
        String link = created(creation).get("link").asText();
        String token = link.substring(link.indexOf('#') + 1);
        assertThat(java.util.Base64.getUrlDecoder().decode(token)).hasSize(32);

        byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.sql("SELECT token_hash FROM ledger_invite WHERE ledger_id = ?").param(family)
                .query(byte[].class).single()).isEqualTo(hash);
        assertThat(jdbc.sql("SELECT t::text FROM ledger_invite t WHERE ledger_id = ?").param(family)
                .query(String.class).single()).doesNotContain(token);

        for (MvcTestResult answer : List.of(get(alice, uri + "/invites"), inviteCall(carol, "lookup", token(token,
                null)), inviteCall(carol, "accept", token(token, "\"displayName\": \"Carol\"")), get(carol, uri),
                get(carol, uri + "/members"), get(alice, uri + "/invites"), inviteCall(dave, "lookup", token(token,
                        null)), inviteCall(dave, "decline", token(token, null)))) {
            answers.add(answer.getResponse().getContentAsString() + answer.getResponse().getHeaderNames().stream()
                    .map(name -> name + ": " + answer.getResponse().getHeaders(name)).toList());
        }
        assertThat(answers).noneMatch(answer -> answer.contains(token));
        assertThat(creation.getResponse().getContentAsString()).contains(token);
    }

    /** Unknown, revoked, expired, used and declined tokens: one answer, word for word, for lookup, accept and decline. */
    @Test
    void aTokenThatLetsNobodyInGetsOneAnswer() throws IOException {
        String revoked = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        assertThat(delete(alice, uri + "/invites/" + ok(get(alice, uri + "/invites")).get(0).get("id").asLong()))
                .hasStatus(HttpStatus.NO_CONTENT);
        // Expired: what is stored of an invite created three days ago, as the owner's link would have made it.
        String expired = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("an expired invite's token"
                .repeat(2).substring(0, 32).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO ledger_invite (ledger_id, token_hash, created_by_member_id, created_at, expires_at)
                VALUES (?, sha256(convert_to(?, 'UTF8')), ?, now() - interval '3 days', now() - interval '1 second')""")
                .params(family, expired, mum).update();
        String used = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        accept(carol, used, "Carol");
        String declined = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        assertThat(inviteCall(dave, "decline", token(declined, null))).hasStatus(HttpStatus.NO_CONTENT);
        String guessed = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        List<String> answers = new ArrayList<>();
        for (String token : List.of(guessed, "not a token at all", "", guessed.repeat(5), revoked, expired, used,
                declined)) {
            // A user of their own for each token, so that the limit per user (10 a minute) stays out of it.
            String holder = newUser();
            for (String action : List.of("lookup", "accept", "decline")) {
                MvcTestResult answer = inviteCall(holder, action, token(token, "\"displayName\": \"Dave\""));
                assertThat(answer).as("%s of %s", action, token).hasStatus(HttpStatus.NOT_FOUND);
                answers.add(answer.getResponse().getContentAsString());
            }
        }
        // The same answer for each action, apart from the path it names.
        assertThat(answers.stream().map(answer -> answer.replaceAll("/api/invites/\\w+", "PATH")))
                .containsOnly(answers.getFirst().replaceAll("/api/invites/\\w+", "PATH"));
        assertThat(json.readTree(answers.getFirst()).get("detail").asText()).isEqualTo(INVALID);
        // Nothing of the family budget for Dave, and the statuses as the owners see them.
        assertThat(get(dave, uri)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(ok(get(alice, uri + "/invites")).findValuesAsText("status"))
                .containsExactly("DECLINED", "ACCEPTED", "REVOKED", "EXPIRED");
        JsonNode accepted = find(ok(get(alice, uri + "/invites")), "status", "ACCEPTED");
        assertThat(accepted.get("acceptedBy").get("displayName").asText()).isEqualTo("Carol");
        assertThat(accepted.get("joinDate").asText()).isEqualTo(today().toString());
        JsonNode declinedOne = find(ok(get(alice, uri + "/invites")), "status", "DECLINED");
        assertThat(declinedOne.has("declinedAt")).isTrue();
        assertThat(declinedOne.has("acceptedBy")).isFalse();
    }

    /** A valid token that the user can't accept: its own 409, and the token stays valid. */
    @Test
    void whoCantAcceptAValidInviteGetsAConflict() throws IOException {
        String member = "You are a member of this family budget already.";
        String newMember = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        for (String user : List.of(alice, bob)) {
            for (String action : List.of("lookup", "accept", "decline")) {
                assertThat(detail(inviteCall(user, action, token(newMember, "\"displayName\": \"Someone\"")),
                        HttpStatus.CONFLICT)).as("%s by %s", action, user).isEqualTo(member);
            }
        }
        // Two invites for Kid's place: once Carol took it, the other one can't be accepted, and says so.
        String first = kidsPlace("2026-09-10");
        String second = kidsPlace("2026-09-10");
        accept(carol, first, "Carol");
        String taken = "Someone has taken this place in the family budget already. Ask for a new invite.";
        assertThat(detail(inviteCall(dave, "lookup", token(second, null)), HttpStatus.CONFLICT)).isEqualTo(taken);
        assertThat(detail(inviteCall(dave, "accept", token(second, "\"displayName\": \"Dave\"")),
                HttpStatus.CONFLICT)).isEqualTo(taken);
        // Carol, a member now, can't take a second place (D-18).
        assertThat(detail(inviteCall(carol, "lookup", token(newMember, null)), HttpStatus.CONFLICT)).isEqualTo(member);
        // A name another member has, whatever its case: the seat's own doesn't count.
        assertThat(detail(inviteCall(dave, "accept", token(newMember, "\"displayName\": \"mum\"")),
                HttpStatus.CONFLICT)).isEqualTo("The family budget has a member named mum already.");
        // A member who left comes back by an invite for a new member since F6a (D-26), never into a place (D-18).
        jdbc.sql("UPDATE ledger_member SET status = 'LEFT', left_date = current_date WHERE id = ?").param(dad).update();
        assertThat(ok(inviteCall(bob, "lookup", token(newMember, null))).get("returning").asBoolean()).isTrue();
        long gran = created(post(alice, uri + "/members", """
                {"displayName": "Gran"}""")).get("id").asLong();
        assertThat(detail(inviteCall(bob, "lookup", token(newInvite(alice, """
                {"kind": "CLAIM", "seatMemberId": %d, "joinDate": "2026-09-10"}""".formatted(gran)), null)),
                HttpStatus.CONFLICT))
                .isEqualTo("You were a member of this family budget before, so you can't take someone else's place; an "
                        + "invite as a new member brings you back.");
        // The token still lets Dave in, as a new member.
        assertThat(accept(dave, newMember, "Dave").get("role").asText()).isEqualTo("MEMBER");
    }

    /** Two users race for one seat, and two for one token: one gets in, the other gets its answer. */
    @Test
    void racesForOneSeatAndOneTokenLetOneIn() throws Exception {
        String first = kidsPlace("2026-09-10");
        String second = kidsPlace("2026-09-10");
        List<MvcTestResult> seat = race(() -> inviteCall(carol, "accept", token(first, "\"displayName\": \"Carol\"")),
                () -> inviteCall(dave, "accept", token(second, "\"displayName\": \"Dave\"")));
        assertThat(seat.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(200, 409);
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("displayName")).containsAnyOf("Carol", "Dave")
                .doesNotContain("Kid");

        String once = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        String erin = newUser();
        String frank = newUser();
        List<MvcTestResult> token = race(() -> inviteCall(erin, "accept", token(once, "\"displayName\": \"Erin\"")),
                () -> inviteCall(frank, "accept", token(once, "\"displayName\": \"Frank\"")));
        assertThat(token.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(200, 404);
        assertThat(ok(get(alice, uri + "/members")).findValuesAsText("displayName").stream()
                .filter(name -> name.equals("Erin") || name.equals("Frank"))).hasSize(1);
    }

    @Test
    void withoutASessionTheInviteEndpointsAnswer401() throws IOException {
        String invite = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        for (String action : List.of("lookup", "accept", "decline")) {
            assertThat(request(HttpMethod.POST, "/api/invites/" + action, null,
                    token(invite, "\"displayName\": \"Nobody\""))).as(action).hasStatus(HttpStatus.UNAUTHORIZED);
        }
        assertThat(find(ok(get(alice, uri + "/invites")), "kind", "NEW_MEMBER").get("status").asText())
                .isEqualTo("PENDING");
    }

    /** 10 attempts a minute per user and per client address: then 429, with when to try again. */
    @Test
    void theRateLimitAnswers429() throws IOException {
        String guess = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        for (int i = 0; i < 10; i++) {
            assertThat(inviteCall(carol, "lookup", token(guess, null))).hasStatus(HttpStatus.NOT_FOUND);
        }
        MvcTestResult limited = inviteCall(carol, "lookup", token(guess, null));
        assertThat(detail(limited, HttpStatus.TOO_MANY_REQUESTS))
                .isEqualTo("Too many attempts with invite links. Try again in a few minutes.");
        assertThat(Integer.parseInt(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1, 60);
        // Every one of the three counts, and a valid token too.
        String valid = newInvite(alice, """
                {"kind": "NEW_MEMBER"}""");
        assertThat(inviteCall(carol, "accept", token(valid, "\"displayName\": \"Carol\"")))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(inviteCall(carol, "decline", token(valid, null))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        // From one address: 10 attempts a minute by two users, then nobody from there. (50 an hour per address:
        // InviteRateLimitTests, with a clock of its own.)
        String address = "203.0.113.77";
        for (int user = 0; user < 2; user++) {
            String someone = newUser();
            for (int i = 0; i < 5; i++) {
                assertThat(inviteCall(someone, "lookup", token(guess, null), address)).hasStatus(HttpStatus.NOT_FOUND);
            }
        }
        assertThat(inviteCall(dave, "lookup", token(valid, null), address)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ok(inviteCall(dave, "lookup", token(valid, null))).get("ledgerName").asText()).isEqualTo("Home");
    }

    /**
     * The whole flow, with the web and SQL loggers at DEBUG: no line of the log holds a token, also for a body that
     * can't be read, a token that is too long and a rate-limited attempt.
     */
    @Test
    void noLogLineHoldsAToken(CapturedOutput output) throws IOException {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        List<String> loggers = List.of("org.springframework.web", "org.springframework.jdbc",
                "org.springframework.security", "com.example.financetracker");
        loggers.forEach(logger -> logging.setLogLevel(logger, LogLevel.DEBUG));
        List<String> tokens = new ArrayList<>();
        try {
            String claim = kidsPlace("2026-09-10");
            String declined = newInvite(alice, """
                    {"kind": "NEW_MEMBER"}""");
            String revoked = newInvite(alice, """
                    {"kind": "NEW_MEMBER"}""");
            tokens.addAll(List.of(claim, declined, revoked));
            ok(get(alice, uri + "/invites"));
            assertThat(delete(alice, uri + "/invites/" + find(ok(get(alice, uri + "/invites")), "kind", "NEW_MEMBER")
                    .get("id").asLong())).hasStatus(HttpStatus.NO_CONTENT);
            ok(inviteCall(carol, "lookup", token(claim, null)));
            accept(carol, claim, "Carol");
            assertThat(inviteCall(dave, "lookup", token(claim, null))).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(inviteCall(dave, "decline", token(declined, null))).hasStatus(HttpStatus.NO_CONTENT);
            assertThat(inviteCall(dave, "accept", token(revoked, "\"displayName\": \"Dave\"")))
                    .hasStatus(HttpStatus.NOT_FOUND);
            // A body that can't be read, a token too long, a missing display name, a member's conflict.
            assertThat(inviteCall(dave, "lookup", "{\"token\": \"" + claim + "\", ")).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(inviteCall(dave, "lookup", token(claim.repeat(5), null))).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(inviteCall(dave, "accept", token(claim, null))).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(inviteCall(alice, "lookup", token(revoked, null))).hasStatus(HttpStatus.NOT_FOUND);
            String another = newInvite(alice, """
                    {"kind": "NEW_MEMBER"}""");
            tokens.add(another);
            assertThat(inviteCall(alice, "accept", token(another, "\"displayName\": \"Mum\"")))
                    .hasStatus(HttpStatus.CONFLICT);
            String erin = newUser();
            for (int i = 0; i < 11; i++) {
                inviteCall(erin, "lookup", token(another, null), "192.0.2.1");
            }
        } finally {
            loggers.forEach(logger -> logging.setLogLevel(logger, null));
        }
        assertThat(output.getAll()).as("the log during the flow").contains("/api/invites/");
        for (String token : tokens) {
            assertThat(output.getAll()).doesNotContain(token);
        }
    }

    /** A claim's token takes its own seat in its own family budget, whatever else the request names. */
    @Test
    void aClaimTakesItsOwnSeatInItsOwnFamilyBudget() throws IOException {
        long otherSeat = body(post(alice, uri + "/members", """
                {"displayName": "Gran"}"""), HttpStatus.CREATED).get("id").asLong();
        long otherFamily = newFamily(alice, """
                {"name": "Other", "baseCurrency": "EUR", "displayName": "Mum"}""").get("id").asLong();
        String token = kidsPlace("2026-09-10");
        JsonNode joined = ok(inviteCall(carol, "accept", token(token, """
                "displayName": "Carol", "seatMemberId": %d, "ledgerId": %d, "joinDate": "2026-09-01\""""
                .formatted(otherSeat, otherFamily))));
        assertThat(joined.get("id").asLong()).isEqualTo(family);
        assertThat(joined.get("memberId").asLong()).isEqualTo(kid);
        JsonNode members = ok(get(carol, uri + "/members"));
        assertThat(find(members, "displayName", "Carol").get("joinDate").asText()).isEqualTo("2026-09-10");
        assertThat(find(members, "displayName", "Gran").get("hasAccount").asBoolean()).isFalse();
        assertThat(get(carol, "/api/family-ledgers/" + otherFamily)).hasStatus(HttpStatus.NOT_FOUND);
    }

    private static List<MvcTestResult> race(Callable<MvcTestResult> one, Callable<MvcTestResult> other)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcTestResult>> results = executor.invokeAll(List.of(one, other));
            List<MvcTestResult> answers = new ArrayList<>();
            for (Future<MvcTestResult> result : results) {
                answers.add(result.get());
            }
            return answers;
        } finally {
            executor.shutdown();
        }
    }
}
