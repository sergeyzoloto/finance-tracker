package com.example.financetracker.ledger.history;

/**
 * What the versions written on this thread say about why, until the frame closes: an import's batch, the demo, a
 * member's departure (ADR 0006, 5.1). Without a frame a version's cause is {@link Cause#EDIT} and its actor the signed-in
 * user. The importer's command line, which has no signed-in user, names the sub.
 */
public final class HistoryContext {

    private static final ThreadLocal<Frame> FRAMES = new ThreadLocal<>();

    private HistoryContext() {
    }

    /** One frame's data; the batch is set once the import has made its batch row. */
    public static final class Frame implements AutoCloseable {
        private final Frame outer;
        private final Cause cause;
        private final String actorSub;
        private Long batchId;

        private Frame(Frame outer, Cause cause, Long batchId, String actorSub) {
            this.outer = outer;
            this.cause = cause;
            this.batchId = batchId;
            this.actorSub = actorSub;
        }

        public void batch(long id) {
            this.batchId = id;
        }

        @Override
        public void close() {
            if (outer == null) {
                FRAMES.remove();
            } else {
                FRAMES.set(outer);
            }
        }
    }

    /** Opens a frame; close it in a try-with-resources. The actor is the signed-in user unless {@code actorSub} is set. */
    public static Frame open(Cause cause, String actorSub) {
        Frame frame = new Frame(FRAMES.get(), cause, null, actorSub);
        FRAMES.set(frame);
        return frame;
    }

    /** Names the import batch for the versions written from now on in the innermost frame. */
    public static void batch(long batchId) {
        Frame frame = FRAMES.get();
        if (frame != null) {
            frame.batchId = batchId;
        }
    }

    static Cause cause() {
        Frame frame = FRAMES.get();
        return frame == null ? Cause.EDIT : frame.cause;
    }

    static Long batchId() {
        Frame frame = FRAMES.get();
        return frame == null ? null : frame.batchId;
    }

    static String actorSub() {
        Frame frame = FRAMES.get();
        return frame == null ? null : frame.actorSub;
    }
}
