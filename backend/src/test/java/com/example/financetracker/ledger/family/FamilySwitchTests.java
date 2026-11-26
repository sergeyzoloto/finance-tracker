package com.example.financetracker.ledger.family;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.financetracker.LogCapture;
import org.junit.jupiter.api.Test;

/** The switch says at startup whether it is on (D-25), so the deploy's log shows it. */
class FamilySwitchTests {

    @Test
    void itLogsItsStateAtStartup() {
        try (LogCapture capture = LogCapture.of(FamilySwitch.class)) {
            new FamilySwitch(false).reportAtStartup();
            new FamilySwitch(true).reportAtStartup();

            assertThat(String.join("\n", capture.messages())).contains("Family ledgers (D-25): off; the family endpoints answer 404",
                    "Family ledgers (D-25): on");
        }
    }
}
