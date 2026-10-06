package com.example.financetracker.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * D-53: {@link Today} is the date in its clock's zone, and with no clock bean in the JVM's default zone, which is the
 * database session's too. Never a zone of its own, never UTC unless the clock is. The instants cross midnight in one
 * zone or another: 23:30 UTC on 6 October is already the 7th in Amsterdam (UTC+2 then) and at UTC+14
 * (Pacific/Kiritimati), and still the 6th at UTC and UTC-12; 00:30 UTC on the 7th is still the 6th at UTC-12.
 */
class TodayTests {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId PLUS_14 = ZoneId.of("Pacific/Kiritimati");
    private static final ZoneId MINUS_12 = ZoneId.of("Etc/GMT+12");
    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

    private record Crossing(String instant, String utc, String amsterdam, String plus14, String minus12) {
    }

    private static final List<Crossing> CROSSINGS = List.of(
            new Crossing("2026-10-06T23:30:00Z", "2026-10-06", "2026-10-07", "2026-10-07", "2026-10-06"),
            new Crossing("2026-10-07T00:30:00Z", "2026-10-07", "2026-10-07", "2026-10-07", "2026-10-06"),
            new Crossing("2026-10-06T09:30:00Z", "2026-10-06", "2026-10-06", "2026-10-06", "2026-10-05"),
            new Crossing("2026-10-06T10:30:00Z", "2026-10-06", "2026-10-06", "2026-10-07", "2026-10-05"),
            // The turn of a month and of a year, the dates D-47's totals and the reports' months turn on.
            new Crossing("2026-10-31T23:30:00Z", "2026-10-31", "2026-11-01", "2026-11-01", "2026-10-31"),
            new Crossing("2026-12-31T23:30:00Z", "2026-12-31", "2027-01-01", "2027-01-01", "2026-12-31"));

    @Test
    void itIsTheDateInTheClocksZoneWhateverTheJvmsZoneIs() {
        for (Crossing crossing : CROSSINGS) {
            Instant instant = Instant.parse(crossing.instant());
            assertThat(today(Clock.fixed(instant, UTC)).date()).as(crossing.instant() + " at UTC")
                    .isEqualTo(LocalDate.parse(crossing.utc()));
            assertThat(today(Clock.fixed(instant, AMSTERDAM)).date()).as(crossing.instant() + " in Amsterdam")
                    .isEqualTo(LocalDate.parse(crossing.amsterdam()));
            assertThat(today(Clock.fixed(instant, PLUS_14)).date()).as(crossing.instant() + " at UTC+14")
                    .isEqualTo(LocalDate.parse(crossing.plus14()));
            assertThat(today(Clock.fixed(instant, MINUS_12)).date()).as(crossing.instant() + " at UTC-12")
                    .isEqualTo(LocalDate.parse(crossing.minus12()));
        }
    }

    /** Without a clock bean, as in production: the JVM's default zone, which is UTC in the api's image. */
    @Test
    void withoutAClockItIsTheJvmsDateInItsDefaultZone() {
        Today today = today(null);
        for (int attempt = 0; attempt < 3; attempt++) {
            LocalDate before = LocalDate.now(ZoneId.systemDefault());
            LocalDate date = today.date();
            LocalDate after = LocalDate.now(ZoneId.systemDefault());
            if (before.equals(after)) {
                assertThat(date).isEqualTo(before);
                return;
            }
        }
        throw new AssertionError("The date turned over in each of three attempts");
    }

    private static Today today(Clock clock) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (clock != null) {
            beans.registerSingleton("clock", clock);
        }
        return new Today(beans.getBeanProvider(Clock.class));
    }
}
