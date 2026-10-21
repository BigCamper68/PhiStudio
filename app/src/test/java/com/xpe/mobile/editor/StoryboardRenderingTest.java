package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.xpe.mobile.editor.EditorSurfaceView.RectangleSelectionMode;
import com.xpe.mobile.model.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public final class StoryboardRenderingTest extends StoryboardTimelineTestSupport {
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

    @Test public void horizontalHeaderScrollNeverChangesBeatOrChart() {
        long revision = chart.revision();
        double beat = view.currentBeat;
        float yy = view.toolbarHeight() + 12 * view.density;
        timeline.down(x(), yy); timeline.move(x() - 160 * view.density, yy); timeline.up(x(), yy);
        assertEquals(beat, view.currentBeat, 0);
        assertEquals(revision, chart.revision());
    }
}
