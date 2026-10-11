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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class StoryboardTimelineTest {
    private EditorView view;
    private ChartDocument chart;
    private StoryboardTimeline timeline;
    private Canvas canvas;
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
    private void draw() { timeline.draw(canvas, view.editorSplitX(), view.toolbarHeight(), 1200,
            700 - view.bottomBarHeight()); }
    private ExtendedLineEvents.TimedEvent add(StoryboardEventType type, int start, int end) {
        ExtendedLineEvents.TimedEvent event = type == StoryboardEventType.TEXT
                ? new ExtendedLineEvents.TextEvent() : type == StoryboardEventType.COLOR
                ? new ExtendedLineEvents.ColorEvent() : new ExtendedLineEvents.NumericEvent();
        event.startTime = new BeatTime(start, 0, 1); event.endTime = new BeatTime(end, 0, 1);
        assertEquals(EditorView.StoryboardApplyResult.APPLIED, view.addStoryboardEvent(type, event));
        return event;
    }
    private float y(double beat) { return view.beatToY(beat, view.timelineY(700 - view.bottomBarHeight())); }
    private float x() { return view.editorSplitX() + 35 * view.density; }
    private void touch(int action, float x, float y) {
        long time = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent event = android.view.MotionEvent.obtain(time, time, action, x, y, 0);
        view.onTouchEvent(event); event.recycle();
    }
    @Test public void clearingSelectionByTapDoesNotSeekButNextEmptyTapDoes() {
        view.settings.tapToSeek=true;
        ExtendedLineEvents.TimedEvent event=add(StoryboardEventType.SCALE_X,4,5);
        view.tool=EditorSurfaceView.Tool.SELECT; timeline.select(StoryboardEventType.SCALE_X,event); draw();
        float yy=y(7); double before=view.currentBeat;
        timeline.down(x(),yy); timeline.up(x(),yy);
        assertEquals(0,timeline.selectionCount()); assertEquals(before,view.currentBeat,0);
        timeline.down(x(),yy); timeline.up(x(),yy);
        assertEquals(7,view.currentBeat,.001);
    }
    @Test public void singleTapCreatesExactlyTheSelectedGridFractionInEveryFamily() {
        Object[] request = formRequest();
        for (int division : new int[]{1, 2, 4, 8, 16, 32}) {
            for (StoryboardEventType type : StoryboardEventType.editableTypes()) {
                timeline.reset(); view.subdivision = division; view.tool = EditorSurfaceView.Tool.EVENT;
                view.currentBeat = 4; draw();
                int lane = java.util.Arrays.asList(StoryboardEventType.editableTypes()).indexOf(type);
                float width = StoryboardTimelineGeometry.laneWidth(1200 - view.editorSplitX(), view.density);
                // Scroll to GIF through the same header gesture used on the real screen.
                if (lane == 5) {
                    view.tool = EditorSurfaceView.Tool.SELECT;
                    timeline.down(x(), view.toolbarHeight() + 12 * view.density);
                    timeline.move(x() - width, view.toolbarHeight() + 12 * view.density);
                    timeline.up(x() - width, view.toolbarHeight() + 12 * view.density);
                    view.tool = EditorSurfaceView.Tool.EVENT; draw();
                }
                float xx = view.editorSplitX() + (lane - (lane == 5 ? 1 : 0) + .5f) * width;
                touch(0, xx, y(4)); touch(1, xx, y(4));
                ExtendedLineEvents.TimedEvent event = timeline.selected();
                assertNotNull(type + " at 1/" + division, event);
                assertEquals(4, event.startTime.toDouble(), 0);
                assertEquals(1.0 / division, event.endTime.minus(event.startTime).toDouble(), .000001);
                assertTrue(chart.judgeLines.get(0).extended.contains(type, event));
                assertNull(request[1]);
                view.performUndo();
            }
        }
    }
    @Test public void downwardCreationAndCancelledCreationMatchOrdinaryEventTouchRules() {
        view.tool = EditorSurfaceView.Tool.EVENT;
        touch(0, x(), y(5)); touch(2, x(), y(4)); touch(1, x(), y(4));
        assertEquals(5, timeline.selected().startTime.toDouble(), 0);
        assertEquals(5.25, timeline.selected().endTime.toDouble(), 0);
        int count = chart.totalEvents();
        touch(0, x(), y(7)); touch(2, x(), y(8)); touch(3, x(), y(8));
        assertEquals(count, chart.totalEvents());
    }
    @Test public void boxSelectsStoryboardAcrossFamiliesAndNeverHiddenNormalEvents() {
        add(StoryboardEventType.SCALE_X, 4, 6);
        add(StoryboardEventType.SCALE_Y, 4, 6);
        LineEvent hidden = new LineEvent(); hidden.type = EventType.MOVE_X;
        hidden.startTime = new BeatTime(4, 0, 1); hidden.endTime = new BeatTime(6, 0, 1);
        view.currentLayer().events(hidden.type).add(hidden);
        draw(); view.tool = EditorSurfaceView.Tool.SELECT;
        view.rectangleSelectionMode = RectangleSelectionMode.ADD;
        float width = StoryboardTimelineGeometry.laneWidth(1200 - view.editorSplitX(), view.density);
        touch(0, view.editorSplitX() + 1, y(6.25));
        touch(2, view.editorSplitX() + width * 2 - 1, y(3.75));
        touch(1, view.editorSplitX() + width * 2 - 1, y(3.75));
        assertEquals(2, timeline.selectionCount());
        assertTrue(view.selectedEvents.isEmpty()); assertNull(view.selectedEvent);
        view.performDeleteSelection();
        assertEquals(0, timeline.selectionCount());
        assertTrue(view.currentLayer().events(hidden.type).contains(hidden));
        assertTrue(chart.judgeLines.get(0).extended.scaleXEvents.isEmpty());
        assertTrue(chart.judgeLines.get(0).extended.scaleYEvents.isEmpty());
        view.performUndo(); assertEquals(2, timeline.selectionCount());
        view.performRedo(); assertEquals(0, timeline.selectionCount());
    }
    @Test public void boxTapAddsToSelectionAndRepeatedBoxDoesNotDuplicateEvents() {
        add(StoryboardEventType.SCALE_X, 4, 5);
        add(StoryboardEventType.SCALE_X, 5, 6); draw();
        view.rectangleSelectionMode = RectangleSelectionMode.ADD;
        touch(0, x(), y(4.5)); touch(1, x(), y(4.5));
        touch(0, x(), y(5.5)); touch(1, x(), y(5.5));
        touch(0, x(), y(5.5)); touch(1, x(), y(5.5));
        assertEquals(2, timeline.selectionCount());
        assertTrue(view.selectedEvents.isEmpty());
    }
    @Test public void boxUsesScrolledLaneGeometryAndIgnoresOffscreenFamilies() {
        ExtendedLineEvents.TimedEvent gif = add(StoryboardEventType.GIF, 4, 6);
        add(StoryboardEventType.SCALE_X, 4, 6);
        timeline.select(StoryboardEventType.GIF, gif); draw(); timeline.clearSelection();
        float width = StoryboardTimelineGeometry.laneWidth(1200 - view.editorSplitX(), view.density);
        view.rectangleSelectionMode = RectangleSelectionMode.ADD;
        float left = 1200 - width;
        touch(0, left + 1, y(6.25)); touch(2, 1199, y(3.75)); touch(1, 1199, y(3.75));
        assertEquals(1, timeline.selectionCount()); assertSame(gif, timeline.selected());
    }
    @Test public void multiClipboardKeepsRelativeTimesAndRejectsOverlappingPasteAtomically() {
        add(StoryboardEventType.SCALE_X, 4, 5);
        add(StoryboardEventType.TEXT, 5, 6); draw();
        timeline.selectRectangle(new android.graphics.RectF(view.editorSplitX(), y(6.25), 1200, y(3.75)), true);
        assertEquals(2, timeline.selectionCount()); timeline.copy();
        view.currentBeat = 8; timeline.paste();
        assertEquals(2, chart.judgeLines.get(0).extended.scaleXEvents.size());
        assertEquals(9, chart.judgeLines.get(0).extended.textEvents.get(1).startTime.toDouble(), 0);
        int count = chart.totalEvents(); timeline.paste(); assertEquals(count, chart.totalEvents());
        view.performUndo();
        assertEquals(1, chart.judgeLines.get(0).extended.scaleXEvents.size());
        assertEquals(1, chart.judgeLines.get(0).extended.textEvents.size());
        view.performRedo(); assertEquals(count, chart.totalEvents());
    }
    @Test public void playbackPanPausesTheClockAndResumesAudioAtTheScrolledBeat() throws Exception {
        long[] state = {0, 0, -1};
        view.setCallback((EditorView.Callback) java.lang.reflect.Proxy.newProxyInstance(
                EditorView.Callback.class.getClassLoader(), new Class<?>[]{EditorView.Callback.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("isAudioReady")) return true;
                    if (method.getName().equals("pauseAudio")) state[0]++;
                    if (method.getName().equals("startAudio")) { state[1]++; state[2] = (long) arguments[0]; }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                }));
        view.playing = true; view.tool = EditorSurfaceView.Tool.SELECT;
        touch(0, x(), y(5)); assertTrue(view.playbackScrubbing);
        assertEquals(1, state[0]);
        touch(2, x(), y(5) + view.pixelsPerBeat);
        assertEquals(5, view.currentBeat, .001);
        java.lang.reflect.Method advance = EditorChromeView.class.getDeclaredMethod("advancePlayback");
        advance.setAccessible(true);
        try { advance.invoke(view); } catch (Exception error) { throw new AssertionError(error); }
        assertEquals(5, view.currentBeat, .001);
        touch(1, x(), y(5) + view.pixelsPerBeat);
        assertFalse(view.playbackScrubbing); assertEquals(1, state[1]);
        assertEquals(chart.beatToAudioMillis(view.currentBeat, view.packageOffsetMs), state[2]);
        assertTrue(view.playing);
    }
    @Test public void longPressSelectDoesNotCreateOrOpenAFormAndNormalEventSelectionIsClearedOnTabSwitch() {
        Object[] request = formRequest(); int count = chart.totalEvents();
        touch(0, x(), y(4.5));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(900));
        touch(1, x(), y(4.5));
        assertEquals(count, chart.totalEvents()); assertNull(request[1]);
        view.setWorkbenchTab(0);
        LineEvent hidden = new LineEvent(); view.selectedEvent = hidden; view.selectedEvents.add(hidden);
        view.setWorkbenchTab(1);
        assertNull(view.selectedEvent); assertTrue(view.selectedEvents.isEmpty());
    }

    private static final class Line {
        final float x1, y1, x2, y2, stroke;
        final int color;
        Line(float x1, float y1, float x2, float y2, Paint paint) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            stroke = paint.getStrokeWidth(); color = paint.getColor();
        }
    }
    // Assert the actual draw commands; this does not pretend to be a device pixel test.
    private static final class RecordingCanvas extends Canvas {
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
    private RecordingCanvas recordStoryboard() {
        RecordingCanvas result = new RecordingCanvas();
        timeline.draw(result, view.editorSplitX(), view.toolbarHeight(), 1200, 700 - view.bottomBarHeight());
        return result;
    }
    @Test public void storyboardAndOrdinaryEventNumbersHaveIdenticalFormatSizeAndAnchors() {
        ExtendedLineEvents.NumericEvent numeric = (ExtendedLineEvents.NumericEvent) add(StoryboardEventType.SCALE_X, 4, 6);
        numeric.start = 1.25; numeric.end = 9;
        LineEvent ordinary = new LineEvent(); ordinary.startTime = numeric.startTime;
        ordinary.endTime = numeric.endTime; ordinary.start = numeric.start; ordinary.end = numeric.end;
        view.currentLayer().events(ordinary.type).add(ordinary);
        view.settings.drawEventNumbers = true;
        RecordingCanvas expected = new RecordingCanvas();
        view.drawEvents(expected, view.editorSplitX(), view.toolbarHeight(), 1200, 700 - view.bottomBarHeight());
        RecordingCanvas actual = recordStoryboard();
        assertEquals(java.util.Arrays.asList("9", "1.25"), expected.labels);
        for (int index = 0; index < expected.labels.size(); index++) {
            int found = actual.labels.indexOf(expected.labels.get(index)); assertTrue(found >= 0);
            assertEquals(expected.labelSize.get(index), actual.labelSize.get(found));
            assertEquals(expected.labelY.get(index), actual.labelY.get(found));
        }
        assertTrue(actual.labelY.get(actual.labels.indexOf("9")) < actual.labelY.get(actual.labels.indexOf("1.25")));
    }
    @Test public void storyboardUsesExactlyTheOrdinaryCurveGeometryAndStrokeIncludingLongEvents() {
        ExtendedLineEvents.NumericEvent numeric = (ExtendedLineEvents.NumericEvent) add(StoryboardEventType.SCALE_X, 4, 6);
        LineEvent ordinary = new LineEvent(); ordinary.startTime = numeric.startTime;
        view.currentLayer().events(ordinary.type).add(ordinary);
        view.settings.drawEventCurves = true;
        for (int duration : new int[]{1, 8, 400}) {
            numeric.endTime = ordinary.endTime = new BeatTime(4 + duration, 0, 1);
            for (int easing = Easing.MIN_TYPE; easing <= Easing.MAX_TYPE; easing++) {
                numeric.easingType = ordinary.easingType = easing;
                numeric.easingLeft = ordinary.easingLeft = .1;
                numeric.easingRight = ordinary.easingRight = .9;
                assertMatchingCurves(false, ordinary, numeric);
            }
            numeric.bezier = ordinary.bezier = true;
            double[] points = {.2, -.3, .8, 1.3};
            System.arraycopy(points, 0, numeric.bezierPoints, 0, 4);
            System.arraycopy(points, 0, ordinary.bezierPoints, 0, 4);
            assertMatchingCurves(true, ordinary, numeric);
            numeric.bezier = ordinary.bezier = false;
        }
    }
    private void assertMatchingCurves(boolean selected, LineEvent ordinary, ExtendedLineEvents.NumericEvent numeric) {
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
    @Test public void storyboardGridResetsTheThickerStrokeLeftByTheNoteGrid() {
        view.drawNoteGrid(canvas, 0, view.toolbarHeight(), view.editorSplitX(), 700 - view.bottomBarHeight());
        assertEquals(2.4f * view.density, view.paint.getStrokeWidth(), .001f);
        RecordingCanvas drawn = recordStoryboard();
        assertFalse(drawn.lines.isEmpty());
        for (Line line : drawn.lines) {
            assertEquals(line.color == EditorSurfaceView.ACCENT ? 2.4f * view.density : view.density,
                    line.stroke, .001f);
        }
        view.paint.setStrokeWidth(9 * view.density);
        view.paint.setStyle(Paint.Style.STROKE);
        recordStoryboard();
        assertEquals(Paint.Style.FILL, view.paint.getStyle());
        assertEquals(2.4f * view.density, view.paint.getStrokeWidth(), .001f);
    }
    @Test public void storyboardAndOrdinaryEventsDrawTheSameBeatGridAndColumnWidth() {
        RecordingCanvas ordinary = new RecordingCanvas();
        view.drawEventGrid(ordinary, view.editorSplitX(), view.toolbarHeight(), 1200, 700 - view.bottomBarHeight());
        RecordingCanvas storyboard = recordStoryboard();
        java.util.List<Float> expected = new java.util.ArrayList<>(), actual = new java.util.ArrayList<>();
        for (Line line : ordinary.lines)
            if (line.y1 == line.y2 && line.color != EditorSurfaceView.ACCENT) expected.add(line.y1);
        for (Line line : storyboard.lines)
            if (line.y1 == line.y2 && line.color != EditorSurfaceView.ACCENT) actual.add(line.y1);
        assertFalse(expected.isEmpty()); assertEquals(expected, actual);
        float width = (1200 - view.editorSplitX()) / EventType.values().length;
        int columns = 0;
        for (Line line : storyboard.lines) {
            if (line.x1 != line.x2 || line.color != Color.rgb(58, 67, 76)) continue;
            assertEquals(view.toolbarHeight(), line.y1, .001f);
            assertEquals(700 - view.bottomBarHeight(), line.y2, .001f);
            assertEquals(Math.round((line.x1 - view.editorSplitX()) / width),
                    (line.x1 - view.editorSplitX()) / width, .001f);
            columns++;
        }
        assertTrue(columns >= EventType.values().length);
    }
    @Test public void currentBeatLineContinuesAcrossAllGridsWithIdenticalWidthColorAndY() {
        float split = view.editorSplitX(), bottom = 700 - view.bottomBarHeight();
        for (int color : new int[]{0x5BD3AC, 0xAE38F0}) {
            view.settings.lineColorRgb = color;
            RecordingCanvas notes = new RecordingCanvas(), ordinary = new RecordingCanvas();
            view.drawNoteGrid(notes, 0, view.toolbarHeight(), split, bottom);
            view.drawEventGrid(ordinary, split, view.toolbarHeight(), 1200, bottom);
            RecordingCanvas storyboard = recordStoryboard();
            Line previous = null;
            for (RecordingCanvas recorded : new RecordingCanvas[]{notes, ordinary, storyboard}) {
                Line cursor = recorded.lines.get(recorded.lines.size() - 1);
                assertEquals(0xFF000000 | color, cursor.color);
                assertEquals(2.4f * view.density, cursor.stroke, .001f);
                assertEquals(view.timelineY(bottom), cursor.y1, .001f);
                assertEquals(cursor.y1, cursor.y2, .001f);
                if (previous != null) assertEquals(previous.y1, cursor.y1, .001f);
                assertEquals(recorded == notes ? 0 : split, cursor.x1, .001f);
                assertEquals(recorded == notes ? split : 1200, cursor.x2, .001f);
                previous = cursor;
            }
        }
    }
    @Test public void ordinaryEventCursorIsDrawnAboveEventBlocksAndNotHiddenByTheirFill() throws Exception {
        view.setWorkbenchTab(0);
        LineEvent event = new LineEvent(); event.startTime = new BeatTime(3, 0, 1);
        event.endTime = new BeatTime(7, 0, 1); view.currentLayer().events(event.type).add(event);
        RecordingCanvas recorded = new RecordingCanvas();
        recorded.trackedEventRectColor = view.eventColor(event.type, false);
        java.lang.reflect.Method draw = EditorChromeView.class.getDeclaredMethod("drawEditor", Canvas.class);
        draw.setAccessible(true); draw.invoke(view, recorded);
        boolean cursorAboveEvents = false;
        for (int index = 0; index < recorded.lines.size(); index++) {
            Line line = recorded.lines.get(index);
            if (line.color == EditorSurfaceView.ACCENT && line.stroke == 2.4f * view.density
                    && line.x1 == view.editorSplitX() && line.x2 == 1200)
                cursorAboveEvents |= recorded.lineOrders.get(index) > recorded.lastEventRectOrder;
        }
        assertTrue(recorded.lastEventRectOrder > 0); assertTrue(cursorAboveEvents);
    }
    @Test public void storyboardHeadersHaveOnlyNamesAndNoOpaqueFrameOrCountRow() {
        RecordingCanvas drawn = recordStoryboard();
        assertEquals(0, drawn.rectangles);
        assertEquals(java.util.Arrays.asList("Scale X", "Scale Y", "Color", "Text", "Paint", "GIF"), drawn.labels);
        for (int index = 0; index < drawn.labels.size(); index++) {
            assertEquals(view.toolbarHeight() + 17 * view.density, drawn.labelY.get(index), .001f);
            assertEquals(10.5f * view.density, drawn.labelSize.get(index), .001f);
        }
    }
    @Test public void areaBelowTheCompactNamesIsTimelineNotAnInvisibleOldHeader() {
        double initial = view.currentBeat;
        long revision = chart.revision();
        float top = view.toolbarHeight() + 28 * view.density;
        timeline.down(x(), top); timeline.move(x(), top + 30 * view.density); timeline.up(x(), top + 30 * view.density);
        assertNotEquals(initial, view.currentBeat, .001);
        assertEquals(revision, chart.revision());
    }

    @Test public void allSixFamiliesCanBeSelectedCopiedPastedAndUndone() {
        for (StoryboardEventType type : StoryboardEventType.editableTypes()) {
            ExtendedLineEvents.TimedEvent event = add(type, 4, 5);
            timeline.select(type, event);
            assertSame(event, timeline.selected());
            timeline.copy();
            view.currentBeat = 6;
            timeline.paste();
            assertEquals(2, chart.judgeLines.get(0).extended.events(type).size());
            assertEquals(6, timeline.selected().startTime.toDouble(), 0);
            assertEquals(7, timeline.selected().endTime.toDouble(), 0);
            view.performUndo();
            assertEquals(1, chart.judgeLines.get(0).extended.events(type).size());
            view.performRedo();
            assertEquals(2, chart.judgeLines.get(0).extended.events(type).size());
            view.currentBeat = 4;
        }
    }
    @Test public void startHandleChangesOnlyStartAndSupportsUndoRedo() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.SCALE_X, 4, 6);
        timeline.select(StoryboardEventType.SCALE_X, event); draw();
        timeline.down(x(), y(4)); timeline.move(x(), y(4.5)); timeline.up(x(), y(4.5));
        assertEquals(4.5, event.startTime.toDouble(), 0);
        assertEquals(6, event.endTime.toDouble(), 0);
        view.performUndo(); assertEquals(4, event.startTime.toDouble(), 0);
        view.performRedo(); assertEquals(4.5, event.startTime.toDouble(), 0);
    }
    @Test public void overlappingResizeIsRejectedWithoutPartiallyChangingTheChart() {
        ExtendedLineEvents.TimedEvent first = add(StoryboardEventType.SCALE_X, 4, 5);
        add(StoryboardEventType.SCALE_X, 5, 7);
        timeline.select(StoryboardEventType.SCALE_X, first); draw();
        timeline.down(x(), y(5)); timeline.move(x(), y(5.5)); timeline.up(x(), y(5.5));
        assertEquals(5, first.endTime.toDouble(), 0);
    }
    @Test public void cancellingAResizeLeavesNoMutationOrUndoEntry() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.SCALE_X, 4, 6);
        timeline.select(StoryboardEventType.SCALE_X, event); draw();
        timeline.down(x(), y(4)); timeline.move(x(), y(4.5)); timeline.cancel();
        assertEquals(4, event.startTime.toDouble(), 0);
        view.performUndo(); assertTrue(chart.judgeLines.get(0).extended.scaleXEvents.isEmpty());
    }
    @Test public void selectingAndDraggingABodyDoesNotAutoMoveOrOpenProperties() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.SCALE_X, 4, 6);
        event.easingType = 7;
        timeline.select(StoryboardEventType.SCALE_X, event); Object[] request = formRequest(); draw();
        timeline.down(x(), y(5)); timeline.move(x(), y(5.5)); timeline.up(x(), y(5.5));
        assertEquals(4, event.startTime.toDouble(), 0);
        assertEquals(6, event.endTime.toDouble(), 0);
        assertEquals(7, event.easingType);
        assertNull(request[1]);
    }
    @Test public void horizontalHeaderScrollNeverChangesBeatOrChart() {
        long revision = chart.revision();
        double beat = view.currentBeat;
        float yy = view.toolbarHeight() + 12 * view.density;
        timeline.down(x(), yy); timeline.move(x() - 160 * view.density, yy); timeline.up(x(), yy);
        assertEquals(beat, view.currentBeat, 0);
        assertEquals(revision, chart.revision());
    }
    @Test public void switchingLinesCancelsSelectionAndCannotDeleteTheOldEvent() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.TEXT, 4, 6);
        timeline.select(StoryboardEventType.TEXT, event);
        chart.judgeLines.add(new JudgeLine()); view.arrangeLine(1);
        assertNull(timeline.selected()); timeline.delete();
        assertTrue(chart.judgeLines.get(0).extended.textEvents.contains(event));
    }
    private Object[] formRequest() {
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
    @Test public void tapsOnlySelectAndPropertiesActionUsesTheActualOwnedEvent() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.SCALE_X, 4, 6);
        Object[] request = formRequest(); draw();
        timeline.down(x(), y(5)); timeline.up(x(), y(5));
        timeline.down(x(), y(5)); timeline.up(x(), y(5));
        assertNull(request[1]);
        timeline.edit();
        assertSame(event, request[1]); assertEquals(false, request[2]);
    }
    @Test public void eventToolCreatesTheDraggedIntervalDirectlyWithOneUndo() {
        Object[] request = formRequest();
        int count = chart.totalEvents();
        view.tool = EditorSurfaceView.Tool.EVENT;
        timeline.down(x(), y(4.25)); timeline.move(x(), y(5.25)); timeline.up(x(), y(5.25));
        ExtendedLineEvents.TimedEvent event = timeline.selected();
        assertNotNull(event); assertNull(request[1]);
        assertEquals(4.25, event.startTime.toDouble(), 0);
        assertEquals(5.25, event.endTime.toDouble(), 0);
        assertEquals(count + 1, chart.totalEvents());
        view.performUndo(); assertEquals(count, chart.totalEvents());
        view.performRedo(); assertEquals(count + 1, chart.totalEvents());
    }
    @Test public void editorTouchDispatchUsesTheSameStoryboardResizeAndCancelRules() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.SCALE_X, 4, 6);
        timeline.select(StoryboardEventType.SCALE_X, event); draw();
        long time = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(time, time, 0, x(), y(4), 0);
        android.view.MotionEvent move = android.view.MotionEvent.obtain(time, time + 20, 2, x(), y(4.5), 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(time, time + 40, 1, x(), y(4.5), 0);
        view.onTouchEvent(down); view.onTouchEvent(move); view.onTouchEvent(up);
        down.recycle(); move.recycle(); up.recycle();
        assertEquals(4.5, event.startTime.toDouble(), 0);
    }
    @Test public void copyingANoteDoesNotLaterPasteAnOlderStoryboardClipboard() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.TEXT, 4, 6);
        timeline.select(StoryboardEventType.TEXT, event); timeline.copy();
        assertTrue(timeline.hasClipboard()); timeline.clearSelection();
        Note note = new Note(); note.startTime = new BeatTime(2, 0, 1);
        note.endTime = note.startTime; chart.judgeLines.get(0).notes.add(note);
        view.selectedNote = note; view.selectedNotes.add(note);
        view.performCopy(); assertFalse(timeline.hasClipboard());
        view.currentBeat = 8; view.performPaste(false);
        assertEquals(2, chart.judgeLines.get(0).notes.size());
        assertEquals(1, chart.judgeLines.get(0).extended.textEvents.size());
    }
    @Test public void deletingAndUndoingPreservesHiddenInclineAndUnknownFields() throws Exception {
        chart = ChartDocument.fromJson("{\"judgeLineList\":[{\"extended\":{\"inclineEvents\":[{\"startTime\":[0,0,1],\"endTime\":[1,0,1],\"start\":8,\"end\":9,\"vendor\":\"keep\"}]}}]}");
        view.setChart(chart); view.setWorkbenchTab(1); draw();
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.TEXT, 4, 6);
        timeline.select(StoryboardEventType.TEXT, event); timeline.delete(); view.performUndo();
        assertTrue(chart.toJsonString().contains("\"vendor\":\"keep\""));
        assertTrue(chart.toJsonString().contains("inclineEvents"));
        assertTrue(chart.judgeLines.get(0).extended.textEvents.contains(event));
    }
}
