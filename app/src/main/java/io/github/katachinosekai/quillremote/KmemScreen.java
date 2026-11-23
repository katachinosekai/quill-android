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
 * The kmem screen: Overview, read-only Tasks and Memory, and the Assistant chat. Portrait shows four tabs; landscape shows
 * Overview, Tasks and Memory on the left and the assistant chat on the right. Data comes from Quill Desktop's /phone/kmem/* routes.
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
    static final int TAB_MEMORY = 2, TAB_ASSISTANT = 3;
    int tab; // 0 Overview, 1 Tasks, 2 Memory, 3 Assistant (portrait only)
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
    TextView tabOv, tabTasks, tabMem;
    // Memory tab (read-only)
    final LinearLayout memPane, memCol;
    final ScrollView memScroll;
    final EditText memSearch;
    final KmemMemoryModel.Nav memNav = new KmemMemoryModel.Nav();
    final Map<String, KmemMemoryModel.TopicView> topicCache = new java.util.HashMap<>();
    final Map<String, KmemMemoryModel.Detail> factCache = new java.util.HashMap<>();
    List<KmemMemoryModel.Topic> memTopics;
    List<KmemMemoryModel.Fact> memResults;
    boolean memTruncated;
    String memError = "", memFor = "";
    long lastTyped, lastFind, blockedUntil;
    int findSeq;
    final Runnable findRun = this::runFind;
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
        memCol = column();
        memScroll = new ScrollView(a);
        memScroll.addView(memCol);
        memSearch = new EditText(a);
        memSearch.setHint("Search memory");
        memSearch.setSingleLine(true);
        memSearch.setTextColor(MainActivity.TEXT);
        memSearch.setHintTextColor(MainActivity.MUTED);
        memSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        memSearch.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
        memSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                onMemQuery(e.toString().trim());
            }
        });
        memPane = column();
        memPane.addView(memSearch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        memPane.addView(memScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        errorLine = new TextView(a);
        errorLine.setTextColor(0xFFE55B5B);
        errorLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        errorLine.setVisibility(View.GONE);
        chatView = new ChatView(a);
        chatView.showTimes = a.prefs.getBoolean("messageTimes", true);
        chatView.animate = a.prefs.getBoolean("animateText", true);
        a.styleChat(chatView);
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
        InsetPad.install(holder);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        // Back inside the Memory tab goes up one page (fact -> topic -> start) before it closes the screen.
        dialog.setOnKeyListener((d, key, ev) -> {
            if (key == android.view.KeyEvent.KEYCODE_BACK && tab == TAB_MEMORY && memNav.canBack()) {
                if (ev.getAction() == android.view.KeyEvent.ACTION_UP) {
                    memBack();
                }
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(d -> {
            Notifier.kmemOpen = false;
            assistHeader.removeCallbacks(tick);
            memCol.removeCallbacks(findRun);
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
        for (View v : new View[]{ovScroll, taskScroll, chips, search, errorLine, assistPane, memPane}) {
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
        tabMem = tabView("Memory", TAB_MEMORY);
        tabs.addView(tabMem, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
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
        body.addView(memPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        if (tab == TAB_MEMORY && memTopics == null) {
            loadMemory(false);
        }
    }

    void showTab() {
        if (landscape && tab == TAB_ASSISTANT) {
            tab = 0;
        }
        ovScroll.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        tasksHolder.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        memPane.setVisibility(tab == TAB_MEMORY ? View.VISIBLE : View.GONE);
        assistPane.setVisibility(landscape || tab == TAB_ASSISTANT ? View.VISIBLE : View.GONE);
        style(tabOv, tab == 0);
        style(tabTasks, tab == 1);
        style(tabMem, tab == TAB_MEMORY);
        if (tabAssist != null) {
            style(tabAssist, tab == TAB_ASSISTANT);
        }
        fillAssist();
        fillOverview();
        fillTasks();
        fillMemory();
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
        if (tab == TAB_MEMORY || memTopics != null) {
            loadMemory(true);
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
            assistHeader.addView(text(KmemAssistModel.modelLine(m.model, m.modelSource), 13, MainActivity.MUTED, false));
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
                TextView rr = row(s);
                if (s.startsWith("Recycle bin:")) {
                    rr.setTextColor(MainActivity.ACCENT);
                    rr.setOnClickListener(v -> openBin());
                }
                ovCol.addView(rr);
            }
        }
        if (count == 0) {
            ovCol.addView(text("Nothing is waiting for you.", 14, MainActivity.MUTED, false));
        }
        TextView binEntry = row("Recycle bin");
        binEntry.setTextColor(MainActivity.ACCENT);
        binEntry.setOnClickListener(v -> openBin());
        ovCol.addView(binEntry);
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
                r.setOnClickListener(v -> openTask(t));
                taskCol.addView(r);
            }
        }
        if (truncated) {
            taskCol.addView(text("The list is cut off; more tasks exist than shown.", 12, MainActivity.MUTED, false));
        }
    }

    // ---- Memory (read-only: search, topics, topic view, fact view) ----

    TextView memRow(String s, int sp, int color, boolean bold, Runnable onTap) {
        TextView t = text(s, sp, color, bold);
        t.setPadding(0, a.dp(3), 0, a.dp(3));
        if (onTap != null) {
            t.setOnClickListener(v -> onTap.run());
        }
        return t;
    }

    /** One fact as a list row: kind and topic, then the first lines. Only rows with an id open the fact view. */
    View factRow(KmemMemoryModel.Fact f) {
        LinearLayout r = column();
        r.setPadding(0, a.dp(6), 0, a.dp(6));
        r.addView(text(KmemMemoryModel.factMeta(f), 12, f.openable() ? MainActivity.ACCENT : MainActivity.MUTED, false));
        r.addView(text(KmemMemoryModel.preview(f.text, 3), 14, MainActivity.TEXT, false));
        if (f.openable()) {
            r.setOnClickListener(v -> memOpen(KmemMemoryModel.Page.FACT, f.id));
        }
        return r;
    }

    View topicRow(KmemMemoryModel.Topic t) {
        LinearLayout r = column();
        r.setPadding(0, a.dp(8), 0, a.dp(8));
        r.addView(text(KmemMemoryModel.topicLine(t), 14, MainActivity.TEXT, false));
        r.setOnClickListener(v -> memOpen(KmemMemoryModel.Page.TOPIC, t.name));
        return r;
    }

    void fillMemory() {
        memCol.removeAllViews();
        memCol.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(24));
        KmemMemoryModel.Page p = memNav.current();
        memSearch.setVisibility(p.type == KmemMemoryModel.Page.HOME ? View.VISIBLE : View.GONE);
        if (!memError.isEmpty()) {
            memCol.addView(text(memError, 14, 0xFFE55B5B, false));
        }
        if (memNav.canBack()) {
            TextView back = pill("← Back", this::memBack);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = a.dp(6);
            memCol.addView(back, lp);
        }
        if (p.type == KmemMemoryModel.Page.FACT) {
            fillFact(p.key);
        } else if (p.type == KmemMemoryModel.Page.TOPIC) {
            fillTopic(p.key);
        } else {
            fillMemHome();
        }
    }

    void fillMemHome() {
        String q = memSearch.getText().toString().trim();
        if (KmemMemoryModel.validQuery(q)) {
            if (memResults == null || !q.equals(memFor)) {
                memCol.addView(text(memError.isEmpty() ? "Searching…" : "", 14, MainActivity.MUTED, false));
                return;
            }
            heading(memCol, "Results (" + memResults.size() + ")");
            if (memResults.isEmpty()) {
                memCol.addView(text("Nothing found.", 14, MainActivity.MUTED, false));
            }
            for (KmemMemoryModel.Fact f : memResults) {
                memCol.addView(factRow(f));
            }
            if (memTruncated) {
                memCol.addView(text("More results are not shown. Narrow the search.", 12, MainActivity.MUTED, false));
            }
            return;
        }
        heading(memCol, "Topics");
        if (memTopics == null) {
            if (memError.isEmpty()) {
                memCol.addView(text("Loading…", 14, MainActivity.MUTED, false));
            }
            return;
        }
        if (memTopics.isEmpty()) {
            memCol.addView(text("No topics.", 14, MainActivity.MUTED, false));
        }
        for (KmemMemoryModel.Topic t : memTopics) {
            memCol.addView(topicRow(t));
        }
    }

    void fillTopic(String name) {
        TextView h = text(name, 16, MainActivity.TEXT, true);
        h.setPadding(0, a.dp(10), 0, a.dp(2));
        memCol.addView(h);
        KmemMemoryModel.TopicView v = topicCache.get(name);
        if (v == null) {
            if (memError.isEmpty()) {
                memCol.addView(text("Loading…", 14, MainActivity.MUTED, false));
            }
            return;
        }
        if (!v.facts.isEmpty()) {
            heading(memCol, "Facts (" + v.facts.size() + ")");
        }
        for (KmemMemoryModel.Fact f : v.facts) {
            memCol.addView(factRow(f));
        }
        if (!v.sections.isEmpty()) {
            heading(memCol, "Sections");
        }
        for (KmemMemoryModel.Topic t : v.sections) {
            memCol.addView(topicRow(t));
        }
        if (v.facts.isEmpty() && v.sections.isEmpty()) {
            memCol.addView(text("Nothing in this topic.", 14, MainActivity.MUTED, false));
        }
        if (v.truncated) {
            memCol.addView(text("More facts exist than shown.", 12, MainActivity.MUTED, false));
        }
    }

    void fillFact(String id) {
        KmemMemoryModel.Detail d = factCache.get(id);
        if (d == null) {
            if (memError.isEmpty()) {
                memCol.addView(text("Loading…", 14, MainActivity.MUTED, false));
            }
            return;
        }
        TextView body = text(d.fact.text, 15, MainActivity.TEXT, false);
        body.setTextIsSelectable(true);
        body.setPadding(0, a.dp(10), 0, a.dp(6));
        memCol.addView(body);
        for (String[] r : KmemMemoryModel.detailRows(d)) {
            TextView t = text(r[0] + ": " + r[1], 13, MainActivity.MUTED, false);
            t.setPadding(0, a.dp(2), 0, a.dp(2));
            t.setTextIsSelectable(true);
            memCol.addView(t);
        }
        if (!d.supersededBy.isEmpty()) {
            memCol.addView(memRow("Newer fact: " + d.supersededBy + " →", 14, MainActivity.ACCENT, true,
                    () -> memOpen(KmemMemoryModel.Page.FACT, d.supersededBy)));
        }
        if (!d.history.isEmpty()) {
            heading(memCol, "History");
            for (KmemMemoryModel.History h : d.history) {
                TextView t = text(KmemMemoryModel.historyLine(h), 13, MainActivity.TEXT, false);
                t.setPadding(0, a.dp(2), 0, a.dp(2));
                memCol.addView(t);
            }
        }
    }

    void memOpen(int type, String key) {
        memNav.push(type, key);
        memError = "";
        fillMemory();
        memScroll.scrollTo(0, 0);
        loadPage(false);
    }

    void memBack() {
        if (memNav.back()) {
            memError = "";
            fillMemory();
            loadPage(false);
        }
    }

    /** Topics for the start page, and the open page's data (refresh = fetch again even when cached). */
    void loadMemory(boolean refresh) {
        if (refresh || memTopics == null) {
            remote.kmem(remote.api::kmemTopics, r -> {
                if (r.ok()) {
                    memTopics = KmemMemoryModel.parseTopics(r.body.optJSONArray("topics"));
                    if (memNav.current().type == KmemMemoryModel.Page.HOME) {
                        memError = "";
                    }
                } else if (r.code != 401 && memNav.current().type == KmemMemoryModel.Page.HOME) {
                    memError = KmemMemoryModel.errorLine(r.code, r.error());
                }
                fillMemory();
            });
        }
        loadPage(refresh);
    }

    void loadPage(boolean refresh) {
        KmemMemoryModel.Page p = memNav.current();
        if (p.type == KmemMemoryModel.Page.TOPIC && (refresh || !topicCache.containsKey(p.key))) {
            remote.kmem(() -> remote.api.kmemGet(p.key), r -> {
                if (r.ok()) {
                    topicCache.put(p.key, KmemMemoryModel.parseTopicView(r.body));
                    memError = "";
                } else if (r.code != 401) {
                    memError = KmemMemoryModel.errorLine(r.code, r.error());
                }
                fillMemory();
            });
        } else if (p.type == KmemMemoryModel.Page.FACT && (refresh || !factCache.containsKey(p.key))) {
            remote.kmem(() -> remote.api.kmemFact(p.key), r -> {
                KmemMemoryModel.Detail d = r.ok() ? KmemMemoryModel.parseDetail(r.body) : null;
                if (d != null) {
                    factCache.put(p.key, d);
                    memError = "";
                } else if (r.code != 401) {
                    memError = KmemMemoryModel.errorLine(r.code, r.error());
                }
                fillMemory();
            });
        }
    }

    // search: debounced, at most one find per second, 429 waits for Retry-After

    void onMemQuery(String q) {
        lastTyped = System.currentTimeMillis();
        memCol.removeCallbacks(findRun);
        findSeq++;
        if (!KmemMemoryModel.validQuery(q)) {
            memResults = null;
            memFor = "";
            memError = "";
            fillMemory();
            return;
        }
        fillMemory();
        memCol.postDelayed(findRun, KmemMemoryModel.findDelayMs(lastTyped, lastTyped, lastFind, blockedUntil));
    }

    void runFind() {
        String q = memSearch.getText().toString().trim();
        if (!KmemMemoryModel.validQuery(q)) {
            return;
        }
        long now = System.currentTimeMillis();
        long wait = KmemMemoryModel.findDelayMs(now, lastTyped, lastFind, blockedUntil);
        if (wait > 0) {
            memCol.postDelayed(findRun, wait);
            return;
        }
        lastFind = now;
        int seq = ++findSeq;
        remote.kmem(() -> remote.api.kmemFind(q), r -> {
            if (seq != findSeq) {
                return; // typed on meanwhile; a newer find is scheduled
            }
            if (r.ok()) {
                memResults = KmemMemoryModel.parseFacts(r.body.optJSONArray("facts"));
                memTruncated = r.body.optBoolean("truncated");
                memFor = q;
                memError = "";
            } else if (r.code == 429) {
                blockedUntil = System.currentTimeMillis() + PhoneApi.retryAfterMs(r.body == null ? 1 : r.body.optInt("retryAfter", 1));
                memCol.postDelayed(findRun, KmemMemoryModel.findDelayMs(System.currentTimeMillis(), lastTyped, lastFind, blockedUntil));
            } else if (r.code != 401) {
                memError = KmemMemoryModel.errorLine(r.code, r.error());
                memResults = null;
            }
            fillMemory();
        });
    }

    // ---- Phase 4: task detail, Done/Release, recycle bin, Restore ----

    android.app.AlertDialog taskDlg, binDlg;
    LinearLayout binCol;
    List<KmemWriteModel.BinItem> binItems;
    boolean binTruncated;
    String binError = "";

    void toast(String s) {
        android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_LONG).show();
    }

    ScrollView scrolled(View v) {
        ScrollView sv = new ScrollView(a);
        sv.addView(v);
        return sv;
    }

    void openTask(KmemModels.Task t) {
        LinearLayout col = column();
        col.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(6));
        col.addView(text(t.title, 15, MainActivity.TEXT, true));
        col.addView(text(KmemModels.taskMeta(t), 12, MainActivity.MUTED, false));
        TextView body = text("Loading…", 13, MainActivity.TEXT, false);
        body.setTextIsSelectable(true);
        body.setPadding(0, a.dp(10), 0, a.dp(10));
        col.addView(body);
        boolean doneOk = t.can("done"), relOk = t.can("release");
        if (remote.control && (doneOk || relOk)) {
            LinearLayout row = new LinearLayout(a);
            if (doneOk) {
                row.addView(pill("Done", () -> askResult(t)));
            }
            if (relOk) {
                row.addView(pill("Release", () -> runFlow("task", t.id, "release", new JSONObject(), "Release task [" + t.id + "]")));
            }
            col.addView(row);
        } else if (!remote.control && (doneOk || relOk)) {
            col.addView(text(KmemWriteModel.HINT_CONTROL_OFF, 12, MainActivity.MUTED, false));
        }
        if (taskDlg != null) {
            taskDlg.dismiss();
        }
        taskDlg = new android.app.AlertDialog.Builder(a).setView(scrolled(col)).setPositiveButton("Close", null).show();
        remote.kmem(() -> remote.api.kmemTask(t.id), r -> {
            if (r.ok()) {
                String x = r.body.optString("text");
                body.setText(x.isEmpty() ? "(no text)" : x + (r.body.optBoolean("truncated") ? "\n\n(cut off)" : ""));
            } else {
                body.setText(r.code == 404 ? "This task no longer exists." : KmemModels.errorLine(r.code, r.error()));
            }
        });
    }

    /** Done needs a result text of 1-300 characters (decision f2b8d3a). */
    void askResult(KmemModels.Task t) {
        EditText in = new EditText(a);
        in.setHint("Result (1-300 characters)");
        in.setMinLines(2);
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(KmemWriteModel.RESULT_MAX * 2)});
        TextView count = text(KmemWriteModel.counter(""), 12, MainActivity.MUTED, false);
        LinearLayout col = column();
        col.setPadding(a.dp(20), a.dp(10), a.dp(20), 0);
        col.addView(text("What was the result of [" + t.id + "] " + t.title + "?", 14, MainActivity.TEXT, false));
        col.addView(in);
        col.addView(count);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(a).setTitle("Mark task done").setView(col)
                .setPositiveButton("Next", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> {
            android.widget.Button ok = d.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            ok.setEnabled(false);
            in.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int st, int c, int af) {
                }

                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                public void afterTextChanged(Editable e) {
                    String v = KmemWriteModel.validResult(e.toString());
                    count.setText(KmemWriteModel.counter(e.toString()));
                    count.setTextColor(v == null && e.length() > 0 ? 0xFFD05050 : MainActivity.MUTED);
                    ok.setEnabled(v != null);
                }
            });
            ok.setOnClickListener(v -> {
                String res = KmemWriteModel.validResult(in.getText().toString());
                if (res != null) {
                    d.dismiss();
                    runFlow("task", t.id, "done", PhoneApi.obj("result", res), "Mark task [" + t.id + "] done");
                }
            });
        });
        d.show();
    }

    /** prepare -> confirm dialog (summary + commands) -> commit -> refresh. Nothing is written before Confirm. */
    void runFlow(String kind, String id, String action, JSONObject body, String title) {
        remote.kmem(() -> remote.api.kmemPrepare(kind, id, action, body), r -> {
            if (!r.ok()) {
                failed(r);
                return;
            }
            String confirm = r.body.optString("confirm");
            new android.app.AlertDialog.Builder(a).setTitle(title)
                    .setMessage(KmemWriteModel.confirmText(r.body))
                    .setPositiveButton("Confirm", (d, w) -> remote.kmem(() -> remote.api.kmemCommit(kind, id, action, confirm), c -> {
                        if (c.ok()) {
                            String out = KmemWriteModel.brief(c.body.optString("output"));
                            toast(out.isEmpty() ? "Done." : out);
                            if (taskDlg != null) {
                                taskDlg.dismiss();
                            }
                            refresh();
                            if (binDlg != null && binDlg.isShowing()) {
                                loadBin();
                            }
                        } else {
                            failed(c);
                        }
                    }))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    void failed(PhoneApi.Reply r) {
        toast(KmemWriteModel.failText(r.code, r.error(), r.detail()));
        if (KmemWriteModel.refreshAfter(r.code, r.error())) {
            if (taskDlg != null) {
                taskDlg.dismiss();
            }
            refresh();
            if (binDlg != null && binDlg.isShowing()) {
                loadBin();
            }
        }
    }

    void openBin() {
        if (binDlg != null && binDlg.isShowing()) {
            return;
        }
        binCol = column();
        binCol.setPadding(a.dp(18), a.dp(10), a.dp(18), a.dp(6));
        binItems = null;
        binError = "";
        fillBin();
        binDlg = new android.app.AlertDialog.Builder(a).setTitle("Recycle bin").setView(scrolled(binCol))
                .setPositiveButton("Close", null).show();
        loadBin();
    }

    void loadBin() {
        remote.kmem(remote.api::kmemBin, r -> {
            if (r.ok()) {
                binItems = KmemWriteModel.parseBin(r.body.optJSONArray("items"));
                binTruncated = r.body.optBoolean("truncated");
                binError = "";
            } else if (r.code != 401) {
                binError = KmemModels.errorLine(r.code, r.error());
            }
            fillBin();
        });
    }

    void fillBin() {
        if (binCol == null) {
            return;
        }
        binCol.removeAllViews();
        binCol.addView(text(KmemWriteModel.NOTE_BIN, 12, MainActivity.MUTED, false));
        if (!remote.control) {
            binCol.addView(text(KmemWriteModel.HINT_CONTROL_OFF, 12, MainActivity.MUTED, false));
        }
        if (!binError.isEmpty()) {
            binCol.addView(text(binError, 13, MainActivity.ACCENT, false));
        }
        if (binItems == null) {
            if (binError.isEmpty()) {
                binCol.addView(text("Loading…", 14, MainActivity.MUTED, false));
            }
            return;
        }
        if (binItems.isEmpty()) {
            binCol.addView(text("The recycle bin is empty.", 14, MainActivity.MUTED, false));
        }
        for (KmemWriteModel.BinItem b : binItems) {
            LinearLayout r = column();
            r.setPadding(0, a.dp(8), 0, a.dp(8));
            r.addView(text(b.label(), 14, MainActivity.TEXT, true));
            String tp = KmemWriteModel.binTopic(b);
            if (!tp.isEmpty()) {
                r.addView(text(tp, 12, MainActivity.MUTED, false));
            }
            r.addView(text(KmemWriteModel.binMeta(b), 12, MainActivity.MUTED, false));
            if (!b.reason.isEmpty()) {
                r.addView(text("Reason: " + b.reason, 12, MainActivity.MUTED, false));
            }
            if (remote.control) {
                r.addView(pill("Restore", () -> runFlow("bin", b.id, "restore", new JSONObject(), "Restore " + b.label())));
            }
            binCol.addView(r);
        }
        if (binTruncated) {
            binCol.addView(text("The list is cut off; more items exist than shown.", 12, MainActivity.MUTED, false));
        }
    }
}
