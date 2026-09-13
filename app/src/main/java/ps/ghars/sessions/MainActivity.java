package ps.ghars.sessions;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline-only UI host. No external page ever has access to the native bridge. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://ghars.local/";
    private static final int AUTH = 201, EXPORT = 202, IMPORT = 203, REPORT = 204, RINGTONE = 205, NOTIFICATIONS = 206;
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> pendingScripts = new ArrayList<>();
    private WebView web;
    private LinearLayout lockScreen;
    private volatile boolean authenticated, visible, storageHealthy = true;
    private boolean authenticating, authCancelled, loaded, pageReady, pickerActive, destroyed, importConfirmationActive, reloadPending;
    private char[] pendingPassword;
    private String pendingReport, pendingImport;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFFFAF8F4);
        applyInsets(this, root, 0, 0);
        web = new WebView(this);
        web.setBackgroundColor(0xFFFAF8F4);
        web.setVisibility(View.INVISIBLE);
        configureWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        lockScreen = new LinearLayout(this); lockScreen.setOrientation(LinearLayout.VERTICAL); lockScreen.setGravity(Gravity.CENTER);
        lockScreen.setPadding(32, 32, 32, 32); lockScreen.setBackgroundColor(0xFFFAF8F4); lockScreen.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView title = new TextView(this); title.setText("غرس • مساحة الأخصائية"); title.setTextSize(25); title.setTextColor(0xFF981765); title.setGravity(Gravity.CENTER); lockScreen.addView(title);
        TextView detail = new TextView(this); detail.setText("افتحي قفل الجهاز للوصول إلى ملفات الطلاب"); detail.setTextSize(16); detail.setGravity(Gravity.CENTER); detail.setPadding(0, 28, 0, 28); lockScreen.addView(detail);
        Button unlock = new Button(this); unlock.setText("فتح التطبيق"); unlock.setAllCaps(false); unlock.setOnClickListener(v -> { authCancelled = false; unlock(); }); lockScreen.addView(unlock);
        root.addView(lockScreen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        RingingService.channels(this);
        if (state != null && state.getBoolean("pickerActive")) result("permissions", false, "أُلغيت العملية بعد إعادة تشغيل الشاشة؛ أعيدي المحاولة");
    }

    static void applyInsets(Activity activity, View root, int horizontal, int vertical) {
        activity.getWindow().setStatusBarColor(0xFFFAF8F4);
        activity.getWindow().setNavigationBarColor(0xFFFAF8F4);
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets inset = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(horizontal + inset.left, vertical + inset.top, horizontal + inset.right, vertical + inset.bottom);
                return windowInsets;
            });
        } else { root.setFitsSystemWindows(true); root.setPadding(horizontal, vertical, horizontal, vertical); }
    }

    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(true); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false); settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSaveFormData(false); settings.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !trustedPage(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return !trustedPage(Uri.parse(url)); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return asset(request.getUrl()); }
            @Override public void onPageFinished(WebView view, String url) {
                if (!trustedPage(Uri.parse(url))) return;
                pageReady = true;
                flushScripts();
                if (authenticated) web.evaluateJavascript("if(window.onNativeResume)window.onNativeResume()", null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult callback) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("حسناً", (d, w) -> callback.confirm()).setOnCancelListener(d -> callback.cancel()).show(); return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult callback) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("تأكيد", (d, w) -> callback.confirm()).setNegativeButton("إلغاء", (d, w) -> callback.cancel()).setOnCancelListener(d -> callback.cancel()).show(); return true;
            }
        });
    }
    private static boolean trustedPage(Uri uri) { return "https".equals(uri.getScheme()) && "ghars.local".equals(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443) && "/index.html".equals(uri.getPath()); }
    private WebResourceResponse asset(Uri uri) {
        try {
            if (!"https".equals(uri.getScheme()) || !"ghars.local".equals(uri.getHost()) || (uri.getPort() != -1 && uri.getPort() != 443)) return denied();
            String path = uri.getPath();
            if (path == null || !path.matches("/[A-Za-z0-9_-]+\\.(html|css|js|png|jpg|svg|woff2)")) return denied();
            String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css" : path.endsWith(".svg") ? "image/svg+xml" : path.endsWith(".woff2") ? "font/woff2" : path.endsWith(".png") ? "image/png" : "image/jpeg";
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
            headers.put("X-Content-Type-Options", "nosniff"); headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(path.substring(1)));
        } catch (Exception ignored) { return denied(); }
    }
    private static WebResourceResponse denied() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", new HashMap<>(), new ByteArrayInputStream(new byte[0])); }

    @Override public void onResume() {
        super.onResume(); visible = true;
        if (!authenticating && !authCancelled) unlock();
        if (authenticated) {
            flushScripts();
            if (pageReady) web.evaluateJavascript("if(window.onNativeResume)window.onNativeResume()", null);
        }
        worker.execute(() -> { try { AlarmScheduler.reconcile(this); } catch (Exception error) { result("permissions", false, "تعذر تحديث المنبّهات؛ راجعي البيانات والصلاحيات"); } });
    }
    @Override public void onPause() {
        visible = false;
        if (lockEnabled()) { authenticated = false; web.setVisibility(View.INVISIBLE); lockScreen.setVisibility(View.VISIBLE); }
        super.onPause();
    }
    private boolean lockEnabled() { return getSharedPreferences("ghars", MODE_PRIVATE).getBoolean("lock", false); }
    private void unlock() {
        if (destroyed || authenticating) return;
        if (!lockEnabled()) { authenticated = true; reveal(); return; }
        if (authenticated) { reveal(); return; }
        KeyguardManager manager = getSystemService(KeyguardManager.class);
        if (!manager.isDeviceSecure()) {
            // A removed system credential must not permanently lock the specialist out.
            getSharedPreferences("ghars", MODE_PRIVATE).edit().putBoolean("lock", false).apply();
            authenticated = true; reveal();
            result("permissions", false, "أضيفي قفلاً للجهاز ثم أعيدي تفعيل حماية غرس");
            return;
        }
        Intent challenge = manager.createConfirmDeviceCredentialIntent("فتح غرس", "تحققي من قفل الجهاز لعرض ملفات الطلاب");
        if (challenge != null) { authenticating = true; startActivityForResult(challenge, AUTH); }
    }
    private void reveal() {
        lockScreen.setVisibility(View.GONE); web.setVisibility(View.VISIBLE);
        if (!loaded) { loaded = true; web.loadUrl(ORIGIN + "index.html"); }
        else if (reloadPending) { reloadPending = false; pageReady = false; web.reload(); }
        flushScripts();
        if (pendingImport != null && visible) confirmImport();
    }
    private boolean allowed() { return authenticated && !destroyed; }
    static void showAlarmIfVisible() {
        MainActivity activity = current.get();
        if (activity != null && activity.visible) activity.runOnUiThread(() -> {
            if (activity.visible && !activity.destroyed) activity.startActivity(new Intent(activity, AlarmActivity.class));
        });
    }

    public final class Bridge {
        @JavascriptInterface public String loadState() {
            if (!allowed()) return "{\"error\":\"التطبيق مقفل\"}";
            try {
                String json = SecureStore.read(MainActivity.this, "state", SecureStore.EMPTY_STATE);
                SchemaValidator.validate(json); storageHealthy = true; return json;
            } catch (Exception error) {
                storageHealthy = false;
                return "{\"error\":" + JSONObject.quote("تعذر قراءة البيانات. لا تُحذف البيانات الحالية؛ استعيدي نسخة احتياطية صالحة.") + "}";
            }
        }
        @JavascriptInterface public String saveState(String json) {
            if (!allowed()) return "افتحي قفل التطبيق أولاً";
            if (!storageHealthy) return "الحفظ متوقف لحماية البيانات السابقة؛ استعيدي نسخة احتياطية صالحة";
            try {
                JSONObject state = SchemaValidator.validate(json);
                SecureStore.write(MainActivity.this, "state", state.toString());
                try { AlarmScheduler.sync(MainActivity.this, AlarmScheduler.itemsForState(state)); }
                catch (Exception error) { result("permissions", false, "حُفظت البيانات، لكن تعذر تحديث المنبّهات؛ افتحي التطبيق مجدداً وراجعي الصلاحيات"); }
                return "";
            } catch (Exception error) { return readable(error, "تعذر حفظ البيانات"); }
        }
        @JavascriptInterface public String syncAlarms(String json) {
            if (!allowed()) return "افتحي قفل التطبيق أولاً";
            try { AlarmScheduler.sync(MainActivity.this, SchemaValidator.validateAlarms(json)); return ""; }
            catch (Exception error) { return readable(error, "تعذر تحديث المنبّهات"); }
        }
        @JavascriptInterface public long toEpoch(String localDateTime) { return SchemaValidator.toEpoch(localDateTime); }
        @JavascriptInterface public String getStatus() {
            try {
                NotificationManager manager = getSystemService(NotificationManager.class);
                NotificationChannel channel = manager.getNotificationChannel(RingingService.CHANNEL);
                AudioManager audio = getSystemService(AudioManager.class);
                JSONObject result = new JSONObject();
                result.put("exact", AlarmScheduler.canExact(MainActivity.this));
                result.put("notifications", manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE));
                result.put("fullScreen", Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent());
                result.put("batteryOptimized", !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()));
                result.put("lockEnabled", lockEnabled()); result.put("canLock", getSystemService(KeyguardManager.class).isDeviceSecure());
                result.put("alarmVolume", audio.getStreamVolume(AudioManager.STREAM_ALARM));
                return result.toString();
            } catch (Exception ignored) { return "{}"; }
        }
        @JavascriptInterface public void requestPermission(String kind) {
            if (!allowed()) return;
            runOnUiThread(() -> permission(kind));
        }
        @JavascriptInterface public void testAlarm() {
            if (!allowed()) return;
            try { AlarmScheduler.addExtra(MainActivity.this, "تجربة منبّه غرس", "هذا تنبيه تجريبي • يمكنك إيقافه أو تأجيله", System.currentTimeMillis() + 5000); result("testAlarm", true, "سيبدأ المنبّه بعد نحو ٥ ثوانٍ؛ يلزم تفعيل المنبّهات الدقيقة للرنين في الخلفية"); }
            catch (Exception error) { result("testAlarm", false, readable(error, "تعذر تشغيل التجربة")); }
        }
        @JavascriptInterface public void chooseRingtone() { if (allowed()) runOnUiThread(() -> ringtone()); }
        @JavascriptInterface public void exportBackup(String password) { if (allowed()) runOnUiThread(() -> backupPicker(false, password)); }
        @JavascriptInterface public void importBackup(String password) { if (allowed()) runOnUiThread(() -> backupPicker(true, password)); }
        @JavascriptInterface public boolean setLockEnabled(boolean enabled) {
            if (!allowed()) return lockEnabled();
            boolean actual = enabled && getSystemService(KeyguardManager.class).isDeviceSecure();
            if (!getSharedPreferences("ghars", MODE_PRIVATE).edit().putBoolean("lock", actual).commit()) return lockEnabled();
            return actual;
        }
        @JavascriptInterface public void exportReport(String filename, String text) {
            if (!allowed()) return;
            runOnUiThread(() -> reportPicker(filename, text));
        }
        @JavascriptInterface public void openAlarmVolume() { if (allowed()) runOnUiThread(() -> openSettings(new Intent(Settings.ACTION_SOUND_SETTINGS))); }
    }

    private void permission(String kind) {
        try {
            Intent intent;
            if ("exact".equals(kind)) {
                if (Build.VERSION.SDK_INT < 31) { result("permissions", true, "المنبّهات الدقيقة مفعّلة"); return; }
                intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()));
            } else if ("notifications".equals(kind)) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS); return;
                }
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else if ("fullScreen".equals(kind)) {
                if (Build.VERSION.SDK_INT < 34) { result("permissions", true, "شاشة المنبّه متاحة"); return; }
                intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName()));
            } else if ("battery".equals(kind)) intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            else return;
            openSettings(intent);
        } catch (Exception error) { result("permissions", false, "افتحي إعدادات الجهاز ثم إعدادات غرس لتغيير هذه الصلاحية"); }
    }
    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (Exception error) { result("permissions", false, "هذا الإعداد غير متاح هنا؛ افتحي إعدادات الجهاز يدوياً"); }
    }
    private boolean beginPicker(String kind) {
        if (pickerActive || pendingImport != null || importConfirmationActive) { result(kind, false, "أكملي العملية الحالية أولاً"); return false; }
        pickerActive = true; return true;
    }
    private void backupPicker(boolean importing, String password) {
        String kind = importing ? "importBackup" : "exportBackup";
        try {
            char[] chars = password == null ? new char[0] : password.toCharArray();
            try { BackupCodec.validatePassword(chars); } catch (Exception error) { Arrays.fill(chars, '\0'); throw error; }
            if (!beginPicker(kind)) { Arrays.fill(chars, '\0'); return; }
            pendingPassword = chars;
            Intent intent = new Intent(importing ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(importing ? "*/*" : "application/octet-stream");
            if (!importing) intent.putExtra(Intent.EXTRA_TITLE, "ghars-backup-" + LocalDate.now(SchemaValidator.GAZA) + ".ghars");
            startActivityForResult(intent, importing ? IMPORT : EXPORT);
        } catch (Exception error) { clearPicker(); result(kind, false, readable(error, "تعذر فتح منتقي الملفات")); }
    }
    private void reportPicker(String filename, String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > SchemaValidator.MAX_BYTES) { result("exportReport", false, "حجم التقرير كبير جداً"); return; }
        if (!beginPicker("exportReport")) return;
        pendingReport = text;
        String name = filename == null ? "ghars-report.csv" : filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.length() > 150) name = name.substring(0, 150);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) name += ".csv";
        try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(Intent.EXTRA_TITLE, name), REPORT); }
        catch (Exception error) { clearPicker(); result("exportReport", false, "تعذر فتح منتقي الملفات"); }
    }
    private void ringtone() {
        if (!beginPicker("ringtone")) return;
        String selected = getSharedPreferences("ghars", MODE_PRIVATE).getString("ringtone", "");
        Uri existing = selected == null || selected.isEmpty() ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) : Uri.parse(selected);
        try {
            startActivityForResult(new Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "اختاري نغمة منبّه غرس").putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true).putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing), RINGTONE);
        } catch (Exception error) { clearPicker(); result("ringtone", false, "منتقي النغمات غير متاح؛ ستستخدم نغمة منبّه الجهاز"); }
    }

    @Override public void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == AUTH) {
            authenticating = false;
            authenticated = code == RESULT_OK;
            authCancelled = !authenticated;
            if (authenticated) reveal();
            return;
        }
        if (request != EXPORT && request != IMPORT && request != REPORT && request != RINGTONE) return;
        String kind = request == EXPORT ? "exportBackup" : request == IMPORT ? "importBackup" : request == REPORT ? "exportReport" : "ringtone";
        if (code != RESULT_OK || data == null) { clearPicker(); result(kind, false, "أُلغيت العملية"); return; }
        if (request == RINGTONE) {
            Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (uri != null) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                getSharedPreferences("ghars", MODE_PRIVATE).edit().putString("ringtone", uri.toString()).apply();
                result(kind, true, "حُفظت نغمة المنبّه");
            } else result(kind, false, "لم يتم تغيير النغمة");
            clearPicker(); return;
        }
        Uri uri = data.getData();
        if (uri == null) { clearPicker(); result(kind, false, "لم يُحدد ملف"); return; }
        char[] password = pendingPassword;
        String report = pendingReport;
        pendingPassword = null; pendingReport = null;
        worker.execute(() -> {
            try {
                if (request == IMPORT) {
                    if (password == null) throw new Exception("أعيدي اختيار النسخة وكلمة المرور بعد إعادة تشغيل التطبيق");
                    String json;
                    try (InputStream input = getContentResolver().openInputStream(uri)) { json = BackupCodec.decrypt(readLimited(input), password); }
                    JSONObject valid = SchemaValidator.validate(json);
                    runOnUiThread(() -> { pickerActive = false; pendingImport = valid.toString(); if (authenticated && visible) confirmImport(); });
                } else {
                    byte[] bytes;
                    if (request == EXPORT) {
                        if (password == null) throw new Exception("أعيدي اختيار كلمة المرور");
                        String state = SecureStore.read(this, "state", SecureStore.EMPTY_STATE); SchemaValidator.validate(state);
                        bytes = BackupCodec.encrypt(state, password);
                    } else {
                        if (report == null) throw new Exception("أعيدي تصدير التقرير");
                        // UTF-8 BOM enables Arabic CSV in desktop spreadsheet programs.
                        bytes = (report.startsWith("\uFEFF") ? report : "\uFEFF" + report).getBytes(StandardCharsets.UTF_8);
                    }
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new Exception("تعذر فتح الملف للكتابة"); output.write(bytes); output.flush();
                    } finally { Arrays.fill(bytes, (byte) 0); }
                    result(kind, true, request == EXPORT ? "حُفظت النسخة المشفّرة؛ احتفظي بكلمة المرور في مكان آمن" : "حُفظ التقرير بنجاح");
                }
            } catch (Exception error) { result(kind, false, request == IMPORT ? "لم تتغير بياناتك. تحققي من كلمة المرور وصلاحية نسخة غرس." : readable(error, "تعذر حفظ الملف")); }
            finally {
                if (password != null) Arrays.fill(password, '\0');
                runOnUiThread(() -> pickerActive = false);
            }
        });
    }

    private void confirmImport() {
        if (pendingImport == null || !authenticated || destroyed) return;
        final String json = pendingImport; pendingImport = null; pickerActive = true; importConfirmationActive = true;
        try {
            JSONObject state = new JSONObject(json);
            String summary = "النسخة تحتوي على " + state.getJSONArray("students").length() + " طالباً و" + state.getJSONArray("sessions").length() + " جلسة و" + state.getJSONArray("reminders").length() + " تذكيراً.\n\nستستبدل البيانات الحالية بالكامل. يمكنك الإلغاء وتصدير نسخة من بياناتك أولاً.";
            new AlertDialog.Builder(this).setTitle("استعادة النسخة الاحتياطية؟").setMessage(summary)
                .setNegativeButton("إلغاء", (dialog, which) -> { pickerActive = false; importConfirmationActive = false; result("importBackup", false, "أُلغيت الاستعادة؛ لم تتغير البيانات"); })
                .setOnCancelListener(dialog -> { pickerActive = false; importConfirmationActive = false; result("importBackup", false, "أُلغيت الاستعادة؛ لم تتغير البيانات"); })
                .setPositiveButton("استبدال واستعادة", (dialog, which) -> worker.execute(() -> {
                    try {
                        SchemaValidator.validate(json);
                        if (storageHealthy) {
                            String old = SecureStore.read(this, "state", SecureStore.EMPTY_STATE);
                            SecureStore.write(this, "before-import", old);
                        }
                        SecureStore.write(this, "state", json); storageHealthy = true;
                        String message = "اكتملت الاستعادة بنجاح";
                        try { AlarmScheduler.clear(this); AlarmScheduler.reconcile(this); }
                        catch (Exception alarmError) { message += "؛ راجعي إعدادات المنبّهات"; }
                        result("importBackup", true, message);
                        runOnUiThread(() -> {
                            pickerActive = false; importConfirmationActive = false; pageReady = false;
                            if (authenticated && visible) web.reload(); else reloadPending = true;
                        });
                    } catch (Exception error) { pickerActive = false; importConfirmationActive = false; result("importBackup", false, "تعذرت الاستعادة؛ لم تُستبدل البيانات. " + readable(error, "تحققي من مساحة الجهاز")); }
                })).show();
        } catch (Exception error) { pickerActive = false; importConfirmationActive = false; result("importBackup", false, "تعذر قراءة النسخة"); }
    }
    private static byte[] readLimited(InputStream input) throws Exception {
        if (input == null) throw new Exception("تعذر فتح الملف");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > SchemaValidator.MAX_BYTES + 1024) throw new Exception("الملف أكبر من الحد المسموح");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    private void clearPicker() { if (pendingPassword != null) Arrays.fill(pendingPassword, '\0'); pendingPassword = null; pendingReport = null; pickerActive = false; }
    private static String readable(Exception error, String fallback) {
        String message = error.getMessage();
        return message != null && message.matches(".*[\u0600-\u06FF].*") ? message : fallback;
    }
    private void result(String kind, boolean ok, String message) {
        String script = "if(window.onNativeResult)window.onNativeResult(" + JSONObject.quote(kind) + "," + ok + "," + JSONObject.quote(message) + ")";
        runOnUiThread(() -> { if (destroyed) return; pendingScripts.add(script); flushScripts(); });
    }
    private void flushScripts() {
        if (!pageReady || !authenticated || !visible || destroyed) return;
        for (String script : pendingScripts) web.evaluateJavascript(script, null);
        pendingScripts.clear();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NOTIFICATIONS) result("permissions", grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED,
            grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED ? "فُعّلت الإشعارات" : "الإشعارات غير مفعّلة؛ يمكنك تغييرها من إعدادات الجهاز");
    }
    @Override public void onSaveInstanceState(Bundle out) { out.putBoolean("pickerActive", pickerActive); super.onSaveInstanceState(out); }
    @Override public void onBackPressed() {
        if (!authenticated) { moveTaskToBack(true); return; }
        web.evaluateJavascript("(function(){return window.onNativeBack?window.onNativeBack():false})()", handled -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
        });
    }
    @Override public void onDestroy() {
        destroyed = true; visible = false; clearPicker(); worker.shutdown();
        if (web != null) { web.removeJavascriptInterface("Native"); web.destroy(); }
        if (current.get() == this) current.clear();
        super.onDestroy();
    }
}
