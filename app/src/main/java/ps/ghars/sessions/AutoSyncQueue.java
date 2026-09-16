package ps.ghars.sessions;

import java.util.function.BooleanSupplier;

/** Runs in-process independently of Android's deferred background job quotas. */
final class AutoSyncQueue {
    interface Ticket { void cancel(); }
    interface Timer { Ticket after(Runnable work, long delayMillis); }
    private final Timer timer;
    private final BooleanSupplier sync;
    private Ticket pending;
    private long pendingDelay, generation;
    private int failures;
    private boolean running, again;

    AutoSyncQueue(Timer timer, BooleanSupplier sync) { this.timer = timer; this.sync = sync; }

    synchronized void request(long delayMillis) {
        failures = 0;
        if (running) { again = true; return; }
        enqueue(delayMillis);
    }

    synchronized void cancel() {
        generation++; again = false; failures = 0;
        if (pending != null) pending.cancel();
        pending = null;
    }

    private void enqueue(long delayMillis) {
        if (pending != null) {
            if (pendingDelay <= delayMillis) return;
            pending.cancel();
        }
        pendingDelay = delayMillis;
        final long token = ++generation;
        pending = timer.after(() -> drain(token), delayMillis);
    }

    private void drain(long token) {
        synchronized (this) {
            if (token != generation) return;
            pending = null; running = true;
        }
        boolean retry = true;
        try { retry = sync.getAsBoolean(); }
        finally {
            synchronized (this) {
                running = false;
                // A save during the request must receive another acknowledgement.
                if (again) { again = false; failures = 0; enqueue(1000); }
                else if (token == generation && retry && failures < 3) {
                    failures++; enqueue(30000L * failures);
                } else if (!retry) failures = 0;
            }
        }
    }
}
