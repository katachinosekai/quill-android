package io.github.katachinosekai.quillremote;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A bottom sheet in a Dialog (no AndroidX): a drag handle, half or full height, back or a tap outside closes it. Dragging
 * the handle up makes it full, down makes it half and then closes it; a tap on the handle toggles. No animation, so the
 * system "Remove animations" setting needs nothing extra. Content goes into {@link #content}.
 */
final class BottomSheet {
    /** Half of the available height, as a fraction. */
    static final float HALF = 0.55f;

    final Dialog dialog;
    final FrameLayout content;
    final View sheet;
    final Root root;
    boolean full;
    MainActivity sessionHost;
    Runnable onSessionPicked;
    FrameLayout sessionDrawer;

    /** Sheet height in pixels for a window of total height (pure, for tests). */
    static int heightFor(int total, boolean full) {
        return full ? total : Math.round(total * HALF);
    }

    /** What a handle drag of dy pixels (negative = up) does: +1 grow to full, -1 shrink (half, then close), 0 nothing. */
    static int dragResult(float dy, int threshold) {
        if (dy <= -threshold) {
            return 1;
        }
        return dy >= threshold ? -1 : 0;
    }

    static int sessionSwipe(float dx, float dy, long elapsed, int threshold) {
        if (elapsed >= 1500 || Math.abs(dx) < threshold || Math.abs(dy) >= Math.abs(dx) * 0.5f) {
            return 0;
        }
        return dx > 0 ? 1 : -1;
    }

    void enableSessionDrawer(MainActivity host, Runnable onPicked) {
        sessionHost = host;
        onSessionPicked = onPicked;
    }

    void openSessionDrawer() {
        MainActivity a = sessionHost;
        if (sessionDrawer != null || a == null || a.bars == null) {
            return;
        }
        sessionDrawer = new FrameLayout(a);
        View scrim = new View(a);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> closeSessionDrawer());
        sessionDrawer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        View panel = a.bars.operatorSessionDrawer(id -> {
            closeSessionDrawer();
            dialog.hide();
            onSessionPicked.run();
            a.bars.picked(id);
        });
        int width = Math.min(root.getWidth() - a.dp(48), a.dp(300));
        sessionDrawer.addView(panel, new FrameLayout.LayoutParams(Math.max(1, width), -1, Gravity.START));
        root.addView(sessionDrawer, new FrameLayout.LayoutParams(-1, -1));
        panel.setTranslationX(root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? width : -width);
        panel.animate().translationX(0).setDuration(180).start();
    }

    void closeSessionDrawer() {
        if (sessionDrawer != null) {
            root.removeView(sessionDrawer);
            sessionDrawer = null;
        }
    }

    BottomSheet(Context c, boolean startFull) {
        float d = c.getResources().getDisplayMetrics().density;
        full = startFull;
        dialog = new Dialog(c, android.R.style.Theme_DeviceDefault_NoActionBar);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.45f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        root = new Root(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MainActivity.BG);
        bg.setCornerRadii(new float[]{16 * d, 16 * d, 16 * d, 16 * d, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setClipToOutline(true);
        sheet = box;
        View handle = handleView(c, d);
        box.addView(handle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(24 * d)));
        content = new FrameLayout(c);
        box.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));
        root.setOnClickListener(v -> dialog.dismiss());
        box.setOnClickListener(v -> { });
        dialog.setContentView(root);
        InsetPad.install(root);
        Touch.watch(dialog);
    }

    static LinearLayout operatorTitleBar(MainActivity a, String title, Runnable refresh, Runnable close) {
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(a.dp(14), 0, a.dp(4), 0);
        TextView label = new TextView(a);
        label.setText(title);
        label.setTextSize(17);
        label.setTextColor(MainActivity.TEXT);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(a.button("⟳", "Refresh", v -> refresh.run()));
        bar.addView(a.button("✕", "Close", v -> close.run()));
        return bar;
    }

    static LinearLayout operatorHeader(MainActivity a) {
        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPaddingRelative(a.dp(14), a.dp(2), a.dp(14), a.dp(6));
        return header;
    }

    static TextView operatorStatus(MainActivity a, String line, boolean running) {
        TextView status = new TextView(a);
        status.setText("● " + line);
        status.setTextSize(14);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(running ? 0xFF6CC06C : MainActivity.TEXT);
        return status;
    }

    View handleView(Context c, float d) {
        FrameLayout h = new FrameLayout(c);
        View bar = new View(c);
        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(2 * d);
        b.setColor(0xFF6A6A64);
        bar.setBackground(b);
        h.addView(bar, new FrameLayout.LayoutParams(Math.round(40 * d), Math.round(4 * d), Gravity.CENTER));
        h.setContentDescription("Drag to resize the sheet; tap to switch between half and full height");
        final float[] down = new float[1];
        final boolean[] moved = new boolean[1];
        h.setOnTouchListener((v, e) -> {
            Touch.ping();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    return true;
                case MotionEvent.ACTION_UP:
                    int r = dragResult(e.getRawY() - down[0], Math.round(24 * d));
                    if (r == 0) {
                        setFull(!full);
                    } else if (r > 0) {
                        setFull(true);
                    } else if (full) {
                        setFull(false);
                    } else {
                        dialog.dismiss();
                    }
                    return true;
                default:
                    return true;
            }
        });
        return h;
    }

    void setFull(boolean f) {
        full = f;
        root.requestLayout();
    }

    /** The window root: sizes the sheet to half or full of its own height before measuring. */
    final class Root extends FrameLayout {
        float startX, startY;
        long startTime;
        boolean tracking;

        Root(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (sessionHost != null) {
                boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        startTime = event.getEventTime();
                        float fromStart = rtl ? getWidth() - event.getX() : event.getX();
                        tracking = sessionDrawer != null || fromStart <= sessionHost.dp(56);
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_CANCEL:
                        tracking = false;
                        break;
                    case MotionEvent.ACTION_UP:
                        float dx = event.getRawX() - startX;
                        int swipe = sessionSwipe(rtl ? -dx : dx, event.getRawY() - startY,
                                event.getEventTime() - startTime, sessionHost.dp(90));
                        if (tracking && (sessionDrawer == null ? swipe == 1 : swipe == -1)) {
                            tracking = false;
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            super.dispatchTouchEvent(cancel);
                            cancel.recycle();
                            if (swipe > 0) {
                                openSessionDrawer();
                            } else {
                                closeSessionDrawer();
                            }
                            return true;
                        }
                        tracking = false;
                        break;
                    default:
                        break;
                }
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int avail = MeasureSpec.getSize(h) - getPaddingTop() - getPaddingBottom();
            ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            int want = heightFor(Math.max(0, avail), full);
            if (lp.height != want) {
                lp.height = want;
            }
            super.onMeasure(w, h);
        }
    }

    void show() {
        dialog.show();
    }
}
