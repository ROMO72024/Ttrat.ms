package ps.ghars.sessions;

import android.Manifest;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import org.json.JSONArray;
import java.util.concurrent.TimeUnit;

/** Real looping alarm audio, independent of whether the WebView is open. */
public final class RingingService extends Service {
    static final String ACTION_RING = "ps.ghars.sessions.RING";
    static final String ACTION_DISMISS = "ps.ghars.sessions.DISMISS";
    static final String ACTION_SNOOZE = "ps.ghars.sessions.SNOOZE";
    static final String ACTION_CLOSED = "ps.ghars.sessions.ALARM_CLOSED";
    static final String CHANNEL = "ghars_ringing_v1";
    static final int ID = 7301;
    static volatile boolean isRinging;
    static volatile String activeTitle = "حان وقت التذكير";
    static volatile String activeDetail = "مدرسة وروضة غرس الحديثة";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private ToneGenerator tone;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private AudioFocusRequest audioFocus;
    private boolean stopping;
    private int count;
    private final JSONArray activeSources = new JSONArray();

    @Override public void onCreate() { super.onCreate(); channels(this); }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (ACTION_DISMISS.equals(action) || ACTION_SNOOZE.equals(action)) {
            if (ACTION_SNOOZE.equals(action) && isRinging) {
                try { AlarmScheduler.addExtra(this, "تذكير مؤجل من غرس", "انتهت مدة التأجيل • افتحي غرس للتفاصيل", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5), activeSources); }
                catch (Exception error) { showFailure(this); }
            }
            stopRinging();
            return START_NOT_STICKY;
        }
        if (!ACTION_RING.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        try {
            JSONArray items = new JSONArray(intent.getStringExtra("items"));
            if (items.length() == 0) { stopSelf(); return START_NOT_STICKY; }
            for (int i = 0; i < items.length(); i++) {
                JSONArray sources = items.getJSONObject(i).optJSONArray("sources");
                if (sources == null) activeSources.put(items.getJSONObject(i));
                else for (int j = 0; j < sources.length(); j++) activeSources.put(sources.getJSONObject(j));
            }
            count += items.length();
            activeTitle = count == 1 ? items.getJSONObject(0).getString("title") : "لديك " + count + " تذكيرات الآن";
            activeDetail = count == 1 ? items.getJSONObject(0).getString("detail") : "افتحي غرس لمراجعة الجلسات والتذكيرات";
            boolean alreadyRinging = isRinging;
            isRinging = true;
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, ringingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, ringingNotification());
            if (!alreadyRinging) {
                acquireWakeLock();
                startSound();
                vibrator = getSystemService(Vibrator.class);
                if (vibrator != null && vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 400, 500, 1400}, 0));
            }
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, TimeUnit.MINUTES.toMillis(5));
            MainActivity.showAlarmIfVisible();
            return START_NOT_STICKY;
        } catch (Exception error) { showFallback(this); stopRinging(); return START_NOT_STICKY; }
    }

    private final Runnable timeout = () -> {
        notifyIfAllowed(this, 7302, basic(this, "تذكير لم تتم متابعته", "توقف الرنين بعد خمس دقائق؛ افتحي غرس للمراجعة", "ghars_info_v1").build());
        stopRinging();
    };

    private Notification ringingNotification() {
        Intent alarm = new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent screen;
        if (Build.VERSION.SDK_INT >= 35) {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            screen = PendingIntent.getActivity(this, 7303, alarm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, options.toBundle());
        } else screen = PendingIntent.getActivity(this, 7303, alarm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent dismiss = PendingIntent.getService(this, 7304, new Intent(this, RingingService.class).setAction(ACTION_DISMISS), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent snooze = PendingIntent.getService(this, 7305, new Intent(this, RingingService.class).setAction(ACTION_SNOOZE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification publicView = basic(this, "منبّه غرس", "حان موعد تذكير • إيقاف أو تأجيل خمس دقائق", CHANNEL).setVisibility(Notification.VISIBILITY_PUBLIC).build();
        return basic(this, activeTitle, activeDetail, CHANNEL).setContentIntent(screen).setFullScreenIntent(screen, true)
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicView)
            .addAction(new Notification.Action.Builder(null, "إيقاف", dismiss).build())
            .addAction(new Notification.Action.Builder(null, "تأجيل ٥ دقائق", snooze).build()).build();
    }

    private void startSound() {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> { /* Alarm continues until explicitly dismissed or timed out. */ }).build();
        getSystemService(AudioManager.class).requestAudioFocus(audioFocus);
        SharedPreferences preferences = getSharedPreferences("ghars", MODE_PRIVATE);
        String selected = preferences.getString("ringtone", "");
        Uri uri = selected == null || selected.isEmpty() ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) : Uri.parse(selected);
        try { play(uri, attributes); }
        catch (Exception first) {
            releasePlayer();
            try { play(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attributes); }
            catch (Exception second) { releasePlayer(); startToneFallback(); }
        }
    }
    private void play(Uri uri, AudioAttributes attributes) throws Exception {
        player = new MediaPlayer();
        player.setAudioAttributes(attributes);
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setDataSource(this, uri);
        player.setLooping(true);
        player.setOnErrorListener((mp, what, extra) -> { releasePlayer(); startToneFallback(); return true; });
        player.prepare();
        player.start();
    }
    private void startToneFallback() {
        if (tone != null || stopping) return;
        try { tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100); handler.post(beep); }
        catch (RuntimeException ignored) { /* Vibration and full-screen alarm remain available. */ }
    }
    private final Runnable beep = new Runnable() {
        @Override public void run() {
            if (tone != null && !stopping) { tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900); handler.postDelayed(this, 1600); }
        }
    };
    private void acquireWakeLock() {
        PowerManager manager = getSystemService(PowerManager.class);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ghars:AlarmAudio");
        wakeLock.acquire(TimeUnit.MINUTES.toMillis(6));
    }
    private void releasePlayer() {
        if (player != null) { try { player.release(); } catch (RuntimeException ignored) { } player = null; }
    }
    private void stopRinging() { stopping = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    @Override public void onDestroy() {
        stopping = true; isRinging = false; count = 0;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (tone != null) { tone.release(); tone = null; }
        if (vibrator != null) vibrator.cancel();
        if (audioFocus != null) getSystemService(AudioManager.class).abandonAudioFocusRequest(audioFocus);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        sendBroadcast(new Intent(ACTION_CLOSED).setPackage(getPackageName()));
        super.onDestroy();
    }

    static Notification.Builder basic(Context context, String title, String detail, String channel) {
        PendingIntent open = PendingIntent.getActivity(context, 7306, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_alarm).setColor(0xFF981765)
            .setContentTitle(title).setContentText(detail).setContentIntent(open).setAutoCancel(true).setShowWhen(true);
    }
    static void channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel alarm = new NotificationChannel(CHANNEL, "منبّه الجلسات المتواصل", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("رنين فعلي متواصل مع شاشة إيقاف وتأجيل، وصوت من مستوى صوت المنبّه");
        alarm.setSound(null, null); alarm.enableVibration(false); alarm.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(alarm);
        NotificationChannel fallback = new NotificationChannel("ghars_fallback_v1", "تنبيهات عند تقييد المنبّه", NotificationManager.IMPORTANCE_HIGH);
        fallback.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        fallback.enableVibration(true); manager.createNotificationChannel(fallback);
        manager.createNotificationChannel(new NotificationChannel("ghars_info_v1", "حالة المنبّه", NotificationManager.IMPORTANCE_DEFAULT));
    }
    static void showFallback(Context context) {
        channels(context);
        notifyIfAllowed(context, 7307, basic(context, "حان موعد تذكير من غرس", "افتحي غرس؛ فعّلي المنبّهات الدقيقة للرنين المتواصل", "ghars_fallback_v1").setCategory(Notification.CATEGORY_ALARM).build());
    }
    static void showFailure(Context context) {
        channels(context);
        notifyIfAllowed(context, 7308, basic(context, "راجعي إعدادات التنبيه", "تعذر تحديث المنبّهات؛ افتحي تطبيق غرس للمراجعة", "ghars_info_v1").build());
    }
    private static void notifyIfAllowed(Context context, int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.areNotificationsEnabled()) manager.notify(id, notification);
    }
}
