package io.github.katachinosekai.quillremote;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.widget.ArrayAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SessionBars {
    static final int MAX_TABS = 12;
    static final int CARD = 0xFF2F2F2C;
    static final Pattern SESSION = Pattern.compile("/code/(session_[A-Za-z0-9]+)");
    static final Pattern REMOTE_ID = Pattern.compile("[A-Za-z0-9_-]{2,80}");
    boolean remote;

    boolean validId(String id) {
        return id != null && (remote ? REMOTE_ID.matcher(id).matches() : SESSION.matcher("/code/" + id).matches());
    }

    static class Item {
        final String id, folder, title;

        Item(String id, String folder, String title) {
            this.id = id;
            this.folder = folder;
            this.title = title;
        }
    }

    final MainActivity a;
    final SharedPreferences prefs;
    final HorizontalScrollView tabScroll, bookScroll;
    final LinearLayout tabRow, bookRow;
    final List<Item> items = new ArrayList<>();
    final List<String> order = new ArrayList<>();
    final List<String[]> tabs = new ArrayList<>();
    String current;
    JSONObject usage;
    JSONObject meta = new JSONObject();
    final HorizontalScrollView usageStrip;
    final LinearLayout usageRow;

    void setUsage(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("version") != 1) {
                return;
            }
            usage = o.optJSONObject("usage");
            meta = o.optJSONObject("meta") == null ? new JSONObject() : o.optJSONObject("meta");
        } catch (JSONException e) {
            return;
        }
        render();
    }

    static String tok(long n) {
        if (n >= 1_000_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fB", n / 1e9);
        }
        if (n >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fM", n / 1e6);
        }
        if (n >= 1000) {
            return String.format(java.util.Locale.ROOT, "%.1fK", n / 1e3);
        }
        return String.valueOf(n);
    }

    static String pct(JSONObject w) {
        return w == null ? "–" : Math.round(w.optDouble("utilization") * 100) + "%";
    }

    static String resets(JSONObject w) {
        long t = w == null ? 0 : w.optLong("resetsAt");
        if (t == 0) {
            return "";
        }
        String f = t * 1000 - System.currentTimeMillis() > 20 * 3600_000L ? "EEE H:mm" : "H:mm";
        return " · resets " + new java.text.SimpleDateFormat(f, java.util.Locale.getDefault()).format(new java.util.Date(t * 1000));
    }

    static final class UsageItem {
        final String title;
        final String value;
        final double bar;

        UsageItem(String title, String value, double bar) {
            this.title = title;
            this.value = value;
            this.bar = bar;
        }
    }

    static String window(JSONObject w, boolean paused) {
        if (w == null) {
            return "no data yet";
        }
        return pct(w) + resets(w) + (paused ? " · paused" : "");
    }

    List<UsageItem> usageItems() {
        if (usage == null) {
            return null;
        }
        JSONObject five = usage.optJSONObject("fiveHour"), week = usage.optJSONObject("weekly");
        boolean paused = usage.optBoolean("paused");
        JSONObject m = current == null ? null : meta.optJSONObject(current);
        List<String> sess = new ArrayList<>();
        if (m != null && m.has("tokens")) {
            sess.add(tok(m.optLong("tokens")) + " tok");
        }
        if (m != null && m.optLong("contextMax") > 0) {
            sess.add("ctx " + Math.round(100.0 * m.optLong("context") / m.optLong("contextMax")) + "%");
        }
        String all = usage.has("today") ? "Today " + tok(usage.optLong("today")) + (usage.has("week") ? " · 7d " + tok(usage.optLong("week")) : "") : "–";
        String credits = usage.optString("credits");
        List<UsageItem> out = new ArrayList<>();
        out.add(new UsageItem("5-hour session", window(five, paused), five == null ? -1 : five.optDouble("utilization")));
        out.add(new UsageItem("This session", current == null ? "No session open" : sess.isEmpty() ? "–" : String.join(" · ", sess), -1));
        out.add(new UsageItem("All sessions", all, -1));
        out.add(new UsageItem("Usage credits", credits.isEmpty() ? "not reported" : credits, -1));
        out.add(new UsageItem("Weekly", window(week, false), week == null ? -1 : week.optDouble("utilization")));
        return out;
    }

    View usageBar(double v, int w, int h) {
        LinearLayout track = new LinearLayout(a);
        GradientDrawable t = new GradientDrawable();
        t.setColor(0x33FFFFFF);
        t.setCornerRadius(h / 2f);
        track.setBackground(t);
        float p = (float) Math.max(0, Math.min(1, v));
        View fill = new View(a);
        GradientDrawable f = new GradientDrawable();
        f.setColor(MainActivity.ACCENT);
        f.setCornerRadius(h / 2f);
        fill.setBackground(f);
        track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p));
        track.addView(new View(a), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1 - p));
        track.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return track;
    }

    TextView usageText(String s, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(MainActivity.MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    void fillStrip(List<UsageItem> list) {
        usageRow.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            UsageItem it = list.get(i);
            if (i > 0) {
                TextView sep = usageText("│", false);
                sep.setPadding(a.dp(10), 0, a.dp(10), 0);
                usageRow.addView(sep);
            }
            usageRow.addView(usageText(it.title, true));
            if (it.bar >= 0) {
                View b = usageBar(it.bar, a.dp(56), a.dp(6));
                ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(a.dp(6), 0, 0, 0);
                ((LinearLayout.LayoutParams) b.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL;
                usageRow.addView(b);
            }
            TextView v = usageText(it.value, false);
            v.setPadding(a.dp(6), 0, 0, 0);
            usageRow.addView(v);
        }
    }

    void fillSide(List<UsageItem> list) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(8), 0, a.dp(8), a.dp(6));
        box.setContentDescription("Usage");
        for (UsageItem it : list) {
            LinearLayout row = new LinearLayout(a);
            row.setPadding(0, a.dp(2), 0, 0);
            row.addView(usageText(it.title, true));
            TextView v = usageText(it.value, false);
            v.setPadding(a.dp(6), 0, 0, 0);
            row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(row);
            if (it.bar >= 0) {
                View b = usageBar(it.bar, LinearLayout.LayoutParams.MATCH_PARENT, a.dp(5));
                ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(0, a.dp(3), 0, a.dp(3));
                box.addView(b);
            }
        }
        sideList.addView(box);
    }

    static int statusColor(String s) {
        switch (s) {
            case "running":
                return 0xFF5BA8F5;
            case "waiting":
                return 0xFFF2B33D;
            case "idle":
                return 0xFF6CC06C;
            case "error":
                return 0xFFE55B5B;
            case "terminal":
                return 0xFFA07CE8;
        }
        return 0x00000000;
    }

    CharSequence subline(Item it) {
        JSONObject m = meta.optJSONObject(it.id);
        if (m == null) {
            return null;
        }
        long last = m.optLong("last");
        String txt = (last > 0 ? FolderSync.ago(System.currentTimeMillis() - last) : "") + (m.optInt("msgs") > 0 ? "  ·  " + m.optInt("msgs") + " msgs" : "");
        int col = statusColor(m.optString("status"));
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (col != 0) {
            sb.append("● ");
            sb.setSpan(new android.text.style.ForegroundColorSpan(col), 0, 1, 0);
        }
        sb.append(txt);
        String kmem = m.optString("kmem");
        if (!kmem.isEmpty()) {
            int at = sb.length() + (txt.isEmpty() ? 0 : 5);
            sb.append(txt.isEmpty() ? "" : "  ·  ").append(kmem);
            sb.setSpan(new android.text.style.ForegroundColorSpan(MainActivity.ACCENT), at, sb.length(), 0);
        }
        return sb;
    }

    View twoLine(Item it, int padLeft, int titleSp) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(a.dp(44));
        l.setPadding(padLeft, a.dp(4), a.dp(10), a.dp(4));
        TextView t = new TextView(a);
        t.setText(it.title);
        t.setTextColor(MainActivity.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        l.addView(t);
        CharSequence sub = subline(it);
        if (sub != null) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextColor(MainActivity.MUTED);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            l.addView(s);
        }
        if (it.id.equals(current)) {
            GradientDrawable o = new GradientDrawable();
            o.setCornerRadius(a.dp(8));
            o.setStroke(a.dp(2), MainActivity.TEXT);
            o.setColor(0);
            l.setBackground(o);
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return l;
    }
    static final int SIDE_W = 300;
    static final int WIDE_DP = 600;
    final LinearLayout side, sideList;
    final android.widget.ScrollView sideScroll;
    boolean vertical;
    android.animation.ValueAnimator sideAnim;

    SessionBars(MainActivity a, SharedPreferences prefs) {
        this.a = a;
        this.prefs = prefs;
        tabRow = row();
        bookRow = row();
        tabScroll = scroller(tabRow);
        usageRow = new LinearLayout(a);
        usageRow.setGravity(Gravity.CENTER);
        usageRow.setPadding(a.dp(8), 0, a.dp(8), a.dp(2));
        usageStrip = scroller(usageRow);
        usageStrip.setFillViewport(true);
        usageStrip.setContentDescription("Usage");
        usageStrip.setVisibility(View.GONE);
        bookScroll = scroller(bookRow);
        sideList = new LinearLayout(a);
        sideList.setOrientation(LinearLayout.VERTICAL);
        sideList.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(16));
        sideScroll = new android.widget.ScrollView(a);
        sideScroll.setBackgroundColor(MainActivity.SIDE);
        sideScroll.addView(sideList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        side = new LinearLayout(a);
        side.setOrientation(LinearLayout.HORIZONTAL);
        side.addView(sideScroll, new LinearLayout.LayoutParams(a.dp(SIDE_W), ViewGroup.LayoutParams.MATCH_PARENT));
        View edge = new View(a);
        edge.setBackgroundColor(0xFF3A3A37);
        side.addView(edge, new LinearLayout.LayoutParams(a.dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        remote = a.phoneApi != null && a.phoneApi.available();
        loadTabs();
    }

    boolean wide() {
        return a.getResources().getConfiguration().screenWidthDp >= WIDE_DP;
    }

    void applyMode(boolean animate) {
        boolean v = true;
        boolean changed = v != vertical;
        vertical = v;
        if (sideAnim != null) {
            sideAnim.cancel();
        }
        setSideWidth(vertical && prefs.getBoolean("sideOpen", true) ? sideFull() : 0);
        a.sideArrow.setVisibility(View.VISIBLE);
        syncArrow();
        render();
        if (changed) {
            a.inject();
        }
    }

    /** The folder list: a 300 dp sidebar on wide screens, the whole width on phones (it covers the chat until a session is picked). */
    int sideFull() {
        return wide() ? a.dp(SIDE_W) + a.dp(1) : a.getResources().getDisplayMetrics().widthPixels;
    }

    /** On phones the folder list closes once a session is picked. */
    void picked(String id) {
        a.openSession(id);
        if (!wide() && prefs.getBoolean("sideOpen", true)) {
            toggleSide();
        }
    }

    void setSideWidth(int w) {
        ViewGroup.LayoutParams lp = side.getLayoutParams();
        if (lp == null) {
            return;
        }
        lp.width = w;
        side.setLayoutParams(lp);
        side.setVisibility(w > 0 ? View.VISIBLE : View.GONE);
    }

    void toggleSide() {
        if (!vertical) {
            return;
        }
        boolean open = !prefs.getBoolean("sideOpen", true);
        prefs.edit().putBoolean("sideOpen", open).apply();
        syncArrow();
        int full = sideFull();
        int from = side.getVisibility() == View.VISIBLE ? side.getWidth() : 0;
        if (sideAnim != null) {
            sideAnim.cancel();
        }
        sideAnim = android.animation.ValueAnimator.ofInt(from, open ? full : 0);
        sideAnim.setDuration(220);
        sideAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        sideAnim.addUpdateListener(an -> setSideWidth((int) an.getAnimatedValue()));
        sideAnim.start();
    }

    void syncArrow() {
        if (a.sideArrow == null) {
            return;
        }
        if (vertical) {
            a.sideArrow.setText(prefs.getBoolean("sideOpen", true) ? "‹" : "›");
        } else {
            a.sideArrow.setText(prefs.getBoolean("barsHidden", false) ? "▾" : "▴");
        }
    }

    void switchTab(int n) {
        if (tabs.isEmpty()) {
            return;
        }
        int i = n >= 9 || n > tabs.size() ? tabs.size() - 1 : n - 1;
        a.openSession(tabs.get(i)[0]);
    }

    boolean folderOpen(String name) {
        return prefs.getBoolean("fold." + name, !name.equals("Archive") && !name.equals("Created by Claude"));
    }

    void renderSide(Map<String, List<Item>> groups) {
        sideList.removeAllViews();
        TextView head = new TextView(a);
        head.setText("Sessions");
        head.setTextColor(MainActivity.TEXT);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(8));
        sideList.addView(head);
        List<UsageItem> parts = usageItems();
        if (parts != null) {
            fillSide(parts);
        }
        if (items.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText(remote ? "No live sessions from Quill Desktop right now." : "No sessions found on this page yet. Open claude.ai's own session list once, then they show up here.");
            empty.setTextColor(MainActivity.MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(a.dp(8), 0, a.dp(8), 0);
            sideList.addView(empty);
            return;
        }
        for (Map.Entry<String, List<Item>> e : groups.entrySet()) {
            List<Item> list = e.getValue();
            if (list.isEmpty()) {
                continue;
            }
            String name = e.getKey();
            boolean open = folderOpen(name);
            TextView f = new TextView(a);
            f.setText(name + "  " + list.size());
            f.setTextColor(MainActivity.TEXT);
            f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            f.setTypeface(Typeface.DEFAULT_BOLD);
            f.setSingleLine(true);
            f.setEllipsize(TextUtils.TruncateAt.END);
            f.setGravity(Gravity.CENTER_VERTICAL);
            f.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.folder, 0, 0, 0);
            f.setCompoundDrawablePadding(a.dp(8));
            f.setPadding(a.dp(8), 0, a.dp(8), 0);
            f.setMinHeight(a.dp(40));
            f.setOnClickListener(v -> {
                prefs.edit().putBoolean("fold." + name, !open).apply();
                render();
            });
            f.setOnLongClickListener(v -> {
                folderMenu(v, name, list);
                return true;
            });
            sideList.addView(f);
            if (!open) {
                continue;
            }
            for (Item it : list) {
                View r = twoLine(it, a.dp(34), 14);
                r.setOnClickListener(v -> picked(it.id));
                r.setOnLongClickListener(v -> {
                    sessionMenu(it.id);
                    return true;
                });
                sideList.addView(r);
            }
        }
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(a.dp(6), 0, a.dp(6), 0);
        return r;
    }

    HorizontalScrollView scroller(LinearLayout r) {
        HorizontalScrollView s = new HorizontalScrollView(a);
        s.setHorizontalScrollBarEnabled(false);
        s.setBackgroundColor(MainActivity.SIDE);
        s.addView(r, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return s;
    }

    public static class Bridge {
        final SessionBars bars;

        Bridge(SessionBars bars) {
            this.bars = bars;
        }

        @JavascriptInterface
        public void sessions(String json) {
            if (json == null || json.length() > 1_000_000) {
                return;
            }
            bars.a.runOnUiThread(() -> {
                if (!bars.remote && bars.a.trustedPage()) {
                    bars.update(json);
                }
            });
        }
    }

    void update(String json) {
        String before = current;
        try {
            JSONObject o = new JSONObject(json);
            items.clear();
            JSONArray arr = o.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                String id = it.optString("id");
                if (validId(id)) {
                    items.add(new Item(id, it.optString("folder"), it.optString("title")));
                }
            }
            order.clear();
            JSONArray ord = o.optJSONArray("order");
            for (int i = 0; ord != null && i < ord.length(); i++) {
                order.add(ord.getString(i));
            }
            if (remote) {
                current = validId(o.optString("current")) ? o.optString("current") : null;
            } else {
                Matcher m = SESSION.matcher(o.optString("path"));
                current = m.find() ? m.group(1) : null;
                Notifier.foregroundRc = current;
            }
            if (current != null && !remote) {
                prefs.edit().putString("last", MainActivity.HOME + "/" + current).apply();
            }
        } catch (JSONException e) {
            return;
        }
        if (current != null && tabIndex(current) < 0) {
            tabs.add(new String[]{current, ""});
            while (tabs.size() > MAX_TABS) {
                tabs.remove(0);
            }
        }
        if (remote && !items.isEmpty()) {
            tabs.removeIf(t -> item(t[0]) == null && !t[0].equals(current));
        }
        for (String[] t : tabs) {
            Item it = item(t[0]);
            if (it != null) {
                t[1] = it.title;
            }
        }
        saveTabs();
        render();
        if (before != null && current != null && !before.equals(current) && !wide() && prefs.getBoolean("sideOpen", true)) {
            toggleSide();
        }
    }

    Item item(String id) {
        for (Item it : items) {
            if (it.id.equals(id)) {
                return it;
            }
        }
        return null;
    }

    int tabIndex(String id) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i)[0].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    void loadTabs() {
        try {
            JSONArray arr = new JSONArray(prefs.getString(remote ? "remote.tabs" : "tabs", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONArray t = arr.getJSONArray(i);
                if (validId(t.getString(0))) {
                    tabs.add(new String[]{t.getString(0), t.optString(1)});
                }
            }
        } catch (JSONException ignored) {
        }
    }

    void saveTabs() {
        JSONArray arr = new JSONArray();
        for (String[] t : tabs) {
            arr.put(new JSONArray().put(t[0]).put(t[1]));
        }
        prefs.edit().putString(remote ? "remote.tabs" : "tabs", arr.toString()).apply();
    }

    void closeTab(String id) {
        int i = tabIndex(id);
        if (i < 0) {
            return;
        }
        tabs.remove(i);
        saveTabs();
        if (id.equals(current)) {
            if (tabs.isEmpty()) {
                a.loadHome();
            } else {
                a.openSession(tabs.get(Math.min(i, tabs.size() - 1))[0]);
            }
        }
        render();
    }

    void render() {
        tabRow.removeAllViews();
        View active = null;
        for (String[] t : tabs) {
            View chip = tabChip(t[0], t[1].isEmpty() ? "Session" : t[1], t[0].equals(current));
            if (t[0].equals(current)) {
                active = chip;
            }
            tabRow.addView(chip);
        }
        boolean barsOn = !prefs.getBoolean("barsHidden", false);
        List<UsageItem> up = usageItems();
        if (up != null) {
            fillStrip(up);
        }
        usageStrip.setVisibility(!vertical && barsOn && up != null ? View.VISIBLE : View.GONE);
        tabScroll.setVisibility(!vertical && barsOn && prefs.getBoolean("showTabs", true) && !tabs.isEmpty() ? View.VISIBLE : View.GONE);
        if (active != null) {
            View v = active;
            tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, v.getLeft() - a.dp(24)), 0));
        }

        bookRow.removeAllViews();
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (String f : order) {
            groups.put(f, new ArrayList<>());
        }
        for (Item it : items) {
            List<Item> g = groups.get(it.folder);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(it.folder, g);
            }
            g.add(it);
        }
        for (Map.Entry<String, List<Item>> e : groups.entrySet()) {
            if (!e.getValue().isEmpty()) {
                bookRow.addView(bookmark(e.getKey(), e.getValue()));
            }
        }
        bookScroll.setVisibility(!vertical && barsOn && prefs.getBoolean("showFolders", true) && bookRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (vertical) {
            renderSide(groups);
        }
    }

    View tabChip(String id, String title, boolean active) {
        LinearLayout chip = new LinearLayout(a);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(a.dp(8));
        if (active) {
            bg.setStroke(a.dp(2), MainActivity.TEXT);
        }
        chip.setBackground(bg);
        chip.setPadding(a.dp(10), 0, 0, 0);
        TextView label = new TextView(a);
        label.setText(title);
        label.setTextColor(MainActivity.TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(a.dp(170));
        chip.addView(label);
        TextView close = new TextView(a);
        close.setText("×");
        close.setContentDescription("Close tab");
        close.setTextColor(MainActivity.MUTED);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> closeTab(id));
        chip.addView(close, new LinearLayout.LayoutParams(a.dp(34), ViewGroup.LayoutParams.MATCH_PARENT));
        chip.setOnClickListener(v -> a.openSession(id));
        chip.setOnLongClickListener(v -> {
            sessionMenu(id);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dp(34));
        lp.setMargins(a.dp(3), 0, a.dp(3), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    static final class Menu {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        Menu add(String label, Runnable act) {
            labels.add(label);
            acts.add(act);
            return this;
        }
    }

    void showMenu(String title, Menu m) {
        new android.app.AlertDialog.Builder(a)
                .setTitle(title)
                .setItems(m.labels.toArray(new String[0]), (d, w) -> m.acts.get(w).run())
                .show();
    }

    void folderMenu(View anchor, String folder, List<Item> list) {
        Menu m = new Menu();
        m.add("Expand all folders", () -> setAllFolders(true));
        m.add("Collapse all folders", () -> setAllFolders(false));
        showMenu(folder, m);
    }

    void setAllFolders(boolean open) {
        SharedPreferences.Editor e = prefs.edit();
        for (String f : order) {
            e.putBoolean("fold." + f, open);
        }
        for (Item it : items) {
            e.putBoolean("fold." + it.folder, open);
        }
        e.apply();
        render();
    }

    void sessionMenu(String id) {
        Item it = item(id);
        int ti = tabIndex(id);
        String title = it != null ? it.title : ti >= 0 ? tabs.get(ti)[1] : id;
        Menu m = new Menu();
        Remote r = remote ? a.remote : null;
        JSONObject s = r == null ? null : r.session(id);
        if (s != null && r.can("rename")) {
            m.add("Rename...", () -> askName(title, name -> r.sessionAction("rename", id, "rename", PhoneApi.obj("title", name))));
        }
        m.add("Open", () -> a.openSession(id));
        if (s != null && r.can("resume")) {
            m.add("Resume", () -> r.sessionAction("resume", id, "resume", null));
        }
        if (s != null && r.can("end")) {
            m.add("End session", () -> r.sessionAction("end session", id, "end", null));
        }
        if (s != null && r.can("delete")) {
            m.add("Delete session...", () -> new android.app.AlertDialog.Builder(a)
                    .setTitle("Delete session?")
                    .setMessage("\"" + title + "\" is removed from Quill Desktop. Its kmem handoff goes to the kmem recycle bin.")
                    .setPositiveButton("Delete", (d, w) -> r.sessionAction("delete", id, "delete", null))
                    .setNegativeButton("Cancel", null)
                    .show());
        }
        if (s != null && r.can("project")) {
            m.add("Project...", () -> projectMenu(r, id, s.optString("project")));
        }
        if (s != null && r.can("rename") && s.optBoolean("renamed")) {
            m.add("Restore original name", () -> r.sessionAction("restore name", id, "rename", PhoneApi.obj("title", "")));
        }
        String kmem = s == null ? "" : s.optString("kmem");
        showMenu(kmem.isEmpty() || kmem.equals(title) ? title : title + "  ·  kmem " + kmem, m);
    }

    /** Quill Desktop groups live sessions by kmem project; "" = automatic. */
    void projectMenu(Remote r, String id, String now) {
        Menu m = new Menu();
        JSONArray pr = r.projects();
        for (int k = 0; pr != null && k < pr.length(); k++) {
            String p = pr.optString(k);
            if (!p.isEmpty()) {
                m.add(p.equals(now) ? p + "  ✓" : p, () -> r.sessionAction("project", id, "project", PhoneApi.obj("project", p)));
            }
        }
        m.add("New project...", () -> askName("Project", "", p -> r.sessionAction("project", id, "project", PhoneApi.obj("project", p))));
        m.add("Automatic", () -> r.sessionAction("project", id, "project", PhoneApi.obj("project", "")));
        showMenu("Project", m);
    }

    void askName(String current, java.util.function.Consumer<String> done) {
        askName("Rename session", current, done);
    }

    void askName(String title, String current, java.util.function.Consumer<String> done) {
        android.widget.EditText e = new android.widget.EditText(a);
        e.setText(current);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        e.setContentDescription(title);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        box.addView(e);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (x, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty() && n.length() <= 200) {
                        done.accept(n);
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        e.requestFocus();
    }

    View bookmark(String folder, List<Item> list) {
        TextView b = new TextView(a);
        b.setText(folder + " (" + list.size() + ")");
        b.setTextColor(MainActivity.TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.folder, 0, 0, 0);
        b.setCompoundDrawablePadding(a.dp(6));
        b.setPadding(a.dp(8), 0, a.dp(10), 0);
        b.setMinHeight(a.dp(40));
        b.setOnClickListener(v -> dropDown(v, list));
        b.setOnLongClickListener(v -> {
            folderMenu(v, folder, list);
            return true;
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dp(40)));
        return b;
    }

    void dropDown(View anchor, List<Item> list) {
        ListPopupWindow pop = new ListPopupWindow(a);
        pop.setAnchorView(anchor);
        pop.setWidth(Math.min(a.dp(340), a.getResources().getDisplayMetrics().widthPixels - a.dp(16)));
        pop.setModal(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(a.dp(8));
        bg.setStroke(a.dp(1), 0xFF444440);
        pop.setBackgroundDrawable(bg);
        pop.setAdapter(new ArrayAdapter<Item>(a, android.R.layout.simple_list_item_1, list) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                return twoLine(getItem(pos), a.dp(14), 15);
            }
        });
        pop.setOnItemClickListener((p, v, pos, id) -> {
            pop.dismiss();
            a.openSession(list.get(pos).id);
        });
        pop.show();
        if (pop.getListView() != null) {
            pop.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
                pop.dismiss();
                sessionMenu(list.get(pos).id);
                return true;
            });
        }
    }
}
