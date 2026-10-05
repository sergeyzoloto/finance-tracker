package com.example.financetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/** JvmDefaultsGuard notices a changed time zone or locale, names the class, and puts the defaults back. */
class JvmDefaultsGuardTests {

    @Test
    void unchangedDefaultsPass() {
        JvmDefaultsGuard.check("SomeTests", JvmDefaultsGuard.Defaults.now());
    }

    @Test
    void aChangedTimeZoneFailsTheClassAndIsPutBack() {
        JvmDefaultsGuard.Defaults before = JvmDefaultsGuard.Defaults.now();
        String other = before.timeZone().getID().equals("Etc/GMT+12") ? "Pacific/Kiritimati" : "Etc/GMT+12";
        TimeZone.setDefault(TimeZone.getTimeZone(other));

        assertThatThrownBy(() -> JvmDefaultsGuard.check("SomeTests", before))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("SomeTests left the JVM's defaults changed: time zone " + before.timeZone().getID())
                .hasMessageContaining("time zone " + other);
        assertThat(JvmDefaultsGuard.Defaults.now()).isEqualTo(before);
    }

    @Test
    void aChangedLocaleFailsTheClassAndIsPutBack() {
        JvmDefaultsGuard.Defaults before = JvmDefaultsGuard.Defaults.now();
        Locale other = before.format().equals(Locale.GERMANY) ? Locale.JAPAN : Locale.GERMANY;
        Locale.setDefault(Locale.Category.FORMAT, other);

        assertThatThrownBy(() -> JvmDefaultsGuard.check("SomeTests", before))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("format " + other);
        assertThat(JvmDefaultsGuard.Defaults.now()).isEqualTo(before);
    }
}
