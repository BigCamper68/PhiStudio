package com.xpe.mobile.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;
import java.util.function.DoubleUnaryOperator;

/** Compact live preview used by the event easing property sheet. */
public final class EasingPreviewView extends View {
    private static final int CURVE_COLOR = Color.rgb(255, 150, 38);

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    private final LineEvent previewEvent = new LineEvent();
    private final float density;
    private DoubleUnaryOperator evaluator = previewEvent::easedProgressAt;

    public EasingPreviewView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        previewEvent.type = EventType.MOVE_X;
        strokePaint.setStyle(Paint.Style.STROKE);
        setMinimumHeight(Math.round(132f * density));
    }

    public void setCurve(int type, double left, double right, boolean bezier,
                         double x1, double y1, double x2, double y2) {
        previewEvent.easingType = type;
        previewEvent.easingLeft = left;
        previewEvent.easingRight = right;
        previewEvent.bezier = bezier;
        previewEvent.bezierPoints[0] = x1;
        previewEvent.bezierPoints[1] = y1;
        previewEvent.bezierPoints[2] = x2;
        previewEvent.bezierPoints[3] = y2;
        evaluator = previewEvent::easedProgressAt;
        invalidate();
    }

    /** Lets shader forms use their playback evaluator rather than approximating it. */
    public void setEvaluator(DoubleUnaryOperator evaluator) {
        this.evaluator = evaluator;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = 14f * density;
        float left = getPaddingLeft() + inset;
        float top = getPaddingTop() + inset;
        float right = getWidth() - getPaddingRight() - inset;
        float bottom = getHeight() - getPaddingBottom() - inset;
        if (right <= left || bottom <= top) return;

        // Equal coordinate units must occupy equal pixel distances on both axes.
        double minimum = 0, maximum = 1;
        for (int index = 0; index <= 128; index++) {
            double value = evaluator.applyAsDouble(index / 128.0);
            if (Double.isFinite(value)) { minimum = Math.min(minimum, value); maximum = Math.max(maximum, value); }
        }
        double span = maximum - minimum;
        PreviewViewportCalculator.Result plot = PreviewViewportCalculator.fit(
                left, top, right, bottom, span, 1);
        left = (float) plot.left;
        top = (float) plot.top;
        right = (float) plot.right;
        bottom = (float) plot.bottom;

        fillPaint.setColor(Color.rgb(25, 31, 38));
        canvas.drawRoundRect(left, top, right, bottom, 8f * density, 8f * density, fillPaint);

        strokePaint.setStrokeWidth(1f * density);
        strokePaint.setColor(Color.rgb(58, 67, 76));
        for (int index = 1; index < 4; index++) {
            float x = left + (right - left) * index / 4f;
            float y = top + (bottom - top) * index / 4f;
            canvas.drawLine(x, top, x, bottom, strokePaint);
            canvas.drawLine(left, y, right, y, strokePaint);
        }

        curvePath.reset();
        int samples = Math.max(32, Math.min(128, Math.round((right - left) / density)));
        for (int index = 0; index <= samples; index++) {
            double t = index / (double) samples;
            double progress = evaluator.applyAsDouble(t);
            if (!Double.isFinite(progress)) continue;
            float x = left + (float) ((progress - minimum) / span) * (right - left);
            float y = bottom - (float) t * (bottom - top);
            if (index == 0) curvePath.moveTo(x, y);
            else curvePath.lineTo(x, y);
        }
        int save = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        strokePaint.setStrokeWidth(3f * density);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(CURVE_COLOR);
        canvas.drawPath(curvePath, strokePaint);
        canvas.restoreToCount(save);
    }
}
