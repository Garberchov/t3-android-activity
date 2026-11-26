package org.t3activity.companion;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import android.text.TextPaint;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ActivityWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new GlassEngine(); }

    private final class GlassEngine extends Engine {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private volatile boolean visible;
        private boolean surfaceReady;
        private boolean destroyed;
        private final Runnable feedChanged = this::draw;
        private Bitmap background;
        private Bitmap blur;
        private long imageModified = -1;
        private int surfaceWidth, surfaceHeight;
        private String lastDraw;

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setOffsetNotificationsEnabled(false);
            ActivityFeed.listeners.add(feedChanged);
            worker.scheduleWithFixedDelay(this::fetch, 0, 4, TimeUnit.SECONDS);
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            surfaceWidth = width; surfaceHeight = height; surfaceReady = true; lastDraw = null; draw(true);
        }
        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) { lastDraw = null; draw(true); }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; super.onSurfaceDestroyed(holder); }
        @Override public void onVisibilityChanged(boolean shown) {
            visible = shown;
            if (shown) { lastDraw = null; draw(true); worker.execute(this::fetch); }
        }
        @Override public void onWallpaperFlagsChanged(int flags) { lastDraw = null; draw(true); }
        private boolean lockSurface() {
            if (isPreview()) return true;
            int flags = getWallpaperFlags();
            if ((flags & WallpaperManager.FLAG_LOCK) == 0) return false;
            if ((flags & WallpaperManager.FLAG_SYSTEM) == 0) return true;
            return getSystemService(KeyguardManager.class).isKeyguardLocked();
        }
        private void fetch() {
            if (!getSystemService(android.os.PowerManager.class).isInteractive()
                    || (!visible && !getSystemService(KeyguardManager.class).isKeyguardLocked())
                    || !ActivityFeed.wallpaperBehind() || !enabled()) return;
            ActivityFeed.Response data = null;
            try { data = new ActivityFeed.Client(ActivityWallpaperService.this).read(null, true); } catch (Exception ignored) { }
            ActivityFeed.Response result = data;
            main.post(() -> {
                if (!destroyed && getSystemService(android.os.PowerManager.class).isInteractive()
                        && (visible || getSystemService(KeyguardManager.class).isKeyguardLocked())
                        && ActivityFeed.wallpaperBehind() && enabled()) {
                    if (result != null || !ActivityFeed.monitoring) {
                        ActivityFeed.publish(result == null ? null : result.data, result == null ? 0 : result.ageMs);
                    }
                }
            });
        }
        private boolean enabled() {
            return getFileStreamPath("monitoring.enabled").exists()
                && ActivityCard.WALLPAPER.equals(ActivityCard.mode(ActivityWallpaperService.this));
        }
        private void reloadImage() {
            long modified = getFileStreamPath("wallpaper.png").lastModified();
            if (modified == imageModified) return;
            imageModified = modified;
            if (background != null) background.recycle();
            if (blur != null) blur.recycle();
            background = null; blur = null;
            try (InputStream input = openFileInput("wallpaper.png")) { background = BitmapFactory.decodeStream(input); }
            catch (Exception ignored) { }
            lastDraw = null;
        }
        private void draw() { draw(false); }
        private void draw(boolean lifecycle) {
            if (!surfaceReady || destroyed || surfaceWidth <= 0 || surfaceHeight <= 0) return;
            if (!lifecycle && !isPreview() && !getSystemService(android.os.PowerManager.class).isInteractive()) return;
            reloadImage();
            boolean lock = lockSurface() && enabled();
            JSONObject data = ActivityFeed.current();
            int count = data == null ? 0 : data.optInt("count");
            JSONArray rows = data == null ? new JSONArray() : data.optJSONArray("rows");
            if (rows == null) rows = new JSONArray();
            int total = data == null ? 0 : data.optInt("total", Math.max(count, rows.length()));
            String state = ActivityFeed.status(count, rows);
            String key = lock + "|" + state + "|" + rows + "|" + total + "|" + surfaceWidth + "x" + surfaceHeight;
            if (key.equals(lastDraw)) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas == null) return;
                float w = canvas.getWidth(), h = canvas.getHeight();
                float scale = w / 1080f;
                RectF picture = imageRect(w, h);
                paint.setAlpha(255);
                canvas.drawColor(0xff111416);
                if (background != null) canvas.drawBitmap(background, null, picture, paint);
                if (lock) {
                    int shown = Math.min(5, rows.length());
                    float left = 32 * scale, right = w - left;
                    // Keep the card clear of the lower-screen fingerprint and shortcut controls.
                    float cardHeight = (shown == 0 ? 146 : 96 + shown * 61) * scale;
                    float top = Math.min(h * .462f, h * .66f - cardHeight);
                    float bottom = top + cardHeight;
                    RectF card = new RectF(left, top, right, bottom);
                    drawCard(canvas, picture, card, state, rows, shown, total, scale);
                }
                lastDraw = key;
            } catch (RuntimeException ignored) {
                lastDraw = null;
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas); }
                    catch (RuntimeException ignored) { lastDraw = null; }
                }
            }
        }
        private RectF imageRect(float width, float height) {
            if (background == null) return new RectF(0, 0, width, height);
            float scale = Math.max(width / background.getWidth(), height / background.getHeight());
            float bw = background.getWidth() * scale, bh = background.getHeight() * scale;
            return new RectF((width - bw) / 2, (height - bh) / 2, (width + bw) / 2, (height + bh) / 2);
        }
        private void drawCard(Canvas canvas, RectF picture, RectF card, String state, JSONArray rows, int shown, int count, float scale) {
            float radius = 28 * scale;
            Path clip = new Path(); clip.addRoundRect(card, radius, radius, Path.Direction.CW);
            int save = canvas.save(); canvas.clipPath(clip);
            // A downsampled copy of the artwork provides a real, softly diffused backdrop.
            if (background != null) {
                if (blur == null) blur = Bitmap.createScaledBitmap(background, Math.max(1, background.getWidth() / 28), Math.max(1, background.getHeight() / 28), true);
                paint.setAlpha(255);
                canvas.drawBitmap(blur, null, picture, paint);
            }
            paint.setColor(0xd0182023); canvas.drawRect(card, paint);
            paint.setShader(new LinearGradient(card.left, card.top, card.left, card.bottom, new int[]{0x1affffff, 0x00ffffff, 0x16000000}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(card, paint); paint.setShader(null);
            drawDither(canvas, card, scale);
            canvas.restoreToCount(save);
            paint.setColor(0x70b9c7ce); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.6f * scale);
            canvas.drawRoundRect(card, radius, radius, paint); paint.setStyle(Paint.Style.FILL);
            float l = card.left + 30 * scale, r = card.right - 30 * scale;
            float baseline = card.top + 57 * scale;
            mono(37 * scale, 0xfff6f7f8, true);
            canvas.drawText("T3", l, baseline, text);
            float markX = l + text.measureText("T3 ");
            mono(29 * scale, 0xff91a4ad, false);
            canvas.drawText("//", markX, baseline, text);
            center(canvas, state, card.centerX(), baseline, 36 * scale, 0xfff6f7f8, true);
            if (count > shown && shown > 0) right(canvas, "+" + (count - shown), r, baseline, 29 * scale, 0xffaab2b7, false);
            paint.setColor(0x34bfced6);
            canvas.drawRect(l, card.top + 77 * scale, r, card.top + 78.5f * scale, paint);
            if (shown == 0) {
                center(canvas, "All caught up".equals(state) ? "No active agents" : "Waiting for your Mac", card.centerX(), card.top + 119 * scale, 32 * scale, 0xffb5bec3, false);
                return;
            }
            float projectX = r - 264 * scale;
            float maxTitleWidth = projectX - l - 17 * scale;
            for (int i = 0; i < shown; i++) {
                JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                float y = card.top + (124 + 61 * i) * scale;
                String title = row.optString("title").replaceAll("[\\r\\n\\t]+", " ");
                font(37 * scale, 0xfff5f6f7, true);
                canvas.drawText(TextUtils.ellipsize(title, text, maxTitleWidth, TextUtils.TruncateAt.END).toString(), l, y, text);
                font(26 * scale, 0xffa7afb6, false);
                String project = row.optString("project").replace('_', ' ');
                canvas.drawText(TextUtils.ellipsize(project, text, 97 * scale, TextUtils.TruncateAt.END).toString(), projectX, y, text);
                String status = row.optString("status");
                mono(32 * scale, ActivityCard.statusColor(status, true), true);
                canvas.drawText(status, r - text.measureText(status), y, text);
            }
        }
        private void drawDither(Canvas canvas, RectF card, float scale) {
            // Ordered 4×4 Bayer stipple, concentrated along the glass edges.
            int[] bayer = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
            float step = 6 * scale, dot = 2 * scale;
            paint.setColor(0x34bfced6);
            paint.setAntiAlias(false);
            for (int y = 0; y * step < card.height(); y++) {
                float edge = Math.min(y * step, card.height() - y * step);
                int threshold = Math.max(0, 8 - (int)(edge / (7 * scale)));
                if (threshold == 0) continue;
                for (int x = 0; x * step < card.width(); x++) {
                    if (bayer[(y & 3) * 4 + (x & 3)] >= threshold) continue;
                    float px = card.left + x * step, py = card.top + y * step;
                    canvas.drawRect(px, py, px + dot, py + dot, paint);
                }
            }
            paint.setAntiAlias(true);
        }
        private void mono(float size, int color, boolean bold) {
            text.setColor(color); text.setTextSize(size);
            text.setTypeface(Typeface.create("monospace", bold ? Typeface.BOLD : Typeface.NORMAL));
        }
        private void font(float size, int color, boolean medium) {
            text.setColor(color); text.setTextSize(size); text.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
        private void center(Canvas canvas, String value, float x, float y, float size, int color, boolean medium) {
            font(size, color, medium); canvas.drawText(value, x - text.measureText(value) / 2, y, text);
        }
        private void right(Canvas canvas, String value, float x, float y, float size, int color, boolean medium) {
            font(size, color, medium); canvas.drawText(value, x - text.measureText(value), y, text);
        }
        @Override public void onDestroy() {
            destroyed = true; visible = false; ActivityFeed.listeners.remove(feedChanged); worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy();
        }
    }
}
