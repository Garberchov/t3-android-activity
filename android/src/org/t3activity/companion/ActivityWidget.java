package org.t3activity.companion;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONArray;

public class ActivityWidget extends AppWidgetProvider {
    static void update(Context context, String title, String body, JSONArray rows, int count) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ActivityWidget.class));
        if (ids.length != 0) manager.updateAppWidget(ids, ActivityCard.table(context, title, body, rows, count, 5, true));
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, "Agent activity", "Open T3 Activity to begin", new JSONArray(), 0);
    }
}
