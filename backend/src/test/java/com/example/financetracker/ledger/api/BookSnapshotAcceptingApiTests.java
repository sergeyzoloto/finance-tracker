package com.example.financetracker.ledger.api;

import java.io.IOException;

import com.example.financetracker.ledger.family.FamilyAcceptanceSwitch;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link BookSnapshotApiTests} with acceptance on (D-1327): the other member's save posts to him only and his Accept
 * takes the family row as a save does; after each test everybody accepts, and the invariants of acceptance hold.
 */
@TestPropertySource(properties = FamilyAcceptanceSwitch.PROPERTY + "=true")
class BookSnapshotAcceptingApiTests extends BookSnapshotApiTests {

    @Override
    HttpStatus acceptAnswers() {
        return HttpStatus.OK;
    }

    @AfterEach
    @Override
    void theInvariantsHold() {
        try {
            AcceptingMode.everybodyAccepts(this, family);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
