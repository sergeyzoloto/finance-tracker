package com.example.financetracker.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.UserDataService;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * "Delete all my data" erases the user's activity events (V19), and the end of a session that follows it must not
 * bring one back (D-1072, D-1074): neither a sign-out nor an expiry nor a refused refresh. A plain sign-out and a
 * sign-in after the deletion are logged as before. The real services and database, the session callbacks called as the
 * container and Spring Security call them.
 */
class SessionEventsErasureTests extends IntegrationTest {

    @Autowired
    private SessionEvents events;

    @Autowired
    private UserDataService userData;

    @Autowired
    private JdbcClient jdbc;

    private final String sub = UUID.randomUUID().toString();
    private final Authentication user = new TestingAuthenticationToken(sub, "n/a", List.of());

    private MockHttpSession signedInSession() {
        user.setAuthenticated(true);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(user));
        return session;
    }

    private void signOut(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        events.logout(request, new MockHttpServletResponse(), user);
        events.sessionDestroyed(new HttpSessionEvent(session));
    }

    private List<String> kinds() {
        return jdbc.sql("SELECT kind FROM activity_event WHERE actor_sub = ? ORDER BY id").param(sub)
                .query(String.class).list();
    }

    private void signInThenDeleteAllData() {
        userData.provision(sub, null, "Someone");
        events.signedIn(user);
        assertThat(kinds()).containsExactly("SIGN_IN");
        userData.deleteAll(sub);
        assertThat(kinds()).isEmpty();
    }

    @Test
    void aSignOutAfterDeletingAllMyDataWritesNothing() {
        MockHttpSession session = signedInSession();
        signInThenDeleteAllData();
        signOut(session);
        assertThat(kinds()).isEmpty();
    }

    @Test
    void anExpiryAfterDeletingAllMyDataWritesNothing() {
        MockHttpSession session = signedInSession();
        signInThenDeleteAllData();
        events.sessionDestroyed(new HttpSessionEvent(session));
        assertThat(kinds()).isEmpty();
    }

    @Test
    void aRefusedRefreshAfterDeletingAllMyDataWritesNothing() {
        MockHttpSession session = signedInSession();
        signInThenDeleteAllData();
        events.refreshRefused(session);
        events.sessionDestroyed(new HttpSessionEvent(session));
        assertThat(kinds()).isEmpty();
    }

    @Test
    void aPlainSignOutIsStillLogged() {
        MockHttpSession session = signedInSession();
        userData.provision(sub, null, "Someone");
        events.signedIn(user);
        signOut(session);
        assertThat(kinds()).containsExactly("SIGN_IN", "SIGN_OUT");
    }

    @Test
    void aSessionThatExpiresWithoutADeletionIsStillLogged() {
        MockHttpSession session = signedInSession();
        events.sessionDestroyed(new HttpSessionEvent(session));
        assertThat(kinds()).containsExactly("SESSION_EXPIRED");
    }

    @Test
    void aSignInAfterTheDeletionIsLoggedAndItsSignOutToo() {
        signInThenDeleteAllData();
        events.signedIn(user);
        signOut(signedInSession());
        assertThat(kinds()).containsExactly("SIGN_IN", "SIGN_OUT");
    }
}
