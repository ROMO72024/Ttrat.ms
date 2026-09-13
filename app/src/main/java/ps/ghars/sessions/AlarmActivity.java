package ps.ghars.sessions;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Lock-screen-safe controls never reveal student names or session notes. */
public final class AlarmActivity extends Activity {
    private final BroadcastReceiver closed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finish(); }
    };
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RingingService.isRinging) { finish(); return; }
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER); layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        layout.setBackgroundColor(Color.rgb(252, 249, 245)); layout.setPadding(dp(28), dp(36), dp(28), dp(36));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        MainActivity.applyInsets(this, scroll, 0, 0);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.logo); logo.setContentDescription("غرس");
        layout.addView(logo, new LinearLayout.LayoutParams(dp(112), dp(112)));
        text(layout, "مدرسة وروضة غرس الحديثة", 16, 0xFF526344, 26);
        text(layout, ZonedDateTime.now(SchemaValidator.GAZA).format(DateTimeFormatter.ofPattern("HH:mm")), 56, 0xFF981765, 22);
        text(layout, "حان وقت التذكير", 27, 0xFF2D302C, 16);
        text(layout, "افتحي غرس لمراجعة الجلسات والتذكيرات", 16, 0xFF667060, 12);
        text(layout, "توقيت غزة • يتوقف الرنين تلقائياً بعد ٥ دقائق", 13, 0xFF667060, 12);
        button(layout, "إيقاف المنبّه", 0xFF981765, Color.WHITE, () -> action(RingingService.ACTION_DISMISS));
        button(layout, "تأجيل ٥ دقائق", 0xFFEAF0DC, 0xFF486A32, () -> action(RingingService.ACTION_SNOOZE));
        button(layout, "إيقاف وفتح غرس", Color.WHITE, 0xFF981765, () -> {
            action(RingingService.ACTION_DISMISS);
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        });
        scroll.addView(layout, new ScrollView.LayoutParams(-1, -1));
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closed, new IntentFilter(RingingService.ACTION_CLOSED), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(closed, new IntentFilter(RingingService.ACTION_CLOSED));
    }
    private void action(String action) { startService(new Intent(this, RingingService.class).setAction(action)); finish(); }
    private void text(LinearLayout root, String value, int size, int color, int margin) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(margin); root.addView(view, params);
    }
    private void button(LinearLayout root, String value, int color, int text, Runnable action) {
        Button button = new Button(this); button.setText(value); button.setTextSize(17); button.setAllCaps(false); button.setTextColor(text);
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(18)); button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58)); params.topMargin = dp(16); root.addView(button, params);
        button.setOnClickListener(v -> action.run());
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() { moveTaskToBack(true); }
    @Override public void onDestroy() { try { unregisterReceiver(closed); } catch (IllegalArgumentException ignored) { } super.onDestroy(); }
}
