package com.example.financetracker.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.example.financetracker.api.TooManyRequestsException;
import org.junit.jupiter.api.Test;

/** The invites' rate limit (D-17): per user and per client address, in any minute and in any hour. */
class InviteRateLimitTests {

    /** A clock that moves only when told to. */
    private static final class Steps extends Clock {

        private Instant now = Instant.parse("2026-10-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final Steps clock = new Steps();
    private final InviteRateLimit limit = new InviteRateLimit(10, 50, clock);

    @Test
    void tenAMinutePerUserThenWaitUntilTheFirstIsAMinuteOld() {
        for (int i = 0; i < 10; i++) {
            limit.attempt("anna", "203.0.113.1");
            clock.advance(Duration.ofSeconds(3));
        }
        assertThatThrownBy(() -> limit.attempt("anna", "203.0.113.2"))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(30)));
        // Someone else from another address isn't held up.
        assertThatCode(() -> limit.attempt("boris", "203.0.113.3")).doesNotThrowAnyException();
        clock.advance(Duration.ofSeconds(30));
        assertThatCode(() -> limit.attempt("anna", "203.0.113.2")).doesNotThrowAnyException();
    }

    @Test
    void fiftyAnHourPerAddressThenWaitUntilTheFirstIsAnHourOld() {
        for (int i = 0; i < 50; i++) {
            limit.attempt("user " + i, "198.51.100.7");
            clock.advance(Duration.ofSeconds(60));
        }
        assertThatThrownBy(() -> limit.attempt("someone new", "198.51.100.7"))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(10)));
        clock.advance(Duration.ofMinutes(10));
        assertThatCode(() -> limit.attempt("someone new", "198.51.100.7")).doesNotThrowAnyException();
    }

    /** D-38: only a public address names a client; any other is limited by the user alone. */
    @Test
    void onlyAPublicAddressHasALimitOfItsOwn() {
        for (String address : List.of("203.0.113.1", "8.8.8.8", "2001:db8::1", "2a01:4f8::1", "::ffff:203.0.113.1")) {
            assertThat(InviteRateLimit.isPublic(address)).as(address).isTrue();
        }
        for (String address : List.of("10.0.0.1", "172.16.0.1", "172.19.0.1", "172.31.255.255", "192.168.1.1",
                "127.0.0.1", "169.254.1.1", "100.64.0.1", "100.127.255.255", "0.0.0.0", "::1", "::", "fe80::1",
                "fe80::1%eth0", "fc00::1", "fd12:3456::1", "::ffff:172.19.0.1", "224.0.0.1", "ff02::1", "", "  ",
                "localhost", "example.com", "unknown", "1.2.3", "300.1.1.1")) {
            assertThat(InviteRateLimit.isPublic(address)).as(address).isFalse();
        }
        assertThat(InviteRateLimit.isPublic(null)).isFalse();
        // Next to them, 172.15/16 and 172.32/16 and 100.63/16 and 100.128/16 are public.
        for (String address : List.of("172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1")) {
            assertThat(InviteRateLimit.isPublic(address)).as(address).isTrue();
        }

        for (int i = 0; i < 60; i++) {
            limit.attempt("user " + i, "172.19.0.1");
        }
        for (int i = 0; i < 10; i++) {
            limit.attempt("anna", "172.19.0.1");
        }
        assertThatThrownBy(() -> limit.attempt("anna", "172.19.0.1")).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limit.attempt("boris", "172.19.0.1")).doesNotThrowAnyException();
    }

    @Test
    void aRefusedAttemptDoesntCount() {
        for (int i = 0; i < 10; i++) {
            limit.attempt("anna", "203.0.113.1");
        }
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limit.attempt("anna", "203.0.113.1")).isInstanceOf(TooManyRequestsException.class);
        }
        clock.advance(Duration.ofMinutes(1).plusMillis(1));
        for (int i = 0; i < 10; i++) {
            limit.attempt("anna", "203.0.113.1");
        }
    }
}
