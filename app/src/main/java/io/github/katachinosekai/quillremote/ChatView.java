package io.github.katachinosekai.quillremote;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
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

        void queueOp(String op, int index, String text);

        void resume();

        /** Typing in the message box (soft keyboards don't reach Activity.onUserInteraction); keeps the device unlock alive. */
        default void typing() {
        }
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
        /** Plan approval options (request.choices) and the recommended index (request.recommended, -1 = none). */
        JSONArray planChoices;
        int planRecommended = -1;
        /** A plan's open questions (request.questions on plan items): [{id, text, kind yesno|single|multi|text, options, recommended}]. */
        JSONArray planQuestions;
        /** request.approval (Quill Desktop with approval options), null on older builds. */
        QuestionCards.Approval planApproval;
        boolean choicesOpen;
        JSONObject diff;
        int rev;
        /** When the message was said (Quill Desktop 0.1.22+ "timeMs"), 0 = unknown. */
        long timeMs;

        static Item from(JSONObject o) {
            Item it = new Item();
            it.seq = o.optInt("seq");
            it.rev = o.optInt("rev");
            it.timeMs = Math.max(0, o.optLong("timeMs"));
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
            if (req != null && "plan".equals(it.kind)) {
                it.planChoices = req.optJSONArray("choices");
                it.planQuestions = req.optJSONArray("questions");
                it.planApproval = QuestionCards.parseApproval(Item.plain(req.optJSONObject("approval")));
                it.planRecommended = req.has("recommended") ? req.optInt("recommended", -1) : -1;
            }
            it.choicesOpen = o.optBoolean("choicesOpen", it.choices != null);
            it.diff = o.optJSONObject("diff");
            return it;
        }

        /** Plain Maps and Lists for JSON values (QuestionCards reads those), null for JSON null. */
        static Object plain(Object o) {
            if (o instanceof JSONObject) {
                Map<String, Object> m = new HashMap<>();
                JSONObject j = (JSONObject) o;
                for (java.util.Iterator<String> k = j.keys(); k.hasNext(); ) {
                    String key = k.next();
                    m.put(key, plain(j.opt(key)));
                }
                return m;
            }
            if (o instanceof JSONArray) {
                List<Object> l = new ArrayList<>();
                JSONArray a = (JSONArray) o;
                for (int i = 0; i < a.length(); i++) {
                    l.add(plain(a.opt(i)));
                }
                return l;
            }
            return o == JSONObject.NULL ? null : o;
        }

        /** A pending plan card only changes with its question set (text, approval, questions), not with every revision. */
        String sig() {
            if ("plan".equals(kind) && (state.isEmpty() || "pending".equals(state))) {
                return "plan|pending|" + QuestionCards.setKey(text, planApproval, QuestionCards.parseQuestions(plain(planQuestions)));
            }
            return kind + "|" + rev + "|" + text.length() + "|" + text.hashCode() + "|" + streaming + "|" + hasResult + "|" + state + "|" + result.length() + "|" + choicesOpen;
        }
    }

    static final int CARD = 0xFF30302D;
    static final int USER_BG = 0xFF3A3A37;
    static final String VPN_HELP = "https://github.com/katachinosekai/quill-android#away-from-home";
    boolean showTimes = true;
    /** ⚙ Animate new text (pref animateText, like Quill Desktop): finished paragraphs fade in, the last one is revealed word by word. */
    boolean animate = true;
    /** ⚙ Text animation speed (pref animateSpeed): slow, normal, fast or instant; only matters while animate is on. */
    String animateSpeed = "normal";
    /** Live views of replies that are still streaming, by seq (updated in place instead of re-rendered). */
    final Map<Integer, StreamView> streams = new HashMap<>();
    static final int BLUE = 0xFF5BA8F5;
    static final int RED = 0xFFE55B5B;
    static final int GREEN = 0xFF6CC06C;

    final ScrollView scroll;
    final LinearLayout list;
    final EditText box;
    final TextView send;
    final TextView status;
    TextView conn;
    final LinearLayout queueBox;
    final LinearLayout pauseBar;
    final TextView pauseText;
    Actions actions;
    /** Opens file links (null = file links stay plain text); set for the session chat only. */
    LinkActions.Host linkHost;
    float sp = 15f;
    final Map<Integer, View> views = new HashMap<>();
    final Map<Integer, String> sigs = new HashMap<>();
    final Set<Integer> expanded = new LinkedHashSet<>();
    final Set<String> picked = new LinkedHashSet<>();
    List<Item> items = new ArrayList<>();
    boolean busy;
    boolean follow = true;
    TextView latest;

    /** Show ↓ only when the newest message is out of view by more than a little. */
    void syncLatest() {
        if (latest == null) {
            return;
        }
        int below = col.getHeight() - scroll.getHeight() - scroll.getScrollY();
        latest.setVisibility(below > dp(80) ? VISIBLE : GONE);
    }
    static final int PAGE = 50;
    int shownLimit = PAGE;
    final TextView earlier;
    final LinearLayout col;
    float scale = 1f;
    boolean centerChat;
    boolean centerInput;
    boolean centerStatus;
    android.view.ScaleGestureDetector pinch;
    boolean touching;
    long touchEnd;

    ChatView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(MainActivity.BG);
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(dp(14), dp(10), dp(14), dp(16));
        col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        earlier = new TextView(c);
        earlier.setTextColor(MainActivity.ACCENT);
        earlier.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        earlier.setGravity(Gravity.CENTER);
        earlier.setPadding(dp(14), dp(12), dp(14), dp(4));
        earlier.setVisibility(GONE);
        earlier.setOnClickListener(v -> {
            int before = col.getHeight();
            int y = scroll.getScrollY();
            follow = false;
            shownLimit += PAGE;
            setItems(items);
            col.post(() -> scroll.scrollTo(0, y + col.getHeight() - before));
        });
        col.addView(earlier, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        col.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        scroll.addView(col);
        // ↓ jumps to the newest message; shown while the chat is scrolled up (like Quill Desktop and claude.ai).
        android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
        frame.addView(scroll, new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        latest = new TextView(c);
        latest.setText("↓");
        latest.setContentDescription("Go to latest message");
        latest.setTextColor(MainActivity.TEXT);
        latest.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        latest.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable();
        lg.setShape(GradientDrawable.OVAL);
        lg.setColor(0xF02F2F2C);
        lg.setStroke(dp(1), 0xFF555550);
        latest.setBackground(lg);
        latest.setElevation(dp(4));
        latest.setVisibility(GONE);
        latest.setOnClickListener(v -> {
            follow = true;
            scroll.fullScroll(View.FOCUS_DOWN);
            scroll.post(this::toBottom);
            latest.setVisibility(GONE);
        });
        android.widget.FrameLayout.LayoutParams ll = new android.widget.FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ll.bottomMargin = dp(12);
        frame.addView(latest, ll);
        addView(frame, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setOnTouchListener((v, e) -> {
            if (pinch != null) {
                pinch.onTouchEvent(e);
                if (e.getPointerCount() > 1) {
                    return true;
                }
            }
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                touching = true;
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                touching = false;
                touchEnd = android.os.SystemClock.uptimeMillis();
            }
            return false;
        });
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            boolean user = touching || android.os.SystemClock.uptimeMillis() - touchEnd < 2000;
            if (!scroll.canScrollVertically(1)) {
                follow = true;
            } else if (user && y < oy) {
                follow = false;
            }
            syncLatest();
        });
        View.OnLayoutChangeListener keep = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (follow && b - t != ob - ot) {
                scroll.post(this::toBottom);
            }
            scroll.post(this::syncLatest);
        };
        list.addOnLayoutChangeListener(keep);
        scroll.addOnLayoutChangeListener(keep);

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
        pauseBar = new LinearLayout(c);
        pauseBar.setGravity(Gravity.CENTER_VERTICAL);
        pauseBar.setPadding(dp(16), dp(6), dp(10), dp(6));
        pauseBar.setBackgroundColor(0xFF4A3A1E);
        pauseText = label("", MainActivity.TEXT, 13);
        pauseBar.addView(pauseText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView resume = button("Resume now", 0xFFF2B33D);
        resume.setOnClickListener(v -> {
            if (actions != null) {
                actions.resume();
            }
        });
        pauseBar.addView(resume);
        pauseBar.setVisibility(GONE);
        addView(pauseBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        queueBox = new LinearLayout(c);
        queueBox.setOrientation(VERTICAL);
        queueBox.setPadding(dp(12), dp(4), dp(12), 0);
        queueBox.setVisibility(GONE);
        addView(queueBox, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(dp(10), dp(6), dp(10), dp(10));
        box = new EditText(c);
        box.setTextColor(MainActivity.TEXT);
        box.setHintTextColor(MainActivity.MUTED);
        box.setHint(hint);
        box.setContentDescription("Message box");
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.setMaxLines(8);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        box.setImeOptions(EditorInfo.IME_ACTION_SEND);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(round(USER_BG, 14));
        // Double-tap (two taps within 0.6 s, close together) fills in the suggestion; a bit more forgiving than Android's 0.3 s.
        long[] lastTap = {0};
        float[] lastXY = {0, 0};
        box.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                long now = e.getEventTime();
                boolean near = Math.abs(e.getX() - lastXY[0]) < dp(48) && Math.abs(e.getY() - lastXY[1]) < dp(48);
                if (now - lastTap[0] < 600 && near && takeSuggestion()) {
                    lastTap[0] = 0;
                } else {
                    lastTap[0] = now;
                    lastXY[0] = e.getX();
                    lastXY[1] = e.getY();
                }
            }
            return false;
        });
        box.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_TAB && !e.isShiftPressed() && !suggestion.isEmpty() && box.getText().length() == 0) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    takeSuggestion();
                }
                return true;
            }
            if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && !e.isShiftPressed()) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    submit();
                }
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
        box.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence t, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                if (actions != null) {
                    actions.typing();
                }
            }
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

    /**
     * Keep following the newest message after the whole window was replaced (heal reload). Swapping item views can let
     * the ScrollView jump near the top (focus moves to an earlier view), after the first toBottom already ran, so scroll
     * down again over the next second while still following and not touched.
     */
    void stickToBottom() {
        follow = true;
        for (int ms : new int[]{0, 120, 400, 900}) {
            postDelayed(() -> {
                if (follow && !touching) {
                    scroll.scrollTo(0, Math.max(0, col.getHeight() - scroll.getHeight()));
                    syncLatest();
                }
            }, ms);
        }
    }

    void toBottom() {
        if (follow) {
            scroll.scrollTo(0, Math.max(0, col.getHeight() - scroll.getHeight()));
        }
    }

    void submit() {
        String t = box.getText().toString();
        if (t.trim().isEmpty() || actions == null) {
            return;
        }
        follow = true;
        scroll.post(this::toBottom);
        actions.send(t);
        box.setText("");
        box.requestFocus();
        box.post(box::requestFocus);
    }

    void setBusy(boolean b, String line) {
        busy = b;
        send.setText(b && box.getText().toString().trim().isEmpty() ? "■" : "➤");
        send.setContentDescription(b ? "Stop" : "Send");
        status.setText(line == null ? "" : line);
        status.setVisibility(line == null || line.isEmpty() ? GONE : VISIBLE);
    }

    boolean readOnly;
    /** Claude Code's prompt suggestion for the open session (Quill Desktop's ghost text); shown as the hint while the box is empty. */
    String suggestion = "";

    void setSuggestion(String s) {
        s = s == null ? "" : s.trim();
        if (s.equals(suggestion)) {
            return;
        }
        suggestion = s;
        syncHint();
    }

    /** The message box hint when there is no suggestion (the kmem screen names its assistant). */
    String hint = "Message Quill";

    void syncHint() {
        box.setHint(readOnly ? "Read only  ·  turn on device control in Quill Desktop" : suggestion.isEmpty() ? hint : suggestion);
        box.setContentDescription(suggestion.isEmpty() || readOnly ? "Message box" : "Message box, suggestion: " + suggestion);
    }

    /** Tab (hardware keyboard) or a double-tap fills in the suggestion while the box is empty; it is sent like any typed text. */
    boolean takeSuggestion() {
        if (readOnly || suggestion.isEmpty() || box.getText().length() > 0) {
            return false;
        }
        box.setText(suggestion);
        box.setSelection(box.getText().length());
        return true;
    }

    void setReadOnly(boolean ro) {
        if (ro == readOnly) {
            return;
        }
        readOnly = ro;
        box.setEnabled(!ro);
        syncHint();
        box.setAlpha(ro ? 0.6f : 1f);
        send.setEnabled(!ro);
        send.setAlpha(ro ? 0.35f : 1f);
    }

    void setPause(boolean paused, long untilMs) {
        if (paused) {
            String until = untilMs > 0 ? " until " + TimeFmt.time(untilMs) : "";
            pauseText.setText("Sessions are paused near the 5-hour limit" + until + ".");
        }
        pauseBar.setVisibility(paused ? VISIBLE : GONE);
    }

    String queueSig = "";

    /** Queued texts the user acted on (edit, Send now, ✕) that are hidden until Quill Desktop confirms; a multiset. */
    final List<String> hiding = new ArrayList<>();
    JSONArray lastQueue;

    void queueDone(String text) {
        // Keep it hidden until the next queue from the server no longer has it (reconciled in setQueue).
    }

    void queueFailed(String text) {
        if (hiding.remove(text)) {
            queueSig = null;
            setQueue(lastQueue);
        }
    }

    /** Hide a queued entry right away; returns false if this tap is a repeat on an entry already being handled. */
    boolean hideQueued(String text) {
        int shown = 0;
        int hid = 0;
        for (int i = 0; lastQueue != null && i < lastQueue.length(); i++) {
            JSONObject e = lastQueue.optJSONObject(i);
            if (e != null && text.equals(e.optString("text"))) {
                shown++;
            }
        }
        for (String h : hiding) {
            if (h.equals(text)) {
                hid++;
            }
        }
        if (hid >= shown) {
            return false;
        }
        hiding.add(text);
        queueSig = null;
        setQueue(lastQueue);
        return true;
    }

    void setQueue(JSONArray q) {
        lastQueue = q;
        // Entries that left the server's queue are done: stop hiding them.
        Map<String, Integer> left = new HashMap<>();
        for (int i = 0; q != null && i < q.length(); i++) {
            JSONObject e = q.optJSONObject(i);
            if (e != null) {
                left.merge(e.optString("text"), 1, Integer::sum);
            }
        }
        List<String> keep = new ArrayList<>();
        for (String h : hiding) {
            Integer n = left.get(h);
            if (n != null && n > 0) {
                keep.add(h);
                left.put(h, n - 1);
            }
        }
        hiding.clear();
        hiding.addAll(keep);
        String sig = (q == null ? "" : q.toString()) + "|" + hiding;
        if (sig.equals(queueSig)) {
            return;
        }
        queueSig = sig;
        Map<String, Integer> skip = new HashMap<>();
        for (String h : hiding) {
            skip.merge(h, 1, Integer::sum);
        }
        boolean typing = box.hasFocus();
        queueBox.removeAllViews();
        // Queued messages follow centering (Quill Desktop 1be563f): one centered column, text centered and wrapped.
        LinearLayout rows = new LinearLayout(getContext());
        rows.setOrientation(VERTICAL);
        List<Columns.Words> qtexts = new ArrayList<>();
        int n = q == null ? 0 : q.length();
        int visible = n - hiding.size();
        if (visible > 0) {
            TextView h = label(visible == 1 ? "1 queued message" : visible + " queued messages", MainActivity.MUTED, 12);
            h.setPadding(dp(4), 0, 0, dp(2));
            queueBox.addView(h);
        }
        for (int i = 0; i < n; i++) {
            JSONObject e = q.optJSONObject(i);
            if (e == null) {
                continue;
            }
            int index = e.optInt("index", i);
            String text = e.optString("text");
            Integer sk = skip.get(text);
            if (sk != null && sk > 0) {
                skip.put(text, sk - 1);
                continue;
            }
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(4), dp(4), dp(4));
            row.setBackground(round(CARD, 10));
            TextView t = label(text, MainActivity.TEXT, 14);
            t.setMaxLines(centerChat ? 4 : 2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (centerChat) {
                t.setGravity(Gravity.CENTER_HORIZONTAL);
            }
            qtexts.add(MarkdownView.words(t.getPaint(), text));
            row.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            // Tap the text (user 2026-11-21): take it out of the queue and into the message box to edit or resend
            // (like editing a queued message on Quill Desktop). Text already in the box is kept after it.
            t.setContentDescription("Edit queued message: " + text);
            t.setOnClickListener(v -> {
                if (actions == null || !hideQueued(text)) {
                    return;
                }
                actions.queueOp("delete", index, text);
                String cur = box.getText().toString();
                box.setText(cur.trim().isEmpty() ? text : text + "\n" + cur);
                box.requestFocus();
                box.setSelection(text.length());
                android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (im != null) {
                    im.showSoftInput(box, 0);
                }
            });
            TextView now = label("Send now", MainActivity.ACCENT, 13);
            now.setTypeface(Typeface.DEFAULT_BOLD);
            now.setPadding(dp(10), dp(8), dp(10), dp(8));
            now.setOnClickListener(v -> {
                if (actions != null && hideQueued(text)) {
                    actions.queueOp("send-now", index, text);
                }
            });
            row.addView(now);
            TextView del = label("✕", MainActivity.MUTED, 16);
            del.setContentDescription("Remove from queue");
            del.setPadding(dp(10), dp(6), dp(10), dp(6));
            del.setOnClickListener(v -> {
                if (actions != null && hideQueued(text)) {
                    actions.queueOp("delete", index, text);
                }
            });
            row.addView(del);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            rows.addView(row, lp);
        }
        Columns.Words[] qw = qtexts.toArray(new Columns.Words[0]);
        queueBox.addView(centerChat && qw.length > 0
                ? new ColumnView(getContext(), true, avail -> Columns.groupWidth(avail, qw, dp(130), dp(8)), rows)
                : rows, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        queueBox.setVisibility(n > 0 ? VISIBLE : GONE);
        if (typing && !box.hasFocus()) {
            box.requestFocus();
        }
    }

    void setConnection(String line) {
        // Lines that mention the VPN get a "VPN help" link (Quill Android's README, "Away from home").
        boolean vpn = line != null && line.contains("VPN");
        conn.setText(line == null ? "" : vpn ? line + "  ·  VPN help ›" : line);
        conn.setVisibility(line == null || line.isEmpty() ? GONE : VISIBLE);
        conn.setOnClickListener(vpn ? v -> getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(VPN_HELP))) : null);
        conn.setClickable(vpn);
    }

    /** Put shared text into the message box (after anything already typed), ready to edit and send. */
    void draft(String text) {
        String now = box.getText().toString();
        box.setText(now.trim().isEmpty() ? text : now + "\n" + text);
        box.setSelection(box.getText().length());
        box.requestFocus();
    }

    void clear() {
        planAns.clear();
        planPlain.clear();
        for (StreamView sv : streams.values()) {
            sv.stop();
        }
        streams.clear();
        list.removeAllViews();
        views.clear();
        sigs.clear();
        expanded.clear();
        items = new ArrayList<>();
        follow = true;
        shownLimit = PAGE;
        setQueue(null);
    }

    void setItems(List<Item> all) {
        int start = windowStart(all, shownLimit);
        int hidden = visibleCount(all, 0, start);
        List<Item> next = start == 0 ? all : all.subList(start, all.size());
        earlier.setText("Show earlier messages (" + hidden + ")");
        earlier.setVisibility(hidden > 0 ? VISIBLE : GONE);
        boolean typing = box.hasFocus();
        Map<Integer, Item> bySeq = new HashMap<>();
        for (Item it : next) {
            bySeq.put(it.seq, it);
        }
        for (Integer seq : new ArrayList<>(views.keySet())) {
            if (!bySeq.containsKey(seq)) {
                list.removeView(views.remove(seq));
                sigs.remove(seq);
                StreamView gone = streams.remove(seq);
                if (gone != null) {
                    gone.stop();
                }
            }
        }
        Item lastAssistant = latestAnswerable(next);
        // One card per question set: a reply in a turn with a pending plan card gets no quick-choice buttons.
        List<String> kinds = new ArrayList<>();
        List<Boolean> pendingPlan = new ArrayList<>();
        for (Item x : next) {
            kinds.add(x.kind);
            pendingPlan.add("plan".equals(x.kind) && pending(x));
        }
        noQuick.clear();
        for (int idx : QuestionCards.quickSuppressed(kinds, pendingPlan)) {
            noQuick.add(next.get(idx).seq);
        }
        // Plan overlay: a pending plan's numbered questions answered in its reply (see PlanOverlay).
        Map<Integer, Item> ovPlan = new HashMap<>();
        Map<Integer, int[][]> ovMatch = new HashMap<>();
        java.util.Set<Integer> hiddenPlans = new java.util.HashSet<>();
        for (int i = 0; i < next.size(); i++) {
            Item p = next.get(i);
            if (!"plan".equals(p.kind) || !pending(p) || p.planQuestions == null || p.planQuestions.length() == 0) {
                continue;
            }
            Item reply = null;
            for (int j = i - 1; j >= 0 && reply == null; j--) {
                Item x = next.get(j);
                if ("user".equals(x.kind) || "plan".equals(x.kind)) {
                    break;
                }
                if ("assistant".equals(x.kind) && !x.text.trim().isEmpty()) {
                    reply = x;
                }
            }
            if (reply == null || reply.streaming) {
                continue;
            }
            int n = p.planQuestions.length();
            int[] nums = new int[n];
            String[] texts = new String[n];
            for (int k = 0; k < n; k++) {
                JSONObject q = p.planQuestions.optJSONObject(k);
                nums[k] = q == null ? 0 : q.optInt("num", 0);
                texts[k] = q == null ? "" : q.optString("text");
            }
            int[][] m = PlanOverlay.match(reply.text, nums, texts);
            if (m != null) {
                ovPlan.put(reply.seq, p);
                ovMatch.put(reply.seq, m);
                if (!planPlain.contains(p.seq)) {
                    hiddenPlans.add(p.seq);
                }
            }
        }
        for (int i = 0; i < next.size(); i++) {
            Item it = next.get(i);
            Item ovp = ovPlan.get(it.seq);
            String sig = it.sig() + (it == lastAssistant ? "|choices" : "") + (noQuick.contains(it.seq) ? "|noquick" : "") + (expanded.contains(it.seq) ? "|open" : "")
                    + (ovp == null ? "" : "|ov" + ovp.seq + ":" + planPlain.contains(ovp.seq) + ":" + ovp.sig())
                    + (hiddenPlans.contains(it.seq) ? "|hidden" : "") + ("plan".equals(it.kind) && planPlain.contains(it.seq) ? "|plain" : "");
            View old = views.get(it.seq);
            StreamView sv = streams.get(it.seq);
            if (old != null && sv != null && "assistant".equals(it.kind) && (it.streaming || !sv.caughtUp(it.text)) && !expanded.contains(it.seq)) {
                if (list.indexOfChild(old) != i) {
                    list.removeView(old);
                    list.addView(old, i);
                }
                sv.update(it.text, it.streaming);
                sigs.put(it.seq, sig);
                continue;
            }
            if (sv != null) {
                sv.stop();
                streams.remove(it.seq);
            }
            if (old != null && sig.equals(sigs.get(it.seq))) {
                if (list.indexOfChild(old) != i) {
                    list.removeView(old);
                    list.addView(old, i);
                }
                continue;
            }
            View v = ovp != null ? overlayReply(it, ovp, ovMatch.get(it.seq), it == lastAssistant)
                    : hiddenPlans.contains(it.seq) ? gone() : render(it, it == lastAssistant);
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
        items = all;
        if (typing && !box.hasFocus()) {
            box.requestFocus();
        }
        if (follow) {
            // Same as after a heal reload: a new card (plan, question) can make the ScrollView jump after the first
            // toBottom, so keep following for a moment (only while following and untouched).
            stickToBottom();
        }
    }

    /** How many of all[from, to) produce visible output (see empty). */
    static int visibleCount(List<Item> all, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (!empty(all.get(i))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Where the shown window starts: the newest `limit` items that produce visible output, so empty ones (blank thinking,
     * tool-only replies, task notifications with no text) can't use up the window and leave one message on a blank
     * screen (user 2026-11-22: "Show earlier messages (160)" and one reply). Items before it sit behind "Show earlier".
     */
    static int windowStart(List<Item> all, int limit) {
        int seen = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (!empty(all.get(i)) && ++seen == limit) {
                return i;
            }
        }
        return 0;
    }

    /** Replies that show no quick-choice buttons because their turn has a pending plan card. */
    final Set<Integer> noQuick = new java.util.HashSet<>();

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

    /** Items with nothing to show (a reply that was only tool calls, an empty notice or thinking block) take no space (user 2026-11-21: blank space in histories). */
    static boolean empty(Item it) {
        if (it.streaming || !it.text.trim().isEmpty()) {
            return false;
        }
        switch (it.kind) {
            case "assistant":
                return it.choices == null || it.choices.length() == 0;
            case "user":
            case "thinking":
            case "error":
            case "notice":
                return true;
            case "tool":
            case "permission":
            case "plan":
            case "question":
            case "rotate":
                return false;
            default:
                return true; // an unknown kind with no text has nothing to draw
        }
    }

    View render(Item it, boolean answerable) {
        if (empty(it)) {
            View none = new View(getContext());
            none.setVisibility(GONE);
            return none;
        }
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
                t.setGravity(centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START);
                t.setTextIsSelectable(true);
                return spaced(t, 8);
            }
            default:
                TextView t = label(it.text, MainActivity.MUTED, 13);
                t.setGravity(centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START);
                return spaced(t, 8);
        }
    }

    TextView label(String s, int color, float size) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * scale);
        return t;
    }

    void setStyle(float s, boolean chat, boolean input, boolean st) {
        if (s == scale && chat == centerChat && input == centerInput && st == centerStatus) {
            return;
        }
        scale = s;
        sp = 15f * s;
        centerChat = chat;
        centerInput = input;
        centerStatus = st;
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * s);
        status.setGravity(st ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        conn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * s);
        conn.setGravity(st ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 * s);
        box.setGravity((input ? Gravity.CENTER_HORIZONTAL : Gravity.START) | Gravity.CENTER_VERTICAL);
        sigs.clear();
        setItems(items);
    }

    static final int FOLD_LINES = 200;
    static final int FOLD_CHARS = 20000;
    static final int FOLD_KEEP_LINES = 60;
    static final int FOLD_KEEP_CHARS = 6000;

    static int lineCount(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    static String fold(String s) {
        if (s.length() <= FOLD_CHARS && lineCount(s) <= FOLD_LINES) {
            return s;
        }
        int end = Math.min(s.length(), FOLD_KEEP_CHARS);
        int n = 0;
        for (int i = 0; i < end; i++) {
            if (s.charAt(i) == '\n' && ++n == FOLD_KEEP_LINES) {
                end = i;
                break;
            }
        }
        return s.substring(0, end);
    }

    View user(Item it) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.END);
        TextView t = label(it.text, MainActivity.TEXT, 15);
        t.setGravity(centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(round(USER_BG, 14));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(48);
        if (centerChat) {
            lp.rightMargin = dp(48);
        }
        row.addView(t, lp);
        TextView when = stamp(it, centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.END);
        if (when == null) {
            return spaced(row, 16);
        }
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.addView(row);
        col.addView(when);
        return spaced(col, 16);
    }

    /** Message time under user/assistant messages (⚙ Message times), in the device's 12/24-hour style. */
    TextView stamp(Item it, int gravity) {
        if (!showTimes || it.timeMs <= 0 || it.streaming) {
            return null;
        }
        TextView t = label(TimeFmt.stamp(it.timeMs, System.currentTimeMillis()), MainActivity.MUTED, 11);
        t.setGravity(gravity);
        t.setPadding(0, dp(5), 0, 0); // about a third of the way to the next message (Quill Desktop 1be563f)
        t.setContentDescription("Sent " + t.getText());
        return t;
    }

    /** ⚙ Message times: re-render every message with or without its time. */
    /** Re-render the messages after the file capability changed (file links appear or turn into plain text). */
    void relink() {
        sigs.clear();
        setItems(items);
    }

    void setShowTimes(boolean on) {
        if (on == showTimes) {
            return;
        }
        showTimes = on;
        sigs.clear();
        setItems(items);
    }

    View assistant(Item it, boolean answerable) {
        if (it.streaming && !it.text.isEmpty()) {
            StreamView sv = new StreamView(getContext());
            StreamView prev = streams.put(it.seq, sv);
            if (prev != null) {
                prev.stop();
            }
            sv.update(it.text, true);
            return spaced(sv, 14);
        }
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        MarkdownView md = new MarkdownView(getContext());
        md.sp = sp;
        md.links = linkHost;
        md.gravity = centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START;
        String shown = expanded.contains(it.seq) ? it.text : fold(it.text);
        // Paragraph by paragraph, the same layout a streaming reply has (Quill Desktop's markdownBlocks), so nothing
        // re-flows when a reply ends.
        StringBuilder liveBuf = new StringBuilder();
        java.util.List<String> paras = StreamText.split(shown.replaceAll("\\n+$", ""), liveBuf);
        if (!liveBuf.toString().trim().isEmpty()) {
            paras.add(liveBuf.toString());
        }
        if (paras.size() <= 1) {
            md.setMarkdown(shown);
            col.addView(md);
        } else {
            for (int k = 0; k < paras.size(); k++) {
                MarkdownView pm = new MarkdownView(getContext());
                pm.sp = sp;
                pm.links = linkHost;
                pm.gravity = md.gravity;
                pm.setMarkdown(paras.get(k));
                col.addView(pm, paraParams(k));
            }
        }
        if (shown.length() < it.text.length()) {
            int lines = lineCount(it.text);
            TextView more = button("Show all " + lines + " lines", MainActivity.ACCENT);
            more.setOnClickListener(v -> {
                expanded.add(it.seq);
                setItems(items);
            });
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            ml.topMargin = dp(8);
            ml.gravity = centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START;
            col.addView(more, ml);
        }
        String[] opts = null;
        if (noQuick.contains(it.seq)) {
            opts = null;
        } else if (it.choices != null) {
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
            final QuestionCards.ClickGuard guard = newGuard();
            for (int k = 0; k < opts.length; k++) {
                final String o = opts[k];
                final int index = k;
                TextView b = button(o.length() > 60 ? o.substring(0, 59) + "…" : o, MainActivity.ACCENT);
                b.setOnClickListener(v -> {
                    if (actions == null || !guard.allows(android.os.SystemClock.uptimeMillis())) {
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
        TextView when = stamp(it, centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        if (when != null) {
            col.addView(when);
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

    /** The guard of the card being built: its buttons ignore taps for 600 ms after the card appears or is rebuilt. */
    QuestionCards.ClickGuard guard = new QuestionCards.ClickGuard(0);

    QuestionCards.ClickGuard newGuard() {
        guard = new QuestionCards.ClickGuard(android.os.SystemClock.uptimeMillis());
        return guard;
    }

    LinearLayout card(String title, int accent) {
        newGuard();
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
        b.setOnClickListener(guarded(l));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        row.addView(b, lp);
    }

    /** l, but ignored for 600 ms after the card it belongs to appeared or changed. */
    View.OnClickListener guarded(View.OnClickListener l) {
        final QuestionCards.ClickGuard g = guard;
        return v -> {
            if (g.allows(android.os.SystemClock.uptimeMillis())) {
                l.onClick(v);
            }
        };
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
        if ("autoAllowed".equals(it.state) || "ruleAllowed".equals(it.state)) {
            View none = new View(getContext());
            none.setVisibility(GONE);
            return none;
        }
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
        md.sp = sp - scale;
        md.links = linkHost;
        md.gravity = centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START;
        md.setMarkdown(it.text);
        md.setPadding(0, dp(8), 0, 0);
        c.addView(md);
        if (pending(it)) {
            EditText fb = changesField(it);
            c.addView(fb);
            JSONArray opts = it.planChoices;
            int n = opts == null ? 0 : opts.length();
            if (it.planQuestions != null && it.planQuestions.length() > 0) {
                planQuestions(c, it, fb);
            } else if (it.planApproval != null) {
                planButtons(c, it, fb);
            } else if (n > 0) {
                // Like Quill Desktop's plan card: approve with the answer to the plan's question.
                int rec = it.planRecommended >= 0 && it.planRecommended < n ? it.planRecommended : -1;
                LinearLayout col = new LinearLayout(getContext());
                col.setOrientation(VERTICAL);
                c.addView(col);
                LinearLayout others = new LinearLayout(getContext());
                others.setOrientation(VERTICAL);
                for (int k = 0; k < n; k++) {
                    int index = k;
                    TextView b = button("Approve: " + opts.optString(k), GREEN);
                    b.setOnClickListener(v -> act(it, "approvePlan", toJson(QuestionCards.goAheadExtras(index, fb.getText().toString()))));
                    LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                    lp.topMargin = dp(8);
                    (rec < 0 || k == rec ? col : others).addView(b, lp);
                }
                if (rec >= 0 && n > 1) {
                    TextView more = button("Other answers…", MainActivity.MUTED);
                    others.setVisibility(GONE);
                    more.setOnClickListener(v -> {
                        others.setVisibility(VISIBLE);
                        more.setVisibility(GONE);
                    });
                    LayoutParams ml = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                    ml.topMargin = dp(8);
                    col.addView(more, ml);
                    col.addView(others);
                }
                LinearLayout row = buttons(c);
                addButton(row, "Keep planning", MainActivity.ACCENT, v -> act(it, "rejectPlan", extraText(fb)));
            } else {
                LinearLayout row = buttons(c);
                addButton(row, "Approve", GREEN, v -> act(it, "approvePlan", extraText(fb)));
                addButton(row, "Keep planning", MainActivity.ACCENT, v -> act(it, "rejectPlan", extraText(fb)));
            }
        }
        return spaced(c, 12);
    }

    static String planTitle(String text) {
        String last = lastLine(text).replace("**", "").replace("__", "").replace("`", "").replaceAll("^[#>*\\-_ ]+", "").trim();
        return last.contains("?") && last.length() <= 300 ? last : "Approve this plan?";
    }

    /**
     * The plan's open questions, each with its own control (like Quill Desktop): yesno → Yes/No (nothing picked),
     * single → radio buttons with the recommended option picked, multi → checkboxes, text → a text field.
     * "Approve plan & send answers" sends planAnswers {id: value}; unanswered questions are left out ("no answer").
     */
    void planQuestions(LinearLayout c, Item it, EditText fb) {
        JSONArray qs = it.planQuestions;
        Map<String, Object> ans = answers(it);
        // The questions form one column (Quill Desktop 1be563f): centered as a whole when centering is on, controls
        // left-aligned inside it; widths scale with the chat (Columns).
        LinearLayout qcol = new LinearLayout(getContext());
        qcol.setOrientation(VERTICAL);
        List<Columns.Words> qwords = new ArrayList<>();
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i);
            if (q == null) {
                continue;
            }
            // "num" = the question's own number in the plan (Quill Desktop, optional); else count from 1.
            int num = q.optInt("num", 0) > 0 ? q.optInt("num") : i + 1;
            TextView qt = label("", MainActivity.TEXT, 15);
            qt.setText(MarkdownView.inline(getContext(), num + ". " + q.optString("text"))); // keeps inline code
            qt.setPadding(0, dp(12), 0, dp(4));
            qt.setGravity(Gravity.START);
            qwords.add(MarkdownView.words(qt.getPaint(), num + ". " + q.optString("text")));
            JSONArray os = q.optJSONArray("options");
            for (int k = 0; os != null && k < os.length(); k++) {
                qwords.add(MarkdownView.words(qt.getPaint(), os.optString(k) + "  (recommended)"));
            }
            qcol.addView(qt);
            planControl(qcol, q, i, ans);
        }
        Columns.Words[] qw = qwords.toArray(new Columns.Words[0]);
        c.addView(centerChat ? new ColumnView(getContext(), true, avail -> Columns.groupWidth(avail, qw, dp(48), dp(8)), qcol) : qcol);
        planButtons(c, it, fb);
    }

    /** One plan's answers, shared by the card and the overlay (Quill Desktop: both views share one answers map). */
    final Map<String, Map<String, Object>> planAns = new HashMap<>();
    /** The plan's "Changes (optional)" text, kept in its answers map so a redraw doesn't lose it. */
    static final String CHANGES = "changes:";

    EditText changesField(Item plan) {
        Map<String, Object> ans = answers(plan);
        EditText fb = new EditText(getContext());
        fb.setHint("Changes (optional)");
        fb.setTextColor(MainActivity.TEXT);
        fb.setHintTextColor(MainActivity.MUTED);
        fb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        Object cur = ans.get(CHANGES);
        if (cur instanceof String) {
            fb.setText((String) cur);
        }
        fb.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable x) {
                ans.put(CHANGES, x.toString());
            }
        });
        return fb;
    }
    /** Plans the user switched to "Show as written" (the reply as written plus the card). */
    final java.util.Set<Integer> planPlain = new java.util.HashSet<>();

    Map<String, Object> answers(Item plan) {
        return planAns.computeIfAbsent("seq:" + plan.seq, k -> new HashMap<>());
    }

    /**
     * A question's control, reading and writing ans: yesno → Yes/No, single → radio buttons, multi → checkboxes,
     * text → a text field; the recommended option starts picked (once, the first time this plan is drawn).
     */
    void planControl(LinearLayout into, JSONObject q, int index, Map<String, Object> ans) {
        String qid = String.valueOf(q.opt("id"));
        JSONArray os = q.optJSONArray("options");
        int rec = q.optInt("recommended", -1);
        String kind = q.optString("kind");
        if (!ans.containsKey(qid) && !ans.containsKey("seen:" + qid)) {
            ans.put("seen:" + qid, Boolean.TRUE);
            if (rec >= 0 && "yesno".equals(kind)) {
                ans.put(qid, rec == 0 ? "yes" : "no");
            } else if (rec >= 0 && "single".equals(kind)) {
                ans.put(qid, rec);
            } else if ("multi".equals(kind) && q.optJSONArray("recommendedPicks") != null) {
                // Quill Desktop 44f40ef: "All of a–e (recommended)" and similar pre-tick these boxes.
                java.util.TreeSet<Integer> picks = new java.util.TreeSet<>();
                JSONArray rp = q.optJSONArray("recommendedPicks");
                for (int k = 0; k < rp.length(); k++) {
                    int idx = rp.optInt(k, -1);
                    if (os != null && idx >= 0 && idx < os.length()) {
                        picks.add(idx);
                    }
                }
                if (!picks.isEmpty()) {
                    ans.put(qid, picks);
                }
            }
        }
        switch (kind) {
            case "yesno":
            case "single": {
                android.widget.RadioGroup g = new android.widget.RadioGroup(getContext());
                boolean yn = "yesno".equals(kind);
                if (yn) {
                    g.setOrientation(HORIZONTAL);
                }
                int n = yn ? 2 : os == null ? 0 : os.length();
                for (int k = 0; k < n; k++) {
                    android.widget.RadioButton rb = new android.widget.RadioButton(getContext());
                    String label = yn ? (k == 0 ? "Yes" : "No") : os.optString(k);
                    rb.setText(label + (k == rec ? "  (recommended)" : ""));
                    rb.setTextColor(MainActivity.TEXT);
                    Object value = yn ? (k == 0 ? "yes" : "no") : (Object) k;
                    rb.setId(View.generateViewId());
                    g.addView(rb);
                    if (value.equals(ans.get(qid))) {
                        rb.setChecked(true);
                    }
                    rb.setOnCheckedChangeListener((b, on) -> {
                        if (on) {
                            ans.put(qid, value);
                        }
                    });
                }
                into.addView(g);
                break;
            }
            case "multi": {
                Object cur = ans.get(qid);
                java.util.TreeSet<Integer> picked = cur instanceof java.util.TreeSet ? castSet(cur) : new java.util.TreeSet<>();
                for (int k = 0; os != null && k < os.length(); k++) {
                    android.widget.CheckBox cb = new android.widget.CheckBox(getContext());
                    cb.setText(os.optString(k));
                    cb.setTextColor(MainActivity.TEXT);
                    cb.setChecked(picked.contains(k));
                    int kk = k;
                    cb.setOnCheckedChangeListener((b, on) -> {
                        if (on) {
                            picked.add(kk);
                        } else {
                            picked.remove(kk);
                        }
                        if (picked.isEmpty()) {
                            ans.remove(qid);
                        } else {
                            ans.put(qid, picked);
                        }
                    });
                    into.addView(cb);
                }
                break;
            }
            default: {
                EditText e = new EditText(getContext());
                e.setHint("Your answer");
                e.setContentDescription("Answer " + (index + 1));
                e.setTextColor(MainActivity.TEXT);
                e.setHintTextColor(MainActivity.MUTED);
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                Object cur = ans.get(qid);
                if (cur instanceof String) {
                    e.setText((String) cur);
                }
                e.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence x, int a, int b, int c) {
                    }

                    @Override
                    public void onTextChanged(CharSequence x, int a, int b, int c) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable x) {
                        String t = x.toString().trim();
                        if (t.isEmpty()) {
                            ans.remove(qid);
                        } else {
                            ans.put(qid, t);
                        }
                    }
                });
                into.addView(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static java.util.TreeSet<Integer> castSet(Object o) {
        return (java.util.TreeSet<Integer>) o;
    }

    /** The plan's answers as planAnswers {id: value}; unanswered questions are left out ("no answer"). */
    JSONObject planAnswersJson(Item plan) {
        JSONObject out = new JSONObject();
        try {
            for (Map.Entry<String, Object> e : answers(plan).entrySet()) {
                if (e.getKey().startsWith("seen:") || e.getKey().startsWith("approval:") || e.getKey().equals(CHANGES)) {
                    continue;
                }
                Object v = e.getValue();
                if (v instanceof java.util.TreeSet) {
                    JSONArray a = new JSONArray();
                    for (Integer k : castSet(v)) {
                        a.put(k);
                    }
                    v = a;
                }
                out.put(e.getKey(), v);
            }
        } catch (org.json.JSONException ignored) {
        }
        return out;
    }

    View gone() {
        View v = new View(getContext());
        v.setVisibility(GONE);
        return v;
    }

    /**
     * A plan's reply with its questions answered in place (Quill Desktop 596036f): each question's lines keep their
     * text with the control right under them, and the card's caption, a changes field and Approve plan & send answers
     * / Don't run close the reply. A toggle at the top switches to the reply as written plus the card.
     */
    View overlayReply(Item reply, Item plan, int[][] m, boolean answerable) {
        boolean plain = planPlain.contains(plan.seq);
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        TextView toggle = label(plain ? "☰  Answer in place" : "▤  Show as written", MainActivity.MUTED, 12);
        toggle.setContentDescription(plain ? "Answer the plan's questions in place" : "Show the reply as written");
        toggle.setPadding(0, dp(2), dp(8), dp(6));
        toggle.setOnClickListener(v -> {
            if (!planPlain.remove(plan.seq)) {
                planPlain.add(plan.seq);
            }
            setItems(items);
        });
        col.addView(toggle, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        if (plain) {
            col.addView(assistant(reply, answerable));
            return spaced(col, 14);
        }
        Map<String, Object> ans = answers(plan);
        int at = 0;
        for (int k = 0; k < m.length; k++) {
            JSONObject q = plan.planQuestions.optJSONObject(k);
            String part = PlanOverlay.lines(reply.text, at, m[k][1]);
            if (!part.trim().isEmpty()) {
                col.addView(markdown(part));
            }
            LinearLayout ctl = new LinearLayout(getContext());
            ctl.setOrientation(VERTICAL);
            ctl.setPadding(dp(16), 0, 0, dp(6));
            if (q != null) {
                planControl(ctl, q, k, ans);
            }
            col.addView(ctl);
            at = m[k][1];
        }
        String rest = PlanOverlay.lines(reply.text, at, Integer.MAX_VALUE);
        if (!rest.trim().isEmpty()) {
            col.addView(markdown(rest));
        }
        LinearLayout c = card(planTitle(plan.text), MainActivity.ACCENT);
        EditText fb = changesField(plan);
        c.addView(fb);
        planButtons(c, plan, fb);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(10);
        col.addView(c, cl);
        return spaced(col, 14);
    }

    MarkdownView markdown(String text) {
        MarkdownView md = new MarkdownView(getContext());
        md.sp = sp;
        md.links = linkHost;
        md.gravity = centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START;
        md.setMarkdown(text);
        return md;
    }

    /** The note and the two buttons that close a plan with questions (card or overlay). */
    void planButtons(LinearLayout c, Item it, EditText fb) {
        if (it.planApproval != null) {
            approvalButtons(c, it, fb);
            return;
        }
        TextView note = label("Unanswered questions are sent as \"no answer\".", MainActivity.MUTED, 12);
        note.setPadding(0, dp(8), 0, 0);
        c.addView(note);
        LinearLayout row = buttons(c);
        addButton(row, "Approve plan & send answers", GREEN, v -> {
            JSONObject extra = toJson(QuestionCards.goAheadExtras(null, fb.getText().toString()));
            try {
                extra.put("planAnswers", planAnswersJson(it));
            } catch (org.json.JSONException ignored) {
            }
            act(it, "approvePlan", extra);
        });
        addButton(row, "Don't run", MainActivity.ACCENT, v -> act(it, "rejectPlan", extraText(fb)));
    }

    static final String PICK = "approval:pick";

    /**
     * The approval question's options as buttons on the plan card (request.approval): the recommended one marked, a
     * stop option red, plus Don't run when approval.dontRun. Without other questions a tap answers at once; with them
     * it marks the pick in place (the card is not rebuilt; it can be changed) and "Send answers" sends it together
     * with the answers. Don't run always answers at once (rejectPlan; its answers go in the text).
     */
    void approvalButtons(LinearLayout c, Item it, EditText fb) {
        QuestionCards.Approval a = it.planApproval;
        List<QuestionCards.Question> qs = QuestionCards.parseQuestions(Item.plain(it.planQuestions));
        Map<String, Object> ans = answers(it);
        List<TextView> btns = new ArrayList<>();
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        c.addView(col);
        for (int k = 0; k < a.options.size(); k++) {
            final int index = k;
            int color = a.stop[k] ? RED : k == a.recommended ? GREEN : BLUE;
            String label = a.options.get(k) + (k == a.recommended ? " (recommended)" : "");
            TextView b = button(label.length() > 60 ? label.substring(0, 59) + "…" : label, color);
            btns.add(b);
            b.setOnClickListener(guarded(v -> {
                if (qs.isEmpty()) {
                    sendApproval(it, a, index, qs, fb);
                    return;
                }
                ans.put(PICK, index);
                for (int j = 0; j < btns.size(); j++) {
                    markPick(btns.get(j), a.stop[j] ? RED : j == a.recommended ? GREEN : BLUE, j == index);
                }
            }));
            // The recommended option is marked by its label and colour only; a pick is always the user's own.
            markPick(b, color, !qs.isEmpty() && ans.get(PICK) instanceof Integer && (Integer) ans.get(PICK) == k);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            col.addView(b, lp);
        }
        if (a.dontRun) {
            TextView b = button("Don't run", MainActivity.ACCENT);
            b.setOnClickListener(guarded(v -> sendApproval(it, a, QuestionCards.DONT_RUN, qs, fb)));
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            col.addView(b, lp);
        }
        if (!qs.isEmpty()) {
            TextView note = label("Pick an answer above, then send. Unanswered questions are sent as \"no answer\".", MainActivity.MUTED, 12);
            note.setPadding(0, dp(8), 0, 0);
            c.addView(note);
            LinearLayout row = buttons(c);
            addButton(row, "Send answers", GREEN, v -> {
                Object pick = ans.get(PICK);
                if (pick instanceof Integer) {
                    sendApproval(it, a, (Integer) pick, qs, fb);
                }
            });
        }
    }

    /** A picked approval button is filled, the others outlined. */
    void markPick(TextView b, int color, boolean on) {
        GradientDrawable g = round((color & 0x00FFFFFF) | (on ? 0x80000000 : 0x24000000), 10);
        g.setStroke(dp(on ? 2 : 1), color);
        b.setBackground(g);
    }

    void sendApproval(Item it, QuestionCards.Approval a, int pick, List<QuestionCards.Question> qs, EditText fb) {
        Map<String, Object> body = QuestionCards.body(a, pick, qs, answers(it), fb.getText().toString());
        JSONObject extra = new JSONObject();
        try {
            for (Map.Entry<String, Object> e : body.entrySet()) {
                Object v = e.getValue();
                if (e.getKey().equals("planAnswers")) {
                    v = planAnswersJson(it);
                }
                extra.put(e.getKey(), v);
            }
        } catch (org.json.JSONException ignored) {
        }
        act(it, QuestionCards.action(pick), extra);
    }

    static JSONObject toJson(Map<String, Object> m) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
        } catch (org.json.JSONException ignored) {
        }
        return o;
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

    /** Space between paragraphs, the same while streaming and after (both lay a reply out paragraph by paragraph). */
    LinearLayout.LayoutParams paraParams(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = index == 0 ? 0 : dp(10);
        return lp;
    }

    static final long STREAM_TICK = 16;

    /**
     * A reply that is still streaming (Quill Desktop's smooth streamView, 2026-11-22): laid out paragraph by paragraph
     * like the finished reply; finished paragraphs are added once and never redrawn or faded; the growing last one is
     * drawn with its open markup closed. With Animate new text on, one ~60 fps tick reveals 2–24 characters a frame,
     * whole words, faster when behind, and stops when caught up (no idle timer). Off: text appears as it arrives.
     * When the reply has ended and the reveal has caught up, the item is rendered the normal way, in the same layout.
     */
    final class StreamView extends LinearLayout {
        final MarkdownView live;
        String text = "";
        boolean streaming = true;
        int pos;
        int done;
        String shown = "";
        boolean ticking;
        boolean stopped;

        StreamView(android.content.Context c) {
            super(c);
            setOrientation(VERTICAL);
            live = md();
            addView(live, paraParams(0));
        }

        MarkdownView md() {
            MarkdownView m = new MarkdownView(getContext());
            m.sp = sp;
            m.links = linkHost;
            m.gravity = centerChat ? Gravity.CENTER_HORIZONTAL : Gravity.START;
            return m;
        }

        boolean caughtUp(String t) {
            return pos >= t.length();
        }

        void update(String t, boolean stillStreaming) {
            if (!t.startsWith(text.substring(0, Math.min(pos, text.length())))) {
                pos = 0; // the text was replaced, not extended: start over
                done = 0;
                shown = "";
                removeAllViews();
                addView(live, paraParams(0));
            }
            text = t;
            streaming = stillStreaming;
            if (!animate || "instant".equals(animateSpeed)) {
                pos = text.length();
                draw();
                finishIfDone();
                return;
            }
            if (!ticking) {
                ticking = true;
                postDelayed(this::tick, STREAM_TICK);
            }
        }

        void tick() {
            if (stopped) {
                ticking = false;
                return;
            }
            if (pos < text.length()) {
                pos = StreamText.nextReveal(text, pos, streaming, animateSpeed);
                draw();
            }
            if (pos >= text.length()) {
                ticking = false;
                finishIfDone();
                return;
            }
            postDelayed(this::tick, STREAM_TICK);
        }

        /** The reply ended and everything is shown: render it the normal way (same layout, plus time and choices). */
        void finishIfDone() {
            if (!streaming && pos >= text.length() && !stopped) {
                stopped = true;
                post(() -> {
                    for (Map.Entry<Integer, StreamView> e : streams.entrySet()) {
                        if (e.getValue() == this) {
                            sigs.remove(e.getKey());
                            streams.remove(e.getKey());
                            break;
                        }
                    }
                    setItems(items);
                });
            }
        }

        final java.util.List<String> drawn = new ArrayList<>();

        void draw() {
            StringBuilder liveText = new StringBuilder();
            java.util.List<String> paras = StreamText.split(text.substring(0, pos), liveText);
            if (paras.size() < drawn.size() || !paras.subList(0, drawn.size()).equals(drawn)) {
                // A later list item joined finished paragraphs into one list: redraw them once.
                removeAllViews();
                addView(live, paraParams(0));
                drawn.clear();
                done = 0;
            }
            if (paras.size() > done) {
                for (int k = done; k < paras.size(); k++) {
                    drawn.add(paras.get(k));
                    MarkdownView b = md();
                    b.setMarkdown(paras.get(k));
                    addView(b, getChildCount() - 1, paraParams(getChildCount() - 1));
                }
                done = paras.size();
                live.setLayoutParams(paraParams(getChildCount() - 1));
            }
            String l = liveText.toString();
            if (!l.equals(shown)) {
                shown = l;
                live.setMarkdown(l.trim().isEmpty() ? "" : StreamText.closeOpen(l));
            }
            toBottom();
        }

        void stop() {
            stopped = true;
        }
    }
}
