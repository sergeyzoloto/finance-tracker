package com.example.financetracker;

import java.time.Clock;
import java.time.Instant;

/**
 * The moment, for a timestamp: when a row was created or archived, when a token expires, when an import started. The
 * system's UTC clock, named (D-117: no {@code now()} without a clock or a zone, in the application or its tests). An
 * instant has no zone, so UTC is no choice but the way to say that the clock is the system's.
 * <p>
 * It is not the application's date. A date is {@code ledger.Today}'s, in the acting user's time zone (D-100, D-101),
 * and the tests move that clock; they never move this one, whose timestamps they read back against the real time.
 */
public final class WallClock {

    private static final Clock SYSTEM = Clock.systemUTC();

    private WallClock() {
    }

    public static Instant now() {
        return Instant.now(SYSTEM);
    }
}
