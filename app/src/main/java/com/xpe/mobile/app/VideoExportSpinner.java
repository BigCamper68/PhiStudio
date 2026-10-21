package com.xpe.mobile.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.Spinner;

/** A local caret avoids projected platform backgrounds escaping the scrolling form. */
@SuppressLint("ViewConstructor") // Created programmatically for this form, never inflated from XML.
final class VideoExportSpinner extends Spinner {
    VideoExportSpinner(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        TypedArray colors = context.obtainStyledAttributes(new int[]{
                android.R.attr.colorControlNormal, android.R.attr.colorAccent});
        int normal = colors.getColor(0, 0xFFB0BEC5), accent = colors.getColor(1, 0xFF80CBC4);
        colors.recycle();
        setBackground(new CaretBackground(density, normal, accent));
        setPaddingRelative(Math.round(8 * density), Math.round(4 * density),
                Math.round(36 * density), Math.round(4 * density));
        setMinimumHeight(Math.round(44 * density));
        setElevation(0); setStateListAnimator(null);
    }

    private static final class CaretBackground extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path caret = new Path();
        private final float density;
        private final int normal, accent;
        private int alpha = 255;

        CaretBackground(float density, int normal, int accent) {
            this.density = density; this.normal = normal; this.accent = accent;
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            boolean enabled = false, focused = false;
            for (int state : getState()) {
                if (state == android.R.attr.state_enabled) enabled = true;
                if (state == android.R.attr.state_pressed || state == android.R.attr.state_focused) focused = true;
            }
            paint.setColor(focused ? accent : normal);
            paint.setAlpha(enabled ? alpha : alpha / 2);
            int save = canvas.save(); canvas.clipRect(bounds);
            canvas.drawRect(bounds.left, bounds.bottom - density, bounds.right, bounds.bottom, paint);
            boolean rtl = getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL;
            float x = rtl ? bounds.left + 18 * density : bounds.right - 18 * density;
            float y = bounds.exactCenterY();
            caret.reset(); caret.moveTo(x - 5 * density, y - 2 * density);
            caret.lineTo(x + 5 * density, y - 2 * density);
            caret.lineTo(x, y + 3 * density); caret.close();
            canvas.drawPath(caret, paint); canvas.restoreToCount(save);
        }

        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
