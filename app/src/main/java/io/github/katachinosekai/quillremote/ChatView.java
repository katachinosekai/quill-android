package io.github.katachinosekai.quillremote;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class ChatView extends LinearLayout {
    interface Actions {
        void send(String text);

        void answer(String requestId, String action, JSONObject extra);

        void interrupt();
    }

    static final class Item {
        int seq;
        String kind;
        String text;
        boolean streaming;
        String toolName;
        String summary;
        String result;
        boolean resultError;
        boolean hasResult;
        String requestId;
        String state;
        String detail;
        boolean canAllowAlways = true;
        JSONArray questions;
        JSONArray choices;
        boolean choicesOpen;
        JSONObject diff;
        int rev;

        static Item from(JSONObject o) {
            Item it = new Item();
            it.seq = o.optInt("seq");
            it.rev = o.optInt("rev");
            it.kind = o.optString("kind");
            it.text = o.optString("text");
            it.streaming = o.optBoolean("streaming");
            JSONObject tool = o.optJSONObject("tool");
            it.toolName = tool != null ? tool.optString("name") : o.optString("toolName");
            it.summary = tool != null ? tool.optString("summary") : o.optString("summary");
            it.result = tool != null ? tool.optString("result") + (tool.optBoolean("resultTruncated") ? "\n…" : "") : o.optString("result");
            it.resultError = tool != null ? tool.optBoolean("isError") : o.optBoolean("resultError");
            it.hasResult = tool != null ? tool.optBoolean("done") : o.optBoolean("hasResult");
            JSONObject req = o.optJSONObject("request");
            it.requestId = req != null ? req.optString("id") : o.optString("requestId");
            it.state = req != null ? req.optString("state") : o.optString("state");
            it.detail = req != null ? req.optString("detail") : "";
            if (req != null && req.has("canAllowAlways")) {
                it.canAllowAlways = req.optBoolean("canAllowAlways");
            }
            if (req != null && !req.optString("tool").isEmpty() && it.toolName.isEmpty()) {
                it.toolName = req.optString("tool");
            }
            it.questions = req != null && req.has("questions") ? req.optJSONArray("questions") : o.optJSONArray("questions");
            it.choices = o.optJSONArray("choices");
            it.choicesOpen = o.optBoolean("choicesOpen", it.choices != null);
            it.diff = o.optJSONObject("diff");
            return it;
        }

        String sig() {
            return kind + "|" + rev + "|" + text.length() + "|" + text.hashCode() + "|" + streaming + "|" + hasResult + "|" + state + "|" + result.length() + "|" + choicesOpen;
        }
    }

    static final int CARD = 0xFF30302D;
    static final int USER_BG = 0xFF3A3A37;
    static final int BLUE = 0xFF5BA8F5;
    static final int RED = 0xFFE55B5B;
    static final int GREEN = 0xFF6CC06C;

    final ScrollView scroll;
    final LinearLayout list;
    final EditText box;
    final TextView send;
    final TextView status;
    TextView conn;
    Actions actions;
    float sp = 15f;
    final Map<Integer, View> views = new HashMap<>();
    final Map<Integer, String> sigs = new HashMap<>();
    final Set<Integer> expanded = new LinkedHashSet<>();
    final Set<String> picked = new LinkedHashSet<>();
    List<Item> items = new ArrayList<>();
    boolean busy;

    ChatView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(MainActivity.BG);
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(dp(14), dp(10), dp(14), dp(16));
        scroll.addView(list);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(c);
        status.setTextColor(MainActivity.MUTED);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(dp(16), dp(2), dp(16), dp(2));
        status.setVisibility(GONE);
        conn = new TextView(c);
        conn.setTextColor(MainActivity.TEXT);
        conn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        conn.setBackgroundColor(0xFF5A2A22);
        conn.setPadding(dp(16), dp(6), dp(16), dp(6));
        conn.setVisibility(GONE);
        addView(conn, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(dp(10), dp(6), dp(10), dp(10));
        box = new EditText(c);
        box.setTextColor(MainActivity.TEXT);
        box.setHintTextColor(MainActivity.MUTED);
        box.setHint("Message Quill");
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.setMaxLines(8);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        box.setImeOptions(EditorInfo.IME_ACTION_SEND);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(round(USER_BG, 14));
        box.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN && !e.isShiftPressed()) {
                submit();
                return true;
            }
            return false;
        });
        box.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });
        bar.addView(box, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        send = new TextView(c);
        send.setText("➤");
        send.setContentDescription("Send");
        send.setTextColor(MainActivity.ACCENT);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        send.setGravity(Gravity.CENTER);
        send.setBackground(round(0x30D97757, 12));
        send.setOnClickListener(v -> {
            if (busy && box.getText().toString().trim().isEmpty() && actions != null) {
                actions.interrupt();
            } else {
                submit();
            }
        });
        LayoutParams sl = new LayoutParams(dp(48), dp(48));
        sl.leftMargin = dp(8);
        bar.addView(send, sl);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    void submit() {
        String t = box.getText().toString();
        if (t.trim().isEmpty() || actions == null) {
            return;
        }
        actions.send(t);
        box.setText("");
    }

    void setBusy(boolean b, String line) {
        busy = b;
        send.setText(b && box.getText().toString().trim().isEmpty() ? "■" : "➤");
        send.setContentDescription(b ? "Stop" : "Send");
        status.setText(line == null ? "" : line);
        status.setVisibility(line == null || line.isEmpty() ? GONE : VISIBLE);
    }

    void setConnection(String line) {
        conn.setText(line == null ? "" : line);
        conn.setVisibility(line == null || line.isEmpty() ? GONE : VISIBLE);
    }

    void clear() {
        list.removeAllViews();
        views.clear();
        sigs.clear();
        expanded.clear();
        items = new ArrayList<>();
    }

    void setItems(List<Item> next) {
        boolean atEnd = !scroll.canScrollVertically(1);
        Map<Integer, Item> bySeq = new HashMap<>();
        for (Item it : next) {
            bySeq.put(it.seq, it);
        }
        for (Integer seq : new ArrayList<>(views.keySet())) {
            if (!bySeq.containsKey(seq)) {
                list.removeView(views.remove(seq));
                sigs.remove(seq);
            }
        }
        Item lastAssistant = latestAnswerable(next);
        for (int i = 0; i < next.size(); i++) {
            Item it = next.get(i);
            String sig = it.sig() + (it == lastAssistant ? "|choices" : "") + (expanded.contains(it.seq) ? "|open" : "");
            View old = views.get(it.seq);
            if (old != null && sig.equals(sigs.get(it.seq))) {
                if (list.indexOfChild(old) != i) {
                    list.removeView(old);
                    list.addView(old, i);
                }
                continue;
            }
            View v = render(it, it == lastAssistant);
            if (old != null) {
                int at = list.indexOfChild(old);
                list.removeView(old);
                list.addView(v, Math.min(at, i));
            } else {
                list.addView(v, Math.min(i, list.getChildCount()));
            }
            views.put(it.seq, v);
            sigs.put(it.seq, sig);
        }
        items = next;
        if (atEnd) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    static Item latestAnswerable(List<Item> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if ("user".equals(it.kind) || "plan".equals(it.kind)) {
                return null;
            }
            if ("assistant".equals(it.kind) && !it.text.trim().isEmpty()) {
                return it.streaming ? null : it;
            }
        }
        return null;
    }

    View spaced(View v, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        v.setLayoutParams(lp);
        return v;
    }

    View render(Item it, boolean answerable) {
        switch (it.kind) {
            case "user":
                return user(it);
            case "assistant":
                return assistant(it, answerable);
            case "tool":
                return tool(it);
            case "permission":
                return permission(it);
            case "plan":
                return plan(it);
            case "question":
                return question(it);
            case "thinking":
                return thinking(it);
            case "rotate":
                return rotate(it);
            case "error": {
                TextView t = label(it.text, RED, 13);
                t.setTextIsSelectable(true);
                return spaced(t, 8);
            }
            default:
                TextView t = label(it.text, MainActivity.MUTED, 13);
                return spaced(t, 8);
        }
    }

    TextView label(String s, int color, float size) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        return t;
    }

    View user(Item it) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.END);
        TextView t = label(it.text, MainActivity.TEXT, sp);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(round(USER_BG, 14));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(48);
        row.addView(t, lp);
        return spaced(row, 16);
    }

    View assistant(Item it, boolean answerable) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        MarkdownView md = new MarkdownView(getContext());
        md.sp = sp;
        md.setMarkdown(it.text);
        col.addView(md);
        String[] opts = null;
        if (it.choices != null) {
            if (it.choicesOpen) {
                opts = new String[it.choices.length()];
                for (int k = 0; k < opts.length; k++) {
                    opts[k] = it.choices.optString(k);
                }
            }
        } else if (answerable) {
            opts = Choices.parse(lastLine(it.text));
        }
        if (opts != null && !picked.contains(it.seq + ":" + it.text.hashCode())) {
            final boolean server = it.choices != null;
            LinearLayout row = new LinearLayout(getContext());
            row.setPadding(0, dp(10), 0, 0);
            for (int k = 0; k < opts.length; k++) {
                final String o = opts[k];
                final int index = k;
                TextView b = button(o.length() > 60 ? o.substring(0, 59) + "…" : o, MainActivity.ACCENT);
                b.setOnClickListener(v -> {
                    if (actions == null) {
                        return;
                    }
                    picked.add(it.seq + ":" + it.text.hashCode());
                    if (server) {
                        JSONObject x = new JSONObject();
                        try {
                            x.put("seq", it.seq);
                            x.put("index", index);
                        } catch (org.json.JSONException ignored) {
                        }
                        actions.answer("seq:" + it.seq, "choice", x);
                    } else {
                        actions.send(o);
                    }
                    row.setVisibility(GONE);
                });
                LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(8);
                row.addView(b, lp);
            }
            col.addView(row);
        }
        return spaced(col, 14);
    }

    static String lastLine(String text) {
        String t = text.replaceAll("[\\s]+$", "");
        int cut = t.lastIndexOf('\n');
        return t.substring(cut + 1);
    }

    TextView button(String s, int color) {
        TextView b = label(s, MainActivity.TEXT, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(40));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = round((color & 0x00FFFFFF) | 0x24000000, 10);
        g.setStroke(dp(1), color);
        b.setBackground(g);
        return b;
    }

    View tool(Item it) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        String dot = !it.hasResult ? "◌ " : it.resultError ? "✕ " : "✓ ";
        TextView head = label(dot + it.toolName + (it.summary.isEmpty() ? "" : "  " + it.summary), it.resultError ? RED : MainActivity.MUTED, 13);
        head.setTypeface(Typeface.MONOSPACE);
        head.setSingleLine(!expanded.contains(it.seq));
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(head);
        if (expanded.contains(it.seq) && it.diff != null) {
            col.addView(diffView(it.diff));
        }
        if (expanded.contains(it.seq) && !it.result.isEmpty()) {
            TextView r = label(it.result, MainActivity.TEXT, 12);
            r.setTypeface(Typeface.MONOSPACE);
            r.setTextIsSelectable(true);
            r.setPadding(dp(10), dp(8), dp(10), dp(8));
            r.setBackground(round(MarkdownView.CODE_BG, 8));
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            col.addView(r, lp);
        }
        col.setOnClickListener(v -> {
            if (!expanded.remove(it.seq)) {
                expanded.add(it.seq);
            }
            sigs.remove(it.seq);
            setItems(items);
        });
        return spaced(col, 6);
    }

    LinearLayout card(String title, int accent) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = round(CARD, 12);
        g.setStroke(dp(1), accent);
        c.setBackground(g);
        TextView t = label(title, MainActivity.TEXT, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        return c;
    }

    LinearLayout buttons(LinearLayout card) {
        LinearLayout row = new LinearLayout(getContext());
        row.setPadding(0, dp(10), 0, 0);
        card.addView(row);
        return row;
    }

    void addButton(LinearLayout row, String text, int color, View.OnClickListener l) {
        TextView b = button(text, color);
        b.setOnClickListener(l);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        row.addView(b, lp);
    }

    boolean pending(Item it) {
        return it.state.isEmpty() || "pending".equals(it.state);
    }

    static String stateLabel(String s) {
        switch (s) {
            case "allowed": return "allowed";
            case "allowedAlways": return "always allowed";
            case "denied": return "denied";
            case "cancelled": return "cancelled";
            case "autoAllowed": return "allowed automatically";
            case "ruleAllowed": return "allowed by a rule";
            case "retryAllowed": return "allowed on retry";
            case "planAsked": return "asked in the plan";
            default: return s;
        }
    }

    View permission(Item it) {
        LinearLayout c = card(pending(it) ? "Allow " + it.toolName + "?" : it.toolName + ": " + stateLabel(it.state), pending(it) ? MainActivity.ACCENT : 0xFF444440);
        String what = !it.detail.isEmpty() ? it.detail : it.summary;
        if (!what.isEmpty()) {
            TextView s = label(what, MainActivity.TEXT, 13);
            s.setTypeface(Typeface.MONOSPACE);
            s.setTextIsSelectable(true);
            s.setPadding(0, dp(6), 0, 0);
            c.addView(s);
        }
        if (pending(it)) {
            LinearLayout row = buttons(c);
            addButton(row, "Allow", GREEN, v -> act(it, "allow", null));
            if (it.canAllowAlways) {
                addButton(row, "Always", BLUE, v -> act(it, "allowAlways", null));
            }
            addButton(row, "Deny", RED, v -> act(it, "deny", null));
        }
        return spaced(c, 12);
    }

    View plan(Item it) {
        LinearLayout c = card(pending(it) ? planTitle(it.text) : "Plan: " + stateLabel(it.state), pending(it) ? MainActivity.ACCENT : 0xFF444440);
        MarkdownView md = new MarkdownView(getContext());
        md.sp = sp - 1;
        md.setMarkdown(it.text);
        md.setPadding(0, dp(8), 0, 0);
        c.addView(md);
        if (pending(it)) {
            EditText fb = new EditText(getContext());
            fb.setHint("Changes (optional)");
            fb.setTextColor(MainActivity.TEXT);
            fb.setHintTextColor(MainActivity.MUTED);
            fb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            c.addView(fb);
            LinearLayout row = buttons(c);
            addButton(row, "Approve", GREEN, v -> act(it, "approvePlan", extraText(fb)));
            addButton(row, "Keep planning", MainActivity.ACCENT, v -> act(it, "rejectPlan", extraText(fb)));
        }
        return spaced(c, 12);
    }

    static String planTitle(String text) {
        String last = lastLine(text).replace("**", "").replace("__", "").replace("`", "").replaceAll("^[#>*\\-_ ]+", "").trim();
        return last.contains("?") && last.length() <= 300 ? last : "Approve this plan?";
    }

    JSONObject extraText(EditText e) {
        JSONObject o = new JSONObject();
        try {
            String t = e.getText().toString().trim();
            if (!t.isEmpty()) {
                o.put("text", t);
            }
        } catch (org.json.JSONException ignored) {
        }
        return o;
    }

    View question(Item it) {
        LinearLayout c = card(pending(it) ? "Claude asks" : "Answered", pending(it) ? MainActivity.ACCENT : 0xFF444440);
        JSONArray qs = it.questions == null ? new JSONArray() : it.questions;
        Map<String, Set<String>> chosen = new HashMap<>();
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i);
            if (q == null) {
                continue;
            }
            String qtext = q.optString("question");
            boolean multi = q.optBoolean("multiSelect");
            if (!q.optString("header").isEmpty()) {
                TextView h = label(q.optString("header").toUpperCase(java.util.Locale.ROOT), MainActivity.MUTED, 11);
                h.setPadding(0, dp(10), 0, 0);
                c.addView(h);
            }
            Set<String> sel = new LinkedHashSet<>();
            chosen.put(qtext, sel);
            TextView qt = label(qtext, MainActivity.TEXT, 15);
            qt.setPadding(0, dp(10), 0, dp(4));
            c.addView(qt);
            JSONArray opts = q.optJSONArray("options");
            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(VERTICAL);
            List<TextView> btns = new ArrayList<>();
            for (int k = 0; opts != null && k < opts.length(); k++) {
                JSONObject o = opts.optJSONObject(k);
                String lab = o == null ? opts.optString(k) : o.optString("label");
                String desc = o == null ? "" : o.optString("description");
                TextView b = button(lab + (desc.isEmpty() ? "" : "\n" + desc), 0xFF666660);
                b.setTypeface(Typeface.DEFAULT);
                btns.add(b);
                b.setOnClickListener(v -> {
                    if (!pending(it)) {
                        return;
                    }
                    if (!multi) {
                        sel.clear();
                    }
                    if (!sel.remove(lab)) {
                        sel.add(lab);
                    }
                    for (int j = 0; j < btns.size(); j++) {
                        TextView x = btns.get(j);
                        String xl = x.getText().toString().split("\n")[0];
                        GradientDrawable g = round(sel.contains(xl) ? 0x40D97757 : 0x24666660, 10);
                        g.setStroke(dp(1), sel.contains(xl) ? MainActivity.ACCENT : 0xFF666660);
                        x.setBackground(g);
                    }
                });
                LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                col.addView(b, lp);
            }
            c.addView(col);
        }
        if (pending(it)) {
            LinearLayout row = buttons(c);
            addButton(row, "Send answers", MainActivity.ACCENT, v -> {
                JSONObject extra = new JSONObject();
                try {
                    JSONObject answers = new JSONObject();
                    for (Map.Entry<String, Set<String>> e : chosen.entrySet()) {
                        answers.put(e.getKey(), new JSONArray(e.getValue()));
                    }
                    extra.put("answers", answers);
                } catch (org.json.JSONException ignored) {
                }
                act(it, "answer", extra);
            });
        }
        return spaced(c, 12);
    }

    View diffView(JSONObject d) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        String path = d.optString("path");
        if (!path.isEmpty()) {
            sb.append(path).append('\n');
            sb.setSpan(new android.text.style.ForegroundColorSpan(MainActivity.MUTED), 0, sb.length(), 0);
        }
        JSONArray lines = d.optJSONArray("lines");
        for (int i = 0; lines != null && i < lines.length(); i++) {
            JSONObject l = lines.optJSONObject(i);
            if (l == null) {
                continue;
            }
            String op = l.optString("op");
            int s = sb.length();
            String mark = "add".equals(op) || "+".equals(op) ? "+ " : "del".equals(op) || "-".equals(op) ? "- " : "  ";
            sb.append(mark).append(l.optString("text")).append('\n');
            int color = mark.startsWith("+") ? GREEN : mark.startsWith("-") ? RED : MainActivity.MUTED;
            sb.setSpan(new android.text.style.ForegroundColorSpan(color), s, sb.length(), 0);
        }
        if (d.optBoolean("truncated")) {
            sb.append("…");
        }
        TextView t = label("", MainActivity.TEXT, 12);
        t.setText(sb);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setHorizontallyScrolling(true);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(getContext());
        h.setBackground(round(MarkdownView.CODE_BG, 8));
        h.addView(t);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        h.setLayoutParams(lp);
        return h;
    }

    View thinking(Item it) {
        boolean open = expanded.contains(it.seq);
        TextView t = label(open ? it.text : "∴ Thinking" + (it.streaming ? "…" : ""), MainActivity.MUTED, 13);
        t.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        t.setOnClickListener(v -> {
            if (!expanded.remove(it.seq)) {
                expanded.add(it.seq);
            }
            sigs.remove(it.seq);
            setItems(items);
        });
        return spaced(t, 8);
    }

    View rotate(Item it) {
        LinearLayout c = card(it.text.isEmpty() ? "Rotate this session?" : it.text, pending(it) ? MainActivity.ACCENT : 0xFF444440);
        if (pending(it)) {
            LinearLayout row = buttons(c);
            addButton(row, "Rotate", MainActivity.ACCENT, v -> act(it, "rotate", null));
            addButton(row, "Not now", 0xFF666660, v -> act(it, "dismiss", null));
        }
        return spaced(c, 12);
    }

    void act(Item it, String action, JSONObject extra) {
        if (actions != null) {
            actions.answer(it.requestId, action, extra == null ? new JSONObject() : extra);
        }
    }

    static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }
}
