package org.t3activity.companion;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

final class ActivityCard {
    static final String MODE = "presentation";
    static final String CUSTOM = "custom";
    static final String NATIVE = "native";
    static final String WALLPAPER = "wallpaper";
    static String mode(Context context) {
        return context.getSharedPreferences(MODE, 0).getString("mode", NATIVE);
    }
    static String summary(int count, JSONArray rows) {
        if (count > 0) return count + (count == 1 ? " active agent" : " active agents");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && "Failed".equals(row.optString("status"))) return "Agent work failed";
        }
        return rows.length() == 0 ? "All caught up" : "Agent work completed";
    }
    static int statusColor(String status, boolean dark) {
        switch (status) {
            case "Done": return Color.parseColor(dark ? "#6EE7B7" : "#059669");
            case "Failed": return Color.parseColor(dark ? "#FCA5A5" : "#DC2626");
            case "Input": return Color.parseColor(dark ? "#A5B4FC" : "#4F46E5");
            case "Waiting":
            case "Approval": return Color.parseColor(dark ? "#FCD34D" : "#D97706");
            default: return Color.parseColor(dark ? "#7DD3FC" : "#0284C7");
        }
    }
    static Intent t3LaunchIntent(Context context) {
        Intent stable = context.getPackageManager().getLaunchIntentForPackage("com.t3tools.t3code");
        return stable != null ? stable : context.getPackageManager().getLaunchIntentForPackage("com.t3tools.t3code.preview");
    }
    static PendingIntent openT3(Context context) {
        Intent open = t3LaunchIntent(context);
        if (open == null) open = new Intent(context, MainActivity.class);
        return PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static RemoteViews table(Context context, String summary, String empty, JSONArray rows, int count, int limit, boolean widget) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.activity_table);
        views.setTextViewText(R.id.summary, summary);
        views.setTextViewText(R.id.overflow, count > limit ? "+" + (count - limit) : "");
        views.removeAllViews(R.id.rows);
        int shown = Math.min(limit, rows.length());
        for (int i = 0; i < shown; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            RemoteViews item = new RemoteViews(context.getPackageName(), R.layout.activity_row);
            item.setTextViewText(R.id.thread_title, row.optString("title").replaceAll("[\\r\\n\\t]+", " "));
            item.setTextViewText(R.id.project_title, row.optString("project").replace('_', ' '));
            String status = row.optString("status");
            item.setTextViewText(R.id.thread_status, status);
            boolean dark = widget || (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            item.setTextColor(R.id.thread_status, statusColor(status, dark));
            if (widget) {
                item.setTextColor(R.id.thread_title, Color.WHITE);
                item.setTextColor(R.id.project_title, Color.parseColor("#B0BAC0"));
            }
            views.addView(R.id.rows, item);
        }
        views.setViewVisibility(R.id.empty, shown == 0 ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.empty, empty);
        if (widget) {
            views.setInt(R.id.card, "setBackgroundResource", R.drawable.widget_background);
            views.setViewPadding(R.id.card, dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
            views.setTextColor(R.id.summary, Color.WHITE);
            views.setTextColor(R.id.mark, Color.WHITE);
            views.setTextColor(R.id.overflow, Color.parseColor("#B0BAC0"));
            views.setTextColor(R.id.empty, Color.parseColor("#B0BAC0"));
            views.setOnClickPendingIntent(R.id.card, openT3(context));
        }
        return views;
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
