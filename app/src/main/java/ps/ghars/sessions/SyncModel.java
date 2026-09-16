package ps.ghars.sessions;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;

/** Pure, testable three-way merge. No network, clocks, Android APIs or destructive migrations. */
final class SyncModel {
    static final String[] FIELDS = {"name", "speechTotal", "behaviorTotal", "classroom", "shift", "phone", "speechWeekly", "behaviorWeekly", "archived", "notes", "goals"};
    private SyncModel() { }
    static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static JSONObject object(JSONObject parent, String key) { JSONObject v = parent.optJSONObject(key); return v == null ? new JSONObject() : v; }
    static Set<String> keys(JSONObject value) { Set<String> out = new LinkedHashSet<>(); value.keys().forEachRemaining(out::add); return out; }
    static boolean same(Object a, Object b) {
        if (a == null || a == JSONObject.NULL) return b == null || b == JSONObject.NULL;
        if (b == null || b == JSONObject.NULL) return false;
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a, y = (JSONObject) b;
            if (!keys(x).equals(keys(y))) return false;
            for (String k : keys(x)) if (!same(x.opt(k), y.opt(k))) return false;
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a, y = (JSONArray) b;
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!same(x.opt(i), y.opt(i))) return false;
            return true;
        }
        if (a instanceof Number && b instanceof Number) return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        return a.equals(b);
    }
    static JSONObject index(JSONArray list) throws Exception {
        JSONObject out = new JSONObject();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i); String id = item.getString("id");
            if (out.has(id)) throw new Exception("معرّف طالب أو سجل مكرر؛ لم تُغيّر البيانات");
            out.put(id, item);
        }
        return out;
    }
    static JSONObject fields(JSONObject student) throws Exception {
        JSONObject out = new JSONObject(), plans = student.getJSONObject("plans");
        for (String key : new String[]{"name", "classroom", "shift", "phone", "archived", "notes", "goals"}) out.put(key, student.get(key));
        for (String type : new String[]{"speech", "behavior"}) {
            out.put(type + "Total", plans.getJSONObject(type).getInt("total"));
            out.put(type + "Weekly", plans.getJSONObject(type).getInt("weekly"));
        }
        return out;
    }
    static void setField(JSONObject student, String field, Object value) throws Exception {
        for (String type : new String[]{"speech", "behavior"}) {
            if (field.equals(type + "Total")) { student.getJSONObject("plans").getJSONObject(type).put("total", value); return; }
            if (field.equals(type + "Weekly")) { student.getJSONObject("plans").getJSONObject(type).put("weekly", value); return; }
        }
        student.put(field, value);
    }
    static JSONObject newStudent(String id, JSONObject values) throws Exception {
        JSONObject s = new JSONObject("{\"notes\":\"\",\"goals\":\"\",\"plans\":{\"speech\":{},\"behavior\":{}}}");
        s.put("id", id);
        for (String field : FIELDS) setField(s, field, values.get(field));
        return s;
    }
    static JSONObject metrics(JSONObject state) throws Exception {
        JSONObject out = new JSONObject(); JSONArray students = state.getJSONArray("students"), sessions = state.getJSONArray("sessions");
        for (int i = 0; i < students.length(); i++) out.put(students.getJSONObject(i).getString("id"), new JSONObject("{\"speechDone\":0,\"behaviorDone\":0}"));
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            if (!"completed".equals(session.getString("status"))) continue;
            JSONArray ids = session.getJSONArray("studentIds"); JSONObject attendance = session.getJSONObject("attendance");
            String key = session.getString("type") + "Done";
            for (int j = 0; j < ids.length(); j++) {
                String id = ids.getString(j);
                if ("present".equals(object(attendance, id).optString("status")) && out.has(id)) {
                    JSONObject m = out.getJSONObject(id); m.put(key, m.getInt(key) + 1);
                }
            }
        }
        return out;
    }
    static JSONArray overlaps(JSONObject state) throws Exception {
        ArrayList<JSONObject> list = new ArrayList<>(); JSONArray sessions = state.getJSONArray("sessions"), warnings = new JSONArray();
        for (int i=0;i<sessions.length();i++) if (sessions.getJSONObject(i).optString("status").equals("scheduled")) list.add(sessions.getJSONObject(i));
        list.sort(Comparator.comparingLong(s -> SchemaValidator.toEpoch(s.optString("start"))));
        JSONObject covering = null; long until = -1;
        for (JSONObject session : list) {
            long start = SchemaValidator.toEpoch(session.getString("start")), end = start + session.getInt("duration") * 60000L;
            if (covering != null && start < until && warnings.length() < 20) warnings.put(new JSONObject()
                .put("id", session.getString("id")).put("start", session.getString("start"))
                .put("otherId", covering.getString("id")).put("otherStart", covering.getString("start")));
            if (end > until) { until = end; covering = session; }
        }
        return warnings;
    }
    static JSONArray requestRecords(JSONObject state) throws Exception {
        JSONObject meta = object(state, "_cloud"), base = object(meta, "base"), conflicts = object(meta, "conflicts");
        JSONArray result = new JSONArray(), records = records(state);
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i); String id = record.getString("id");
            JSONObject values = record.getJSONObject("values"), prior = base.optJSONObject(id);
            for (String field : keys(values)) if (conflicts.has(id + "|" + field) && prior != null) values.put(field, prior.get(field));
            result.put(new JSONObject().put("id", id).put("values", values).put("base", prior == null ? JSONObject.NULL : prior));
        }
        return result;
    }
    static JSONArray records(JSONObject state) throws Exception {
        JSONArray out = new JSONArray();
        for (String collection : new String[]{"students", "sessions"}) {
            JSONArray list = state.getJSONArray(collection); String kind = collection.equals("students") ? "student" : "session";
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i), values = kind.equals("student") ? fields(item) : copy(item);
                values.remove("id"); out.put(new JSONObject().put("id", kind + ":" + item.getString("id")).put("values", values));
            }
        }
        return out;
    }
    static int pending(JSONObject state) throws Exception {
        int count = 0; JSONObject base = object(object(state, "_cloud"), "base");
        JSONArray records = records(state);
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.getJSONObject(i);
            if (!same(r.get("values"), base.opt(r.getString("id")))) count++;
        }
        return count;
    }
    static JSONObject apply(JSONObject sent, JSONObject current, JSONObject response) throws Exception {
        JSONObject result = copy(current), meta = copy(object(current, "_cloud")), base = copy(object(meta, "base"));
        JSONObject conflicts = copy(object(meta, "conflicts")), oldBase = object(object(sent, "_cloud"), "base");
        JSONObject before = index(records(sent)), now = index(records(result));
        JSONArray remote = response.getJSONArray("records"); index(remote); // reject repeated IDs, don't merge twice
        JSONArray serverConflicts = response.getJSONArray("conflicts"); Set<String> rejected = new LinkedHashSet<>();
        for (int i = 0; i < serverConflicts.length(); i++) {
            JSONObject c = serverConflicts.getJSONObject(i); rejected.add(c.getString("id") + "|" + c.getString("field"));
        }
        for (int i = 0; i < remote.length(); i++) {
            JSONObject record = remote.getJSONObject(i), values = record.getJSONObject("values"); String id = record.getString("id");
            if (!id.matches("(student|session):[A-Za-z0-9][A-Za-z0-9_-]{0,119}")) throw new Exception("معرّف غير صالح في رد المزامنة");
            if (!now.has(id)) { now.put(id, new JSONObject().put("id", id).put("values", copy(values))); }
            else {
                JSONObject local = now.getJSONObject(id).getJSONObject("values");
                JSONObject snapshot = before.has(id) ? before.getJSONObject(id).getJSONObject("values") : new JSONObject();
                if (!keys(local).equals(keys(values))) throw new Exception("حقول رد المزامنة غير متوافقة؛ لم تُستبدل البيانات");
                for (String field : keys(values)) {
                    String key = id + "|" + field; Object l = local.get(field), r = values.get(field), was = snapshot.opt(field);
                    boolean changedInFlight = !same(l, was);
                    boolean conflict = conflicts.has(key) || rejected.contains(key) ||
                        (changedInFlight && !same(r, was) && !same(r, object(oldBase, id).opt(field)));
                    if (same(l, r)) { conflicts.remove(key); }
                    else if (conflict) {
                        conflicts.put(key, new JSONObject().put("id", id).put("field", field).put("local", l).put("remote", r));
                    } else if (!changedInFlight) { local.put(field, r); }
                }
            }
            base.put(id, copy(values));
        }
        // A response omitting a sent record must not be acknowledged as a complete sync.
        JSONObject remoteIds = index(remote);
        for (String id : keys(before)) if (!remoteIds.has(id)) throw new Exception("رد المزامنة غير مكتمل؛ بقيت بيانات الجهاز محفوظة");
        JSONArray students = new JSONArray(), sessions = new JSONArray();
        for (String key : keys(now)) {
            JSONObject values = now.getJSONObject(key).getJSONObject("values"); String id = key.substring(key.indexOf(':') + 1);
            if (key.startsWith("student:")) students.put(newStudent(id, values));
            else sessions.put(copy(values).put("id", id));
        }
        result.put("students", students).put("sessions", sessions);
        meta.put("base", base).put("conflicts", conflicts);
        meta.put("lastSync", response.getString("serverTime"));
        result.put("_cloud", meta);
        SchemaValidator.validate(result.toString());
        return result;
    }
    /** Merge an open form against the latest on-disk snapshot, preserving unrelated remote changes. */
    static JSONObject mergeEdit(JSONObject before, JSONObject edited, JSONObject current) throws Exception {
        JSONObject result = copy(current);
        for (String collection : new String[]{"students", "sessions", "reminders"}) {
            JSONObject b = index(before.getJSONArray(collection)), e = index(edited.getJSONArray(collection)), c = index(current.getJSONArray(collection));
            Set<String> all = keys(c); all.addAll(keys(e)); JSONArray merged = new JSONArray();
            for (String id : all) {
                Object value = mergeValue(b.opt(id), e.opt(id), c.opt(id));
                if (value != null && value != JSONObject.NULL) merged.put(value);
            }
            result.put(collection, merged);
        }
        result.put("settings", mergeValue(before.get("settings"), edited.get("settings"), current.get("settings")));
        SchemaValidator.validate(result.toString()); return result;
    }
    private static Object mergeValue(Object before, Object edited, Object current) throws Exception {
        if (same(before, edited)) return current;
        if (same(before, current) || same(edited, current)) return edited;
        if (before instanceof JSONObject && edited instanceof JSONObject && current instanceof JSONObject) {
            JSONObject b = (JSONObject) before, e = (JSONObject) edited, c = (JSONObject) current, result = copy(c);
            Set<String> all = keys(b); all.addAll(keys(e));
            for (String key : all) {
                Object value = mergeValue(b.opt(key), e.opt(key), c.opt(key));
                if (value == null) result.remove(key); else result.put(key, value);
            }
            return result;
        }
        throw new Exception("تغيّرت المعلومة نفسها أثناء فتح النموذج. انسخي تعديلك، أغلقي النموذج ثم افتحيه لمراجعة أحدث البيانات");
    }
}
