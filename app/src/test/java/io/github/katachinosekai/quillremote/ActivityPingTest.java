package io.github.katachinosekai.quillremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ActivityPingTest {
    /** Runs queued pings only when told to, so a test sees exactly what was queued. */
    static final class Queue implements java.util.concurrent.Executor {
        final ArrayDeque<Runnable> q = new ArrayDeque<>();

        public void execute(Runnable r) {
            q.add(r);
        }

        void drain() {
            while (!q.isEmpty()) {
                q.poll().run();
            }
        }
    }

    long now = 1_000_000;
    boolean unlocked = true;
    final List<ActivityPing.Result> replies = new ArrayList<>();
    int sent;
    int locked;
    final Queue queue = new Queue();
    final ActivityPing ping = new ActivityPing(() -> {
        sent++;
        return replies.isEmpty() ? new ActivityPing.Result(true, false, -1) : replies.remove(0);
    }, queue, () -> now, () -> unlocked, () -> locked++);

    @Test
    public void typingOrTouchSendsAPing() {
        ping.touch();
        queue.drain();
        assertEquals(1, sent);
    }

    @Test
    public void throttledToOncePerMinuteAfterSuccess() {
        ping.touch();
        queue.drain();
        now += 59_999;
        ping.touch();
        queue.drain();
        assertEquals(1, sent);
        now += 1;
        ping.touch();
        queue.drain();
        assertEquals(2, sent);
    }

    @Test
    public void manyTouchesBeforeTheReplyQueueOnlyOne() {
        for (int i = 0; i < 20; i++) {
            ping.touch();
        }
        assertEquals(1, queue.q.size());
    }

    @Test
    public void failedPingIsRetriedAtTheNextInteraction() {
        replies.add(new ActivityPing.Result(false, false, -1));
        ping.touch();
        queue.drain();
        now += 1000; // well inside the minute, but the first ping never arrived
        ping.touch();
        queue.drain();
        assertEquals(2, sent);
        now += 1000;
        ping.touch();
        queue.drain();
        assertEquals(2, sent); // the second one did arrive: throttled now
    }

    @Test
    public void nothingIsSentWhileLocked() {
        unlocked = false;
        ping.touch();
        assertTrue(queue.q.isEmpty());
    }

    @Test
    public void resetCountsTheUnlockAsActivity() {
        ping.reset();
        now += 10_000;
        ping.touch();
        assertTrue(queue.q.isEmpty());
    }

    @Test
    public void idleLeftIsStoredWhenReported() {
        assertEquals(-1, ping.idleLeftSec);
        replies.add(new ActivityPing.Result(true, false, 840));
        ping.touch();
        queue.drain();
        assertEquals(840, ping.idleLeftSec);
    }

    @Test
    public void expiredTokenReportsLocked() {
        replies.add(new ActivityPing.Result(false, true, -1));
        ping.touch();
        queue.drain();
        assertEquals(1, locked);
    }

    @Test
    public void pingRunsOnItsOwnExecutorEvenWhileTheWorkExecutorIsBlocked() throws Exception {
        ExecutorService work = Executors.newSingleThreadExecutor();
        ExecutorService pings = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch sentNow = new CountDownLatch(1);
        try {
            work.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                }
            });
            ActivityPing p = new ActivityPing(() -> {
                sentNow.countDown();
                return new ActivityPing.Result(true, false, -1);
            }, pings, () -> now, () -> true, null);
            p.touch();
            assertTrue(sentNow.await(2, TimeUnit.SECONDS));
            assertFalse(release.getCount() == 0);
        } finally {
            release.countDown();
            work.shutdownNow();
            pings.shutdownNow();
        }
    }
}
