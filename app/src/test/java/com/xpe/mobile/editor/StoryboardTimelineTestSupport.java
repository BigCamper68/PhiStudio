package com.xpe.mobile.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.xpe.mobile.editor.EditorSurfaceView.RectangleSelectionMode;
import com.xpe.mobile.model.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

abstract class StoryboardTimelineTestSupport {
    protected EditorView view;

    protected ChartDocument chart;

    protected StoryboardTimeline timeline;

    protected Canvas canvas;

    @Before public void setup() throws Exception {
        view = new EditorView(RuntimeEnvironment.getApplication());
        chart = ChartDocument.fromJson("{}");
        view.setChart(chart);
        view.setWorkbenchTab(1);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1200, 700);
        view.currentBeat = 4;
        timeline = view.storyboardTimeline;
        canvas = new Canvas(Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888));
        draw();
    }

    protected void draw() { timeline.draw(canvas, view.editorSplitX(), view.toolbarHeight(), 1200,
            700 - view.bottomBarHeight()); }

    protected ExtendedLineEvents.TimedEvent add(StoryboardEventType type, int start, int end) {
        ExtendedLineEvents.TimedEvent event = type == StoryboardEventType.TEXT
                ? new ExtendedLineEvents.TextEvent() : type == StoryboardEventType.COLOR
                ? new ExtendedLineEvents.ColorEvent() : new ExtendedLineEvents.NumericEvent();
        event.startTime = new BeatTime(start, 0, 1); event.endTime = new BeatTime(end, 0, 1);
        assertEquals(EditorView.StoryboardApplyResult.APPLIED, view.addStoryboardEvent(type, event));
        return event;
    }

    protected float y(double beat) { return view.beatToY(beat, view.timelineY(700 - view.bottomBarHeight())); }

    protected float x() { return view.editorSplitX() + 35 * view.density; }

    protected void touch(int action, float x, float y) {
        long time = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent event = android.view.MotionEvent.obtain(time, time, action, x, y, 0);
        view.onTouchEvent(event); event.recycle();
    }

    protected static final class Line {
        final float x1, y1, x2, y2, stroke;
        final int color;
        Line(float x1, float y1, float x2, float y2, Paint paint) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            stroke = paint.getStrokeWidth(); color = paint.getColor();
        }
    }

    protected static final class RecordingCanvas extends Canvas {
        final java.util.List<Line> lines = new java.util.ArrayList<>();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Float> labelY = new java.util.ArrayList<>();
        final java.util.List<Float> labelSize = new java.util.ArrayList<>();
        final java.util.List<Integer> lineOrders = new java.util.ArrayList<>();
        int order, lastEventRectOrder;
        Integer trackedEventRectColor;
        int rectangles;
        final java.util.List<java.util.List<org.robolectric.shadows.ShadowPath.Point>> paths = new java.util.ArrayList<>();
        final java.util.List<Float> pathWidths = new java.util.ArrayList<>();
        final java.util.List<Integer> pathColors = new java.util.ArrayList<>();
        @Override public void drawPath(android.graphics.Path path, Paint paint) {
            paths.add(new java.util.ArrayList<>(org.robolectric.Shadows.shadowOf(path).getPoints()));
            pathWidths.add(paint.getStrokeWidth()); pathColors.add(paint.getColor());
            assertEquals(Paint.Cap.ROUND, paint.getStrokeCap());
            assertEquals(Paint.Join.ROUND, paint.getStrokeJoin());
        }
        @Override public void drawLine(float x1, float y1, float x2, float y2, Paint paint) {
            lines.add(new Line(x1, y1, x2, y2, paint));
            lineOrders.add(++order);
        }
        @Override public void drawRoundRect(android.graphics.RectF rect, float rx, float ry, Paint paint) {
            ++order;
            if (trackedEventRectColor == null || trackedEventRectColor == paint.getColor()) lastEventRectOrder = order;
        }
        @Override public void drawText(String text, float x, float y, Paint paint) {
            labels.add(text); labelY.add(y); labelSize.add(paint.getTextSize());
        }
        @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            rectangles++;
        }
    }

    protected RecordingCanvas recordStoryboard() {
        RecordingCanvas result = new RecordingCanvas();
        timeline.draw(result, view.editorSplitX(), view.toolbarHeight(), 1200, 700 - view.bottomBarHeight());
        return result;
    }

    protected void assertMatchingCurves(boolean selected, LineEvent ordinary, ExtendedLineEvents.NumericEvent numeric) {
        view.selectedEvent = selected ? ordinary : null;
        RecordingCanvas expected = new RecordingCanvas();
        view.drawEvents(expected, view.editorSplitX(), view.toolbarHeight(), 1200, 700 - view.bottomBarHeight());
        view.selectedEvent = null;
        if (selected) timeline.select(StoryboardEventType.SCALE_X, numeric); else timeline.clearSelection();
        RecordingCanvas actual = recordStoryboard();
        assertEquals(1, expected.paths.size()); assertEquals(1, actual.paths.size());
        assertEquals(expected.pathWidths, actual.pathWidths); assertEquals(expected.pathColors, actual.pathColors);
        java.util.List<org.robolectric.shadows.ShadowPath.Point> a = expected.paths.get(0), b = actual.paths.get(0);
        assertTrue(a.size() >= 11); assertEquals(a.size(), b.size());
        for (int index = 0; index < a.size(); index++) {
            assertEquals(a.get(index).getType(), b.get(index).getType());
            assertEquals(a.get(index).getX(), b.get(index).getX(), .001f);
            assertEquals(a.get(index).getY(), b.get(index).getY(), .001f);
        }
    }

    protected Object[] formRequest() {
        Object[] request = new Object[3];
        view.setCallback((EditorView.Callback) java.lang.reflect.Proxy.newProxyInstance(
                EditorView.Callback.class.getClassLoader(), new Class<?>[]{EditorView.Callback.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("requestStoryboardProperties"))
                        System.arraycopy(arguments, 0, request, 0, 3);
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                }));
        return request;
    }
}
