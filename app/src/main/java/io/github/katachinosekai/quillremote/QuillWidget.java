package io.github.katachinosekai.quillremote;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Home-screen widget: Quill Desktop sessions that need you first, then running and recent ones (see Home.widget). */
public class QuillWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager wm, int[] ids) {
        wm.updateAppWidget(ids, Home.widget(c));
        PendingResult pr = goAsync();
        Home.refresh(c, pr::finish);
    }
}
