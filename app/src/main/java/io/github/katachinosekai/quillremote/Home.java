package io.github.katachinosekai.quillremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Home-screen features fed by Quill Desktop's session list (remote mode): launcher shortcuts (recent sessions + New
 * session), pinned session shortcuts, and the "waiting for you" widget. The list is cached in prefs ("home.sessions")
 * so the widget and shortcuts work while the app is closed.
 */
final class Home {
    static final String PREF = "home.sessions";
    static final String EXTRA_NEW = "quill.newSession";
    static final int RECENT = 3;
    static final int ROWS = 5;
    static final int[] ROW_IDS = {R.id.w_row0, R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4};
    static final Handler ui = new Handler(Looper.getMainLooper());
    static String last = "";

    private Home() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("quill", Context.MODE_PRIVATE);
    }

    /** Compact copy of Quill Desktop's session list: [{id, title, folder, status, updated}] in Quill Desktop's order. */
    static String compact(List<JSONObject> sessions) {
        JSONArray out = new JSONArray();
        try {
            for (JSONObject s : sessions) {
                out.put(new JSONObject()
                        .put("id", s.optString("id"))
                        .put("title", s.optString("title"))
                        .put("folder", s.optString("project", s.optString("group", "")))
                        .put("status", s.optString("status"))
                        .put("updated", s.optLong("updatedMs")));
            }
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    /** Called with every session list from Quill Desktop; only does work when something the home screen shows changed. */
    static void sessions(Context c, List<JSONObject> sessions) {
        String json = compact(sessions);
        synchronized (Home.class) {
            if (json.equals(last)) {
                return;
            }
            last = json;
        }
        Context app = c.getApplicationContext();
        prefs(app).edit().putString(PREF, json).putLong(PREF + ".at", System.currentTimeMillis()).apply();
        ui.post(() -> {
            shortcuts(app, json);
            widgets(app);
        });
    }

    static List<JSONObject> cached(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(PREF, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && SessionBars.REMOTE_ID.matcher(o.optString("id")).matches()) {
                    out.add(o);
                }
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    static Intent open(Context c, String id) {
        return new Intent(Intent.ACTION_VIEW, null, c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Notifier.EXTRA_OPEN_QUILL, id);
    }

    static String shortcutId(String id) {
        return "s:" + id;
    }

    // ---- Launcher shortcuts ----

    static void shortcuts(Context c, String json) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null) {
            return;
        }
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                list.add(a.getJSONObject(i));
            }
        } catch (JSONException e) {
            return;
        }
        List<JSONObject> recent = new ArrayList<>(list);
        recent.sort((x, y) -> Long.compare(y.optLong("updated"), x.optLong("updated")));
        List<ShortcutInfo> dyn = new ArrayList<>();
        Icon icon = Icon.createWithResource(c, R.drawable.icon);
        dyn.add(new ShortcutInfo.Builder(c, "new")
                .setShortLabel("New session")
                .setLongLabel("New session")
                .setIcon(icon)
                .setRank(0)
                .setIntent(new Intent(Intent.ACTION_VIEW, null, c, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_NEW, true))
                .build());
        int max = Math.min(RECENT, Math.max(0, sm.getMaxShortcutCountPerActivity() - 1));
        for (int i = 0; i < recent.size() && dyn.size() <= max; i++) {
            JSONObject s = recent.get(i);
            String title = label(s.optString("title"));
            dyn.add(session(c, s.optString("id"), title, icon).setRank(dyn.size()).build());
        }
        Set<String> live = new HashSet<>();
        for (JSONObject s : list) {
            live.add(shortcutId(s.optString("id")));
        }
        try {
            sm.setDynamicShortcuts(dyn);
            // Pinned session shortcuts follow renames, and go grey when the session is gone from Quill Desktop.
            List<ShortcutInfo> upd = new ArrayList<>();
            List<String> gone = new ArrayList<>();
            List<String> back = new ArrayList<>();
            for (ShortcutInfo p : sm.getPinnedShortcuts()) {
                String sid = p.getId();
                if (!sid.startsWith("s:")) {
                    continue;
                }
                if (!live.contains(sid)) {
                    if (p.isEnabled()) {
                        gone.add(sid);
                    }
                    continue;
                }
                if (!p.isEnabled()) {
                    back.add(sid);
                }
                for (JSONObject s : list) {
                    if (shortcutId(s.optString("id")).equals(sid)) {
                        upd.add(session(c, s.optString("id"), label(s.optString("title")), icon).build());
                    }
                }
            }
            if (!back.isEmpty()) {
                sm.enableShortcuts(back);
            }
            if (!gone.isEmpty()) {
                sm.disableShortcuts(gone, "This session is no longer in Quill Desktop");
            }
            if (!upd.isEmpty()) {
                sm.updateShortcuts(upd);
            }
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // Rate-limited (background) or a launcher quirk: the next list change tries again.
        }
    }

    static String label(String title) {
        String t = title == null || title.trim().isEmpty() ? "Session" : title.trim();
        return t.length() > 24 ? t.substring(0, 23) + "…" : t;
    }

    static ShortcutInfo.Builder session(Context c, String id, String title, Icon icon) {
        return new ShortcutInfo.Builder(c, shortcutId(id))
                .setShortLabel(title)
                .setLongLabel(title)
                .setIcon(icon)
                .setIntent(open(c, id));
    }

    static boolean canPin(Context c) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        return sm != null && sm.isRequestPinShortcutSupported();
    }

    /** Long-press menu → "Add to home screen": the launcher asks the user to place it. */
    static boolean pin(Context c, String id, String title) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported() || !SessionBars.REMOTE_ID.matcher(id).matches()) {
            return false;
        }
        try {
            return sm.requestPinShortcut(session(c, id, label(title), Icon.createWithResource(c, R.drawable.icon)).build(), null);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return false;
        }
    }

    // ---- Widget ----

    static boolean hasWidgets(Context c) {
        AppWidgetManager wm = AppWidgetManager.getInstance(c);
        return wm != null && wm.getAppWidgetIds(new ComponentName(c, QuillWidget.class)).length > 0;
    }

    static void widgets(Context c) {
        AppWidgetManager wm = AppWidgetManager.getInstance(c);
        if (wm == null) {
            return;
        }
        int[] ids = wm.getAppWidgetIds(new ComponentName(c, QuillWidget.class));
        if (ids.length > 0) {
            wm.updateAppWidget(ids, widget(c));
        }
    }

    static int rank(String status) {
        switch (status) {
            case "waiting":
                return 0;
            case "error":
                return 1;
            case "running":
                return 2;
            default:
                return 3;
        }
    }

    static int color(String status) {
        switch (status) {
            case "waiting":
                return MainActivity.ACCENT;
            case "error":
                return 0xFFE5534B;
            case "running":
                return 0xFF57AB5A;
            default:
                return MainActivity.MUTED;
        }
    }

    /** Sessions that need you first (waiting, error), then running ones, then the most recent; at most ROWS. */
    static List<JSONObject> widgetRows(List<JSONObject> all) {
        List<JSONObject> rows = new ArrayList<>(all);
        rows.sort((x, y) -> {
            int r = Integer.compare(rank(x.optString("status")), rank(y.optString("status")));
            return r != 0 ? r : Long.compare(y.optLong("updated"), x.optLong("updated"));
        });
        return rows.size() > ROWS ? rows.subList(0, ROWS) : rows;
    }

    static RemoteViews widget(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        List<JSONObject> all = cached(c);
        int waiting = 0;
        for (JSONObject s : all) {
            if ("waiting".equals(s.optString("status"))) {
                waiting++;
            }
        }
        v.setTextViewText(R.id.w_head, waiting == 0 ? "Quill" : "Quill · " + waiting + " waiting for you");
        Intent app = new Intent(c, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        v.setOnClickPendingIntent(R.id.w_head, PendingIntent.getActivity(c, 0, app, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        List<JSONObject> rows = widgetRows(all);
        long now = System.currentTimeMillis();
        for (int i = 0; i < ROWS; i++) {
            if (i >= rows.size()) {
                v.setViewVisibility(ROW_IDS[i], View.GONE);
                continue;
            }
            JSONObject s = rows.get(i);
            v.setViewVisibility(ROW_IDS[i], View.VISIBLE);
            v.setTextViewText(ROW_IDS[i], row(s, now));
            v.setOnClickPendingIntent(ROW_IDS[i], PendingIntent.getActivity(c, 100 + i, open(c, s.optString("id")),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        boolean paired = new PhoneApi(prefs(c)).available();
        v.setViewVisibility(R.id.w_empty, rows.isEmpty() ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_empty, paired ? "No sessions yet. Open Quill Android to refresh." : "Pair Quill Android with Quill Desktop to see your sessions here.");
        return v;
    }

    static CharSequence row(JSONObject s, long now) {
        String st = s.optString("status");
        SpannableStringBuilder b = new SpannableStringBuilder();
        b.append("● ");
        b.setSpan(new ForegroundColorSpan(color(st)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String t = s.optString("title").trim();
        b.append(t.isEmpty() ? "Session" : t);
        String what = "waiting".equals(st) ? "needs you" : "error".equals(st) ? "error" : "running".equals(st) ? "running" : "";
        String folder = s.optString("folder");
        long up = s.optLong("updated");
        StringBuilder meta = new StringBuilder();
        for (String x : new String[]{what, folder, up > 0 ? FolderSync.ago(now - up) : ""}) {
            if (!x.isEmpty()) {
                meta.append(meta.length() == 0 ? "" : " · ").append(x);
            }
        }
        if (meta.length() > 0) {
            int at = b.length();
            b.append("   ").append(meta);
            b.setSpan(new ForegroundColorSpan(MainActivity.MUTED), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new RelativeSizeSpan(0.8f), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    /** Widget refresh while the app is closed: one /phone/sessions call (fails quietly when away or locked). */
    static void refresh(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                PhoneApi api = new PhoneApi(prefs(app));
                if (api.available()) {
                    PhoneApi.Reply r = api.sessions();
                    JSONArray arr = r.ok() ? r.body.optJSONArray("sessions") : null;
                    if (arr != null) {
                        List<JSONObject> list = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o != null) {
                                list.add(o);
                            }
                        }
                        sessions(app, list);
                    }
                }
            } finally {
                ui.post(() -> {
                    widgets(app);
                    done.run();
                });
            }
        }, "quill-widget").start();
    }
}
