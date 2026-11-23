package io.github.katachinosekai.quillremote;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import org.json.JSONObject;

/**
 * The full-screen file viewer (Quill Desktop docs/design/phone-links.md section 8): text with light syntax colours,
 * line numbers and a jump to :N, images with pinch zoom, binary and too-large notes, short error messages. File
 * contents live in memory while the viewer is open only. No FLAG_SECURE: screenshots stay allowed (decided).
 */
final class FileViewer {
    static final int C_COMMENT = 0xFF8A9A7B, C_STRING = 0xFFD9A066, C_NUMBER = 0xFF7FB3D5, C_KEYWORD = 0xFFC792EA;
    static final int C_HIT = 0x40D97757;

    final Context ctx;
    final PhoneApi api;
    final String session, path;
    final int line, lineEnd;
    final Runnable onLocked;
    final Dialog dialog;
    final FrameLayout holder;
    final FrameLayout body;
    final TextView title, sub, note;
    int gen;
    boolean closed;
    long mtimeMs;

    FileViewer(Context ctx, PhoneApi api, String session, String path, int line, int lineEnd, Runnable onLocked) {
        this.ctx = ctx;
        this.api = api;
        this.session = session;
        this.path = path;
        this.line = line;
        this.lineEnd = lineEnd;
        this.onLocked = onLocked;
        dialog = new Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar);
        holder = new FrameLayout(ctx);
        holder.setBackgroundColor(MainActivity.BG);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        holder.addView(col, new FrameLayout.LayoutParams(-1, -1));
        dialog.setContentView(holder);
        InsetPad.install(holder);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(14), dp(10), dp(14), dp(6));
        title = label(FileModel.baseName(path), 17, MainActivity.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sub = label(path, 12, MainActivity.MUTED);
        head.addView(title);
        head.addView(sub);
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Copy path", () -> LinkActions.copy(ctx, path)));
        buttons.addView(button("Reload", this::load));
        buttons.addView(button("Close", dialog::dismiss));
        head.addView(buttons);
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));
        note = label("", 12, MainActivity.MUTED);
        note.setBackgroundColor(MarkdownView.CODE_BG);
        note.setPadding(dp(14), dp(6), dp(14), dp(6));
        note.setVisibility(View.GONE);
        col.addView(note, new LinearLayout.LayoutParams(-1, -2));
        body = new FrameLayout(ctx);
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        dialog.setOnDismissListener(d -> {
            closed = true;
            body.removeAllViews(); // the contents go with the views
        });
    }

    void show() {
        Touch.watch(dialog).show();
        load();
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    TextView label(String text, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    TextView button(String text, Runnable r) {
        TextView t = label(text, 14, MainActivity.ACCENT);
        t.setPadding(0, dp(8), dp(18), dp(4));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    /** Fetch on a worker thread; a 429 waits Retry-After once, then shows the message. */
    void load() {
        final int g = ++gen;
        note.setVisibility(View.GONE);
        message("Loading…", false);
        new Thread(() -> {
            PhoneApi.Reply r = api.file(session, path);
            if (r.code == 429 && !closed && g == gen) {
                int wait = FileModel.retryWait(r.body == null ? 1 : r.body.optInt("retryAfter", 1));
                holder.post(() -> {
                    if (!closed && g == gen) {
                        message("Too many files opened. Waiting " + wait + " s…", false);
                    }
                });
                try {
                    Thread.sleep(wait * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (closed || g != gen) {
                    return;
                }
                r = api.file(session, path);
            }
            final PhoneApi.Reply fin = r;
            holder.post(() -> {
                if (!closed && g == gen) {
                    render(fin);
                }
            });
        }, "quill-file").start();
    }

    void render(PhoneApi.Reply r) {
        if (!r.ok()) {
            if (r.code == 401 && "locked".equals(r.error()) && onLocked != null) {
                onLocked.run();
            }
            message(FileModel.errorMessage(r.code, r.error(), r.detail(), r.body == null ? 0 : r.body.optInt("retryAfter", 1)), true);
            return;
        }
        JSONObject b = r.body;
        FileModel m = new FileModel(b.optString("kind"), b.optString("path", path), b.optString("name"), b.optLong("size"),
                b.optString("lang"), b.optString("text"), b.optBoolean("truncated"), b.optString("mime"), b.optString("data"));
        mtimeMs = b.optLong("mtimeMs");
        title.setText(m.name);
        String when = mtimeMs > 0 ? " · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(mtimeMs)) : "";
        sub.setText(path + "\n" + FileModel.sizeText(m.size) + when);
        body.removeAllViews();
        switch (m.kind) {
            case FileModel.TEXT:
                showText(m);
                break;
            case FileModel.IMAGE:
                showImage(m);
                break;
            case FileModel.BINARY:
            case FileModel.TOO_LARGE:
                message(m.name + "\n" + m.notShownNote(), false);
                break;
            default:
                message("Quill Android can't show this kind of file. Update Quill Android.", false);
        }
    }

    void message(String text, boolean retry) {
        body.removeAllViews();
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView t = label(text, 15, MainActivity.TEXT);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        if (retry) {
            TextView b = button("Retry", this::load);
            b.setPadding(dp(18), dp(14), dp(18), dp(8));
            c.addView(b);
        }
        body.addView(c, new FrameLayout.LayoutParams(-1, -1));
    }

    void showText(FileModel m) {
        String tn = m.truncationNote();
        note.setText(tn);
        note.setVisibility(tn.isEmpty() ? View.GONE : View.VISIBLE);
        StringBuilder nums = new StringBuilder();
        int n = m.lines().size();
        for (int i = 1; i <= n; i++) {
            nums.append(i).append(i < n ? "\n" : "");
        }
        SpannableString code = new SpannableString(m.text);
        for (Syntax.Span s : Syntax.spans(m.lang, m.text)) {
            int color = s.type == Syntax.COMMENT ? C_COMMENT : s.type == Syntax.STRING ? C_STRING : s.type == Syntax.NUMBER ? C_NUMBER : C_KEYWORD;
            code.setSpan(new ForegroundColorSpan(color), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        final int from = m.jumpLine(line), to = m.rangeEnd(line, lineEnd);
        if (from > 0) {
            int a = m.lineOffset(from);
            int e = m.lineOffset((to > 0 ? to : from) + 1);
            while (e > a && e <= m.text.length() && e > 0 && m.text.charAt(e - 1) == '\n') {
                e--;
            }
            if (e > a) {
                code.setSpan(new BackgroundColorSpan(C_HIT), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        TextView gutter = mono(nums.toString(), MainActivity.MUTED);
        gutter.setGravity(Gravity.END);
        gutter.setPadding(dp(10), dp(8), dp(8), dp(8));
        TextView tv = mono("", MainActivity.TEXT);
        tv.setText(code);
        tv.setTextIsSelectable(true);
        tv.setHorizontallyScrolling(true);
        tv.setPadding(dp(8), dp(8), dp(14), dp(8));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(gutter, new LinearLayout.LayoutParams(-2, -2));
        HorizontalScrollView h = new HorizontalScrollView(ctx);
        h.addView(tv);
        row.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        ScrollView sv = new ScrollView(ctx);
        sv.addView(row, new ViewGroup.LayoutParams(-1, -2));
        body.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        if (from > 0) {
            tv.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    Layout lay = tv.getLayout();
                    if (lay == null) {
                        return;
                    }
                    tv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    sv.scrollTo(0, Math.max(0, lay.getLineTop(Math.min(from - 1, lay.getLineCount() - 1)) - dp(48)));
                }
            });
        }
    }

    TextView mono(String text, int color) {
        TextView t = label(text, 12.5f, color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    void showImage(FileModel m) {
        Bitmap bmp = null;
        try {
            byte[] bytes = Base64.decode(m.data, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= dm.widthPixels && o.outHeight / (sample * 2) >= dm.heightPixels) {
                sample *= 2;
            }
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (IllegalArgumentException | OutOfMemoryError ignored) {
        }
        if (bmp == null) {
            message("This image can't be shown.", false);
            return;
        }
        body.addView(new ZoomImage(ctx, bmp), new FrameLayout.LayoutParams(-1, -1));
    }

    /** An image with pinch zoom, pan and double-tap (fit / 3x). */
    static final class ZoomImage extends ImageView {
        final Matrix m = new Matrix();
        final Bitmap bmp;
        final ScaleGestureDetector scale;
        final GestureDetector taps;
        float base = 1, lastX, lastY;
        boolean multi;

        ZoomImage(Context c, Bitmap b) {
            super(c);
            bmp = b;
            setScaleType(ScaleType.MATRIX);
            setImageBitmap(b);
            scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (current() > base * 1.05f) {
                        fit();
                    } else {
                        zoomBy(3f, e.getX(), e.getY());
                    }
                    return true;
                }
            });
        }

        float current() {
            float[] v = new float[9];
            m.getValues(v);
            return v[Matrix.MSCALE_X];
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            fit();
        }

        void fit() {
            if (getWidth() == 0 || getHeight() == 0) {
                return;
            }
            base = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
            m.setScale(base, base);
            m.postTranslate((getWidth() - bmp.getWidth() * base) / 2, (getHeight() - bmp.getHeight() * base) / 2);
            setImageMatrix(m);
        }

        void zoomBy(float f, float fx, float fy) {
            float cur = current();
            f = Math.max(base / cur, Math.min(base * 8 / cur, f));
            m.postScale(f, f, fx, fy);
            clamp();
        }

        void clamp() {
            RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
            m.mapRect(r);
            float dx = 0, dy = 0;
            dx = r.width() <= getWidth() ? (getWidth() - r.width()) / 2 - r.left : r.left > 0 ? -r.left : r.right < getWidth() ? getWidth() - r.right : 0;
            dy = r.height() <= getHeight() ? (getHeight() - r.height()) / 2 - r.top : r.top > 0 ? -r.top : r.bottom < getHeight() ? getHeight() - r.bottom : 0;
            m.postTranslate(dx, dy);
            setImageMatrix(m);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scale.onTouchEvent(e);
            taps.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    multi = false;
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multi = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() == 1 && !multi) {
                        m.postTranslate(e.getX() - lastX, e.getY() - lastY);
                        clamp();
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                default:
                    break;
            }
            return true;
        }
    }
}
