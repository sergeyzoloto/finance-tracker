package com.example.financetracker;

/**
 * The volume and the seed of the random tests ({@code *Random*}, tagged {@code random}; TEST-1b, D-1204). The system
 * property {@code tests.random.scale} (0 < scale <= 1, default 1: full volume, today's numbers, which CI runs) scales
 * the number of iterations; {@code tests.random.seed} replays one seed. No helper changes what one iteration checks.
 */
public final class RandomVolume {

    /** A test body that may throw a checked exception. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private RandomVolume() {
    }

    /** The scale of this run: {@code tests.random.scale}, in (0, 1], default 1. */
    public static double scale() {
        String text = System.getProperty("tests.random.scale", "1");
        double scale;
        try {
            scale = Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("tests.random.scale must be a number with 0 < scale <= 1, got '" + text
                    + "'", e);
        }
        if (!(scale > 0 && scale <= 1)) {
            throw new IllegalArgumentException("tests.random.scale must be a number with 0 < scale <= 1, got '" + text
                    + "'");
        }
        return scale;
    }

    /** A number of rounds: the full number at scale 1, else the scaled one, at least 1. */
    public static int rounds(int full) {
        double scale = scale();
        return scale < 1 ? Math.max(1, (int) Math.round(full * scale)) : full;
    }

    /**
     * A number of operations or steps: the full number at scale 1, else the scaled one with a floor of 5 (so that every
     * kind of step stays present), never above the full number.
     */
    public static int count(int full) {
        double scale = scale();
        return scale == 1 ? full : Math.min(full, Math.max(5, (int) Math.round(full * scale)));
    }

    /** The seed to use: {@code tests.random.seed} when set, else the class's own. */
    public static long seedOr(long classSeed) {
        String text = System.getProperty("tests.random.seed");
        if (text == null || text.isBlank()) {
            return classSeed;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("tests.random.seed must be a long, got '" + text + "'", e);
        }
    }

    /** Full volume and no seed override: only then the end-of-test fingerprint of the random mix holds. */
    public static boolean fullVolume() {
        String seed = System.getProperty("tests.random.seed");
        return scale() == 1 && (seed == null || seed.isBlank());
    }

    /** Runs a test body; a failure is rethrown as an AssertionError that names the seed and the replay command. */
    public static void run(Class<?> test, long seed, ThrowingRunnable body) {
        try {
            body.run();
        } catch (org.opentest4j.TestAbortedException | VirtualMachineError unwrapped) {
            throw unwrapped;
        } catch (Throwable failure) {
            String name = test.getSimpleName();
            throw new AssertionError("random test " + name + " failed with seed " + seed + ", scale " + scale()
                    + "; replay: ./mvnw -B test -Dtest=" + name + " -Dtests.random.seed=" + seed
                    + " -Dtests.random.scale=" + scale() + " (" + failure + ")", failure);
        }
    }
}
