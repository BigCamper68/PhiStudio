package com.xpe.mobile.editor;

import com.xpe.mobile.i18n.ModelLabels;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import com.xpe.mobile.editor.EditorSurfaceView.Tool;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

/** EditorTimelinePainter responsibilities extracted from EditorSurfaceView. */
final class EditorTimelinePainter {
    private final EditorSurfaceView delegateOwner;

    EditorTimelinePainter(EditorSurfaceView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    final void drawNoteBackground(
            Canvas canvas, float left, float top, float right, float bottom) {
        // A docked IME can leave less space than the fixed toolbar and footer.
        // There is then no timeline to crop into; keep the illustration for the next frame.
        if (right <= left || bottom <= top) return;
        delegateOwner.paint.setColor(Color.rgb(10, 14, 18));
        canvas.drawRect(left, top, right, bottom, delegateOwner.paint);
        Bitmap illustration = delegateOwner.backgroundIllustration;
        if (illustration == null || illustration.isRecycled()) return;
        RectF destination = new RectF(left, top, right, bottom);
        CenterCropCalculator.Crop crop = CenterCropCalculator.calculate(
                illustration.getWidth(), illustration.getHeight(),
                destination.width(), destination.height());
        Rect source = new Rect(crop.left, crop.top, crop.right, crop.bottom);
        int save = canvas.save();
        canvas.clipRect(destination);
        delegateOwner.backgroundPaint.setAlpha(delegateOwner.settings.backgroundBrightness);
        canvas.drawBitmap(illustration, source, destination, delegateOwner.backgroundPaint);
        canvas.restoreToCount(save);
    }

    final void prepareTimelineGridPaint() {
        // The shared paint may still have the thicker judgment-line stroke from the last grid.
        delegateOwner.paint.setStyle(Paint.Style.FILL);
        delegateOwner.paint.setStrokeWidth(delegateOwner.density);
    }

    final void drawNoteGrid(
            Canvas canvas, float left, float top, float right, float bottom) {
        float centerY = delegateOwner.timelineY(bottom);
        delegateOwner.prepareTimelineGridPaint();
        TimelineGridRange.Range range = TimelineGridRange.visible(
                delegateOwner.currentBeat, centerY, top, bottom, delegateOwner.pixelsPerBeat, delegateOwner.subdivision);
        for (int step = range.firstStep; step <= range.lastStep; step++) {
            double beat = step / (double) delegateOwner.subdivision;
            float y = delegateOwner.beatToY(beat, centerY);
            if (y < top || y > bottom) continue;
            boolean major = Math.floorMod(step, delegateOwner.subdivision) == 0;
            delegateOwner.paint.setColor(major ? EditorSurfaceView.GRID_MAJOR : EditorSurfaceView.GRID_MINOR);
            canvas.drawLine(left, y, right, y, delegateOwner.paint);

        }

        delegateOwner.drawTimelineBeatNumbers(canvas, left, top, left + 38f * delegateOwner.density, bottom,
                delegateOwner.currentBeat, delegateOwner.pixelsPerBeat, false);
        for (int index = 0; index < delegateOwner.verticalGridLines; index++) {
            float x = VerticalGrid.screenX(index, delegateOwner.verticalGridLines, left, right);
            delegateOwner.paint.setColor(index == delegateOwner.verticalGridLines / 2
                    ? Color.rgb(83, 101, 62) : EditorSurfaceView.GRID_MINOR);
            canvas.drawLine(x, top, x, bottom, delegateOwner.paint);
        }

        delegateOwner.drawCurrentBeatLine(canvas, left, right, bottom);
    }

    final void drawCurrentBeatLine(Canvas canvas, float left, float right, float bottom) {
        delegateOwner.paint.setStyle(Paint.Style.FILL);
        delegateOwner.paint.setStrokeWidth(2.4f * delegateOwner.density);
        delegateOwner.paint.setColor(Color.rgb((delegateOwner.settings.lineColorRgb >> 16) & 0xff,
                (delegateOwner.settings.lineColorRgb >> 8) & 0xff, delegateOwner.settings.lineColorRgb & 0xff));
        float y = delegateOwner.timelineY(bottom);
        canvas.drawLine(left, y, right, y, delegateOwner.paint);
    }

    final void drawTimelineRows(Canvas canvas, float left, float top, float right,
                                      float bottom, double position, float scale, int divisions) {
        float baseline = delegateOwner.timelineY(bottom);
        delegateOwner.prepareTimelineGridPaint();
        TimelineGridRange.Range range = TimelineGridRange.visible(
                position, baseline, top, bottom, scale, divisions);
        for (int step = range.firstStep; step <= range.lastStep; step++) {
            float y = baseline - (float) (step / (double) divisions - position) * scale;
            if (y < top || y > bottom) continue;
            delegateOwner.paint.setColor(Math.floorMod(step, divisions) == 0 ? EditorSurfaceView.GRID_MAJOR : EditorSurfaceView.GRID_MINOR);
            canvas.drawLine(left, y, right, y, delegateOwner.paint);
        }
    }

    final void drawTimelineBeatNumbers(Canvas canvas, float left, float top, float right,
                                             float bottom, double position, float scale,
                                             boolean rightAligned) {
        int save = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        delegateOwner.paint.setStyle(Paint.Style.FILL);
        delegateOwner.paint.setTextSize(11f * delegateOwner.density);
        delegateOwner.paint.setTextAlign(rightAligned ? Paint.Align.RIGHT : Paint.Align.LEFT);
        delegateOwner.paint.setColor(Color.LTGRAY);
        float offset = 3f * delegateOwner.density;
        float ascent = delegateOwner.paint.ascent(), descent = delegateOwner.paint.descent();
        float baseline = delegateOwner.timelineY(bottom);
        TimelineGridRange.Range labels = TimelineGridRange.visible(position, baseline,
                top + offset - descent, bottom + offset - ascent, scale, 1);
        for (int tick = labels.firstStep; tick <= labels.lastStep; tick++) {
            float textY = baseline - (float) (tick - position) * scale - offset;
            if (textY + descent >= top && textY + ascent <= bottom)
                canvas.drawText(Integer.toString(tick), rightAligned ? right - 4f * delegateOwner.density
                        : left + 5f * delegateOwner.density, textY, delegateOwner.paint);
        }
        delegateOwner.paint.setTextAlign(Paint.Align.LEFT);
        canvas.restoreToCount(save);
    }

    final void drawTimelineBaseline(Canvas canvas, float right, float bottom) {
        delegateOwner.drawCurrentBeatLine(canvas, 0, right, bottom);
    }

    final void drawTimelineBlock(Canvas canvas, RectF rect, int color) {
        delegateOwner.paint.setStyle(Paint.Style.FILL);
        delegateOwner.paint.setColor(color);
        canvas.drawRoundRect(rect, 4f * delegateOwner.density, 4f * delegateOwner.density, delegateOwner.paint);
    }

    final void drawTimelineSelection(Canvas canvas, RectF rect, float startY, float endY) {
        delegateOwner.drawSelection(canvas, rect);
        delegateOwner.drawEventHandles(canvas, rect.centerX(), startY, endY, false);
    }

    final void drawEventGrid(
            Canvas canvas, float left, float top, float right, float bottom) {
        delegateOwner.drawTimelineRows(canvas, left, top, right, bottom, delegateOwner.currentBeat, delegateOwner.pixelsPerBeat, delegateOwner.subdivision);

        EventType[] types = EventType.values();
        float width = (right - left) / types.length;
        for (int index = 0; index <= types.length; index++) {
            delegateOwner.paint.setColor(Color.rgb(58, 67, 76));
            canvas.drawLine(left + index * width, top, left + index * width, bottom, delegateOwner.paint);
        }
        delegateOwner.drawCurrentBeatLine(canvas, left, right, bottom);
    }

    final void drawEventHeaders(Canvas canvas, float left, float top, float right) {
        int save = canvas.save();
        canvas.clipRect(left, top, right, top + 22f * delegateOwner.density);
        EventType[] types = EventType.values();
        float width = (right - left) / types.length;
        delegateOwner.paint.setStyle(Paint.Style.FILL);
        delegateOwner.paint.setTextAlign(Paint.Align.CENTER);
        delegateOwner.paint.setTextSize(10.5f * delegateOwner.density);
        for (int index = 0; index < types.length; index++) {
            delegateOwner.paint.setColor(types[index] == delegateOwner.selectedEventType && delegateOwner.tool == Tool.EVENT
                    ? EditorSurfaceView.ACCENT : Color.LTGRAY);
            canvas.drawText(ModelLabels.event(delegateOwner.getContext(), types[index]), left + (index + .5f) * width,
                    top + 17f * delegateOwner.density, delegateOwner.paint);
        }
        delegateOwner.paint.setTextAlign(Paint.Align.LEFT);
        canvas.restoreToCount(save);
    }

    final void drawEventEasingCurve(
            Canvas canvas, LineEvent event, RectF rect, boolean invalid) {
        delegateOwner.drawTimelineEasingCurve(canvas, rect, invalid, event == delegateOwner.selectedEvent, event::easedProgressAt);
    }

    final void drawTimelineEasingCurve(Canvas canvas, RectF rect, boolean invalid,
                                                boolean selected, java.util.function.DoubleUnaryOperator easing) {
        float insetX = Math.min(5f * delegateOwner.density, rect.width() * 0.14f);
        float insetY = Math.min(4f * delegateOwner.density, rect.height() * 0.12f);
        float curveLeft = rect.left + insetX;
        float curveRight = rect.right - insetX;
        float curveTop = rect.top + insetY;
        float curveBottom = rect.bottom - insetY;
        if (curveRight <= curveLeft || curveBottom <= curveTop) return;

        int samples = Math.max(10, Math.min(72,
                Math.round((curveBottom - curveTop) / Math.max(2f * delegateOwner.density, 1f))));
        delegateOwner.eventCurvePath.reset();
        for (int index = 0; index <= samples; index++) {
            double t = index / (double) samples;
            double evaluated = easing.applyAsDouble(t);
            float progress = Double.isFinite(evaluated)
                    ? EditorSurfaceView.clamp((float) evaluated, -2f, 3f) : (float) t;
            float x = curveLeft + progress * (curveRight - curveLeft);
            float y = curveBottom - (float) t * (curveBottom - curveTop);
            if (index == 0) delegateOwner.eventCurvePath.moveTo(x, y);
            else delegateOwner.eventCurvePath.lineTo(x, y);
        }

        int save = canvas.save();
        canvas.clipRect(rect);
        delegateOwner.strokePaint.setColor(invalid ? Color.WHITE : EditorSurfaceView.EVENT_EASING_CURVE);
        delegateOwner.strokePaint.setStrokeWidth((selected ? 2.6f : 2.1f) * delegateOwner.density);
        delegateOwner.strokePaint.setStrokeCap(Paint.Cap.ROUND);
        delegateOwner.strokePaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(delegateOwner.eventCurvePath, delegateOwner.strokePaint);
        canvas.restoreToCount(save);
    }
}
