package com.example.financetracker.ledger;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.example.financetracker.ledger.access.LedgerScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Today's date for everything the api decides or checks against "today": a start date (D-27), a new member's and a
 * claim's join date (D-18), a leave date (D-19), the reports' default day, the rates that apply today, D-47's totals
 * and the demo's last day.
 * <p>
 * By D-100 and D-101 it is the date in the acting user's own time zone, the IANA id in their settings
 * ({@link #zone}); a user who has set none is on the UTC date. In a family budget it is the acting member's zone: two
 * members in different zones each get their own today. Callers pass the user ({@link #date(String)}) or the ledger
 * scope they act through ({@link #date(LedgerScope)}); there is no date without a user, because a default would only
 * hide a caller that forgot one. The contexts with no user, which stay on UTC, name it ({@link #utc()}).
 * <p>
 * The tests set the clock to a moment of their choosing through a {@link Clock} bean; the application has none and
 * uses the system clock. The clock's own zone plays no part: the zone is the user's.
 */
@Component
public class Today {

    private final Clock clock;
    private final UserSettingsRepository settings;

    Today(ObjectProvider<Clock> clock, UserSettingsRepository settings) {
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        this.settings = settings;
    }

    /** The date in the user's time zone, or in UTC if they have set none. */
    public LocalDate date(String userId) {
        return LocalDate.now(clock.withZone(zone(userId)));
    }

    /** The date in the zone of the member that the scope belongs to. */
    public LocalDate date(LedgerScope scope) {
        return date(scope.userId());
    }

    /** The date in a given zone, at the clock's moment. */
    public LocalDate date(ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    /**
     * The date in UTC, for work that no user triggers (D-101). Nothing in the api's request paths calls it.
     */
    public LocalDate utc() {
        return date(ZoneOffset.UTC);
    }

    /** The user's zone, or UTC for a user who has set none (or has no settings yet). */
    public ZoneId zone(String userId) {
        return settings.findTimeZone(userId).map(Today::zoneOf).orElse(ZoneOffset.UTC);
    }

    /** A stored id this JVM's zone data doesn't know reads as no zone, so that a request never fails for it. */
    private static ZoneId zoneOf(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
