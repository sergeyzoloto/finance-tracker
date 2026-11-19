package com.example.financetracker.security;

import com.example.financetracker.ledger.history.ActivityLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.financetracker.ledger.UserDataDeleted;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sign-in, sign-out and the end of a session as activity events (ADR 0006, 2.3, D-1007): the Keycloak sub and the
 * time, no email, no address, no browser. A session that ends with no sign-out and no refused refresh behind it ended by
 * expiry. Sessions live in the JVM, so a restart or a deploy ends every session with no call here: the log cannot show
 * those ends, and the first event after one is a sign-in. A write that fails is logged and never fails the request.
 * <p>
 * "Delete all my data" erases the user's events with the rest (V19), so no event may come back for the session end
 * that follows it (D-1072, D-1074): after {@link UserDataDeleted} commits, the sub is kept in {@link #erased} and a
 * sign-out, an expiry or a refused refresh of any session of that sub writes nothing, until the user signs in again
 * (which is logged, as before). The set is in the JVM like the sessions, so a restart forgets it together with them.
 */
@Component
@ConditionalOnWebApplication
class SessionEvents implements HttpSessionListener, LogoutHandler {

    /** Set on a session whose end is already logged (sign-out, a refused refresh). */
    private static final String ENDED = SessionEvents.class.getName() + ".ended";

    private final ActivityLog activity;
    private final Set<String> erased = ConcurrentHashMap.newKeySet();

    SessionEvents(ActivityLog activity) {
        this.activity = activity;
    }

    /** The user deleted all their data: the ends of their sessions from now on are not logged. */
    @TransactionalEventListener
    public void dataDeleted(UserDataDeleted event) {
        erased.add(event.userId());
    }

    void signedIn(Authentication authentication) {
        if (authentication != null) {
            erased.remove(authentication.getName());
            activity.logAlone(authentication.getName(), "SIGN_IN", null);
        }
    }

    /** The logout handler: runs before the session is invalidated, and marks it so that its end isn't "expiry". */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession(false);
        String sub = authentication != null ? authentication.getName() : subOf(session);
        if (session != null) {
            session.setAttribute(ENDED, Boolean.TRUE);
        }
        if (sub != null && !erased.contains(sub)) {
            activity.logAlone(sub, "SIGN_OUT", null);
        }
    }

    /** The session filter's refresh was refused, so it ends the session: its end is logged here, with the reason. */
    void refreshRefused(HttpSession session) {
        String sub = subOf(session);
        session.setAttribute(ENDED, Boolean.TRUE);
        if (sub != null && !erased.contains(sub)) {
            activity.logAlone(sub, "SESSION_ENDED", Map.of("reason", "REFRESH_REFUSED"));
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession session = event.getSession();
        if (session.getAttribute(ENDED) != null) {
            return;
        }
        String sub = subOf(session);
        if (sub != null && !erased.contains(sub)) {
            activity.logAlone(sub, "SESSION_EXPIRED", null);
        }
    }

    private static String subOf(HttpSession session) {
        if (session == null) {
            return null;
        }
        try {
            Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (context instanceof SecurityContext security && security.getAuthentication() != null
                    && security.getAuthentication().isAuthenticated()) {
                return security.getAuthentication().getName();
            }
        } catch (IllegalStateException e) {
            // The session is already invalid: nothing to read.
        }
        return null;
    }
}
