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
        /** Position in Quill Desktop's tab bar (0-based), -1 when not an open tab or not reported. */
        final int tab;

        Item(String id, String folder, String title) {
            this(id, folder, title, -1);
        }

        Item(String id, String folder, String title, int tab) {
            this.id = id;
            this.folder = folder;
            this.title = title;
            this.tab = tab;
        }
    }

    /**
     * Flat portrait order: open tabs first in Quill Desktop's tab order (the "tab" field of /phone/sessions), then
     * the remaining items in the order they arrive. Items without a tab (older Quill Desktop builds, local claude.ai
     * mode) keep their arrival order, which in local mode is the page order. Repeated ids are dropped (first wins).
     */
    static List<Item> portraitOrder(List<Item> in) {
        List<Item> tabbed = new ArrayList<>();
        List<Item> rest = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Item it : in) {
            if (seen.add(it.id)) {
                (it.tab >= 0 ? tabbed : rest).add(it);
            }
        }
        tabbed.sort((x, y) -> Integer.compare(x.tab, y.tab)); // stable
        tabbed.addAll(rest);
        return tabbed;
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

    /** "12.3k tok · ctx 42%" from a session's meta; context only when known; "" without usage. */
    static String rowUsage(JSONObject m) {
        if (!m.has("tokens") || m.optLong("tokens") <= 0) {
            return ""; // nothing without usage, like Quill Desktop's caption
        }
        String out = tok(m.optLong("tokens")) + " tok";
        long used = m.optLong("context"), max = m.optLong("contextMax");
        if (max > 0) {
            out += " · ctx " + Math.round(100.0 * used / max) + "%";
        }
        return out;
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
        boolean far = t * 1000 - System.currentTimeMillis() > 20 * 3600_000L;
        return " · resets " + TimeFmt.format(far ? "EEE" : "", t * 1000);
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
                sep.setPaddingRelative(a.dp(10), 0, a.dp(10), 0);
                usageRow.addView(sep);
            }
            usageRow.addView(usageText(it.title, true));
            if (it.bar >= 0) {
                View b = usageBar(it.bar, a.dp(56), a.dp(6));
                ((LinearLayout.LayoutParams) b.getLayoutParams()).setMarginStart(a.dp(6));
                ((LinearLayout.LayoutParams) b.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL;
                usageRow.addView(b);
            }
            TextView v = usageText(it.value, false);
            v.setPaddingRelative(a.dp(6), 0, 0, 0);
            usageRow.addView(v);
        }
    }

    void fillSide(List<UsageItem> list) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(a.dp(8), 0, a.dp(8), a.dp(6));
        box.setContentDescription("Usage");
        for (UsageItem it : list) {
            LinearLayout row = new LinearLayout(a);
            row.setPaddingRelative(0, a.dp(2), 0, 0);
            row.addView(usageText(it.title, true));
            TextView v = usageText(it.value, false);
            v.setPaddingRelative(a.dp(6), 0, 0, 0);
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
        if (m.optBoolean("autoApprove")) {
            int at = sb.length() + (sb.length() == 0 ? 0 : 5);
            sb.append(sb.length() == 0 ? "" : "  ·  ").append(AutoApprove.listMark(true));
            sb.setSpan(new android.text.style.ForegroundColorSpan(0xFF6FCF97), at, sb.length(), 0);
        }
        // Portrait (the usage panel shows only the open session): each row says its tokens and context, like
        // Quill Desktop's portrait session list (941a3cf): "12.3k tok · ctx 42%".
        String usage = rowUsage(m);
        if (!usage.isEmpty() && a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            sb.append(sb.length() == 0 ? "" : "  ·  ").append(usage);
        }
        return sb;
    }

    View twoLine(Item it, int padLeft, int titleSp) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(a.dp(44));
        l.setPaddingRelative(padLeft, a.dp(4), a.dp(10), a.dp(4));
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
        usageRow.setPaddingRelative(a.dp(8), 0, a.dp(8), a.dp(2));
        usageStrip = scroller(usageRow);
        usageStrip.setFillViewport(true);
        usageStrip.setContentDescription("Usage");
        usageStrip.setVisibility(View.GONE);
        bookScroll = scroller(bookRow);
        sideList = new LinearLayout(a);
        sideList.setOrientation(LinearLayout.VERTICAL);
        sideList.setPaddingRelative(a.dp(8), a.dp(8), a.dp(8), a.dp(16));
        sideScroll = new android.widget.ScrollView(a);
        sideScroll.setBackgroundColor(MainActivity.SIDE);
        sideScroll.addView(sideList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        side = new LinearLayout(a);
        side.setOrientation(LinearLayout.HORIZONTAL);
        side.addView(sideScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        // The edge between the list and the chat; in portrait, hold it and drag to resize the list.
        android.widget.FrameLayout handle = new android.widget.FrameLayout(a);
        handle.setBackgroundColor(MainActivity.SIDE);
        handle.setContentDescription("Session list edge");
        View edge = new View(a);
        edge.setBackgroundColor(0xFF3A3A37);
        handle.addView(edge, new android.widget.FrameLayout.LayoutParams(a.dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        View grip = new View(a);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF5A5A55);
        g.setCornerRadius(a.dp(2));
        grip.setBackground(g);
        handle.addView(grip, new android.widget.FrameLayout.LayoutParams(a.dp(5), a.dp(56), Gravity.CENTER));
        edgeTouch = resizer();
        handle.setOnTouchListener(edgeTouch);
        side.addView(handle, new LinearLayout.LayoutParams(a.dp(HANDLE_W), ViewGroup.LayoutParams.MATCH_PARENT));
        this.handle = handle;
        excludeGestures();
        remote = a.phoneApi != null && a.phoneApi.available();
        loadTabs();
    }

    static final int HANDLE_W = 18;
    static final int MIN_SIDE_DP = 160;
    static final int MIN_CHAT_DP = 160;
    static final int SNAP_DP = 100;
    View handle;
    View.OnTouchListener edgeTouch;
    static final int GRAB_SLOP_DP = 24;

    /** True when a touch at this screen point is on or within GRAB_SLOP_DP of the edge strip (so slow grabs never hit a row's long-press). */
    boolean nearEdge(float rawX, float rawY) {
        if (handle == null || handle.getVisibility() != View.VISIBLE || side.getVisibility() != View.VISIBLE) {
            return false;
        }
        android.graphics.Rect r = new android.graphics.Rect();
        if (!handle.getGlobalVisibleRect(r)) {
            return false;
        }
        // Wider on the chat side; narrower over the list so taps on the end of a row still open it.
        return rawX >= r.left - a.dp(GRAB_SLOP_DP) / 2 && rawX <= r.right + a.dp(GRAB_SLOP_DP) && rawY >= r.top && rawY <= r.bottom;
    }

    boolean portrait() {
        return a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    int screenW() {
        return a.getResources().getDisplayMetrics().widthPixels;
    }

    /** Width the user dragged the list to in portrait (dp), or 0 for the default. */
    /** The pref holding the dragged list width for the current orientation (dp). */
    String widthKey() {
        return portrait() ? "sideW.portrait" : "sideW.landscape";
    }

    /** Width the user dragged the list to in this orientation (dp), or 0 for the default. */
    int portraitW() {
        return prefs.getInt(widthKey(), 0);
    }

    int clampSide(int px) {
        int min = a.dp(MIN_SIDE_DP);
        int max = screenW() - a.dp(MIN_CHAT_DP);
        return Math.max(min, Math.min(px, Math.max(min, max)));
    }

    /** True when the open list hides the chat (phones at the default width). */
    boolean coversChat() {
        return sideFull() >= screenW() - a.dp(4);
    }

    /** Portrait only: hold the edge ~0.35 s (a short buzz), then drag left or right; the width is remembered for portrait. */
    /**
     * Portrait: touch the edge and drag (grabs at once); tap toggles the list. While dragging only the edge strip moves
     * (translationX, cheap); the list and chat are resized once on release, because re-laying out a long chat on every
     * move made the drag lag (user 2026-11-21: "takes a long time to grab").
     */
    View.OnTouchListener resizer() {
        boolean[] armed = {false};
        boolean[] moved = {false};
        float[] down = {0, 0};
        int[] startW = {0};
        int[] target = {0};
        int slop = android.view.ViewConfiguration.get(a).getScaledTouchSlop();
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    armed[0] = false;
                    moved[0] = false;
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    startW[0] = side.getWidth();
                    target[0] = startW[0];
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    // Drag works in both orientations (user 2026-11-21: off-centre grabs failed in landscape, where it was tap-only).
                    startDrag(v, armed);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - down[0]) > slop) {
                        moved[0] = true;
                    }
                    if (armed[0] && moved[0]) {
                        int[] at = new int[2];
                        side.getLocationOnScreen(at);
                        int w = Math.max(a.dp(HANDLE_W), Math.min((int) e.getRawX() - at[0] + a.dp(HANDLE_W) / 2, screenW()));
                        target[0] = w;
                        v.setTranslationX(w - startW[0]);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    boolean wasArmed = armed[0];
                    armed[0] = false;
                    v.setTranslationX(0);
                    side.setTranslationZ(0);
                    v.setBackgroundColor(MainActivity.SIDE);
                    if (!moved[0]) {
                        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            toggleSide();
                        }
                        return true;
                    }
                    if (!wasArmed) {
                        return true;
                    }
                    int w = target[0];
                    float d = a.getResources().getDisplayMetrics().density;
                    SharedPreferences.Editor ed = prefs.edit();
                    if (w < a.dp(SNAP_DP)) {
                        // Dragged to the left edge: closed (the last width is kept for the next open).
                        ed.putBoolean("sideOpen", false);
                        setSideWidth(0);
                    } else if (w > screenW() - a.dp(SNAP_DP)) {
                        // Dragged to the right edge: the list fills the screen.
                        ed.putBoolean("sideOpen", true).putInt(widthKey(), Math.round(screenW() / d));
                        setSideWidth(screenW());
                    } else {
                        int c = clampSide(w);
                        ed.putBoolean("sideOpen", true).putInt(widthKey(), Math.round(c / d));
                        setSideWidth(c);
                    }
                    ed.apply();
                    syncArrow();
                    a.syncTitle();
                    return true;
                default:
                    return true;
            }
        };
    }

    void startDrag(View v, boolean[] armed) {
        if (armed[0]) {
            return;
        }
        armed[0] = true;
        if (sideAnim != null) {
            sideAnim.cancel();
        }
        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        v.setBackgroundColor(0xFF4A3A33);
        // Let the strip move past the list's bounds and draw above the chat while dragging.
        side.setClipChildren(false);
        if (side.getParent() instanceof ViewGroup) {
            ((ViewGroup) side.getParent()).setClipChildren(false);
        }
        side.setTranslationZ(a.dp(6));
    }

    /** Keep Android's back-swipe from taking touches on the grip (it sits on the screen edge when the list is closed). */
    void excludeGestures() {
        if (android.os.Build.VERSION.SDK_INT < 29 || handle == null) {
            return;
        }
        handle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int band = Math.min(h, a.dp(200));
            int top = Math.max(0, (h - band) / 2);
            v.setSystemGestureExclusionRects(java.util.Collections.singletonList(new android.graphics.Rect(0, top, r - l, top + band)));
        });
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

        syncArrow();
        render();
        if (changed) {
            a.inject();
        }
    }

    /** The folder list: a 300 dp sidebar on wide screens, the whole width on phones (it covers the chat until a session is picked). */
    int sideFull() {
        int pw = portraitW();
        if (pw > 0) {
            return a.dp(pw) >= screenW() - a.dp(SNAP_DP) ? screenW() : clampSide(a.dp(pw));
        }
        return wide() ? a.dp(SIDE_W) + a.dp(HANDLE_W) : screenW();
    }

    /** On phones the folder list closes once a session is picked. */
    void picked(String id) {
        a.openSession(id);
        if (coversChat() && prefs.getBoolean("sideOpen", true)) {
            toggleSide();
        }
    }

    void setSideWidth(int w) {
        ViewGroup.LayoutParams lp = side.getLayoutParams();
        if (lp == null) {
            return;
        }
        // At a screen edge (list closed, or filling the screen) the strip widens by Android's back-gesture zone, so part of
        // it is outside that zone at every height (Android only lets an app reserve ~200 dp of the edge from the gesture).
        int[] gi = gestureInsets();
        boolean closed = vertical && w <= a.dp(HANDLE_W) + gi[0];
        boolean full = w >= screenW() - a.dp(4);
        int hw = a.dp(HANDLE_W) + (closed ? gi[0] : full ? gi[1] : 0);
        if (handle != null && handle.getLayoutParams() != null && handle.getLayoutParams().width != hw) {
            handle.getLayoutParams().width = hw;
            handle.requestLayout();
        }
        // A closed list keeps its edge strip (tap opens it; in portrait it can also be dragged open).
        if (closed) {
            w = hw;
        }
        lp.width = w;
        side.setLayoutParams(lp);
        side.setVisibility(w > 0 ? View.VISIBLE : View.GONE);
    }

    /** Left and right system-gesture insets in px (0 before Android 10 or when gesture navigation is off). */
    int[] gestureInsets() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            return new int[]{0, 0};
        }
        android.view.WindowInsets wi = a.getWindow().getDecorView().getRootWindowInsets();
        if (wi == null) {
            return new int[]{0, 0};
        }
        android.graphics.Insets g = wi.getSystemGestureInsets();
        return new int[]{g.left, g.right};
    }

    void toggleSide() {
        if (!vertical) {
            return;
        }
        boolean open = !prefs.getBoolean("sideOpen", true);
        prefs.edit().putBoolean("sideOpen", open).apply();
        syncArrow();
        a.syncTitle();
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

    /** kmem button: count bubble, a dot while the assistant runs, an accent outline and "!" while it waits for the user. */
    /** The Quill and kmem buttons in one row (Quill only when hello says quillOperator is on). */
    View assistButtons(Remote rm) {
        if (!rm.quillOn) {
            return kmemButton(rm);
        }
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, 0, 0, a.dp(8));
        row.setLayoutParams(rp);
        View q = quillButton(rm);
        View k = rm.kmemOn ? kmemButton(rm) : null;
        row.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (k != null) {
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            kp.setMarginStart(a.dp(8));
            row.addView(k, kp);
        }
        return row;
    }

    /** Quill button: the same bubble and running dot as kmem's, from GET /phone/quill and the stream. */
    View quillButton(Remote rm) {
        QuillOpModel.State s = rm.quillState;
        boolean waits = s != null && !s.waiting.isEmpty();
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(a.dp(44));
        l.setPaddingRelative(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(a.dp(8));
        bg.setColor(CARD);
        bg.setStroke(a.dp(waits ? 2 : 1), waits ? MainActivity.ACCENT : 0xFF444440);
        l.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.setLayoutParams(lp);
        TextView t = new TextView(a);
        t.setText("Quill");
        t.setTextColor(MainActivity.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (s != null && s.dot) {
            View dot = new View(a);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0xFF6CC06C);
            dot.setBackground(d);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(a.dp(8), a.dp(8));
            dp.setMarginEnd(a.dp(8));
            l.addView(dot, dp);
        }
        String bub = s == null ? "" : s.bubble();
        if (!bub.isEmpty()) {
            TextView b = new TextView(a);
            b.setText(bub);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(waits ? 0xFF1F1F1D : MainActivity.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setPaddingRelative(a.dp(7), a.dp(1), a.dp(7), a.dp(1));
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(a.dp(10));
            bb.setColor(waits ? MainActivity.ACCENT : 0xFF5A5A55);
            b.setBackground(bb);
            l.addView(b);
        }
        l.setContentDescription("Quill operator" + (s == null ? "" : ", " + s.line()) + (s != null && s.count > 0 ? ", " + s.count + " waiting" : ""));
        l.setOnClickListener(v -> a.openQuill());
        return l;
    }

    View kmemButton(Remote rm) {
        boolean waits = !rm.kmemWaiting.isEmpty();
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(a.dp(44));
        l.setPaddingRelative(a.dp(12), a.dp(4), a.dp(12), a.dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(a.dp(8));
        bg.setColor(CARD);
        bg.setStroke(a.dp(waits ? 2 : 1), waits ? MainActivity.ACCENT : 0xFF444440);
        l.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, a.dp(8));
        l.setLayoutParams(lp);
        TextView t = new TextView(a);
        t.setText("kmem");
        t.setTextColor(MainActivity.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        l.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (KmemModels.running(rm.kmemState)) {
            View dot = new View(a);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0xFF6CC06C);
            dot.setBackground(d);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(a.dp(8), a.dp(8));
            dp.setMarginEnd(a.dp(8));
            l.addView(dot, dp);
        }
        String bub = KmemModels.bubble(rm.kmemCount, rm.kmemWaiting);
        if (!bub.isEmpty()) {
            TextView b = new TextView(a);
            b.setText(bub);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextColor(waits ? 0xFF1F1F1D : MainActivity.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setPaddingRelative(a.dp(7), a.dp(1), a.dp(7), a.dp(1));
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(a.dp(10));
            bb.setColor(waits ? MainActivity.ACCENT : 0xFF5A5A55);
            b.setBackground(bb);
            l.addView(b);
        }
        l.setContentDescription("kmem, " + rm.kmemCount + " waiting" + (waits ? ", the kmem assistant needs you" : ""));
        l.setOnClickListener(v -> a.openKmem());
        return l;
    }

    void renderSide(Map<String, List<Item>> groups) {
        boolean flat = portrait();
        sideList.removeAllViews();
        Remote rm = remote ? a.remote : null;
        if (rm != null && (rm.kmemOn || rm.quillOn)) {
            sideList.addView(assistButtons(rm));
        }
        TextView head = new TextView(a);
        head.setText("Sessions");
        head.setTextColor(MainActivity.TEXT);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPaddingRelative(a.dp(8), a.dp(4), a.dp(8), a.dp(8));
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
            empty.setPaddingRelative(a.dp(8), 0, a.dp(8), 0);
            sideList.addView(empty);
            return;
        }
        if (flat) {
            for (Item it : portraitOrder(items)) {
                View r = twoLine(it, a.dp(34), 14);
                r.setOnClickListener(v -> picked(it.id));
                r.setOnLongClickListener(v -> {
                    sessionMenu(it.id);
                    return true;
                });
                sideList.addView(r);
            }
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
            f.setPaddingRelative(a.dp(8), 0, a.dp(8), 0);
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

    View operatorSessionDrawer(java.util.function.Consumer<String> onPicked) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        scroll.setBackgroundColor(MainActivity.SIDE);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPaddingRelative(a.dp(8), a.dp(8), a.dp(8), a.dp(16));
        TextView heading = new TextView(a);
        heading.setText("Sessions");
        heading.setTextColor(MainActivity.TEXT);
        heading.setTextSize(16);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPaddingRelative(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        list.addView(heading);
        for (Item item : portraitOrder(items)) {
            View entry = twoLine(item, a.dp(40), 14);
            entry.setOnClickListener(v -> onPicked.accept(item.id));
            list.addView(entry);
        }
        if (items.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText("No live sessions from Quill Desktop right now.");
            empty.setTextColor(MainActivity.MUTED);
            list.addView(empty);
        }
        scroll.addView(list);
        return scroll;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(a.dp(6), 0, a.dp(6), 0);
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
                    items.add(new Item(id, it.optString("folder"), it.optString("title"), it.optInt("tab", -1)));
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
        if (before != null && current != null && !before.equals(current) && coversChat() && prefs.getBoolean("sideOpen", true)) {
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
        a.syncTitle();
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
        chip.setPaddingRelative(a.dp(10), 0, 0, 0);
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
        Touch.show(new android.app.AlertDialog.Builder(a)
                .setTitle(title)
                .setItems(m.labels.toArray(new String[0]), (d, w) -> m.acts.get(w).run())
                );
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
        if (s != null && s.optBoolean("restartPending") && r.can("cancel-restart")) {
            m.add("Cancel session restart", () -> r.sessionAction("cancel restart", id, "cancel-restart", null));
        } else if (s != null && r.can("restart-session") && java.util.Arrays.asList("idle", "running", "waiting").contains(s.optString("status"))) {
            m.add("Restart session…", () -> {
                boolean codex = s.optBoolean("codex");
                String message = codex
                        ? "Codex will start a new thread from a fresh kmem handoff. The Quill tab, transcript and draft stay in place."
                        : "Restart with the current provider settings. The tab and conversation stay in place.";
                Touch.show(new android.app.AlertDialog.Builder(a).setTitle("Restart session?")
                        .setMessage(message)
                        .setPositiveButton("Restart", (dlg, choice) -> r.sessionAction("restart session", id, "restart-session",
                                PhoneApi.obj("fromHandoff", codex)))
                        .setNegativeButton("Cancel", null));
            });
        }
        if (s != null && r.can("delete")) {
            m.add("Delete session...", () -> Touch.show(new android.app.AlertDialog.Builder(a)
                    .setTitle("Delete session?")
                    .setMessage("\"" + title + "\" is removed from Quill Desktop. Its kmem handoff goes to the kmem recycle bin.")
                    .setPositiveButton("Delete", (d, w) -> r.sessionAction("delete", id, "delete", null))
                    .setNegativeButton("Cancel", null)
                    ));
        }
        if (s != null && r.can("project")) {
            m.add("Project...", () -> projectMenu(r, id, s.optString("project")));
        }
        if (s != null && r.can("rename") && s.optBoolean("renamed")) {
            m.add("Restore original name", () -> r.sessionAction("restore name", id, "rename", PhoneApi.obj("title", "")));
        }
        if (s != null && Home.canPin(a)) {
            m.add("Add to home screen", () -> {
                if (!Home.pin(a, id, title)) {
                    a.toast("This launcher can't add shortcuts.");
                }
            });
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
        Touch.typing(e);
        e.setText(current);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        e.setContentDescription(title);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setPaddingRelative(a.dp(20), a.dp(8), a.dp(20), 0);
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
        Touch.watch(d).show();
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
        b.setPaddingRelative(a.dp(8), 0, a.dp(10), 0);
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
            pop.getListView().setOnTouchListener((v, e) -> {
                Touch.ping();
                return false;
            });
        }
        if (pop.getListView() != null) {
            pop.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
                pop.dismiss();
                sessionMenu(list.get(pos).id);
                return true;
            });
        }
    }
}
