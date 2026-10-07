package com.example.financetracker.hygiene.fixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * What {@code TestHygieneTests} must refuse: a date or a moment from a clock nobody named. It is excluded from the
 * real rules and imported alone by the tests of the rules. The last three methods are what the rule allows.
 */
public final class ImplicitClocks {

    private ImplicitClocks() {
    }

    public static Object localDate() {
        return LocalDate.now();
    }

    public static Object localDateTime() {
        return LocalDateTime.now();
    }

    public static Object localTime() {
        return LocalTime.now();
    }

    public static Object zonedDateTime() {
        return ZonedDateTime.now();
    }

    public static Object offsetDateTime() {
        return OffsetDateTime.now();
    }

    public static Object instant() {
        return Instant.now();
    }

    public static Object year() {
        return Year.now();
    }

    public static Object yearMonth() {
        return YearMonth.now();
    }

    public static Object systemDefaultZone() {
        return Clock.systemDefaultZone();
    }

    public static Supplier<Instant> methodReference() {
        return Instant::now;
    }

    public static Supplier<Object> lambda() {
        return () -> LocalDate.now();
    }

    public static Object withAClockOrAZone(Clock clock) {
        return LocalDate.now(clock).toString() + LocalDate.now(ZoneOffset.UTC) + Instant.now(clock)
                + ZonedDateTime.now(ZoneOffset.UTC) + Clock.systemUTC();
    }
}
