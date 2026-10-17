package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import com.xpe.mobile.editor.EditorSurfaceView.GestureMode;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

/** EditorEventPainter responsibilities extracted from EditorSurfaceView. */
final class EditorEventPainter {
    private final EditorSurfaceView delegateOwner;

    EditorEventPainter(EditorSurfaceView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    final void drawEvents(
            Canvas canvas, float left, float top, float right, float bottom) {
        EventLayer layer = delegateOwner.currentLayer();
        EventType[] types = EventType.values();
        float columnWidth = (right - left) / types.length;
        float centerY = delegateOwner.timelineY(bottom);
        delegateOwner.paint.setTextAlign(Paint.Align.CENTER);
        delegateOwner.paint.setTextSize(8.5f * delegateOwner.density);

        for (int typeIndex = 0; typeIndex < types.length; typeIndex++) {
            EventType type = types[typeIndex];
            for (LineEvent event : layer.events(type)) {
                boolean draggedPreview = event == delegateOwner.draggedEvent || event == delegateOwner.draggedEventPair;
                BeatTime visibleStart = draggedPreview && delegateOwner.dragCandidateStart != null
                        ? delegateOwner.dragCandidateStart : event.startTime;
                BeatTime visibleEnd = draggedPreview && delegateOwner.dragCandidateEnd != null
                        ? delegateOwner.dragCandidateEnd : event.endTime;
                float startY = delegateOwner.beatToY(visibleStart.toDouble(), centerY);
                float endY = delegateOwner.beatToY(visibleEnd.toDouble(), centerY);
                float rectTop = Math.min(startY, endY);
                float rectBottom = Math.max(startY, endY);
                if (rectBottom < top || rectTop > bottom) continue;
                float x1 = left + typeIndex * columnWidth + 3f * delegateOwner.density;
                float x2 = left + (typeIndex + 1) * columnWidth - 3f * delegateOwner.density;
                if (rectBottom - rectTop < 6f * delegateOwner.density) rectBottom = rectTop + 6f * delegateOwner.density;
                RectF rect = new RectF(x1, rectTop, x2, rectBottom);
                boolean invalidDrag = draggedPreview
                        && (delegateOwner.dragValidation != EventDragValidator.Error.NONE
                        || delegateOwner.xyDragValidation != XYBindingValidator.Error.NONE);
                delegateOwner.drawTimelineBlock(canvas, rect, invalidDrag
                        ? EditorSurfaceView.INVALID_PREVIEW : delegateOwner.eventColor(type, event.linkGroup != 0));
                if (delegateOwner.settings.drawEventCurves) {
                    delegateOwner.drawEventEasingCurve(canvas, event, rect, invalidDrag);
                }
                if (delegateOwner.settings.drawEventNumbers && rect.height() > 30f * delegateOwner.density) {
                    delegateOwner.paint.setColor(Color.WHITE);
                    delegateOwner.paint.setTextSize(7.5f * delegateOwner.density);
                    canvas.drawText(EditorSurfaceView.compactValue(event.end), rect.centerX(),
                            rect.top + 9f * delegateOwner.density, delegateOwner.paint);
                    canvas.drawText(EditorSurfaceView.compactValue(event.start), rect.centerX(),
                            rect.bottom - 3f * delegateOwner.density, delegateOwner.paint);
                }
                if (event == delegateOwner.selectedEvent || delegateOwner.selectedEvents.contains(event)) {
                    delegateOwner.drawSelection(canvas, rect);
                    if (event == delegateOwner.selectedEvent && delegateOwner.selectionCount() == 1) {
                        delegateOwner.drawEventHandles(canvas, rect.centerX(), startY, endY, invalidDrag);
                    }
                }
            }
        }
        delegateOwner.paint.setTextAlign(Paint.Align.LEFT);
    }

    final void drawEventHandles(
            Canvas canvas, float centerX, float startY, float endY, boolean invalidDrag) {
        float radius = 9f * delegateOwner.density;
        delegateOwner.paint.setColor(invalidDrag ? EditorSurfaceView.INVALID_PREVIEW : Color.WHITE);
        canvas.drawCircle(centerX, startY, radius, delegateOwner.paint);
        canvas.drawCircle(centerX, endY, radius, delegateOwner.paint);
        delegateOwner.paint.setColor(Color.rgb(25, 31, 38));
        delegateOwner.paint.setTextAlign(Paint.Align.CENTER);
        delegateOwner.paint.setTextSize(8f * delegateOwner.density);
        float baselineOffset = -(delegateOwner.paint.ascent() + delegateOwner.paint.descent()) / 2f;
        canvas.drawText("S", centerX, startY + baselineOffset, delegateOwner.paint);
        canvas.drawText("E", centerX, endY + baselineOffset, delegateOwner.paint);
        delegateOwner.paint.setTextAlign(Paint.Align.LEFT);
    }

    final void drawSelectionRectangle(Canvas canvas) {
        if (delegateOwner.gestureMode != GestureMode.RECT_SELECT || delegateOwner.selectionRectangle == null) return;
        RectF bounds = EditorSurfaceView.normalized(delegateOwner.selectionRectangle);
        delegateOwner.paint.setColor(EditorSurfaceView.withAlpha(EditorSurfaceView.ACCENT, 48));
        canvas.drawRect(bounds, delegateOwner.paint);
        delegateOwner.strokePaint.setColor(EditorSurfaceView.ACCENT);
        delegateOwner.strokePaint.setStrokeWidth(2f * delegateOwner.density);
        canvas.drawRect(bounds, delegateOwner.strokePaint);
    }

    final void drawPlacementPreview(
            Canvas canvas, float split, float top, float bottom) {
        if (delegateOwner.gestureMode == GestureMode.HOLD_PREVIEW) {
            float centerY = delegateOwner.timelineY(bottom);
            float x = EditorSurfaceView.chartXToScreen(EditorSurfaceView.screenToChartX(delegateOwner.gestureStartX, 0f, split), 0f, split);
            float startY = delegateOwner.beatToY(delegateOwner.previewStartBeat, centerY);
            float endY = delegateOwner.beatToY(delegateOwner.previewEndBeat, centerY);
            delegateOwner.drawHoldTexture(canvas, x, startY, endY, 58f * delegateOwner.density, 58f * delegateOwner.density, false, 150);
        } else if (delegateOwner.gestureMode == GestureMode.EVENT_PREVIEW && delegateOwner.previewEventType != null) {
            EventType[] types = EventType.values();
            int index = delegateOwner.previewEventType.ordinal();
            float width = (delegateOwner.getWidth() - split) / types.length;
            float centerY = delegateOwner.timelineY(bottom);
            RectF rect = new RectF(
                    split + index * width + 3f * delegateOwner.density,
                    Math.min(delegateOwner.beatToY(delegateOwner.previewStartBeat, centerY),
                            delegateOwner.beatToY(delegateOwner.previewEndBeat, centerY)),
                    split + (index + 1) * width - 3f * delegateOwner.density,
                    Math.max(delegateOwner.beatToY(delegateOwner.previewStartBeat, centerY),
                            delegateOwner.beatToY(delegateOwner.previewEndBeat, centerY)));
            delegateOwner.paint.setColor(EditorSurfaceView.withAlpha(delegateOwner.eventColor(delegateOwner.previewEventType, false), 145));
            canvas.drawRoundRect(rect, 4f * delegateOwner.density, 4f * delegateOwner.density, delegateOwner.paint);
        }
    }
}
