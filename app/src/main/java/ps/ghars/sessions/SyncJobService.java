package ps.ghars.sessions;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Network-constrained, persistent background retries; no foreground service or exact-alarm dependency. */
public final class SyncJobService extends JobService {
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Map<Integer, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
    private final Map<Integer, Future<?>> tasks = new ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        final AtomicBoolean stopped = new AtomicBoolean(false);
        cancelled.put(params.getJobId(), stopped);
        Future<?> task = worker.submit(() -> {
            boolean retry = CloudSync.run(getApplicationContext());
            if (!stopped.get()) jobFinished(params, retry);
            cancelled.remove(params.getJobId(), stopped);
        });
        tasks.put(params.getJobId(), task);
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        AtomicBoolean stopped = cancelled.remove(params.getJobId()); if (stopped != null) stopped.set(true);
        Future<?> task = tasks.remove(params.getJobId()); if (task != null) task.cancel(true); return true;
    }
    @Override public void onDestroy() { for (AtomicBoolean stopped : cancelled.values()) stopped.set(true); worker.shutdownNow(); super.onDestroy(); }
}
