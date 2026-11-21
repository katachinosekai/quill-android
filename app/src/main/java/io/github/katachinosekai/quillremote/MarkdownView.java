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
        for (int i = 0; i < blocks.size(); i++) {
            View v = block(blocks.get(i));
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : gap(blocks.get(i - 1), blocks.get(i));
            addView(v, lp);
        }
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
