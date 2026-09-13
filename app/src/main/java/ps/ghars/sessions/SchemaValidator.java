package ps.ghars.sessions;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Validates untrusted imported files before any stored data is replaced. */
public final class SchemaValidator {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final ZoneId GAZA = ZoneId.of("Asia/Gaza");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private SchemaValidator() { }

    public static long toEpoch(String value) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")) return -1;
            LocalDateTime date = LocalDateTime.parse(value, LOCAL);
            if (date.getYear() < 2000 || date.getYear() > 2100 || GAZA.getRules().getValidOffsets(date).isEmpty()) return -1;
            // At a daylight-saving overlap the earlier offset is used consistently.
            return date.atZone(GAZA).toInstant().toEpochMilli();
        } catch (Exception ignored) { return -1; }
    }

    public static JSONObject validate(String json) throws Exception {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) fail("حجم البيانات أكبر من الحد المسموح");
        JSONObject root = new JSONObject(json);
        number(root, "version", 1, 1);
        JSONArray students = array(root, "students", 5000);
        JSONArray sessions = array(root, "sessions", 30000);
        JSONArray reminders = array(root, "reminders", 2000);
        JSONObject settings = root.getJSONObject("settings");
        number(settings, "defaultReminder", -1, 10080);
        Set<String> studentIds = new HashSet<>();
        for (int i = 0; i < students.length(); i++) {
            JSONObject student = students.getJSONObject(i);
            unique(studentIds, string(student, "id", 120, true));
            string(student, "name", 200, true);
            string(student, "classroom", 200, false);
            oneOf(student, "shift", "morning", "evening");
            string(student, "phone", 100, false);
            string(student, "notes", 20000, false);
            string(student, "goals", 20000, false);
            bool(student, "archived");
            JSONObject plans = student.getJSONObject("plans");
            for (String type : new String[]{"speech", "behavior"}) {
                JSONObject plan = plans.getJSONObject(type);
                number(plan, "total", 0, 10000);
                number(plan, "weekly", 0, 50);
            }
        }
        Set<String> sessionIds = new HashSet<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            unique(sessionIds, string(session, "id", 120, true));
            oneOf(session, "type", "speech", "behavior");
            date(session, "start");
            number(session, "duration", 5, 480);
            number(session, "reminderMinutes", -1, 10080);
            oneOf(session, "status", "scheduled", "completed", "cancelled");
            string(session, "note", 20000, false);
            string(session, "seriesId", 120, false);
            JSONArray participants = array(session, "studentIds", 50);
            if (participants.length() == 0) fail("جلسة دون طلاب");
            Set<String> selected = new HashSet<>();
            for (int j = 0; j < participants.length(); j++) {
                Object value = participants.get(j);
                if (!(value instanceof String) || !studentIds.contains(value)) fail("جلسة تشير إلى طالب غير موجود");
                unique(selected, (String) value);
            }
            JSONObject attendance = session.getJSONObject("attendance");
            Iterator<String> keys = attendance.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!selected.contains(key)) fail("حضور لطالب خارج الجلسة");
                JSONObject entry = attendance.getJSONObject(key);
                oneOf(entry, "status", "present", "absent", "excused");
                string(entry, "note", 20000, false);
                number(entry, "progress", 0, 5);
            }
            if ("completed".equals(session.getString("status"))) {
                for (String participant : selected) if (!attendance.has(participant)) fail("يجب تسجيل حضور كل طالب عند تسليم الجلسة");
            }
        }
        Set<String> reminderIds = new HashSet<>();
        for (int i = 0; i < reminders.length(); i++) {
            JSONObject reminder = reminders.getJSONObject(i);
            unique(reminderIds, string(reminder, "id", 120, true));
            string(reminder, "title", 300, true);
            date(reminder, "at");
            bool(reminder, "enabled");
        }
        return root;
    }

    static JSONArray validateAlarms(String json) throws Exception {
        if (json == null || json.length() > MAX_BYTES) fail("قائمة التنبيهات كبيرة جداً");
        JSONArray items = new JSONArray(json);
        if (items.length() > 110000) fail("قائمة التنبيهات كبيرة جداً");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            unique(ids, string(item, "id", 256, true));
            string(item, "title", 500, true);
            string(item, "detail", 2000, false);
            Object at = item.get("at");
            if (!(at instanceof Number) || !Double.isFinite(((Number) at).doubleValue()) || ((Number) at).doubleValue() != ((Number) at).longValue()
                || ((Number) at).longValue() < 0 || ((Number) at).longValue() > 7289654400000L) fail("موعد تنبيه غير صالح");
        }
        return items;
    }

    private static void date(JSONObject o, String key) throws Exception {
        if (toEpoch(string(o, key, 16, true)) == -1) fail("تاريخ أو وقت غير صالح: " + key);
    }
    private static JSONArray array(JSONObject o, String key, int max) throws Exception {
        JSONArray value = o.getJSONArray(key);
        if (value.length() > max) fail("عدد السجلات أكبر من الحد المسموح: " + key);
        return value;
    }
    private static String string(JSONObject o, String key, int max, boolean required) throws Exception {
        Object value = o.get(key);
        if (!(value instanceof String)) fail("حقل نصي غير صالح: " + key);
        String text = (String) value;
        if (text.length() > max || (required && text.trim().isEmpty())) fail("قيمة غير صالحة: " + key);
        return text;
    }
    private static void number(JSONObject o, String key, int min, int max) throws Exception {
        Object value = o.get(key);
        if (!(value instanceof Number)) fail("حقل رقمي غير صالح: " + key);
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) fail("رقم خارج المجال: " + key);
    }
    private static void bool(JSONObject o, String key) throws Exception {
        if (!(o.get(key) instanceof Boolean)) fail("قيمة منطقية غير صالحة: " + key);
    }
    private static void oneOf(JSONObject o, String key, String... values) throws Exception {
        String value = string(o, key, 30, true);
        for (String valid : values) if (valid.equals(value)) return;
        fail("خيار غير صالح: " + key);
    }
    private static void unique(Set<String> set, String id) throws Exception {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,255}") || "constructor".equals(id) || "prototype".equals(id) || "__proto__".equals(id)) fail("معرّف غير صالح");
        if (!set.add(id)) fail("معرّف مكرر");
    }
    private static void fail(String message) throws Exception { throw new Exception(message); }
}

