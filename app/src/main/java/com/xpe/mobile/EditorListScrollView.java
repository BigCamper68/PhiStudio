package com.xpe.mobile;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;

/** A list viewport inside a scrollable form: own the drag, pass excess distance via nested scroll. */
final class EditorListScrollView extends ScrollView {
    EditorListScrollView(Context context) {
        super(context);
        setFillViewport(true);
        setNestedScrollingEnabled(true);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // Also recover after a cancelled gesture, page switch, or rows rebuilt by a click.
            stopNestedScroll();
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            startNestedScroll(View.SCROLL_AXIS_VERTICAL);
        }
        try {
            return super.dispatchTouchEvent(event);
        } finally {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) releaseDrag();
        }
    }

    private void releaseDrag() {
        stopNestedScroll();
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }

    @Override protected void onDetachedFromWindow() {
        releaseDrag();
        super.onDetachedFromWindow();
    }
}
