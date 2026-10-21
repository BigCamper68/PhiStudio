package com.xpe.mobile.editor;

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
public final class StoryboardTimelineTest extends StoryboardTimelineTestSupport {
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

    @Test public void downwardCreationAndCancelledCreationMatchOrdinaryEventTouchRules() {
        view.tool = EditorSurfaceView.Tool.EVENT;
        touch(0, x(), y(5)); touch(2, x(), y(4)); touch(1, x(), y(4));
        assertEquals(5, timeline.selected().startTime.toDouble(), 0);
        assertEquals(5.25, timeline.selected().endTime.toDouble(), 0);
        int count = chart.totalEvents();
        touch(0, x(), y(7)); touch(2, x(), y(8)); touch(3, x(), y(8));
        assertEquals(count, chart.totalEvents());
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

    @Test public void overlappingResizeIsRejectedWithoutPartiallyChangingTheChart() {
        ExtendedLineEvents.TimedEvent first = add(StoryboardEventType.SCALE_X, 4, 5);
        add(StoryboardEventType.SCALE_X, 5, 7);
        timeline.select(StoryboardEventType.SCALE_X, first); draw();
        timeline.down(x(), y(5)); timeline.move(x(), y(5.5)); timeline.up(x(), y(5.5));
        assertEquals(5, first.endTime.toDouble(), 0);
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

    @Test public void tapsOnlySelectAndPropertiesActionUsesTheActualOwnedEvent() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.SCALE_X, 4, 6);
        Object[] request = formRequest(); draw();
        timeline.down(x(), y(5)); timeline.up(x(), y(5));
        timeline.down(x(), y(5)); timeline.up(x(), y(5));
        assertNull(request[1]);
        timeline.edit();
        assertSame(event, request[1]); assertEquals(false, request[2]);
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
}
