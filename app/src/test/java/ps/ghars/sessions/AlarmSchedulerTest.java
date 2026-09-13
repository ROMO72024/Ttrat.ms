package ps.ghars.sessions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AlarmSchedulerTest {
    private static final String START = "2028-01-17T10:00";
    private static final long START_AT = SchemaValidator.toEpoch(START);
    private static final long BEFORE = START_AT - 8 * 24 * 60 * 60000L;

    private static JSONObject state(int advanceMinutes) throws Exception {
        JSONObject session = new JSONObject().put("id", "lesson-1").put("start", START)
            .put("type", "speech").put("status", "scheduled").put("reminderMinutes", advanceMinutes);
        return new JSONObject().put("sessions", new JSONArray().put(session)).put("reminders", new JSONArray());
    }

    private static JSONObject session(JSONObject state) throws Exception {
        return state.getJSONArray("sessions").getJSONObject(0);
    }

    private static JSONObject alarm(JSONArray alarms, String id) throws Exception {
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject alarm = alarms.getJSONObject(i);
            if (id.equals(alarm.getString("id"))) return alarm;
        }
        fail("Missing alarm: " + id);
        return null;
    }

    @Test public void disabledAdvanceAndLegacyZeroEachProduceOneStartAlarm() throws Exception {
        for (int advanceMinutes : new int[]{-1, 0}) {
            JSONObject state = state(advanceMinutes);
            JSONArray alarms = AlarmScheduler.itemsForState(state, BEFORE);
            assertEquals(1, alarms.length());
            JSONObject start = alarm(alarms, "start-session-lesson-1");
            assertEquals(START_AT, start.getLong("at"));
            assertTrue(AlarmScheduler.isCurrent(state, start));
        }
    }

    @Test public void advanceReminderAlwaysKeepsSeparateStartAlarm() throws Exception {
        for (int advanceMinutes : new int[]{1, 30, 1200, 1440, 10080}) {
            JSONObject state = state(advanceMinutes);
            JSONArray alarms = AlarmScheduler.itemsForState(state, BEFORE);
            assertEquals(2, alarms.length());
            JSONObject start = alarm(alarms, "start-session-lesson-1");
            JSONObject advance = alarm(alarms, "session-lesson-1");
            assertEquals(START_AT, start.getLong("at"));
            assertEquals(START_AT - advanceMinutes * 60000L, advance.getLong("at"));
            assertTrue(AlarmScheduler.isCurrent(state, start));
            assertTrue(AlarmScheduler.isCurrent(state, advance));
        }
    }

    @Test public void passedAdvanceDoesNotRemoveUpcomingStart() throws Exception {
        JSONObject state = state(30);
        JSONArray alarms = AlarmScheduler.itemsForState(state, START_AT - 10 * 60000L);
        assertEquals(1, alarms.length());
        assertEquals(START_AT, alarm(alarms, "start-session-lesson-1").getLong("at"));
        assertEquals(0, AlarmScheduler.itemsForState(state, START_AT).length());
        assertEquals(0, AlarmScheduler.itemsForState(state, START_AT + 60000L).length());
    }

    @Test public void changingAdvanceOnlyInvalidatesAdvanceAndItsSnoozeSource() throws Exception {
        JSONObject state = state(30);
        JSONArray alarms = AlarmScheduler.itemsForState(state, BEFORE);
        JSONObject start = alarm(alarms, "start-session-lesson-1");
        JSONObject advance = alarm(alarms, "session-lesson-1");
        session(state).put("reminderMinutes", 60);
        assertTrue(AlarmScheduler.isCurrent(state, start));
        assertFalse(AlarmScheduler.isCurrent(state, advance));
        session(state).put("reminderMinutes", -1);
        assertTrue(AlarmScheduler.isCurrent(state, start));
        assertFalse(AlarmScheduler.isCurrent(state, advance));
    }

    @Test public void reschedulingInvalidatesBothOldAlarmSources() throws Exception {
        JSONObject state = state(30);
        JSONArray alarms = AlarmScheduler.itemsForState(state, BEFORE);
        session(state).put("start", "2028-01-17T11:00");
        assertFalse(AlarmScheduler.isCurrent(state, alarm(alarms, "start-session-lesson-1")));
        assertFalse(AlarmScheduler.isCurrent(state, alarm(alarms, "session-lesson-1")));
        JSONArray updated = AlarmScheduler.itemsForState(state, BEFORE);
        assertEquals(2, updated.length());
        assertEquals(START_AT + 60 * 60000L, alarm(updated, "start-session-lesson-1").getLong("at"));
    }

    @Test public void completedCancelledAndDeletedSessionsInvalidateBothAlarms() throws Exception {
        for (String status : new String[]{"completed", "cancelled"}) {
            JSONObject state = state(30);
            JSONArray alarms = AlarmScheduler.itemsForState(state, BEFORE);
            session(state).put("status", status);
            assertEquals(0, AlarmScheduler.itemsForState(state, BEFORE).length());
            assertFalse(AlarmScheduler.isCurrent(state, alarm(alarms, "start-session-lesson-1")));
            assertFalse(AlarmScheduler.isCurrent(state, alarm(alarms, "session-lesson-1")));
        }
        JSONObject state = state(30);
        JSONArray alarms = AlarmScheduler.itemsForState(state, BEFORE);
        state.put("sessions", new JSONArray());
        assertFalse(AlarmScheduler.isCurrent(state, alarm(alarms, "start-session-lesson-1")));
        assertFalse(AlarmScheduler.isCurrent(state, alarm(alarms, "session-lesson-1")));
    }

    @Test public void legacyZeroReminderIdCannotDuplicateStartAfterUpgrade() throws Exception {
        JSONObject state = state(0);
        JSONObject legacyStart = new JSONObject().put("id", "session-lesson-1").put("at", START_AT);
        assertFalse(AlarmScheduler.isCurrent(state, legacyStart));
        JSONArray alarms = AlarmScheduler.itemsForState(state, BEFORE);
        assertEquals(1, alarms.length());
        assertTrue(AlarmScheduler.isCurrent(state, alarm(alarms, "start-session-lesson-1")));
    }

    @Test public void customRemindersStillRespectTheirEnabledFlag() throws Exception {
        JSONObject state = state(-1);
        state.put("sessions", new JSONArray());
        JSONObject reminder = new JSONObject().put("id", "custom-1").put("at", START).put("enabled", true);
        state.getJSONArray("reminders").put(reminder);
        JSONArray alarms = AlarmScheduler.itemsForState(state, BEFORE);
        assertEquals(1, alarms.length());
        JSONObject custom = alarm(alarms, "reminder-custom-1");
        assertTrue(AlarmScheduler.isCurrent(state, custom));
        reminder.put("enabled", false);
        assertEquals(0, AlarmScheduler.itemsForState(state, BEFORE).length());
        assertFalse(AlarmScheduler.isCurrent(state, custom));
    }
}
