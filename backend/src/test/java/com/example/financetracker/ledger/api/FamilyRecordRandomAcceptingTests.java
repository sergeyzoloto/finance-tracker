package com.example.financetracker.ledger.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.TestPropertySource;

/** {@link FamilyRecordRandomTests}'s steps again with family acceptance on, every member accepting after each ({@link AcceptingMode}). */
@Tag("random")
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
class FamilyRecordRandomAcceptingTests extends FamilyRecordRandomTests {

    @Override
    protected Map<Long, BigDecimal> invariants(long family) throws IOException {
        return AcceptingMode.everybodyAccepts(this, family);
    }
}
