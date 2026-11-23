package io.github.katachinosekai.quillremote;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * The kmem screen: Overview, read-only Tasks and (phase 2) the Assistant chat. Portrait shows three tabs; landscape shows
 * Overview and Tasks on the left and the assistant chat on the right. Data comes from Quill Desktop's /phone/kmem/* routes.
 */
class KmemScreen {
    final MainActivity a;
    final Remote remote;
    final Dialog dialog;
    final FrameLayout holder;
    final LinearLayout ovCol, taskCol;
    final ScrollView ovScroll, taskScroll;
    final LinearLayout chips;
    final EditText search;
    final TextView errorLine;
    static final int TAB_ASSISTANT = 2;
    int tab; // 0 Overview, 1 Tasks, 2 Assistant (portrait only)
    final ChatView chatView;
    final KmemAssistant assist;
    final LinearLayout assistPane, assistHeader;
    TextView stateView, tabAssist;
    final Runnable tick = this::tickState;
    JSONObject overview;
    List<KmemModels.Task> tasks;
    boolean truncated;
    String filter = KmemModels.ALL;
    String query = "";
    String taskError = "";
    TextView tabOv, tabTasks;
    boolean landscape;

    KmemScreen(MainActivity a, Remote remote) {
        this.a = a;
        this.remote = remote;
        ovCol = column();
        taskCol = column();
        ovScroll = new ScrollView(a);
        ovScroll.addView(ovCol);
        taskScroll = new ScrollView(a);
        taskScroll.addView(taskCol);
        chips = new LinearLayout(a);
        search = new EditText(a);
        search.setHint("Search titles");
        search.setSingleLine(true);
        search.setTextColor(MainActivity.TEXT);
        search.setHintTextColor(MainActivity.MUTED);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString();
                fillTasks();
            }
        });
        errorLine = new TextView(a);
        errorLine.setTextColor(0xFFE55B5B);
        errorLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        errorLine.setVisibility(View.GONE);
        chatView = new ChatView(a);
        chatView.showTimes = a.prefs.getBoolean("messageTimes", true);
        chatView.animate = a.prefs.getBoolean("animateText", true);
        assist = new KmemAssistant(remote, chatView);
        assist.onModel = this::fillAssist;
        assistHeader = column();
        assistHeader.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(6));
        assistPane = column();
        assistPane.addView(assistHeader, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        assistPane.addView(chatView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
        holder = new FrameLayout(a);
        holder.setBackgroundColor(MainActivity.BG);
        dialog.setContentView(holder);
        dialog.setOnDismissListener(d -> {
            Notifier.kmemOpen = false;
            assistHeader.removeCallbacks(tick);
            if (a.kmemScreen == this) {
                a.kmemScreen = null;
            }
        });
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    void show() {
        Notifier.kmemOpen = true;
        dialog.show();
        layout();
        refresh();
        assist.refresh(true);
    }

    void close() {
        dialog.dismiss();
    }

    /** Rebuild the frame for the current orientation (also called on rotation); content views are kept. */
    void layout() {
        landscape = a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        for (View v : new View[]{ovScroll, taskScroll, chips, search, errorLine, assistPane}) {
            if (v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
        }
        holder.removeAllViews();
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(topBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(48)));
        View panels = panels();
        if (landscape) {
            LinearLayout split = new LinearLayout(a);
            split.setOrientation(LinearLayout.HORIZONTAL);
            split.addView(panels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            View edge = new View(a);
            edge.setBackgroundColor(0xFF3A3A37);
            split.addView(edge, new LinearLayout.LayoutParams(a.dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
            split.addView(assistPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            root.addView(split, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            root.addView(panels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        holder.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showTab();
    }

    View topBar() {
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(MainActivity.SIDE);
        bar.setPadding(a.dp(4), 0, a.dp(8), 0);
        bar.addView(a.button("←", "Back", v -> close()));
        TextView t = new TextView(a);
        t.setText("kmem");
        t.setTextColor(MainActivity.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(a.dp(8), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(a.button("⟳", "Refresh", v -> {
            refresh();
            assist.refresh(false);
        }));
        return bar;
    }

    View panels() {
        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs = new LinearLayout(a);
        tabs.setBackgroundColor(MainActivity.SIDE);
        tabOv = tabView("Overview", 0);
        tabTasks = tabView("Tasks", 1);
        tabs.addView(tabOv, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        tabs.addView(tabTasks, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        if (!landscape) {
            tabAssist = tabView("Assistant", TAB_ASSISTANT);
            tabs.addView(tabAssist, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        } else {
            tabAssist = null;
        }
        p.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        p.addView(errorLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        errorLine.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
        FrameLayout body = new FrameLayout(a);
        body.addView(ovScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout tasksPane = new LinearLayout(a);
        tasksPane.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView cs = new HorizontalScrollView(a);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(chips);
        tasksPane.addView(cs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        search.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
        tasksPane.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tasksPane.addView(taskScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tasksPane.setTag("tasks");
        body.addView(tasksPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tasksHolder = tasksPane;
        if (!landscape) {
            body.addView(assistPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        p.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return p;
    }

    View tasksHolder;

    TextView tabView(String name, int index) {
        TextView t = new TextView(a);
        t.setText(name);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setOnClickListener(v -> selectTab(index));
        return t;
    }

    /** Landscape has no Assistant tab (the chat is always on the right): that choice shows Overview. */
    void selectTab(int index) {
        tab = landscape && index == TAB_ASSISTANT ? 0 : index;
        showTab();
        if (tab == 1 && tasks == null) {
            refreshTasks();
        }
    }

    void showTab() {
        if (landscape && tab == TAB_ASSISTANT) {
            tab = 0;
        }
        ovScroll.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        tasksHolder.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        assistPane.setVisibility(landscape || tab == TAB_ASSISTANT ? View.VISIBLE : View.GONE);
        style(tabOv, tab == 0);
        style(tabTasks, tab == 1);
        if (tabAssist != null) {
            style(tabAssist, tab == TAB_ASSISTANT);
        }
        fillAssist();
        fillOverview();
        fillTasks();
    }

    void style(TextView t, boolean on) {
        t.setTextColor(on ? MainActivity.ACCENT : MainActivity.MUTED);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    void refresh() {
        assist.streamChanged(remote.kmemEpoch, remote.kmemRev);
        refreshOverview();
        if (tab == 1 || tasks != null) {
            refreshTasks();
        }
    }

    void showError(String line) {
        errorLine.setText(line);
        errorLine.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void refreshOverview() {
        remote.kmem(remote.api::kmemOverview, r -> {
            if (r.ok()) {
                overview = r.body;
                showError("");
                fillOverview();
            } else if (r.code != 401) {
                showError(KmemModels.errorLine(r.code, r.error()));
            }
        });
    }

    void refreshTasks() {
        remote.kmem(() -> remote.api.kmemTasks("", true), r -> {
            if (r.ok()) {
                tasks = KmemModels.parseTasks(r.body.optJSONArray("tasks"));
                truncated = r.body.optBoolean("truncated");
                taskError = "";
                if (!KmemModels.filters(tasks).contains(filter)) {
                    filter = KmemModels.ALL;
                }
                fillTasks();
            } else if (r.code != 401) {
                taskError = KmemModels.errorLine(r.code, r.error());
                fillTasks();
            }
        });
    }

    // ---- Assistant ----

    void fillAssist() {
        assistHeader.removeCallbacks(tick);
        assistHeader.removeAllViews();
        KmemAssistModel m = assist.model;
        if (m == null) {
            assistHeader.addView(text("Loading…", 14, MainActivity.MUTED, false));
            stateView = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (!m.model.isEmpty()) {
            assistHeader.addView(text("Model: " + m.model + (m.modelSource.isEmpty() ? "" : " (" + m.modelSource + ")"), 13, MainActivity.MUTED, false));
        }
        stateView = text("", 14, MainActivity.TEXT, true);
        stateView.setText("● " + m.stateLine(now));
        assistHeader.addView(stateView);
        String w = KmemModels.waitingText(m.waiting);
        if (!w.isEmpty()) {
            assistHeader.addView(text(w, 13, MainActivity.ACCENT, false));
        }
        if (!m.reason.isEmpty()) {
            TextView r = text(m.reason, 13, MainActivity.MUTED, false);
            r.setPadding(0, a.dp(2), 0, 0);
            assistHeader.addView(r);
        }
        boolean control = remote.control;
        LinearLayout row = new LinearLayout(a);
        boolean primaryLabel = !m.button.isEmpty();
        if (control && m.can("start")) {
            row.addView(pill(primaryLabel ? m.button : "Start assistant", () -> assist.lifecycle("start", "start")));
            primaryLabel = false;
        }
        if (control && m.can("restart")) {
            row.addView(pill(primaryLabel ? m.button : "Restart", () -> assist.lifecycle("restart", "restart")));
        }
        if (control && m.can("save")) {
            row.addView(pill("Save now", () -> assist.lifecycle("save", "save")));
        }
        if (control && m.can("stop")) {
            row.addView(pill("Stop assistant", () -> new android.app.AlertDialog.Builder(a)
                    .setMessage("Stop the kmem assistant? It saves its kmem handoff first (up to 60 s).")
                    .setPositiveButton("Stop", (d, i) -> assist.lifecycle("stop", "stop"))
                    .setNegativeButton("Cancel", null)
                    .show()));
        }
        if (row.getChildCount() > 0) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(row);
            hs.setPadding(0, a.dp(6), 0, 0);
            assistHeader.addView(hs);
        }
        KmemAssistModel.Question q = m.question;
        if (q != null) {
            LinearLayout card = column();
            card.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(a.dp(10));
            bg.setColor(SessionBars.CARD);
            bg.setStroke(a.dp(1), MainActivity.ACCENT);
            card.setBackground(bg);
            card.addView(text(q.text, 14, MainActivity.TEXT, false));
            for (int i = 0; control && i < q.choices.size(); i++) {
                int idx = i;
                TextView b = pill(q.choices.get(i), () -> assist.choose(idx));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = a.dp(6);
                b.setGravity(Gravity.CENTER);
                card.addView(b, lp);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = a.dp(8);
            assistHeader.addView(card, cp);
        }
        if (m.leftS(now) > 0) {
            assistHeader.postDelayed(tick, 1000);
        }
    }

    /** Count down locally between snapshots. */
    void tickState() {
        KmemAssistModel m = assist.model;
        if (m == null || stateView == null) {
            return;
        }
        long now = System.currentTimeMillis();
        stateView.setText("● " + m.stateLine(now));
        if (m.leftS(now) > 0) {
            assistHeader.postDelayed(tick, 1000);
        }
    }

    TextView pill(String label, Runnable onTap) {
        TextView c = text(label, 13, MainActivity.TEXT, false);
        c.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(a.dp(16));
        bg.setColor(SessionBars.CARD);
        c.setBackground(bg);
        c.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, a.dp(6), 0);
        c.setLayoutParams(lp);
        return c;
    }

    // ---- Overview ----

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    void heading(LinearLayout col, String s) {
        TextView t = text(s, 13, MainActivity.MUTED, true);
        t.setPadding(0, a.dp(16), 0, a.dp(4));
        col.addView(t);
    }

    void fillOverview() {
        ovCol.removeAllViews();
        ovCol.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(24));
        if (overview == null) {
            ovCol.addView(text("Loading…", 14, MainActivity.MUTED, false));
            return;
        }
        JSONObject n = overview.optJSONObject("needs");
        JSONObject as = overview.optJSONObject("assistant");
        if (as != null) {
            String w = as.optString("waiting");
            ovCol.addView(text(KmemModels.assistantLine(as.optString("state"), w, as.optString("model")), 14,
                    w.isEmpty() ? MainActivity.TEXT : MainActivity.ACCENT, !w.isEmpty()));
        }
        int count = n == null ? 0 : n.optInt("count");
        heading(ovCol, "Waiting for you (" + count + ")");
        if (n != null) {
            JSONArray issues = n.optJSONArray("issues");
            int ut = n.optJSONArray("userTasks") == null ? 0 : n.optJSONArray("userTasks").length();
            for (int i = 0; issues != null && i < issues.length(); i++) {
                JSONObject o = issues.optJSONObject(i);
                if (o != null) {
                    ovCol.addView(row("[" + o.optString("id") + "] p" + o.optInt("prio") + " · " + o.optString("title")));
                }
            }
            for (String s : KmemModels.needsLines(n.optInt("bin"), n.optString("audit"), n.optInt("maint"),
                    n.optString("maintReport"), n.optString("rolloutWait"), ut)) {
                ovCol.addView(row(s));
            }
        }
        if (count == 0) {
            ovCol.addView(text("Nothing is waiting for you.", 14, MainActivity.MUTED, false));
        }
        JSONObject c = overview.optJSONObject("counts");
        if (c != null) {
            heading(ovCol, "Counts");
            ovCol.addView(text(c.optInt("topics") + " topics · " + c.optInt("handoffs") + " handoffs · " + c.optInt("openTasks") + " open tasks",
                    14, MainActivity.TEXT, false));
        }
        JSONArray big = overview.optJSONArray("bigTopics");
        if (big != null && big.length() > 0) {
            heading(ovCol, "Sections over 8 KB");
            for (int i = 0; i < big.length(); i++) {
                JSONObject o = big.optJSONObject(i);
                if (o != null) {
                    ovCol.addView(row(o.optString("name") + " · " + o.optInt("kb") + " KB"));
                }
            }
        }
        String brief = overview.optString("brief");
        if (!brief.isEmpty()) {
            heading(ovCol, "kmem brief");
            TextView b = text(brief, 13, MainActivity.TEXT, false);
            b.setTextIsSelectable(true);
            ovCol.addView(b);
        }
    }

    TextView row(String s) {
        TextView t = text(s, 14, MainActivity.TEXT, false);
        t.setPadding(0, a.dp(3), 0, a.dp(3));
        return t;
    }

    // ---- Tasks (read-only) ----

    void fillTasks() {
        chips.removeAllViews();
        taskCol.removeAllViews();
        taskCol.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(24));
        if (!taskError.isEmpty()) {
            taskCol.addView(text(taskError, 14, 0xFFE55B5B, false));
        }
        if (tasks == null) {
            if (taskError.isEmpty()) {
                taskCol.addView(text("Loading…", 14, MainActivity.MUTED, false));
            }
            return;
        }
        chips.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(2));
        for (String f : KmemModels.filters(tasks)) {
            TextView c = text(f, 13, f.equals(filter) ? 0xFF1F1F1D : MainActivity.TEXT, f.equals(filter));
            c.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(a.dp(16));
            bg.setColor(f.equals(filter) ? MainActivity.ACCENT : SessionBars.CARD);
            c.setBackground(bg);
            c.setOnClickListener(v -> {
                filter = f;
                fillTasks();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(a.dp(3), 0, a.dp(3), 0);
            chips.addView(c, lp);
        }
        Map<String, List<KmemModels.Task>> g = KmemModels.group(tasks, filter, query);
        if (g.isEmpty()) {
            taskCol.addView(text(tasks.isEmpty() ? "No open tasks." : "No tasks match.", 14, MainActivity.MUTED, false));
        }
        for (Map.Entry<String, List<KmemModels.Task>> e : g.entrySet()) {
            heading(taskCol, e.getKey() + " (" + e.getValue().size() + ")");
            for (KmemModels.Task t : e.getValue()) {
                LinearLayout r = new LinearLayout(a);
                r.setOrientation(LinearLayout.VERTICAL);
                r.setPadding(0, a.dp(4), 0, a.dp(4));
                r.addView(text(t.title, 14, MainActivity.TEXT, t.needsUser));
                r.addView(text(KmemModels.taskMeta(t) + (t.needsUser ? " · waiting for you" : ""), 12,
                        t.needsUser ? MainActivity.ACCENT : MainActivity.MUTED, false));
                taskCol.addView(r);
            }
        }
        if (truncated) {
            taskCol.addView(text("The list is cut off; more tasks exist than shown.", 12, MainActivity.MUTED, false));
        }
    }
}
