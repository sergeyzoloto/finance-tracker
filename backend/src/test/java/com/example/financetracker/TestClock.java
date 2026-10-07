package com.example.financetracker;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The clock of the application's {@code ledger.Today} in the tests: the system's, until a test sets a moment of its
 * choosing, such as 23:30 UTC on a summer evening, when Amsterdam is already on the next day. A test that sets it
 * resets it afterwards; the other tests of the context share it.
 */
public final class TestClock extends Clock {

    private volatile Clock clock = Clock.systemUTC();

    /** From now on, the time stands still at the moment, in the zone. */
    public void set(Instant instant, ZoneId zone) {
        clock = Clock.fixed(instant, zone);
    }

    /** Back to the system's clock. */
    public void reset() {
        clock = Clock.systemUTC();
    }

    @Override
    public ZoneId getZone() {
        return clock.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return clock.withZone(zone);
    }

    @Override
    public Instant instant() {
        return clock.instant();
    }
}
