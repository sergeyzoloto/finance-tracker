package com.example.financetracker.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.history.ActivityLog;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Sign-in, sign-out and expiry as activity events (ADR 0006, 2.3, D-1007): by sub, and each end logged once. */
class SessionEventsTests {

    private final ActivityLog log = mock(ActivityLog.class);
    private final SessionEvents events = new SessionEvents(log);
    private final Authentication user = new TestingAuthenticationToken("sub-123", "n/a", List.of());

    private MockHttpSession signedInSession() {
        user.setAuthenticated(true);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(user));
        return session;
    }

    @Test
    void aSignInIsLoggedByTheSubAlone() {
        events.signedIn(user);
        verify(log).logAlone("sub-123", "SIGN_IN", null);
    }

    @Test
    void aSessionThatEndsByItselfIsAnExpiry() {
        events.sessionDestroyed(new HttpSessionEvent(signedInSession()));
        verify(log).logAlone("sub-123", "SESSION_EXPIRED", null);
    }

    @Test
    void anAnonymousSessionLeavesNothing() {
        events.sessionDestroyed(new HttpSessionEvent(new MockHttpSession()));
        verifyNoInteractions(log);
    }

    @Test
    void aSignOutIsLoggedOnceAndItsSessionEndIsNotAnExpiry() {
        MockHttpSession session = signedInSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        events.logout(request, new MockHttpServletResponse(), user);
        events.sessionDestroyed(new HttpSessionEvent(session));
        verify(log).logAlone("sub-123", "SIGN_OUT", null);
        verify(log, never()).logAlone(eq("sub-123"), eq("SESSION_EXPIRED"), any());
    }

    @Test
    void aRefusedRefreshEndsTheSessionWithItsReasonAndNoExpiryFollows() {
        MockHttpSession session = signedInSession();
        events.refreshRefused(session);
        events.sessionDestroyed(new HttpSessionEvent(session));
        verify(log).logAlone("sub-123", "SESSION_ENDED", Map.of("reason", "REFRESH_REFUSED"));
        verify(log, never()).logAlone(eq("sub-123"), eq("SESSION_EXPIRED"), any());
    }
}
