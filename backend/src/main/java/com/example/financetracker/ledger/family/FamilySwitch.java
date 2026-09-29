package com.example.financetracker.ledger.family;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The feature switch of the family budget (D-25): off by default, and off in production until F7. While it is off,
 * the family endpoints don't exist, so they answer 404 like any unknown path, and the frontend hides the family pages
 * ({@code /api/me} tells it). The local development and test configurations turn it on.
 */
@Component
public class FamilySwitch {

    /** The configuration property; its environment variable is FAMILY_LEDGERS_ENABLED (application.yml). */
    public static final String PROPERTY = "app.family.enabled";

    private static final Logger log = LoggerFactory.getLogger(FamilySwitch.class);

    private final boolean enabled;

    FamilySwitch(@Value("${" + PROPERTY + ":false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reportAtStartup() {
        log.info("Family ledgers (D-25): {}", enabled ? "on" : "off; the family endpoints answer 404");
    }
}
