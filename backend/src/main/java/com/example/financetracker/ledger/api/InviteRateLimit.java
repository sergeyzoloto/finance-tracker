package com.example.financetracker.ledger.api;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.example.financetracker.api.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The rate limit of looking up, accepting and declining invites (D-17; ADR 0003, topic G), in memory: per user and
 * per client address, each at most {@code perMinute} attempts in any minute and {@code perHour} in any hour. A 256-bit
 * token can't be guessed anyway; this keeps a script from trying. One api instance runs, as the sessions already
 * assume; a restart forgets the counts.
 * <p>
 * The client address is the request's. In production the auth server's Caddy proxies {@code /api/*} straight to
 * the api (deploy/finance.caddy; the web container serves only the frontend), and sets {@code X-Forwarded-For} to the
 * address its connection came from, replacing whatever the client sent (Caddy 2.11 without {@code trusted_proxies}).
 * Tomcat's {@code RemoteIpValve} ({@code server.forward-headers-strategy: native}) takes the right-most address in it
 * that isn't a trusted proxy's, and trusts private addresses such as Caddy's on the Docker network {@code edge}; the
 * api publishes no port. So each browser counts on its own, and none picks its address ({@code ClientAddressTests}).
 * Only attempts that were let through count, so a caller who waits gets in again.
 * <p>
 * The per-address limit applies only to a public address (D-38). A private, loopback, link-local or shared (CGNAT)
 * address after Tomcat's resolution means the real client is unknown: an IPv6 client reaches Caddy through
 * docker-proxy from the {@code edge} bridge's gateway, which Caddy then names in {@code X-Forwarded-For}. Counting it
 * would put every such client under one limit, so for such an address only the per-user limit applies.
 */
@Component
class InviteRateLimit {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);
    /** Callers kept before the ones without an attempt in the last hour are dropped. */
    private static final int SWEEP_AT = 10_000;

    private final int perMinute;
    private final int perHour;
    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new HashMap<>();

    @Autowired
    InviteRateLimit(@Value("${app.family.invites.per-minute:10}") int perMinute,
            @Value("${app.family.invites.per-hour:50}") int perHour) {
        this(perMinute, perHour, Clock.systemUTC());
    }

    InviteRateLimit(int perMinute, int perHour, Clock clock) {
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.clock = clock;
    }

    /**
     * Counts an attempt by the user from the address.
     *
     * @throws TooManyRequestsException if either of them has had its most attempts, with when the next may come
     */
    synchronized void attempt(String userId, String address) {
        Instant now = clock.instant();
        String user = "user " + userId;
        String from = isPublic(address) ? "address " + address : null;
        Duration wait = from == null ? waitFor(user, now) : max(waitFor(user, now), waitFor(from, now));
        if (!wait.isZero()) {
            throw new TooManyRequestsException("Too many attempts with invite links. Try again in a few minutes",
                    wait);
        }
        attempts.computeIfAbsent(user, key -> new ArrayDeque<>()).addLast(now);
        if (from != null) {
            attempts.computeIfAbsent(from, key -> new ArrayDeque<>()).addLast(now);
        }
        if (attempts.size() > SWEEP_AT) {
            attempts.values().forEach(times -> forget(times, now));
            attempts.values().removeIf(Deque::isEmpty);
        }
    }

    /**
     * Whether the address, an IP literal as Tomcat gives it, is a public one, which names the client (D-38): not
     * private (10/8, 172.16/12, 192.168/16, fc00::/7), loopback, link-local, shared (100.64/10, Tomcat trusts it as a
     * proxy's), unspecified or multicast. Anything that isn't an IP literal names no client either, and is never
     * looked up.
     */
    static boolean isPublic(String address) {
        if (address == null || !address.matches("[0-9A-Fa-f:.%]+")) {
            return false;
        }
        if (!address.contains(":") && (!address.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                || Arrays.stream(address.split("\\.")).anyMatch(octet -> Integer.parseInt(octet) > 255))) {
            return false;
        }
        InetAddress ip;
        try {
            // An IP literal, checked above: IPv4 as four octets, or IPv6, which is never looked up either.
            ip = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return false;
        }
        if (ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isSiteLocalAddress() || ip.isAnyLocalAddress()
                || ip.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = ip.getAddress();
        if (ip instanceof Inet4Address) {
            // 100.64.0.0/10, shared address space.
            return !((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64);
        }
        // fc00::/7, unique local.
        return (bytes[0] & 0xFE) != 0xFC;
    }

    /** How long the caller waits before the next attempt; zero if it may come now. */
    private Duration waitFor(String caller, Instant now) {
        Deque<Instant> times = attempts.get(caller);
        if (times == null) {
            return Duration.ZERO;
        }
        forget(times, now);
        Duration wait = Duration.ZERO;
        if (times.size() >= perHour) {
            wait = Duration.between(now, times.peekFirst().plus(HOUR));
        }
        long lastMinute = times.stream().filter(time -> time.isAfter(now.minus(MINUTE))).count();
        if (lastMinute >= perMinute) {
            Instant first = times.stream().filter(time -> time.isAfter(now.minus(MINUTE))).findFirst().orElseThrow();
            wait = max(wait, Duration.between(now, first.plus(MINUTE)));
        }
        return wait;
    }

    /** Drops the attempts older than an hour. */
    private static void forget(Deque<Instant> times, Instant now) {
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(HOUR))) {
            times.removeFirst();
        }
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
