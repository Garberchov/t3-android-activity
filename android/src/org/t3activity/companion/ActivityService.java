package org.t3activity.companion;

import android.app.*;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
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
    private ExecutorService worker, wakeWorker;
    private boolean wakeInFlight;
    private final Object screenGate = new Object();
    private boolean interactive, requestInFlight;
    private int screenEpoch;
    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            boolean needsParallelFetch;
            synchronized (screenGate) {
                interactive = on;
                if (on) screenEpoch++;
                needsParallelFetch = on && requestInFlight;
                screenGate.notifyAll();
            }
            if (on) {
                android.util.Log.d("T3Activity", "screen wake");
                for (Runnable listener : ActivityFeed.listeners) listener.run();
                if (needsParallelFetch) refreshAfterWake();
            }
        }
    };
    private String last, lastNotificationMode;
    private volatile boolean stopped;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { deleteFile("monitoring.enabled"); stopSelf(); return START_NOT_STICKY; }
        if (worker != null) {
            synchronized (screenGate) { screenEpoch++; screenGate.notifyAll(); }
            return START_STICKY;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Agent activity", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel glass = new NotificationChannel(GLASS_CHANNEL, "T3 Glass monitor", NotificationManager.IMPORTANCE_LOW);
        glass.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(glass);
        ActivityFeed.monitoring = true;
        lastNotificationMode = ActivityCard.mode(this);
        startForeground(ID, card("Connecting", "Waiting for your Mac", new JSONArray(), 0, 0, lastNotificationMode), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        synchronized (screenGate) { interactive = getSystemService(PowerManager.class).isInteractive(); }
        worker = Executors.newSingleThreadExecutor();
        wakeWorker = Executors.newSingleThreadExecutor();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenState, filter, Context.RECEIVER_NOT_EXPORTED);
        worker.execute(this::refreshLoop);
        return START_STICKY;
    }
    private boolean glassMode() { return ActivityCard.WALLPAPER.equals(ActivityCard.mode(this)); }

    // No traffic while the glass wallpaper is invisible. Native notification
    // modes retain low-rate background updates; waking always bypasses delays.
    private int awaitRequest(int previousEpoch, long delayMs) throws InterruptedException {
        synchronized (screenGate) {
            long deadline = SystemClock.elapsedRealtime() + delayMs;
            while (!stopped) {
                // Reconcile delayed/missed broadcasts with the actual display state.
                boolean awake = getSystemService(PowerManager.class).isInteractive();
                if (awake != interactive) {
                    interactive = awake;
                    if (awake) screenEpoch++;
                }
                if (!interactive && glassMode()) {
                    screenGate.wait(4000);
                    continue;
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining > 0 && previousEpoch == screenEpoch) {
                    screenGate.wait(remaining);
                    continue;
                }
                requestInFlight = true;
                return screenEpoch;
            }
            throw new InterruptedException();
        }
    }

    private void refreshLoop() {
        ActivityFeed.Client client = null;
        String revision = null;
        int retryMs = 1000, previousEpoch = -1;
        long delayMs = 0;
        boolean freshen = false;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                int epoch = awaitRequest(previousEpoch, delayMs);
                boolean fresh = epoch != previousEpoch;
                previousEpoch = epoch;
                // Don't reuse a socket pool that may have gone stale while asleep.
                if (client == null || fresh) client = new ActivityFeed.Client(this);
                long requestStarted = SystemClock.elapsedRealtime();
                ActivityFeed.Response response = client.read(fresh ? null : revision, fresh || freshen);
                freshen = response.ageMs > 2500;
                android.util.Log.d("T3Activity", (java.util.Objects.equals(revision, response.revision) ? "heartbeat " : "update ")
                        + (SystemClock.elapsedRealtime() - requestStarted) + "ms, age " + response.ageMs + "ms");
                revision = response.revision;
                retryMs = 1000;
                main.post(() -> deliver(response));
                delayMs = revision == null ? 3000 : 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                android.util.Log.w("T3Activity", "Reconnecting: " + e.getClass().getSimpleName());
                main.post(() -> deliver(null));
                client = null; revision = null; freshen = true;
                delayMs = retryMs;
                retryMs = Math.min(8000, retryMs * 2);
            } finally {
                synchronized (screenGate) {
                    requestInFlight = false;
                    if (!interactive && !glassMode()) delayMs = Math.max(delayMs, 30_000);
                }
            }
        }
    }
    // A sleeping phone can retain a suspended socket. Fetch immediately on wake
    // instead of waiting for that older request to time out.
    private void refreshAfterWake() {
        if (stopped || wakeInFlight || !ActivityFeed.needsRefresh()) return;
        wakeInFlight = true;
        wakeWorker.execute(() -> {
            ActivityFeed.Response response = null;
            try { response = new ActivityFeed.Client(this).read(null, true); }
            catch (Exception ignored) { }
            ActivityFeed.Response result = response;
            main.post(() -> {
                wakeInFlight = false;
                if (!stopped && result != null && ActivityFeed.needsRefresh()) {
                    android.util.Log.d("T3Activity", "Recovered on screen wake");
                    deliver(result);
                }
            });
        });
    }
    private void deliver(ActivityFeed.Response response) {
        if (stopped) return;
        if (response == null) ActivityFeed.publish(null);
        else if (response.data == null) ActivityFeed.heartbeat(response.ageMs);
        else ActivityFeed.publish(response.data, response.ageMs);
        JSONObject current = ActivityFeed.current();
        int count = current == null ? 0 : current.optInt("count");
        JSONArray rows = current == null ? new JSONArray() : current.optJSONArray("rows");
        if (rows == null) rows = new JSONArray();
        int total = current == null ? 0 : current.optInt("total", Math.max(count, rows.length()));
        String title = ActivityFeed.status(count, rows);
        String empty = current == null ? "Waiting for a connection to your Mac" : "No active agents";
        publish(title, empty, rows, count, total);
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
        String mode = ActivityCard.mode(this);
        String key = mode + "\n" + title + "\n" + empty + rows + total;
        if (key.equals(last)) return;
        last = key;
        ActivityWidget.update(this, title, empty, rows, total);
        // The glass monitor is a static, hidden notification. Reposting it on
        // every activity change needlessly wakes NotificationManager.
        if (ActivityCard.WALLPAPER.equals(mode) && mode.equals(lastNotificationMode)) return;
        lastNotificationMode = mode;
        getSystemService(NotificationManager.class).notify(ID, card(title, empty, rows, count, total, mode));
    }
    private Notification card(String title, String empty, JSONArray rows, int count, int total, String mode) {
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ActivityService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        if (ActivityCard.WALLPAPER.equals(mode)) {
            PendingIntent manage = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            return new Notification.Builder(this, GLASS_CHANNEL)
                .setSmallIcon(R.drawable.t3_notification_mark).setContentTitle("T3 Glass")
                .setContentText("Lock-screen activity is updating").setContentIntent(manage)
                .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET).build();
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
        ActivityFeed.clear();
        if (worker != null) {
            unregisterReceiver(screenState);
            synchronized (screenGate) { screenGate.notifyAll(); }
            worker.shutdownNow();
            wakeWorker.shutdownNow();
        }
        ActivityWidget.update(this, "Agent activity", "Updates stopped", new JSONArray(), 0);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
