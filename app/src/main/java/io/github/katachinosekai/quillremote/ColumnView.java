package io.github.katachinosekai.quillremote;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Holds one child in a column whose width is worked out from the width available on every measure (Columns), so
 * rotating, resizing or pinching re-wraps at once; centered when center is set. Quill Desktop's colLayout.
 */
final class ColumnView extends FrameLayout {
    interface Width {
        float of(float avail);
    }

    final Width width;
    final boolean center;
    float lastAvail = -1;
    int lastW;
    int childW;

    ColumnView(Context c, boolean center, Width width, View child) {
        super(c);
        this.center = center;
        this.width = width;
        addView(child, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        View child = getChildAt(0);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) {
            child.measure(widthSpec, heightSpec);
            childW = child.getMeasuredWidth();
            setMeasuredDimension(childW, child.getMeasuredHeight());
            return;
        }
        int avail = MeasureSpec.getSize(widthSpec);
        if (avail != lastAvail) {
            lastAvail = avail;
            lastW = Math.round(width.of(avail));
        }
        int w = lastW <= 0 ? avail : Math.min(lastW, avail);
        child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        childW = w;
        setMeasuredDimension(avail, child.getMeasuredHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        View child = getChildAt(0);
        int x = center ? Math.max(0, (r - l - childW) / 2) : 0;
        child.layout(x, 0, x + childW, child.getMeasuredHeight());
    }
}
