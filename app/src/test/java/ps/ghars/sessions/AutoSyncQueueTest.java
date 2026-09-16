package ps.ghars.sessions;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AutoSyncQueueTest {
    private static final class Task implements AutoSyncQueue.Ticket {
        final Runnable work; final long delay; boolean cancelled;
        Task(Runnable work, long delay) { this.work = work; this.delay = delay; }
        public void cancel() { cancelled = true; }
    }
    private static final class Timer implements AutoSyncQueue.Timer {
        final List<Task> tasks = new ArrayList<>();
        public AutoSyncQueue.Ticket after(Runnable work, long delay) {
            Task task = new Task(work, delay); tasks.add(task); return task;
        }
        Task next() {
            while (!tasks.isEmpty()) { Task t = tasks.remove(0); if (!t.cancelled) return t; }
            throw new AssertionError("No pending task");
        }
        int pending() { int n = 0; for (Task t : tasks) if (!t.cancelled) n++; return n; }
    }
    @Test public void savingStartsWithoutAnAndroidJobAndCoalescesRepeatedRequests() {
        Timer timer = new Timer(); AtomicInteger calls = new AtomicInteger();
        AutoSyncQueue queue = new AutoSyncQueue(timer, () -> { calls.incrementAndGet(); return false; });
        queue.request(1000); queue.request(1000); queue.request(1000);
        assertEquals(1, timer.pending()); assertEquals(0, calls.get());
        timer.next().work.run(); assertEquals(1, calls.get()); assertEquals(0, timer.pending());
    }
    @Test public void offlineFailureRetriesAndManualSyncDoesNotWaitForBackoff() {
        Timer timer = new Timer(); AtomicInteger calls = new AtomicInteger();
        AutoSyncQueue queue = new AutoSyncQueue(timer, () -> calls.incrementAndGet() == 1);
        queue.request(0); timer.next().work.run();
        assertEquals(30000, timer.tasks.get(0).delay);
        queue.request(0); Task now = timer.next(); assertEquals(0, now.delay);
        now.work.run(); assertEquals(2, calls.get()); assertEquals(0, timer.pending());
    }
    @Test public void savesDuringUploadTriggerAnotherAttempt() {
        Timer timer = new Timer(); AtomicInteger calls = new AtomicInteger();
        AutoSyncQueue[] holder = new AutoSyncQueue[1];
        holder[0] = new AutoSyncQueue(timer, () -> {
            if (calls.incrementAndGet() == 1) { holder[0].request(1000); holder[0].request(1000); }
            return false;
        });
        holder[0].request(0); timer.next().work.run(); assertEquals(1, timer.pending());
        timer.next().work.run(); assertEquals(2, calls.get()); assertEquals(0, timer.pending());
    }
    @Test public void logoutCancelsRetriesAndStaleQueuedCallbacks() {
        Timer timer = new Timer(); AtomicInteger calls = new AtomicInteger();
        AutoSyncQueue queue = new AutoSyncQueue(timer, () -> { calls.incrementAndGet(); return true; });
        queue.request(0); Task stale = timer.next(); queue.cancel(); stale.work.run();
        assertEquals(0, calls.get()); assertEquals(0, timer.pending());
        queue.request(0); timer.next().work.run(); assertEquals(1, timer.pending());
        queue.cancel(); assertEquals(0, timer.pending());
    }
    @Test public void persistentFailuresHaveBoundedInProcessRetries() {
        Timer timer = new Timer(); AtomicInteger calls = new AtomicInteger();
        AutoSyncQueue queue = new AutoSyncQueue(timer, () -> { calls.incrementAndGet(); return true; });
        queue.request(0);
        for (long delay : new long[]{0,30000,60000,90000}) {
            Task task = timer.next(); assertEquals(delay, task.delay); task.work.run();
        }
        assertEquals(4, calls.get()); assertEquals(0, timer.pending());
    }
    @Test public void logoutDuringUploadDoesNotRescheduleOldAccount() {
        Timer timer = new Timer(); AutoSyncQueue[] holder = new AutoSyncQueue[1];
        holder[0] = new AutoSyncQueue(timer, () -> { holder[0].cancel(); return true; });
        holder[0].request(0); timer.next().work.run(); assertEquals(0, timer.pending());
    }
}
