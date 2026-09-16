package ps.ghars.sessions;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Private offline caches are keyed by immutable school/account IDs, NEVER by the editable code. */
final class ProfileStore {
    private ProfileStore() { }
    static JSONObject config(Context c) throws Exception { return new JSONObject(SecureStore.read(c, "cloud-config", "{}")); }
    static JSONArray registry(Context c) throws Exception { return new JSONArray(SecureStore.read(c, "cloud-profiles", "[]")); }
    static String key(JSONObject conf) { return conf.optString("serverId") + "/" + conf.optString("accountId"); }
    static String file(JSONObject conf) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key(conf).getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder("profile-");
        for (byte b : hash) name.append((char) ('a' + ((b & 255) >>> 4))).append((char) ('a' + (b & 15)));
        return name.toString();
    }
    static void remember(Context c, JSONObject conf) throws Exception {
        JSONArray records = registry(c), next = new JSONArray(); boolean replaced = false;
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.getJSONObject(i);
            if (key(item).equals(key(conf))) { next.put(SyncModel.copy(conf)); replaced = true; } else next.put(item);
        }
        if (!replaced) next.put(SyncModel.copy(conf));
        SecureStore.write(c, "cloud-profiles", next.toString());
    }
    static JSONObject cached(Context c, String url, String code) throws Exception {
        JSONArray records = registry(c);
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.getJSONObject(i);
            if (item.optString("url").equals(url) && item.optString("code").equals(code) && !item.optBoolean("revoked")) return SyncModel.copy(item);
        }
        return null;
    }
    static void activate(Context c, JSONObject conf) throws Exception {
        synchronized (CloudSync.DATA_LOCK) {
            JSONObject current = CloudSync.state(c), oldMeta = SyncModel.object(current, "_cloud");
            boolean legacy = oldMeta.optString("accountId").isEmpty();
            boolean same = !legacy && key(oldMeta).equals(key(conf));
            if (!legacy) SecureStore.write(c, file(oldMeta), current.toString());
            else if (current.getJSONArray("students").length() > 0 || current.getJSONArray("sessions").length() > 0)
                SecureStore.write(c, "before-cloud", current.toString());
            JSONObject selected;
            if (same || legacy) selected = current;
            else selected = SchemaValidator.validate(SecureStore.read(c, file(conf), SecureStore.EMPTY_STATE));
            JSONObject meta = SyncModel.object(selected, "_cloud");
            if (!meta.optString("accountId").isEmpty() && !key(meta).equals(key(conf))) throw new Exception("ملف المساحة المحلية غير مطابق للحساب؛ لم تتغير البيانات");
            meta.put("serverId", conf.getString("serverId")).put("accountId", conf.getString("accountId")); selected.put("_cloud", meta);
            boolean pendingLegacy = legacy && (current.getJSONArray("students").length() > 0 || current.getJSONArray("sessions").length() > 0);
            conf.put("enabled", !pendingLegacy).put("generation", UUID.randomUUID().toString()).put("revoked", false);
            SecureStore.write(c, "state", selected.toString());
            SecureStore.write(c, "cloud-config", conf.toString()); remember(c, conf);
        }
    }
    static void assertBinding(JSONObject state, JSONObject conf) throws Exception {
        JSONObject meta = SyncModel.object(state, "_cloud");
        if (conf.optString("accountId").isEmpty() || !key(meta).equals(key(conf))) throw new Exception("المساحة المحلية لا تطابق حساب الدخول. سجّلي خروجاً ثم ادخلي بالكود الصحيح");
    }
    static void revoke(Context c) throws Exception {
        revoke(c, config(c));
    }
    static void revoke(Context c, JSONObject cached) throws Exception {
        synchronized (CloudSync.DATA_LOCK) {
            JSONObject conf = SyncModel.copy(cached); conf.put("revoked", true).put("enabled", false).put("generation", UUID.randomUUID().toString());
            if (key(config(c)).equals(key(conf))) SecureStore.write(c, "cloud-config", conf.toString());
            remember(c, conf);
        }
    }
}
