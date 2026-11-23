package io.github.katachinosekai.quillremote;

import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Keeps Quill Desktop's device unlock alive while the user is active (touches, typing; user 2026-11-24: nothing else
 * counts, and the idle time stays 15 minutes). At most one ping a minute, sent on its own executor so slow fetches
 * never hold it back, and the minute only starts once Quill Desktop has answered (a failed ping is tried again with the
 * next interaction).
 */
final class ActivityPing {
    static final long THROTTLE_MS = 60_000;

    /** What came back from one ping. */
    static final class Result {
        final boolean ok;
        /** Quill Desktop said the unlock token ran out (401 locked). */
        final boolean locked;
        /** Seconds Quill Desktop's idle timer had left (idleLeftSec), or -1 when it didn't say. */
        final long idleLeftSec;

        Result(boolean ok, boolean locked, long idleLeftSec) {
            this.ok = ok;
            this.locked = locked;
            this.idleLeftSec = idleLeftSec;
        }
    }

    interface Sender {
        Result ping();
    }

    private final Sender sender;
    private final Executor executor;
    private final LongSupplier clock;
    private final java.util.function.BooleanSupplier unlocked;
    private final Runnable onLocked;
    private long lastOk;
    private boolean inFlight;
    private boolean everOk;
    /** The idle seconds left that Quill Desktop reported with the last ping, -1 = unknown (no UI yet). */
    volatile long idleLeftSec = -1;

    ActivityPing(Sender sender, Executor executor, LongSupplier clock, java.util.function.BooleanSupplier unlocked, Runnable onLocked) {
        this.sender = sender;
        this.executor = executor;
        this.clock = clock;
        this.unlocked = unlocked;
        this.onLocked = onLocked;
    }

    /** The unlock just happened: that counts as activity, so the first ping can wait a minute. */
    synchronized void reset() {
        lastOk = clock.getAsLong();
        everOk = true;
    }

    /** Any touch or typing. Cheap; call as often as you like. */
    void touch() {
        synchronized (this) {
            long now = clock.getAsLong();
            if (inFlight || !unlocked.getAsBoolean() || (everOk && now - lastOk < THROTTLE_MS)) {
                return;
            }
            inFlight = true;
        }
        try {
            executor.execute(this::send);
        } catch (RuntimeException e) {
            synchronized (this) {
                inFlight = false;
            }
        }
    }

    private void send() {
        Result r = null;
        try {
            r = sender.ping();
        } finally {
            synchronized (this) {
                inFlight = false;
                if (r != null && r.ok) {
                    lastOk = clock.getAsLong();
                    everOk = true;
                }
            }
        }
        if (r == null) {
            return;
        }
        if (r.ok && r.idleLeftSec >= 0) {
            idleLeftSec = r.idleLeftSec;
        }
        if (r.locked && onLocked != null) {
            onLocked.run();
        }
    }
}
