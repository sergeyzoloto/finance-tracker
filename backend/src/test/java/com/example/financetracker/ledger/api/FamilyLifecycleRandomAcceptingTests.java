package com.example.financetracker.ledger.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.TestPropertySource;

/** {@link FamilyLifecycleRandomTests}'s steps again with family acceptance on, every member accepting after each ({@link AcceptingMode}). */
@Tag("random")
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
class FamilyLifecycleRandomAcceptingTests extends FamilyLifecycleRandomTests {

    @Override
    protected Map<Long, BigDecimal> invariants(long family) throws IOException {
        return AcceptingMode.everybodyAccepts(this, family);
    }
}
