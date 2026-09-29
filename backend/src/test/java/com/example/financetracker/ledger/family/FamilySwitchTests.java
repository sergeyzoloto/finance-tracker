package com.example.financetracker.ledger.family;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The switch says at startup whether it is on (D-25), so the deploy's log shows it. */
@ExtendWith(OutputCaptureExtension.class)
class FamilySwitchTests {

    @Test
    void itLogsItsStateAtStartup(CapturedOutput output) {
        new FamilySwitch(false).reportAtStartup();
        new FamilySwitch(true).reportAtStartup();

        assertThat(output.getOut()).contains("Family ledgers (D-25): off; the family endpoints answer 404",
                "Family ledgers (D-25): on");
    }
}
