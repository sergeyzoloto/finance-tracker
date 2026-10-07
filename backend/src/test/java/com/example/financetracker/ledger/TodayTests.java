package com.example.financetracker.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * D-53, D-100, D-101: {@link Today} is the date in the acting user's own time zone, and in UTC for a user who has set
 * none. The clock's zone plays no part. The instants cross midnight in one zone or another: 23:30 UTC on 6 October is
 * already the 7th in Amsterdam (UTC+2 then) and at UTC+14 (Pacific/Kiritimati), still the 6th at UTC, in Los Angeles
 * (UTC-7) and at UTC-12; 03:00 UTC on the 7th is still the evening of the 6th in Los Angeles.
 */
class TodayTests {

    private static final String UTC = "UTC";
    private static final String PLUS_14 = "Pacific/Kiritimati";
    private static final String MINUS_12 = "Etc/GMT+12";
    private static final String AMSTERDAM = "Europe/Amsterdam";
    private static final String LOS_ANGELES = "America/Los_Angeles";

    /** The same instant for each zone: its date there, written out by hand. */
    private record Crossing(String instant, String utc, String amsterdam, String plus14, String minus12,
            String losAngeles) {
    }

    private static final List<Crossing> CROSSINGS = List.of(
            new Crossing("2026-10-06T23:30:00Z", "2026-10-06", "2026-10-07", "2026-10-07", "2026-10-06", "2026-10-06"),
            new Crossing("2026-10-07T00:30:00Z", "2026-10-07", "2026-10-07", "2026-10-07", "2026-10-06", "2026-10-06"),
            new Crossing("2026-10-07T03:00:00Z", "2026-10-07", "2026-10-07", "2026-10-07", "2026-10-06", "2026-10-06"),
            new Crossing("2026-10-07T07:30:00Z", "2026-10-07", "2026-10-07", "2026-10-07", "2026-10-06", "2026-10-07"),
            new Crossing("2026-10-06T09:30:00Z", "2026-10-06", "2026-10-06", "2026-10-06", "2026-10-05", "2026-10-06"),
            new Crossing("2026-10-06T10:30:00Z", "2026-10-06", "2026-10-06", "2026-10-07", "2026-10-05", "2026-10-06"),
            // The turn of a month and of a year, the dates D-47's totals and the reports' months turn on.
            new Crossing("2026-10-31T23:30:00Z", "2026-10-31", "2026-11-01", "2026-11-01", "2026-10-31", "2026-10-31"),
            new Crossing("2026-12-31T23:30:00Z", "2026-12-31", "2027-01-01", "2027-01-01", "2026-12-31", "2026-12-31"));

    @Test
    void itIsTheDateInTheUsersZoneAtEachCrossing() {
        for (Crossing crossing : CROSSINGS) {
            Instant instant = Instant.parse(crossing.instant());
            // Whatever zone the clock itself is in.
            for (ZoneId clockZone : List.of(ZoneOffset.UTC, ZoneId.of(PLUS_14), ZoneId.of(MINUS_12))) {
                Settings users = new Settings();
                users.set("utc", UTC);
                users.set("ams", AMSTERDAM);
                users.set("kir", PLUS_14);
                users.set("west", MINUS_12);
                users.set("la", LOS_ANGELES);
                Today today = today(Clock.fixed(instant, clockZone), users);
                assertThat(today.date("utc")).as(crossing.instant() + " at UTC")
                        .isEqualTo(LocalDate.parse(crossing.utc()));
                assertThat(today.date("ams")).as(crossing.instant() + " in Amsterdam")
                        .isEqualTo(LocalDate.parse(crossing.amsterdam()));
                assertThat(today.date("kir")).as(crossing.instant() + " at UTC+14")
                        .isEqualTo(LocalDate.parse(crossing.plus14()));
                assertThat(today.date("west")).as(crossing.instant() + " at UTC-12")
                        .isEqualTo(LocalDate.parse(crossing.minus12()));
                assertThat(today.date("la")).as(crossing.instant() + " in Los Angeles")
                        .isEqualTo(LocalDate.parse(crossing.losAngeles()));
                // Not set, and not even a settings row: the UTC date.
                assertThat(today.date("nobody")).as(crossing.instant() + " with no zone")
                        .isEqualTo(LocalDate.parse(crossing.utc()));
                users.rows.put("unset", Optional.empty());
                assertThat(today.date("unset")).as(crossing.instant() + " with no zone set")
                        .isEqualTo(LocalDate.parse(crossing.utc()));
                assertThat(today.utc()).isEqualTo(LocalDate.parse(crossing.utc()));
            }
        }
    }

    /** Two users acting at one instant each get their own date. */
    @Test
    void usersInDifferentZonesGetTheirOwnTodayAtTheSameInstant() {
        Settings users = new Settings();
        users.set("kir", PLUS_14);
        users.set("la", LOS_ANGELES);
        Today today = today(Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC), users);
        assertThat(today.date("kir")).isEqualTo(LocalDate.parse("2026-10-07"));
        assertThat(today.date("la")).isEqualTo(LocalDate.parse("2026-10-06"));
    }

    @Test
    void aStoredIdThatThisJvmDoesNotKnowReadsAsNoZone() {
        Settings users = new Settings();
        users.rows.put("odd", Optional.of("Mars/Olympus_Mons"));
        Today today = today(Clock.fixed(Instant.parse("2026-10-06T23:30:00Z"), ZoneOffset.UTC), users);
        assertThat(today.date("odd")).isEqualTo(LocalDate.parse("2026-10-06"));
        assertThat(today.zone("odd")).isEqualTo(ZoneOffset.UTC);
    }

    /** Without a clock bean, as in production: the system clock, and the user's zone, never the JVM's. */
    @Test
    void withoutAClockItIsTheSystemsMomentInTheUsersZone() {
        Settings users = new Settings();
        Today today = today(null, users);
        users.set("kir", PLUS_14);
        for (int attempt = 0; attempt < 3; attempt++) {
            LocalDate before = LocalDate.now(ZoneId.of(PLUS_14));
            LocalDate date = today.date("kir");
            LocalDate utc = today.date("nobody");
            if (before.equals(LocalDate.now(ZoneId.of(PLUS_14))) && utc.equals(LocalDate.now(ZoneOffset.UTC))) {
                assertThat(date).isEqualTo(before);
                return;
            }
        }
        throw new AssertionError("The date turned over in each of three attempts");
    }

    private static Today today(Clock clock, Settings users) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (clock != null) {
            beans.registerSingleton("clock", clock);
        }
        return new Today(beans.getBeanProvider(Clock.class), users);
    }

    /** The settings, in memory: a user's zone, or none. */
    private static final class Settings implements UserSettingsRepository {

        final Map<String, Optional<String>> rows = new HashMap<>();

        void set(String userId, String zone) {
            rows.put(userId, Optional.of(zone));
        }

        @Override
        public Optional<UserSettings> findById(String userId) {
            return Optional.empty();
        }

        @Override
        public boolean insertIfAbsent(String userId, String baseCurrency) {
            return false;
        }

        @Override
        public Optional<String> findTimeZone(String userId) {
            return rows.getOrDefault(userId, Optional.empty());
        }

        @Override
        public boolean updateTimeZone(String userId, String timeZone) {
            rows.put(userId, Optional.of(timeZone));
            return true;
        }

        @Override
        public void upsert(String userId, String baseCurrency, Long sharedAccountId, BigDecimal defaultShareRatio) {
        }
    }
}
