package com.example.financetracker.ledger.family;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The acceptance switch (HIST-3, D-1156): off unless its property says otherwise; nothing reads it yet. */
class FamilyAcceptanceSwitchTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(FamilyAcceptanceSwitch.class);

    @Test
    void itIsOffByDefault() {
        context.run(run -> assertThat(run.getBean(FamilyAcceptanceSwitch.class).enabled()).isFalse());
    }

    @Test
    void itReadsItsProperty() {
        context.withPropertyValues(FamilyAcceptanceSwitch.PROPERTY + "=true")
                .run(run -> assertThat(run.getBean(FamilyAcceptanceSwitch.class).enabled()).isTrue());
        context.withPropertyValues(FamilyAcceptanceSwitch.PROPERTY + "=false")
                .run(run -> assertThat(run.getBean(FamilyAcceptanceSwitch.class).enabled()).isFalse());
    }
}
