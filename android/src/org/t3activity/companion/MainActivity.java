package org.t3activity.companion;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.Bundle;
import android.util.AtomicFile;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int PAIR = 10, PHOTO = 11;
    private LinearLayout layout;
    private TextView status;
    private boolean openPreview;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getInsetsController().setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        ScrollView scroll = new ScrollView(this);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(40), dp(24), dp(32));
        scroll.addView(layout);
        TextView title = new TextView(this);
        title.setText("T3 Activity"); title.setTextSize(30);
        layout.addView(title);
        TextView detail = new TextView(this);
        detail.setText("Unofficial companion for T3 Code. Pair your Mac, then choose how agent activity appears on your phone.");
        detail.setTextSize(16); detail.setPadding(0, dp(12), 0, dp(12));
        layout.addView(detail);
        status = new TextView(this); status.setTextSize(15); layout.addView(status);
        showPairing();
        button("Import Mac pairing file", () -> choose(PAIR, "*/*"));
        button("Test connection", this::testConnection);
        button("Choose wallpaper image (optional)", () -> choose(PHOTO, "image/*"));
        button("Set glass lock screen", () -> setMode(ActivityCard.WALLPAPER, true));
        button("Native live card", () -> setMode(ActivityCard.NATIVE, false));
        button("Styled notification card", () -> setMode(ActivityCard.CUSTOM, false));
        button("Stop updates", () -> {
            deleteFile("monitoring.enabled");
            stopService(new Intent(this, ActivityService.class));
            ActivityFeed.clear();
            status.setText("Updates stopped.");
        });
        button("Open T3 Code", () -> {
            Intent intent = ActivityCard.t3LaunchIntent(this);
            if (intent != null) startActivity(intent);
            else status.setText("T3 Code is not installed.");
        });
        TextView note = new TextView(this);
        note.setText("Glass mode draws chat titles directly on your lock screen: anyone who can see the screen can read them. Choose Lock screen only in Android's wallpaper picker. Your existing picture is not automatically copied; select an image above or use the dark default.");
        note.setTextSize(14); note.setPadding(0, dp(20), 0, 0); layout.addView(note);
        setContentView(scroll);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run()); layout.addView(button);
    }
    private void showPairing() {
        status.setText(ActivityFeed.paired(this) ? "Pairing file imported. Ready to connect." : "Not paired. Run the Mac setup and import its pairing file.");
    }
    private void choose(int request, String type) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType(type);
        startActivityForResult(intent, request);
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        if (request == PAIR) {
            try (InputStream stream = getContentResolver().openInputStream(intent.getData())) {
                ActivityFeed.pair(this, stream);
                deleteFile("monitoring.enabled");
                stopService(new Intent(this, ActivityService.class));
                ActivityFeed.clear();
                showPairing();
            } catch (Exception e) { status.setText("Could not import pairing. Select the pairing.json from Mac setup."); }
        } else if (request == PHOTO) {
            try {
                Bitmap image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), intent.getData()), (decoder, info, source) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    float factor = Math.min(1f, 2560f / Math.max(w, h));
                    decoder.setTargetSize(Math.max(1, Math.round(w * factor)), Math.max(1, Math.round(h * factor)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                AtomicFile file = new AtomicFile(getFileStreamPath("wallpaper.png"));
                FileOutputStream out = file.startWrite();
                try {
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException();
                    file.finishWrite(out);
                } catch (Exception e) { file.failWrite(out); throw e; }
                for (Runnable listener : ActivityFeed.listeners) listener.run();
                status.setText("Wallpaper imported. Set the glass lock screen to preview it.");
            } catch (Exception e) { status.setText("Could not import image. Try a PNG or JPEG."); }
        }
    }
    private void testConnection() {
        if (!ActivityFeed.paired(this)) { showPairing(); return; }
        status.setText("Testing connection…");
        new Thread(() -> {
            try {
                org.json.JSONObject data = ActivityFeed.read(this);
                runOnUiThread(() -> status.setText("Connected. " + data.optInt("count") + " active agents."));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Cannot connect. Check that the Mac bridge is running and both devices are on the same Wi-Fi."));
            }
        }).start();
    }
    private void setMode(String mode, boolean preview) {
        if (!ActivityFeed.paired(this)) { showPairing(); return; }
        openPreview = preview;
        getSharedPreferences(ActivityCard.MODE, MODE_PRIVATE).edit().putString("mode", mode).apply();
        startMonitoring();
    }
    private void startMonitoring() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        try { openFileOutput("monitoring.enabled", MODE_PRIVATE).close(); }
        catch (java.io.IOException e) { status.setText("Unable to start updates."); return; }
        startForegroundService(new Intent(this, ActivityService.class));
        status.setText("Updates started.");
        if (openPreview) {
            openPreview = false;
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, ActivityWallpaperService.class));
            startActivity(intent);
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startMonitoring();
    }
}
