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

    SessionBars(MainActivity a, SharedPreferences prefs) {
        this.a = a;
        this.prefs = prefs;
        tabRow = row();
        bookRow = row();
        tabScroll = scroller(tabRow);
        bookScroll = scroller(bookRow);
        loadTabs();
        render();
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
            bars.a.runOnUiThread(() -> bars.update(json));
        }
    }

    void update(String json) {
        try {
            JSONObject o = new JSONObject(json);
            items.clear();
            JSONArray arr = o.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject it = arr.getJSONObject(i);
                String id = it.optString("id");
                if (SESSION.matcher("/code/" + id).matches()) {
                    items.add(new Item(id, it.optString("folder"), it.optString("title")));
                }
            }
            order.clear();
            JSONArray ord = o.optJSONArray("order");
            for (int i = 0; ord != null && i < ord.length(); i++) {
                order.add(ord.getString(i));
            }
            Matcher m = SESSION.matcher(o.optString("path"));
            current = m.find() ? m.group(1) : null;
        } catch (JSONException e) {
            return;
        }
        if (current != null && tabIndex(current) < 0) {
            tabs.add(new String[]{current, ""});
            while (tabs.size() > MAX_TABS) {
                tabs.remove(0);
            }
        }
        for (String[] t : tabs) {
            Item it = item(t[0]);
            if (it != null) {
                t[1] = it.title;
            }
        }
        saveTabs();
        render();
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
            JSONArray arr = new JSONArray(prefs.getString("tabs", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONArray t = arr.getJSONArray(i);
                if (SESSION.matcher("/code/" + t.getString(0)).matches()) {
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
        prefs.edit().putString("tabs", arr.toString()).apply();
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
        tabScroll.setVisibility(prefs.getBoolean("showTabs", true) && !tabs.isEmpty() ? View.VISIBLE : View.GONE);
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
        bookScroll.setVisibility(prefs.getBoolean("showFolders", true) && bookRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dp(34));
        lp.setMargins(a.dp(3), 0, a.dp(3), 0);
        chip.setLayoutParams(lp);
        return chip;
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
                TextView t = convert instanceof TextView ? (TextView) convert : new TextView(a);
                Item it = getItem(pos);
                t.setText(it.title);
                t.setTextColor(MainActivity.TEXT);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setMinHeight(a.dp(44));
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(a.dp(14), 0, a.dp(14), 0);
                boolean cur = it.id.equals(current);
                t.setTypeface(cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                if (cur) {
                    GradientDrawable o = new GradientDrawable();
                    o.setCornerRadius(a.dp(8));
                    o.setStroke(a.dp(2), MainActivity.TEXT);
                    o.setColor(0);
                    t.setBackground(o);
                } else {
                    t.setBackground(new ColorDrawable(0));
                }
                return t;
            }
        });
        pop.setOnItemClickListener((p, v, pos, id) -> {
            pop.dismiss();
            a.openSession(list.get(pos).id);
        });
        pop.show();
    }
}
