package com.example.financetracker.ledger.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** {@link FamilyLifecycleRandomTests}'s steps again with family acceptance on, every member accepting after each ({@link AcceptingMode}). */
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
// A context of its own: closed after the class, so that its connection pools don't add to the cached contexts'.
@DirtiesContext
class FamilyLifecycleRandomAcceptingTests extends FamilyLifecycleRandomTests {

    @Override
    protected Map<Long, BigDecimal> invariants(long family) throws IOException {
        return AcceptingMode.everybodyAccepts(this, family);
    }
}
