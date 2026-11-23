package io.github.katachinosekai.quillremote;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/**
 * The Quill operator as a bottom sheet (design §4): header with the status line, the operator chat with its own message
 * box, and a Changes tab with Undo. The phone is only the user's hands: it chats, answers plan cards and undoes; it has no
 * way to change a setting.
 */
class QuillSheet {
    final MainActivity a;
    final Remote remote;
    final BottomSheet sheet;
    final Dialog dialog;
    final ChatView chatView;
    final KmemAssistant assist;
    final LinearLayout header, chatPane, changesCol;
    final ScrollView changesScroll;
    final LinearLayout changesPane;
    TextView tabChat, tabChanges;
    int tab; // 0 chat, 1 changes
    volatile List<QuillOpModel.Change> changes = java.util.Collections.emptyList();
    boolean changesLoaded;

    QuillSheet(MainActivity a, Remote remote) {
        this.a = a;
        this.remote = remote;
        sheet = new BottomSheet(a, a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        dialog = sheet.dialog;
        chatView = new ChatView(a);
        chatView.showTimes = a.prefs.getBoolean("messageTimes", true);
        chatView.animate = a.prefs.getBoolean("animateText", true);
        a.styleChat(chatView);
        assist = new KmemAssistant(remote, chatView, true);
        assist.onModel = this::onModel;
        header = column();
        header.setPaddingRelative(a.dp(14), a.dp(2), a.dp(14), a.dp(6));
        chatPane = column();
        chatPane.addView(chatView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        changesCol = column();
        changesCol.setPaddingRelative(a.dp(14), a.dp(8), a.dp(14), a.dp(12));
        changesScroll = new ScrollView(a);
        changesScroll.addView(changesCol);
        changesPane = column();
        changesPane.addView(changesScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setOnDismissListener(d -> {
            Notifier.quillOpen = false;
            if (a.quillSheet == this) {
                a.quillSheet = null;
            }
            remote.onQuillChanges = null;
        });
        remote.onQuillChanges = () -> loadChanges();
        build();
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    void show() {
        Notifier.quillOpen = true;
        sheet.show();
        refresh();
    }

    void refresh() {
        remote.work.execute(remote::quillRefresh);
        assist.refresh(true);
        loadChanges();
    }

    /** Rebuild after rotation. */
    void layout() {
        if (a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            sheet.setFull(true);
        }
    }

    void build() {
        LinearLayout root = column();
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(a.dp(14), 0, a.dp(4), 0);
        TextView t = text("Quill operator", 17, MainActivity.TEXT, true);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(a.button("⟳", "Refresh", v -> refresh()));
        bar.addView(a.button("✕", "Close", v -> dialog.dismiss()));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout tabs = new LinearLayout(a);
        tabs.setBackgroundColor(MainActivity.SIDE);
        tabChat = tabView("Chat", 0);
        tabChanges = tabView("Changes", 1);
        tabs.addView(tabChat, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        tabs.addView(tabChanges, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout body = new FrameLayout(a);
        body.addView(chatPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(changesPane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sheet.content.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showTab();
    }

    TextView tabView(String name, int index) {
        TextView t = text(name, 14, MainActivity.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> {
            tab = index;
            showTab();
        });
        return t;
    }

    void showTab() {
        chatPane.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        changesPane.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        style(tabChat, tab == 0);
        style(tabChanges, tab == 1);
        fillHeader();
        fillChanges();
    }

    void style(TextView t, boolean on) {
        t.setTextColor(on ? MainActivity.ACCENT : MainActivity.MUTED);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** The summary, the chat model or the changes moved. */
    void onModel() {
        fillHeader();
        fillChanges();
    }

    void fillHeader() {
        header.removeAllViews();
        QuillOpModel.State s = remote.quillState;
        KmemAssistModel m = assist.model;
        if (s == null && m == null) {
            header.addView(text("Loading…", 14, MainActivity.MUTED, false));
            return;
        }
        String line = s != null ? s.line() : KmemModels.stateText(m.state);
        TextView st = text("● " + line, 14, MainActivity.TEXT, true);
        boolean live = s != null && s.dot;
        st.setCompoundDrawablePadding(0);
        header.addView(st);
        if (live) {
            st.setTextColor(0xFF6CC06C);
        }
        if (m != null && !m.model.isEmpty()) {
            header.addView(text("Model: " + m.model, 13, MainActivity.MUTED, false));
        }
        if (m != null && !m.reason.isEmpty()) {
            header.addView(text(m.reason, 13, MainActivity.MUTED, false));
        }
        if (m != null && remote.control) {
            LinearLayout row = new LinearLayout(a);
            row.setPaddingRelative(0, a.dp(6), 0, 0);
            if (m.can("start")) {
                row.addView(pill(m.button.isEmpty() ? "Start" : m.button, () -> assist.lifecycle("start", "start")));
            }
            if (m.can("restart")) {
                row.addView(pill("Restart", () -> assist.lifecycle("restart", "restart")));
            }
            if (m.can("stop")) {
                row.addView(pill("Stop", () -> Touch.show(new android.app.AlertDialog.Builder(a)
                        .setMessage("Stop the Quill operator? It runs its end-of-session check first.")
                        .setPositiveButton("Stop", (d, i) -> assist.lifecycle("stop", "stop"))
                        .setNegativeButton("Cancel", null))));
            }
            if (row.getChildCount() > 0) {
                header.addView(row);
            }
        }
    }

    // ---- Changes ----

    /** Read the change log (also when the stream's lastChange moves). */
    void loadChanges() {
        remote.work.execute(() -> {
            PhoneApi.Reply r = remote.api.quillChanges();
            if (r.ok()) {
                List<QuillOpModel.Change> l = QuillOpModel.parseChanges(ChatView.Item.plain(r.body.optJSONArray("changes")));
                remote.ui.post(() -> {
                    changes = l;
                    changesLoaded = true;
                    fillChanges();
                });
            } else if (r.code == 401 && "locked".equals(r.error())) {
                remote.expired();
            }
        });
    }

    void fillChanges() {
        if (changesCol == null) {
            return;
        }
        changesCol.removeAllViews();
        if (!changesLoaded) {
            changesCol.addView(text("Loading…", 14, MainActivity.MUTED, false));
            return;
        }
        if (changes.isEmpty()) {
            changesCol.addView(text("No settings changes yet. Settings the Quill operator changes for you show up here, with Undo.", 14, MainActivity.MUTED, false));
            return;
        }
        long now = System.currentTimeMillis();
        for (QuillOpModel.Change c : changes) {
            LinearLayout card = column();
            card.setPaddingRelative(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(a.dp(10));
            bg.setColor(SessionBars.CARD);
            card.setBackground(bg);
            card.addView(text(c.line(), 14, c.undone ? MainActivity.MUTED : MainActivity.TEXT, true));
            if (!c.why.isEmpty()) {
                card.addView(text("Request: " + c.why, 13, MainActivity.MUTED, false));
            }
            String when = c.timeMs > 0 ? TimeFmt.stamp(c.timeMs, now) : "";
            if (!when.isEmpty() || c.undone) {
                card.addView(text((c.undone ? "Undone" + (when.isEmpty() ? "" : " · ") : "") + when, 12, MainActivity.MUTED, false));
            }
            TextView undo = pill(c.undone ? "Undone" : "Undo", () -> undo(c));
            boolean on = c.undoable() && remote.control;
            undo.setEnabled(on);
            undo.setAlpha(on ? 1f : 0.4f);
            undo.setContentDescription((on ? "Undo " : "Can't undo ") + c.line());
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            up.topMargin = a.dp(6);
            card.addView(undo, up);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = a.dp(8);
            changesCol.addView(card, cp);
        }
    }

    void undo(QuillOpModel.Change c) {
        remote.work.execute(() -> {
            PhoneApi.Reply r = remote.api.quillUndo(c.n);
            if (r.ok()) {
                List<QuillOpModel.Change> l = QuillOpModel.parseChanges(ChatView.Item.plain(r.body.optJSONArray("changes")));
                remote.ui.post(() -> {
                    if (r.body.has("changes")) {
                        changes = l;
                    }
                    fillChanges();
                    remote.host.toast("Undone: " + c.line());
                });
                return;
            }
            String msg;
            if (r.code == 401 && "locked".equals(r.error())) {
                remote.expired();
                return;
            } else if (r.code == 403 && "control_off".equals(r.error())) {
                remote.control = false;
                msg = "Quill Desktop doesn't let paired devices control sessions.";
            } else if (r.code == 409 && "stale".equals(r.error())) {
                msg = "Can't undo that one now (already undone, or the setting changed again).";
                loadChanges();
            } else {
                msg = "Undo failed: " + r.error();
            }
            remote.ui.post(() -> remote.host.toast(msg));
        });
    }

    TextView pill(String label, Runnable onTap) {
        TextView c = text(label, 13, MainActivity.TEXT, false);
        c.setPaddingRelative(a.dp(12), a.dp(7), a.dp(12), a.dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(a.dp(16));
        bg.setColor(0xFF3A3A37);
        c.setBackground(bg);
        c.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(a.dp(6));
        c.setLayoutParams(lp);
        return c;
    }

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
}
