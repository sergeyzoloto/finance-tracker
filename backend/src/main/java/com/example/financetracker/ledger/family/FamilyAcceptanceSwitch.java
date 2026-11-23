package com.example.financetracker.ledger.family;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The switch of family acceptance (HIST-3, ADR 0006 section 7; D-1156): off by default. It only reports: nothing
 * branches on it yet, and it has no effect without the family budget's switch ({@link FamilySwitch}). The acceptance
 * counters (V21) are kept true either way.
 */
@Component
public class FamilyAcceptanceSwitch {

    /** The configuration property; its environment variable is FAMILY_ACCEPTANCE_ENABLED (application.yml). */
    public static final String PROPERTY = "app.family.acceptance.enabled";

    private static final Logger log = LoggerFactory.getLogger(FamilyAcceptanceSwitch.class);

    private final boolean enabled;

    FamilyAcceptanceSwitch(@Value("${" + PROPERTY + ":false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reportAtStartup() {
        log.info("Family acceptance: {}", enabled ? "on" : "off");
    }
}
