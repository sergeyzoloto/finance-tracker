package com.example.financetracker.ledger;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Today's date for the family budget's rules: a start date (D-27), a new member's and a claim's join date (D-18), a
 * leave date (D-19). It is the date in the api's time zone, which is also the database session's (the JDBC driver
 * gives the session the JVM's zone), so it is the {@code current_date} that the triggers check against; in production
 * the api runs in UTC. A browser a time zone ahead may already be on the next day: the forms leave a date out when it
 * is the browser's today, so that this one counts.
 * <p>
 * The tests set the clock to a moment of their choosing through a {@link Clock} bean; the application has none and
 * uses the system clock in the default zone.
 */
@Component
public class Today {

    private final Clock clock;

    Today(ObjectProvider<Clock> clock) {
        this.clock = clock.getIfAvailable(Clock::systemDefaultZone);
    }

    public LocalDate date() {
        return LocalDate.now(clock);
    }
}
