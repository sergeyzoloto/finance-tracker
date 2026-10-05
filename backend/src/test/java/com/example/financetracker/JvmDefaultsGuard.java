package com.example.financetracker;

import java.util.Locale;
import java.util.TimeZone;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test class that leaves the JVM's default time zone or locale changed, and puts them back, so that the classes
 * after it run as before (OPS-2b). Both are JVM-wide: a class that changes them changes every class after it, in an
 * order that differs between runners. IntegrationTest's static block used to switch the zone to Pacific/Kiritimati at
 * the first integration test, so that the classes before it ran in the runner's zone; the test JVM now starts in that
 * zone (pom.xml, {@code user.timezone}). Registered for every class by
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension}.
 */
public class JvmDefaultsGuard implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(JvmDefaultsGuard.class);

    /** The defaults that a test class must leave as it found them. */
    record Defaults(TimeZone timeZone, Locale locale, Locale display, Locale format) {

        static Defaults now() {
            return new Defaults(TimeZone.getDefault(), Locale.getDefault(), Locale.getDefault(Locale.Category.DISPLAY),
                    Locale.getDefault(Locale.Category.FORMAT));
        }

        void restore() {
            TimeZone.setDefault(timeZone);
            Locale.setDefault(locale);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }

        @Override
        public String toString() {
            return "time zone %s, locale %s (display %s, format %s)".formatted(timeZone.getID(), locale, display, format);
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getStore(NAMESPACE).put(Defaults.class, Defaults.now());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        check(context.getRequiredTestClass().getName(), context.getStore(NAMESPACE).get(Defaults.class, Defaults.class));
    }

    /** Throws when the defaults differ from {@code before}, after putting them back. */
    static void check(String testClass, Defaults before) {
        Defaults after = Defaults.now();
        if (!after.equals(before)) {
            before.restore();
            throw new AssertionError("%s left the JVM's defaults changed: %s before it, %s after it. Restore them in "
                    .formatted(testClass, before, after) + "the class, or set them for the whole test JVM in pom.xml.");
        }
    }
}
