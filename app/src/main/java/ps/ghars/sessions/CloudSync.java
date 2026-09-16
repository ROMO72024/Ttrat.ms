package ps.ghars.sessions;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional native HTTPS synchronization; all screens and local operations remain offline. */
final class CloudSync {
    static final Object DATA_LOCK = new Object();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final int JOB_ONCE = 47001, JOB_PERIODIC = 47002;
    private static volatile String message = "", error = "";
    private static final ScheduledExecutorService DIRECT_WORKER = Executors.newSingleThreadScheduledExecutor();
    private static AutoSyncQueue directQueue;
    private static final AtomicBoolean PULL_QUEUED = new AtomicBoolean();
    private CloudSync() { }
    private static synchronized void requestDirect(Context c, long delayMillis) {
        final Context app = c.getApplicationContext();
        if (directQueue == null) directQueue = new AutoSyncQueue((work, delay) -> {
            ScheduledFuture<?> future = DIRECT_WORKER.schedule(work, delay, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, () -> run(app));
        directQueue.request(delayMillis);
    }
    private static synchronized void cancelDirect() { if (directQueue != null) directQueue.cancel(); }
    static void enqueuePull(Context c) {
        if (!PULL_QUEUED.compareAndSet(false, true)) return;
        final Context app = c.getApplicationContext();
        // Serialize downloads with in-process uploads rather than racing HTTP requests.
        DIRECT_WORKER.execute(() -> { try { pull(app); } finally { PULL_QUEUED.set(false); } });
    }
    static void syncNow(Context c) throws Exception {
        synchronized (DATA_LOCK) {
            JSONObject conf = config(c); ProfileStore.assertBinding(state(c), conf);
            if (!conf.optBoolean("enabled") || conf.optBoolean("revoked"))
                throw new Exception("المزامنة متوقفة. اضغطي تفعيل المزامنة أولاً");
            requestDirect(c, 0);
        }
    }
    static JSONObject state(Context c) throws Exception { return SchemaValidator.validate(SecureStore.read(c, "state", SecureStore.EMPTY_STATE)); }
    private static JSONObject config(Context c) throws Exception { return ProfileStore.config(c); }
    static String schoolUrl(Context c) {
        try {
            JSONObject conf = config(c);
            if (!conf.optString("url").isEmpty()) return conf.getString("url");
            try (InputStream in = c.getAssets().open("sync-config.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int length;
                while ((length = in.read(buffer)) != -1) { if (out.size() > 10000) return ""; out.write(buffer, 0, length); }
                return new JSONObject(out.toString("UTF-8")).optString("url");
            }
        } catch (Exception e) { return ""; }
    }
    static String loginInfo(Context c) {
        try { return new JSONObject().put("url", schoolUrl(c)).toString(); } catch (Exception e) { return "{}"; }
    }
    /** Only successful online validation, or a previously validated offline cache, can prepare a space. */
    static JSONObject prepareLogin(Context c, String url, String code) throws Exception {
        url = url.trim(); code = code.trim(); validateEndpoint(url);
        if (!code.matches("[A-Za-z0-9]{5,32}")) throw new Exception("أدخلي الكود من ٥ إلى ٣٢ حرفاً إنجليزياً أو رقماً، كما هو في الشيت");
        JSONObject old = config(c), cached = ProfileStore.cached(c, url, code);
        String device = old.optString("deviceId", UUID.randomUUID().toString());
        ConnectivityManager manager = c.getSystemService(ConnectivityManager.class);
        NetworkCapabilities network = manager.getNetworkCapabilities(manager.getActiveNetwork());
        boolean online = network != null && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (!online && cached != null) { cached.put("offlineLogin", true); return cached; }
        if (!online) throw new Exception("أول دخول بهذا الكود يحتاج الإنترنت. بعد التحقق يمكنك فتح المساحة نفسها دون إنترنت");
        JSONObject response;
        try {
            response = post(url, new JSONObject().put("protocol", 2).put("action", "login").put("code", code).put("deviceId", device).toString());
        } catch (Exception networkError) {
            if (cached != null) { cached.put("offlineLogin", true); return cached; }
            throw networkError;
        }
        try { checkResponse(response); }
        catch (RemoteFailure rejected) {
            if (cached != null && rejected.code.equals("AUTH")) ProfileStore.revoke(c, cached);
            throw rejected;
        }
        JSONObject account = response.getJSONObject("account");
        return new JSONObject().put("url", url).put("code", code).put("deviceId", device).put("serverId", response.getString("serverId"))
            .put("accountId", account.getString("id")).put("accountName", account.getString("name")).put("offlineLogin", false);
    }
    static JSONObject loginSummary(Context c, JSONObject prepared) throws Exception {
        synchronized (DATA_LOCK) {
            JSONObject current = state(c), meta = SyncModel.object(current, "_cloud");
            int legacy = meta.optString("accountId").isEmpty() ? current.getJSONArray("students").length() : 0;
            return new JSONObject().put("accountName", prepared.getString("accountName")).put("legacyStudents", legacy)
                .put("offline", prepared.optBoolean("offlineLogin"));
        }
    }
    static void activate(Context c, JSONObject prepared) throws Exception {
        ProfileStore.activate(c, prepared); error = ""; message = prepared.optBoolean("offlineLogin") ?
            "دخول دون إنترنت إلى المساحة المحفوظة؛ ستُراجع صلاحية الكود عند الاتصال." : "تم الدخول إلى مساحة " + prepared.getString("accountName");
        schedule(c, true);
        try { AlarmScheduler.clear(c); AlarmScheduler.reconcile(c); } catch (Exception e) { message += "؛ راجعي أذونات المنبه."; }
    }
    static JSONObject saveEdit(Context c, String beforeJson, String editedJson) throws Exception {
        synchronized (DATA_LOCK) {
            JSONObject before = SchemaValidator.validate(beforeJson), edited = SchemaValidator.validate(editedJson), current = state(c);
            ProfileStore.assertBinding(current, config(c));
            if (!ProfileStore.key(SyncModel.object(before, "_cloud")).equals(ProfileStore.key(SyncModel.object(current, "_cloud"))))
                throw new Exception("النموذج يخص مساحة أخرى. أغلقيه وافتحيه بعد الدخول للحساب المطلوب");
            JSONObject merged = SyncModel.mergeEdit(before, edited, current);
            SecureStore.write(c, "state", merged.toString()); return merged;
        }
    }
    static String status(Context c) {
        try { synchronized (DATA_LOCK) {
            JSONObject conf = config(c), current = state(c), meta = SyncModel.object(current, "_cloud");
            JSONArray conflicts = new JSONArray(); JSONObject map = SyncModel.object(meta, "conflicts"), records = SyncModel.index(SyncModel.records(current));
            for (String key : SyncModel.keys(map)) {
                JSONObject item = SyncModel.copy(map.getJSONObject(key)); String id = item.getString("id");
                if (records.has(id)) {
                    JSONObject values = records.getJSONObject(id).getJSONObject("values");
                    item.put("name", id.startsWith("student:") ? values.optString("name") : "جلسة " + values.optString("start"));
                    item.put("local", values.get(item.getString("field"))); conflicts.put(item);
                }
            }
            return new JSONObject().put("configured", !conf.optString("accountId").isEmpty()).put("url", conf.optString("url"))
                .put("verified", !conf.optString("accountId").isEmpty()).put("enabled", conf.optBoolean("enabled"))
                .put("accountName", conf.optString("accountName")).put("busy", RUNNING.get()).put("lastSync", meta.optString("lastSync"))
                .put("pending", SyncModel.pending(current)).put("students", current.getJSONArray("students").length())
                .put("syncedStudents", conf.optInt("syncedStudents", -1)).put("lastAttempt", conf.optString("lastAttempt"))
                .put("errorCode", conf.optString("lastErrorCode"))
                .put("conflicts", conflicts).put("overlaps", SyncModel.overlaps(current)).put("message", message)
                .put("error", error.isEmpty() ? conf.optString("lastError") : error).toString();
        }} catch (Exception e) { return "{\"error\":\"تعذر قراءة حالة المزامنة؛ لم تتغير بياناتك\"}"; }
    }
    static void enable(Context c) throws Exception {
        synchronized (DATA_LOCK) {
            JSONObject conf = config(c); ProfileStore.assertBinding(state(c), conf);
            if (conf.optBoolean("revoked")) throw new Exception("راجعي الكود مع المحاسب ثم سجّلي الدخول من جديد");
            conf.put("enabled", true).put("lastError", "").put("lastErrorCode", ""); SecureStore.write(c, "cloud-config", conf.toString()); ProfileStore.remember(c, conf);
            message = "المزامنة مفعّلة؛ الملفات محفوظة محلياً إلى أن ينجح الاتصال."; error = "";
        }
        schedule(c, true);
    }
    static void pause(Context c) throws Exception {
        synchronized (DATA_LOCK) {
            JSONObject conf = config(c); conf.put("enabled", false).put("generation", UUID.randomUUID().toString());
            SecureStore.write(c, "cloud-config", conf.toString());
            if (!conf.optString("accountId").isEmpty()) ProfileStore.remember(c, conf);
            message = "المزامنة متوقفة. ملفات المساحة محفوظة على الجهاز.";
        }
        cancelJobs(c);
    }
    static void restore(Context c, String json, boolean healthy) throws Exception {
        synchronized (DATA_LOCK) {
            JSONObject conf = config(c), valid = SchemaValidator.validate(json), imported = SyncModel.object(valid, "_cloud");
            if (!imported.optString("accountId").isEmpty() && !ProfileStore.key(imported).equals(ProfileStore.key(conf)))
                throw new Exception("النسخة تخص أخصائية أو مدرسة أخرى. ادخلي إلى مساحتها أولاً");
            if (healthy) SecureStore.write(c, "before-import", SecureStore.read(c, "state", SecureStore.EMPTY_STATE));
            pause(c); valid.put("_cloud", new JSONObject().put("serverId", conf.getString("serverId")).put("accountId", conf.getString("accountId")));
            SecureStore.write(c, "state", valid.toString()); message = "استُعيدت النسخة وتوقفت المزامنة. راجعي البيانات قبل إعادة التفعيل.";
        }
    }
    static void resolve(Context c, String id, String field, String choice, String expectedRemote) throws Exception {
        synchronized (DATA_LOCK) {
            if (RUNNING.get()) throw new Exception("انتظري اكتمال المزامنة ثم راجعي التعارض");
            if (!choice.equals("local") && !choice.equals("remote")) throw new Exception("اختاري القيمة المطلوبة");
            JSONObject current = state(c); ProfileStore.assertBinding(current, config(c));
            JSONObject meta = SyncModel.object(current, "_cloud"), conflicts = SyncModel.object(meta, "conflicts");
            String key = id + "|" + field; JSONObject conflict = conflicts.optJSONObject(key);
            if (conflict == null) throw new Exception("هذا التعارض حُلّ بالفعل؛ حدّثي الشاشة");
            Object viewed = new JSONArray("[" + expectedRemote + "]").get(0);
            if (!SyncModel.same(viewed, conflict.get("remote"))) throw new Exception("تغيّرت القيمة مجدداً. أغلقي قائمة الاختلافات وافتحيها لمراجعة أحدث قيمة");
            if (choice.equals("remote")) {
                String collection = id.startsWith("student:") ? "students" : "sessions", recordId = id.substring(id.indexOf(':') + 1);
                JSONObject target = SyncModel.index(current.getJSONArray(collection)).getJSONObject(recordId);
                if (collection.equals("students")) SyncModel.setField(target, field, conflict.get("remote")); else target.put(field, conflict.get("remote"));
            }
            conflicts.remove(key); meta.put("conflicts", conflicts); current.put("_cloud", meta);
            SchemaValidator.validate(current.toString()); SecureStore.write(c, "state", current.toString());
        }
        schedule(c, true);
    }
    static void schedule(Context c, boolean immediate) {
        try { synchronized (DATA_LOCK) {
            JSONObject conf = config(c); if (!conf.optBoolean("enabled") || conf.optBoolean("revoked")) return;
            // Save/login/network recovery get an in-process attempt, even when Android
            // delays or rejects background jobs. All HTTP work stays off the UI thread.
            if (immediate) requestDirect(c, 1000);
            JobScheduler scheduler = c.getSystemService(JobScheduler.class); ComponentName service = new ComponentName(c, SyncJobService.class);
            if (scheduler.getPendingJob(JOB_PERIODIC) == null) scheduler.schedule(new JobInfo.Builder(JOB_PERIODIC, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15 * 60 * 1000L).build());
            if (immediate && scheduler.getPendingJob(JOB_ONCE) == null) scheduler.schedule(new JobInfo.Builder(JOB_ONCE, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(3000)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
        }} catch (Exception ignored) { message = "قد تتأخر المزامنة في الخلفية؛ تستمر المحاولة المباشرة أثناء فتح التطبيق."; }
    }
    private static void cancelJobs(Context c) {
        cancelDirect();
        try { JobScheduler s = c.getSystemService(JobScheduler.class); s.cancel(JOB_ONCE); s.cancel(JOB_PERIODIC); }
        catch (Exception ignored) { /* Persisted enabled=false still prevents further work. */ }
    }
    static boolean run(Context c) {
        return transfer(c, false);
    }
    static boolean pull(Context c) {
        return transfer(c, true);
    }
    private static boolean transfer(Context c, boolean pullOnly) {
        if (!RUNNING.compareAndSet(false, true)) return true;
        String generation = "";
        MainActivity.cloudChanged(false);
        try {
            final JSONObject conf, snapshot;
            synchronized (DATA_LOCK) {
                conf = config(c); if (!conf.optBoolean("enabled") || conf.optBoolean("revoked")) return false;
                snapshot = state(c); ProfileStore.assertBinding(snapshot, conf);
                generation = conf.optString("generation");
                conf.put("lastAttempt", Instant.now().toString());
                SecureStore.write(c, "cloud-config", conf.toString());
            }
            JSONObject request = new JSONObject().put("protocol", 2).put("action", pullOnly ? "pull" : "sync").put("deviceId", conf.getString("deviceId"))
                .put("code", conf.getString("code")).put("serverId", conf.getString("serverId")).put("accountId", conf.getString("accountId"))
                .put("records", pullOnly ? new JSONArray() : SyncModel.requestRecords(snapshot));
            JSONObject response = post(conf.getString("url"), request.toString());
            if (Thread.currentThread().isInterrupted()) return true;
            checkResponse(response);
            synchronized (DATA_LOCK) {
                JSONObject latestConfig = config(c);
                if (!conf.optString("generation").equals(latestConfig.optString("generation")) || !latestConfig.optBoolean("enabled")) return false;
                if (!conf.getString("serverId").equals(response.getString("serverId")) ||
                    !conf.getString("accountId").equals(response.getJSONObject("account").getString("id")))
                    throw new Exception("رد المزامنة يخص مساحة مختلفة. لم تتغير ملفات الجهاز");
                JSONObject latest = state(c); ProfileStore.assertBinding(latest, latestConfig);
                JSONObject merged = pullOnly ? SyncModel.applyPull(snapshot, latest, response) : SyncModel.apply(snapshot, latest, response);
                SecureStore.write(c, "state", merged.toString());
                int syncedStudents = 0;
                JSONArray received = response.getJSONArray("records");
                for (int i = 0; i < received.length(); i++)
                    if (received.getJSONObject(i).getString("id").startsWith("student:")) syncedStudents++;
                latestConfig.put("accountName", response.getJSONObject("account").getString("name"))
                    .put("syncedStudents", syncedStudents).put("lastError", "").put("lastErrorCode", "");
                SecureStore.write(c, "cloud-config", latestConfig.toString()); ProfileStore.remember(c, latestConfig);
                int count = SyncModel.object(SyncModel.object(merged, "_cloud"), "conflicts").length();
                message = "آخر تأكيد من الشيت: " + syncedStudents + " ملف طالب." + (count > 0 ? " توجد اختلافات تحتاج مراجعتك." : "");
                error = "";
                try { AlarmScheduler.sync(c, AlarmScheduler.itemsForState(merged)); }
                catch (Exception e) { error = "تزامنت البيانات؛ راجعي أذونات المنبه لتفعيل المواعيد الجديدة."; }
            }
            return false;
        } catch (Exception e) {
            try { synchronized (DATA_LOCK) {
                if (!generation.isEmpty() && !generation.equals(config(c).optString("generation"))) return false;
                if (e instanceof RemoteFailure && (((RemoteFailure) e).code.equals("AUTH") || ((RemoteFailure) e).code.equals("BINDING"))) {
                    ProfileStore.revoke(c); cancelJobs(c); MainActivity.cloudRevoked();
                }
            } }
            catch (Exception ignored) { return true; }
            String detail = e.getMessage();
            error = detail != null && detail.matches("(?s).*[\\u0600-\\u06FF].*") ? detail :
                "تعذر الاتصال بالشيت. بياناتك محفوظة محلياً وستُعاد المحاولة عند توفر الاتصال.";
            try { synchronized (DATA_LOCK) {
                JSONObject conf = config(c);
                if (generation.equals(conf.optString("generation"))) {
                    conf.put("lastError", error).put("lastErrorCode", e instanceof RemoteFailure ? ((RemoteFailure)e).code : "CONNECTION");
                    SecureStore.write(c, "cloud-config", conf.toString());
                }
            }} catch (Exception ignored) { /* Do not replace student data to record an error. */ }
            message = ""; return true;
        } finally { RUNNING.set(false); MainActivity.cloudChanged(true); }
    }
    private static void checkResponse(JSONObject response) throws Exception {
        if (!response.optBoolean("ok")) throw new RemoteFailure(response.optString("code"), response.optString("message", "تعذر الاتصال بالمدرسة"));
        if (response.optInt("protocol") != 2 || response.optString("serverId").isEmpty() || response.optJSONObject("account") == null)
            throw new Exception("رد الربط غير متوافق. استخدم نسخة Code.gs المرفقة مع هذا التطبيق");
    }
    private static final class RemoteFailure extends Exception {
        final String code;
        RemoteFailure(String code, String message) { super(message); this.code = code; }
    }
    private static void validateEndpoint(String endpoint) throws Exception {
        if (!endpoint.matches("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec")) throw new Exception("استخدمي رابط النشر https://script.google.com/macros/s/…/exec بدون إضافات، وليس رابط المحرر أو /dev");
    }
    private static boolean allowedRedirect(URL url) {
        return url.getProtocol().equals("https") && (url.getPort() == -1 || url.getPort() == 443) && url.getUserInfo() == null
            && (url.getHost().equals("script.googleusercontent.com") || url.getHost().equals("script.google.com"));
    }
    private static JSONObject post(String endpoint, String body) throws Exception {
        validateEndpoint(endpoint); URL url = new URL(endpoint); byte[] payload = body.getBytes(StandardCharsets.UTF_8); boolean send = true;
        if (payload.length > 5 * 1024 * 1024) throw new Exception("حجم المزامنة أكبر من الحد المسموح");
        for (int redirect = 0; redirect < 5; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(20000); connection.setReadTimeout(55000); connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                if (send) {
                    connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
                }
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new Exception("رد توجيه غير صالح");
                    URL next = new URL(url, location);
                    if (!allowedRedirect(next)) throw new Exception("النشر يطلب تسجيل دخول Google. اضبط الوصول إلى تطبيق الويب حسب دليل الربط، ولا تجعل الشيت عاماً");
                    // ContentService returns one-time HTTPS GET URLs. Never forward the bearer token on redirect.
                    if (status == 307 || status == 308) throw new Exception("نوع توجيه غير متوقع من Google؛ راجع رابط /exec");
                    url = next; send = false; continue;
                }
                if (status != 200) throw new Exception("تعذر الاتصال بخادم Google. رمز الاستجابة: " + status);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192]; int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (out.size() + length > 5 * 1024 * 1024) throw new Exception("رد المزامنة أكبر من الحد المسموح");
                        out.write(buffer, 0, length);
                    }
                }
                String json = out.toString(StandardCharsets.UTF_8.name());
                if (!json.trim().startsWith("{")) throw new Exception("وصلت صفحة Google بدل بيانات المزامنة. تأكد من نشر Code.gs الصحيح كتطبيق ويب ومن استخدام رابط /exec");
                return new JSONObject(json);
            } finally { connection.disconnect(); }
        }
        throw new Exception("تكررت إعادة التوجيه؛ تحققي من رابط تطبيق الويب");
    }
}
