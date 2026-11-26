package org.t3activity.companion;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import org.json.*;
import java.util.concurrent.*;

public class ActivityService extends Service {
    private static final int ID = 42;
    private static final String CHANNEL = "agent_activity";
    private static final String GLASS_CHANNEL = "glass_monitor";
    private ScheduledExecutorService worker;
    private String last;
    private boolean stopped;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { deleteFile("monitoring.enabled"); stopSelf(); return START_NOT_STICKY; }
        if (worker != null) return START_STICKY;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Agent activity", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel glass = new NotificationChannel(GLASS_CHANNEL, "T3 Glass monitor", NotificationManager.IMPORTANCE_LOW);
        glass.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(glass);
        ActivityFeed.monitoring = true;
        startForeground(ID, card("Connecting", "Waiting for your Mac", new JSONArray(), 0, 0, ActivityCard.mode(this)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(this::refresh, 0, 15, TimeUnit.SECONDS);
        return START_STICKY;
    }
    private void refresh() {
        try {
            JSONObject snapshot = ActivityFeed.read(this);
            int count = snapshot.getInt("count");
            JSONArray rows = snapshot.getJSONArray("rows");
            main.post(() -> { if (!stopped) ActivityFeed.publish(snapshot); });
            publish(ActivityCard.summary(count, rows), rows.length() == 0 ? "No active agents" : "", rows, count, snapshot.optInt("total", Math.max(count, rows.length())));
        } catch (Exception e) {
            main.post(() -> { if (!stopped) ActivityFeed.publish(null); });
            publish("Disconnected", "Waiting for a connection to your Mac", new JSONArray(), 0, 0);
        }
    }
    private CharSequence nativeBody(String empty, JSONArray rows, int count) {
        if (rows.length() == 0) return empty;
        StringBuilder lines = new StringBuilder();
        TextPaint paint = new TextPaint();
        int textSize = getResources().getIdentifier("notification_text_size", "dimen", "android");
        paint.setTextSize(textSize == 0 ? TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, getResources().getDisplayMetrics()) : getResources().getDimension(textSize) * 15f / 14f);
        int bodyFont = getResources().getIdentifier("config_bodyFontFamily", "string", "android");
        paint.setTypeface(Typeface.create(bodyFont == 0 ? "sans-serif" : getResources().getString(bodyFont), Typeface.NORMAL));
        float density = getResources().getDisplayMetrics().density;
        // Pixel's promoted notification body reserves horizontal space for the app icon and card margins.
        float right = getResources().getDisplayMetrics().widthPixels - 136 * density;
        float gap = paint.measureText("  ");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            if (lines.length() > 0) lines.append("\n");
            String status = row.optString("status");
            float statusStart = (float) Math.floor((right - paint.measureText(status)) / 20) * 20;
            String raw = row.optString("title").replaceAll("[\\r\\n\\t]+", " ");
            String title = TextUtils.ellipsize(raw, paint, Math.max(50 * density, statusStart - gap), TextUtils.TruncateAt.END).toString();
            lines.append(title);
            float remaining = statusStart - 10 - paint.measureText(title);
            for (String space : new String[]{"\u2002", "\u2009", "\u200A"}) {
                float width = paint.measureText(space);
                while (width > 0 && remaining >= width - .2f) {
                    lines.append(space);
                    remaining -= width;
                }
            }
            lines.append('\t').append(status);
        }
        if (count > rows.length()) lines.append("\n+").append(String.valueOf(count - rows.length())).append(" more");
        return lines;
    }
    private void publish(String title, String empty, JSONArray rows, int count, int total) {
        main.post(() -> {
            if (stopped) return;
            String mode = ActivityCard.mode(this);
            ActivityWidget.update(this, title, empty, rows, total);
            String key = mode + "\n" + title + "\n" + empty + rows;
            if (key.equals(last)) return;
            last = key;
            getSystemService(NotificationManager.class).notify(ID, card(title, empty, rows, count, total, mode));
        });
    }
    private Notification card(String title, String empty, JSONArray rows, int count, int total, String mode) {
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ActivityService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        if (ActivityCard.WALLPAPER.equals(mode)) {
            PendingIntent manage = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            return new Notification.Builder(this, GLASS_CHANNEL)
                .setSmallIcon(R.drawable.t3_notification_mark).setContentTitle("T3 Glass")
                .setContentText("Lock-screen activity is updating").setContentIntent(manage)
                .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET).setDeleteIntent(stop).build();
        }
        boolean custom = ActivityCard.CUSTOM.equals(mode);
        Bundle extras = new Bundle(); extras.putBoolean("android.requestPromotedOngoing", !custom && rows.length() > 0);
        CharSequence body = nativeBody(empty, rows, total);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.t3_notification_mark).setContentTitle(title).setContentText(body.toString().split("\\n", 2)[0])
            .setContentIntent(ActivityCard.openT3(this))
            .setOnlyAlertOnce(true).setOngoing(true).setColor(0xff17233b).setColorized(false).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS)
            .setExtras(extras).setDeleteIntent(stop);
        if (custom) {
            builder.setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(ActivityCard.table(this, title, empty, rows, total, 1, false))
                .setCustomBigContentView(ActivityCard.table(this, title, empty, rows, total, 5, false));
        } else {
            builder.setStyle(new Notification.BigTextStyle().bigText(body));
            if (rows.length() > 0) builder.setShortCriticalText(count == 0 ? (title.endsWith("failed") ? "Failed" : "Done") : count > 99 ? "99+ live" : count + " live");
        }
        return builder.build();
    }
    @Override public void onDestroy() {
        stopped = true;
        ActivityFeed.monitoring = false;
        ActivityFeed.publish(null);
        if (worker != null) worker.shutdownNow();
        ActivityWidget.update(this, "Agent activity", "Updates stopped", new JSONArray(), 0);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
