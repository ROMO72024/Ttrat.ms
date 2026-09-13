package ps.ghars.sessions;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import org.json.JSONArray;

public final class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION_FIRE = "ps.ghars.sessions.FIRE";
    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_FIRE.equals(intent.getAction())) return;
        PendingResult result = goAsync();
        PowerManager.WakeLock wake = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ghars:AlarmDispatch");
        wake.acquire(15000L);
        new Thread(() -> {
            try {
                JSONArray due = AlarmScheduler.consumeDue(context);
                if (due.length() == 0) return;
                Intent ring = new Intent(context, RingingService.class).setAction(RingingService.ACTION_RING).putExtra("items", due.toString());
                try { context.startForegroundService(ring); }
                catch (RuntimeException restricted) { RingingService.showFallback(context); }
            } catch (Exception error) { RingingService.showFailure(context); }
            finally { if (wake.isHeld()) wake.release(); result.finish(); }
        }, "ghars-alarm").start();
    }
}
