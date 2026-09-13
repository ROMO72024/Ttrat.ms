package ps.ghars.sessions;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reschedules without starting a foreground service from BOOT_COMPLETED. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            && !Intent.ACTION_TIME_CHANGED.equals(action) && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)
            && !"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) return;
        PendingResult result = goAsync();
        new Thread(() -> {
            try { AlarmScheduler.reconcile(context); }
            catch (Exception error) { RingingService.showFailure(context); }
            finally { result.finish(); }
        }, "ghars-reschedule").start();
    }
}
