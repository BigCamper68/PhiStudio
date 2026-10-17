package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import com.xpe.mobile.model.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w1200dp-h700dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class EditorGridGestureTest {
    private EditorView view;
    private long time = 1000;

    @Before public void setup() throws Exception {
        view = new EditorView(RuntimeEnvironment.getApplication());
        view.setChart(ChartDocument.fromJson("{}"));
        view.currentLine().eventLayers.set(0, new EventLayer());
        view.xyBindingEnabled = false;
        view.tool = EditorSurfaceView.Tool.SELECT;
        view.currentBeat = 4;
        view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1200, 700);
    }
    private float y(double beat) { return view.beatToY(beat, view.timelineY(700 - view.bottomBarHeight())); }
    private float noteX() { return view.editorSplitX() / 2; }
    private float eventX() {
        return view.editorSplitX() + (EventType.ALPHA.ordinal() + .5f)
                * (view.getWidth() - view.editorSplitX()) / EventType.values().length;
    }
    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(1000, time, action, x, y, 0);
        view.onTouchEvent(event); event.recycle(); time += 20;
    }
    private void swipe(float x, float start, float delta) {
        touch(MotionEvent.ACTION_DOWN, x, start);
        touch(MotionEvent.ACTION_MOVE, x, start + delta);
        touch(MotionEvent.ACTION_UP, x, start + delta);
    }
    private Note note(int beat) {
        Note result = new Note(); result.startTime = new BeatTime(beat, 0, 1); result.endTime = result.startTime;
        view.currentLine().notes.add(result); return result;
    }
    private LineEvent event() {
        LineEvent result = new LineEvent(); result.type = EventType.ALPHA;
        result.startTime = new BeatTime(5, 0, 1); result.endTime = new BeatTime(7, 0, 1);
        result.start = 255; result.end = 0;
        view.currentLayer().events(result.type).add(result); return result;
    }
    private void select(Note note) { view.selectedNote = note; view.selectedNotes.add(note); }

    @Test public void notesSelectOnlyAfterATapIsReleased() {
        Note source = note(6); float x = noteX(), yy = y(6);
        touch(MotionEvent.ACTION_DOWN, x, yy);
        assertNull(view.selectedNote);
        assertEquals(EditorChromeView.BottomPanel.CREATE, view.bottomPanel);
        touch(MotionEvent.ACTION_UP, x, yy);
        assertSame(source, view.selectedNote);
        assertEquals(EditorChromeView.BottomPanel.EDIT, view.bottomPanel);
    }

    @Test public void upwardAndDownwardSwipesOverNotesPreserveTheEntirePreviousSelection() {
        Note source = note(6), old = note(20), other = note(21);
        select(old); view.selectedNotes.add(other);
        view.noteMoveMode = NoteMoveMode.FREE;
        for (float delta : new float[]{48, -48}) {
            view.currentBeat = 4;
            swipe(noteX(), y(6), delta);
            assertSame(old, view.selectedNote);
            assertEquals(2, view.selectedNotes.size());
            assertFalse(view.selectedNotes.contains(source));
            assertEquals(4 + delta / view.pixelsPerBeat, view.currentBeat, .001);
            assertFalse(view.history.canUndo());
        }
    }

    @Test public void swipesOverEventBodiesAndUnselectedHandlesPanWithoutSelectingOrEditing() {
        LineEvent source = event(); Note old = note(20); select(old);
        for (double beat : new double[]{5, 6, 7}) for (float delta : new float[]{48, -48}) {
            view.currentBeat = 4;
            swipe(eventX(), y(beat), delta);
            assertSame(old, view.selectedNote);
            assertNull(view.selectedEvent);
            assertTrue(view.selectedEvents.isEmpty());
            assertEquals(5, source.startTime.toDouble(), 0);
            assertEquals(7, source.endTime.toDouble(), 0);
            assertEquals(4 + delta / view.pixelsPerBeat, view.currentBeat, .001);
            assertFalse(view.history.canUndo());
        }
    }

    @Test public void anEventTapWithSmallFingerJitterStillSelects() {
        LineEvent source = event(); float yy = y(6);
        float jitter = ViewConfiguration.get(view.getContext()).getScaledTouchSlop() / 2f;
        touch(MotionEvent.ACTION_DOWN, eventX(), yy);
        assertNull(view.selectedEvent);
        touch(MotionEvent.ACTION_MOVE, eventX(), yy + jitter);
        touch(MotionEvent.ACTION_UP, eventX(), yy + jitter);
        assertSame(source, view.selectedEvent);
        assertEquals(4, view.currentBeat, 0);
    }

    @Test public void cancelledSwipesAndReturningToTheStartingPointCannotSelect() {
        note(6); float x = noteX(), yy = y(6);
        touch(MotionEvent.ACTION_DOWN, x, yy);
        touch(MotionEvent.ACTION_CANCEL, x, yy);
        touch(MotionEvent.ACTION_UP, x, yy);
        assertNull(view.selectedNote);
        touch(MotionEvent.ACTION_DOWN, x, yy);
        touch(MotionEvent.ACTION_MOVE, x, yy + 48);
        touch(MotionEvent.ACTION_MOVE, x, yy);
        touch(MotionEvent.ACTION_UP, x, yy);
        assertNull(view.selectedNote);
    }

    @Test public void aFarReleaseWithoutMoveEventsIsStillASwipe() {
        note(6); float yy = y(6);
        touch(MotionEvent.ACTION_DOWN, noteX(), yy);
        touch(MotionEvent.ACTION_UP, noteX(), yy + 48);
        assertNull(view.selectedNote);
        assertEquals(4.5, view.currentBeat, .001);
    }

    @Test public void simpleNoteToolsCreateOnTapAndDoNotCreateOnSwipe() {
        for (EditorSurfaceView.Tool tool : new EditorSurfaceView.Tool[]{EditorSurfaceView.Tool.TAP,
                EditorSurfaceView.Tool.DRAG, EditorSurfaceView.Tool.FLICK}) {
            view.tool = tool;
            int before = view.currentLine().notes.size();
            swipe(noteX(), y(6), 48);
            assertEquals(before, view.currentLine().notes.size());
            float yy = y(6);
            touch(MotionEvent.ACTION_DOWN, noteX(), yy);
            assertEquals(before, view.currentLine().notes.size());
            touch(MotionEvent.ACTION_UP, noteX(), yy);
            assertEquals(before + 1, view.currentLine().notes.size());
        }
    }

    @Test public void explicitNoteMoveAndSelectedEventResizeRemainUndoable() {
        Note source = note(6); select(source); view.noteMoveMode = NoteMoveMode.Y_ONLY;
        swipe(noteX(), y(6), -view.pixelsPerBeat);
        assertEquals(7, source.startTime.toDouble(), 0);
        view.performUndo(); assertEquals(6, source.startTime.toDouble(), 0);
        view.clearSelection();
        LineEvent event = event(); view.selectedEvent = event; view.selectedEvents.add(event);
        swipe(eventX(), y(5), -view.pixelsPerBeat);
        assertEquals(6, event.startTime.toDouble(), 0);
        assertEquals(4, view.currentBeat, 0);
        view.performUndo(); assertEquals(5, event.startTime.toDouble(), 0);
    }

    @Test public void unselectedHoldHandlesPanAndSelectedHoldHandlesResize() {
        Note hold = note(5); hold.type = NoteType.HOLD; hold.endTime = new BeatTime(7, 0, 1);
        swipe(noteX(), y(5), -48);
        assertNull(view.selectedNote);
        assertEquals(5, hold.startTime.toDouble(), 0);
        view.currentBeat = 4;
        select(hold);
        swipe(noteX(), y(5), -view.pixelsPerBeat);
        assertEquals(6, hold.startTime.toDouble(), 0);
        assertEquals(4, view.currentBeat, 0);
        view.performUndo(); assertEquals(5, hold.startTime.toDouble(), 0);
    }

    @Test public void storyboardSwipesPreserveSelectionAndTapsSelectOnlyOnRelease() {
        view.setWorkbenchTab(1); view.tool = EditorSurfaceView.Tool.SELECT;
        ExtendedLineEvents.NumericEvent old = storyboard(0, 1), source = storyboard(5, 7);
        view.storyboardTimeline.select(StoryboardEventType.SCALE_X, old);
        float x = view.editorSplitX() + 35 * view.density;
        for (float delta : new float[]{48, -48}) {
            view.currentBeat = 4; drawStoryboard();
            swipe(x, y(6), delta);
            assertSame(old, view.storyboardTimeline.selected());
            assertEquals(4 + delta / view.pixelsPerBeat, view.currentBeat, .001);
        }
        view.currentBeat = 4; drawStoryboard();
        touch(MotionEvent.ACTION_DOWN, x, y(6));
        assertSame(old, view.storyboardTimeline.selected());
        touch(MotionEvent.ACTION_UP, x, y(6));
        assertSame(source, view.storyboardTimeline.selected());
    }
    private ExtendedLineEvents.NumericEvent storyboard(int start, int end) {
        ExtendedLineEvents.NumericEvent event = new ExtendedLineEvents.NumericEvent();
        event.startTime = new BeatTime(start, 0, 1); event.endTime = new BeatTime(end, 0, 1);
        assertEquals(EditorView.StoryboardApplyResult.APPLIED, view.addStoryboardEvent(StoryboardEventType.SCALE_X, event));
        return event;
    }
    private void drawStoryboard() {
        view.storyboardTimeline.draw(new Canvas(), view.editorSplitX(), view.toolbarHeight(), 1200,
                700 - view.bottomBarHeight());
    }
}
