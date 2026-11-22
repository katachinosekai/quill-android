package io.github.katachinosekai.quillremote;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

final class MarkdownView extends LinearLayout {
    static final int CODE_BG = 0xFF1A1A18;
    static final int CODE_INLINE_BG = 0x33FFFFFF;
    static final int RULE = 0xFF3A3A37;

    float sp = 15f;
    int gravity = android.view.Gravity.START;
    String shown;

    MarkdownView(Context c) {
        super(c);
        setOrientation(VERTICAL);
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    void setMarkdown(String md) {
        if (md == null) {
            md = "";
        }
        if (md.equals(shown)) {
            return;
        }
        shown = md;
        removeAllViews();
        List<Markdown.Block> blocks = Markdown.parse(md);
        boolean centered = gravity != android.view.Gravity.START;
        for (int i = 0; i < blocks.size(); i++) {
            Markdown.Block b = blocks.get(i);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : gap(blocks.get(i - 1), b);
            if (centered && isList(b)) {
                // Centered lists keep their formatting (user rule, 2026-11-22): markers line up, lines start at one
                // left edge, and the list as a whole sits centered as one column that scales with the chat.
                int j = i;
                while (j < blocks.size() && isList(blocks.get(j))) {
                    j++;
                }
                addView(listColumn(blocks.subList(i, j)), lp);
                i = j - 1;
                continue;
            }
            if (centered && b.type == Markdown.Type.PARAGRAPH) {
                int j = i;
                while (j < blocks.size() && blocks.get(j).type == Markdown.Type.PARAGRAPH) {
                    j++;
                }
                List<View> views = paragraphRun(blocks.subList(i, j));
                for (int k = 0; k < views.size(); k++) {
                    LayoutParams pl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                    pl.topMargin = i + k == 0 ? 0 : gap(blocks.get(i + k - 1), blocks.get(i + k));
                    addView(views.get(k), pl);
                }
                i = j - 1;
                continue;
            }
            addView(block(b), lp);
        }
    }

    static boolean isList(Markdown.Block b) {
        return b.type == Markdown.Type.BULLET || b.type == Markdown.Type.NUMBERED;
    }

    static String plainText(Markdown.Block b) {
        StringBuilder sb = new StringBuilder(b.marker == null ? "" : b.marker + " ");
        if (b.runs != null) {
            for (Markdown.Run r : b.runs) {
                sb.append(r.text);
            }
        }
        return sb.toString();
    }

    static Columns.Words words(android.text.TextPaint p, String text) {
        String[] w = text.trim().split("\\s+");
        float[] ws = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            ws[i] = p.measureText(w[i]);
        }
        return new Columns.Words(ws, p.measureText(" "));
    }

    /** A centered list: items left-aligned inside one centered column (Columns.groupWidth). */
    View listColumn(List<Markdown.Block> items) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        Columns.Words[] texts = new Columns.Words[items.size()];
        int extra = 0;
        for (int k = 0; k < items.size(); k++) {
            Markdown.Block b = items.get(k);
            TextView t = (TextView) block(b);
            t.setGravity(android.view.Gravity.START);
            texts[k] = words(t.getPaint(), plainText(b));
            extra = Math.max(extra, dp(18) * b.level + dp(16) + dp(6));
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = k == 0 ? 0 : gap(items.get(k - 1), b);
            col.addView(t, lp);
        }
        final int ex = extra;
        return new ColumnView(getContext(), true, avail -> Columns.groupWidth(avail, texts, ex, dp(8)), col);
    }

    /** A run of centered paragraphs: an overlong one wraps balanced (Quill Desktop's balancedParagraph). */
    List<View> paragraphRun(List<Markdown.Block> run) {
        List<View> out = new java.util.ArrayList<>();
        float[] widths = new float[run.size()];
        Columns.Words[] words = new Columns.Words[run.size()];
        for (int k = 0; k < run.size(); k++) {
            TextView t = (TextView) block(run.get(k));
            words[k] = words(t.getPaint(), plainText(run.get(k)));
            widths[k] = words[k].total;
            out.add(t);
        }
        int idx = Columns.overlong(widths);
        if (idx >= 0) {
            float other = Columns.widestOther(widths, idx);
            Columns.Words long_ = words[idx];
            int pad = dp(4);
            out.set(idx, new ColumnView(getContext(), true, avail -> {
                float b = long_.balanced(other, Columns.FLOOR * avail - pad);
                return b == 0 ? avail : b + dp(8) + pad;
            }, out.get(idx)));
        }
        return out;
    }

    int gap(Markdown.Block prev, Markdown.Block cur) {
        boolean list = (prev.type == Markdown.Type.BULLET || prev.type == Markdown.Type.NUMBERED)
                && (cur.type == Markdown.Type.BULLET || cur.type == Markdown.Type.NUMBERED);
        return dp(list ? 3 : cur.type == Markdown.Type.HEADING ? 14 : 9);
    }

    TextView text() {
        TextView t = new TextView(getContext());
        t.setTextColor(MainActivity.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0, 1.35f);
        t.setTextIsSelectable(true);
        t.setGravity(gravity);
        return t;
    }

    View block(Markdown.Block b) {
        switch (b.type) {
            case CODE: {
                TextView t = text();
                t.setText(b.raw);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * 0.85f);
                t.setLineSpacing(0, 1.2f);
                t.setGravity(android.view.Gravity.START);
                t.setHorizontallyScrolling(true);
                t.setPadding(dp(12), dp(10), dp(12), dp(10));
                HorizontalScrollView h = new HorizontalScrollView(getContext());
                h.setFillViewport(true);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(CODE_BG);
                bg.setCornerRadius(dp(8));
                h.setBackground(bg);
                h.addView(t);
                return h;
            }
            case RULE: {
                View v = new View(getContext());
                v.setBackgroundColor(RULE);
                v.setMinimumHeight(dp(1));
                return v;
            }
            case TABLE: {
                TextView t = text();
                t.setText(table(b.raw));
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * 0.85f);
                t.setGravity(android.view.Gravity.START);
                t.setHorizontallyScrolling(true);
                HorizontalScrollView h = new HorizontalScrollView(getContext());
                h.addView(t);
                return h;
            }
            default: {
                TextView t = text();
                SpannableStringBuilder sb = new SpannableStringBuilder();
                if (b.marker != null) {
                    sb.append(b.marker).append(' ');
                    sb.setSpan(new ForegroundColorSpan(MainActivity.MUTED), 0, b.marker.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                int start = sb.length();
                runs(sb, b.runs);
                if (b.type == Markdown.Type.HEADING) {
                    float size = b.level <= 1 ? 1.4f : b.level == 2 ? 1.25f : b.level == 3 ? 1.12f : 1.0f;
                    sb.setSpan(new RelativeSizeSpan(size), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (b.marker != null) {
                    int indent = dp(18) * b.level;
                    sb.setSpan(new LeadingMarginSpan.Standard(indent, indent + dp(16)), 0, sb.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                }
                if (b.type == Markdown.Type.QUOTE) {
                    sb.setSpan(new ForegroundColorSpan(MainActivity.MUTED), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.QuoteSpan(MainActivity.ACCENT, dp(3), dp(10)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                t.setText(sb);
                if (hasLink(b.runs)) {
                    t.setMovementMethod(LinkMovementMethod.getInstance());
                }
                return t;
            }
        }
    }

    /** Inline markdown (bold, italic, `code`, links) as styled text, for labels outside a MarkdownView. */
    static CharSequence inline(Context c, String md) {
        MarkdownView m = new MarkdownView(c);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        m.runs(sb, Markdown.inline(md));
        return sb;
    }

    static boolean hasLink(List<Markdown.Run> runs) {
        if (runs != null) {
            for (Markdown.Run r : runs) {
                if (r.link != null) {
                    return true;
                }
            }
        }
        return false;
    }

    void runs(SpannableStringBuilder sb, List<Markdown.Run> runs) {
        if (runs == null) {
            return;
        }
        for (Markdown.Run r : runs) {
            int s = sb.length();
            sb.append(r.text);
            int e = sb.length();
            if ((r.style & Markdown.BOLD) != 0 && (r.style & Markdown.ITALIC) != 0) {
                sb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ((r.style & Markdown.BOLD) != 0) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ((r.style & Markdown.ITALIC) != 0) {
                sb.setSpan(new StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if ((r.style & Markdown.STRIKE) != 0) {
                sb.setSpan(new StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if ((r.style & Markdown.CODE_SPAN) != 0) {
                sb.setSpan(new TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BackgroundColorSpan(CODE_INLINE_BG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(0.9f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.link != null) {
                final String url = r.link;
                sb.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View w) {
                        Uri u = Uri.parse(url);
                        if ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) {
                            try {
                                w.getContext().startActivity(new Intent(Intent.ACTION_VIEW, u));
                            } catch (android.content.ActivityNotFoundException ignored) {
                            }
                        }
                    }
                }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(MainActivity.ACCENT), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    static String table(String raw) {
        List<String[]> rows = Markdown.tableRows(raw);
        int cols = 0;
        for (String[] r : rows) {
            cols = Math.max(cols, r.length);
        }
        int[] w = new int[cols];
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                w[i] = Math.max(w[i], plain(r[i]).length());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < rows.size(); k++) {
            String[] r = rows.get(k);
            for (int i = 0; i < cols; i++) {
                String cell = i < r.length ? plain(r[i]) : "";
                sb.append(cell);
                for (int p = cell.length(); p < w[i] + 2 && i < cols - 1; p++) {
                    sb.append(' ');
                }
            }
            sb.append('\n');
            if (k == 0) {
                for (int i = 0; i < cols; i++) {
                    for (int p = 0; p < w[i] + (i < cols - 1 ? 2 : 0); p++) {
                        sb.append(p < w[i] ? '─' : ' ');
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString().replaceAll("\n$", "");
    }

    static String plain(String md) {
        StringBuilder sb = new StringBuilder();
        for (Markdown.Run r : Markdown.inline(md)) {
            sb.append(r.text);
        }
        return sb.toString();
    }
}
