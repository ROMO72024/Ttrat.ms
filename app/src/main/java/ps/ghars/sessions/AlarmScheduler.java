package ps.ghars.sessions;

import android.app.AlarmManager;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** Keeps only the next alarm registered with Android; the full queue is persisted. */
final class AlarmScheduler {
    private static final String EMPTY = "{\"items\":[],\"extras\":[]}";
    private static final int REQUEST = 4201;
    private AlarmScheduler() { }

    static boolean canExact(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms();
    }

    static synchronized void sync(Context context, JSONArray desired) throws Exception {
        JSONObject store = read(context);
        JSONArray future = new JSONArray();
        long now = System.currentTimeMillis();
        JSONObject state = SchemaValidator.validate(SecureStore.read(context, "state", SecureStore.EMPTY_STATE));
        // Canonical state wins over a UI snapshot if a save and resume race each other.
        desired = itemsForState(state);
        Set<String> included = new HashSet<>();
        for (int i = 0; i < desired.length(); i++) {
            JSONObject item = desired.getJSONObject(i);
            if (item.getLong("at") > now) { future.put(item); included.add(item.getString("id") + "@" + item.getLong("at")); }
        }
        // Do not erase a just-due alarm when the UI resumes before its receiver runs.
        JSONArray previous = store.optJSONArray("items");
        if (previous != null) for (int i = 0; i < previous.length(); i++) {
            JSONObject item = previous.getJSONObject(i); long at = item.getLong("at");
            if (at <= now && at >= now - 30 * 60000L && isCurrent(state, item) && included.add(item.getString("id") + "@" + at)) future.put(item);
        }
        store.put("items", future);
        store.put("extras", relevantExtras(store.optJSONArray("extras"), now, state));
        persistAndSchedule(context, store);
    }

    static synchronized void reconcile(Context context) throws Exception {
        JSONObject state = SchemaValidator.validate(SecureStore.read(context, "state", SecureStore.EMPTY_STATE));
        sync(context, itemsForState(state));
    }

    static JSONArray itemsForState(JSONObject state) throws Exception {
        return itemsForState(state, System.currentTimeMillis());
    }

    static JSONArray itemsForState(JSONObject state, long now) throws Exception {
        JSONArray result = new JSONArray();
        JSONArray sessions = state.getJSONArray("sessions");
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            if (!"scheduled".equals(session.getString("status"))) continue;
            long start = SchemaValidator.toEpoch(session.getString("start"));
            String kind = "speech".equals(session.getString("type")) ? "نطق" : "سلوك";
            String detail = "موعد الجلسة " + session.getString("start").substring(11) + " • افتحي غرس للتفاصيل";
            // The session-start alarm is independent of any optional advance reminder.
            if (start > now) result.put(new JSONObject().put("id", "start-session-" + session.getString("id")).put("at", start)
                .put("title", "حان موعد جلسة " + kind).put("detail", detail));
            int advanceMinutes = session.getInt("reminderMinutes");
            long advance = start - advanceMinutes * 60000L;
            // Zero already means the mandatory start alarm; never schedule it twice.
            if (advanceMinutes > 0 && advance > now) result.put(new JSONObject().put("id", "session-" + session.getString("id")).put("at", advance)
                .put("title", "تذكير جلسة " + kind).put("detail", detail));
        }
        JSONArray reminders = state.getJSONArray("reminders");
        for (int i = 0; i < reminders.length(); i++) {
            JSONObject reminder = reminders.getJSONObject(i);
            long at = SchemaValidator.toEpoch(reminder.getString("at"));
            if (reminder.getBoolean("enabled") && at > now) result.put(new JSONObject().put("id", "reminder-" + reminder.getString("id"))
                .put("at", at).put("title", "تذكير خاص من غرس").put("detail", "افتحي التطبيق لعرض التذكير"));
        }
        return result;
    }

    static synchronized void addExtra(Context context, String title, String detail, long at) throws Exception {
        addExtra(context, title, detail, at, null);
    }
    static synchronized void addExtra(Context context, String title, String detail, long at, JSONArray sources) throws Exception {
        JSONObject store = read(context);
        JSONObject state = SchemaValidator.validate(SecureStore.read(context, "state", SecureStore.EMPTY_STATE));
        JSONArray extras = relevantExtras(store.optJSONArray("extras"), System.currentTimeMillis(), state);
        JSONObject extra = new JSONObject().put("id", "extra-" + UUID.randomUUID()).put("title", title).put("detail", detail).put("at", at);
        if (sources != null && sources.length() > 0) extra.put("sources", sources);
        extras.put(extra);
        store.put("extras", extras);
        persistAndSchedule(context, store);
    }

    static synchronized JSONArray consumeDue(Context context) throws Exception {
        JSONObject store = read(context);
        JSONArray due = new JSONArray();
        long now = System.currentTimeMillis();
        // Read canonical data again, so a crash during a save cannot ring a removed session.
        JSONObject state = SchemaValidator.validate(SecureStore.read(context, "state", SecureStore.EMPTY_STATE));
        for (String key : new String[]{"items", "extras"}) {
            JSONArray items = store.optJSONArray(key), keep = new JSONArray();
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                long at = item.getLong("at");
                if (at > now + 1000L) keep.put(item);
                else if (at >= now - 30 * 60000L && ("extras".equals(key) ? extraRelevant(state, item) : isCurrent(state, item))) due.put(item);
            }
            store.put(key, keep);
        }
        persistAndSchedule(context, store);
        return due;
    }

    static synchronized void clear(Context context) throws Exception {
        context.getSystemService(AlarmManager.class).cancel(operation(context));
        SecureStore.write(context, "alarms", EMPTY);
        context.stopService(new Intent(context, RingingService.class));
    }

    static boolean isCurrent(JSONObject state, JSONObject item) throws Exception {
        String id = item.getString("id");
        boolean startAlarm = id.startsWith("start-session-");
        if (startAlarm || id.startsWith("session-")) {
            JSONArray sessions = state.getJSONArray("sessions");
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.getJSONObject(i);
                if (id.equals((startAlarm ? "start-session-" : "session-") + s.getString("id"))) {
                    if (!"scheduled".equals(s.getString("status"))) return false;
                    long start = SchemaValidator.toEpoch(s.getString("start"));
                    if (startAlarm) return start == item.getLong("at");
                    return s.getInt("reminderMinutes") > 0 && start - s.getInt("reminderMinutes") * 60000L == item.getLong("at");
                }
            }
        } else if (id.startsWith("reminder-")) {
            JSONArray reminders = state.getJSONArray("reminders");
            for (int i = 0; i < reminders.length(); i++) {
                JSONObject r = reminders.getJSONObject(i);
                if (id.equals("reminder-" + r.getString("id"))) return r.getBoolean("enabled") && SchemaValidator.toEpoch(r.getString("at")) == item.getLong("at");
            }
        }
        return false;
    }
    private static JSONObject read(Context context) throws Exception { return new JSONObject(SecureStore.read(context, "alarms", EMPTY)); }
    private static JSONArray relevantExtras(JSONArray items, long now, JSONObject state) throws Exception {
        JSONArray result = new JSONArray();
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.getLong("at") >= now - 30 * 60000L && extraRelevant(state, item)) result.put(item);
        }
        return result;
    }
    private static boolean extraRelevant(JSONObject state, JSONObject item) throws Exception {
        JSONArray sources = item.optJSONArray("sources");
        if (sources == null) return true;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.getJSONObject(i);
            if (source.getString("id").startsWith("extra-") || isCurrent(state, source)) return true;
        }
        return false;
    }
    private static PendingIntent operation(Context context) {
        return PendingIntent.getBroadcast(context, REQUEST, new Intent(context, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_FIRE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    @SuppressLint("ScheduleExactAlarm") // canExact checks the permission; the call also catches a revocation race.
    private static void persistAndSchedule(Context context, JSONObject store) throws Exception {
        SecureStore.write(context, "alarms", store.toString());
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        PendingIntent pending = operation(context);
        manager.cancel(pending);
        long next = Long.MAX_VALUE;
        for (String key : new String[]{"items", "extras"}) {
            JSONArray items = store.optJSONArray(key);
            if (items != null) for (int i = 0; i < items.length(); i++) next = Math.min(next, items.getJSONObject(i).getLong("at"));
        }
        if (next == Long.MAX_VALUE) return;
        next = Math.max(next, System.currentTimeMillis() + 100L);
        if (canExact(context)) {
            PendingIntent show = PendingIntent.getActivity(context, 4202, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            try { manager.setAlarmClock(new AlarmManager.AlarmClockInfo(next, show), pending); return; }
            catch (SecurityException ignored) { /* Permission may change between the check and scheduling. */ }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
    }
}
