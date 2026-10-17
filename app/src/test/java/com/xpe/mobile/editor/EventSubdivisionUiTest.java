package com.xpe.mobile.editor;

import com.xpe.mobile.R;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public final class EventSubdivisionUiTest {
    private EditorView view;
    private LineEvent source;

    @Before public void setup() throws Exception {
        view = new EditorView(RuntimeEnvironment.getApplication());
        view.setChart(ChartDocument.fromJson("{}"));
        view.currentLine().eventLayers.set(0, new com.xpe.mobile.model.EventLayer());
        view.xyBindingEnabled = false;
        view.subdivision = 32;
        view.currentBeat = 1;
        view.bottomPanel = EditorChromeView.BottomPanel.EDIT;
        source = new LineEvent();
        source.type = EventType.ALPHA;
        source.startTime = new BeatTime(1, 0, 1);
        source.endTime = new BeatTime(5, 0, 1);
        source.start = source.end = -1;
        view.currentLayer().events(source.type).add(source);
        view.selectedEvent = source;
        view.selectedEvents.add(source);
    }

    @Test public void aSingleEventExposesBatchCloneAndBothSplitActions() {
        assertTrue(action(R.string.controls_batch_edit).enabled);
        assertTrue(action(R.string.controls_event_clone).enabled);
        assertTrue(action(R.string.event_subdivide_button).enabled);
        assertTrue(action(R.string.event_split_button).enabled);
    }

    @Test public void actionUsesCurrentGridAndSelectsAllPartsWithOneUndoAndRedo() {
        action(R.string.event_subdivide_button).action.run();
        assertEquals(128, view.currentLayer().events(EventType.ALPHA).size());
        assertEquals(128, view.selectedEvents.size());
        assertTrue(action(R.string.controls_batch_edit).enabled);
        assertTrue(action(R.string.event_subdivide_button).enabled);
        view.eventLayerIndex = 1;
        view.performUndo();
        assertEquals(0, view.eventLayerIndex);
        assertSame(source, view.currentLayer().events(EventType.ALPHA).get(0));
        assertSame(source, view.selectedEvent);
        assertEquals(1, view.selectedEvents.size());
        assertFalse(view.history.canUndo());
        view.performRedo();
        assertEquals(128, view.selectedEvents.size());
        assertEquals(128, view.currentLayer().events(EventType.ALPHA).size());
    }

    @Test public void changingTheGridImmediatelyChangesTheNumberOfParts() {
        view.subdivision = 16;
        action(R.string.event_subdivide_button).action.run();
        assertEquals(64, view.selectedEvents.size());
        view.performUndo();
        view.subdivision = 8;
        action(R.string.event_subdivide_button).action.run();
        assertEquals(32, view.selectedEvents.size());
        assertEquals(new BeatTime(1, 1, 8), view.selectedEvents.iterator().next().endTime);
    }

    @Test public void originalOnePointSplitRemainsAvailable() {
        view.currentBeat = 2;
        action(R.string.event_split_button).action.run();
        assertEquals(2, view.currentLayer().events(EventType.ALPHA).size());
        view.performUndo();
        assertEquals(1, view.currentLayer().events(EventType.ALPHA).size());
        assertEquals(new BeatTime(5, 0, 1), source.endTime);
    }

    @Test public void singleNoteAllowsBatchButDoesNotEnableEventOperations() {
        view.clearSelection();
        Note note = new Note();
        view.currentLine().notes.add(note);
        view.selectedNote = note;
        view.selectedNotes.add(note);
        assertTrue(action(R.string.controls_batch_edit).enabled);
        assertFalse(action(R.string.controls_event_clone).enabled);
        assertNull(findAction(R.string.event_subdivide_button));
    }

    private EditorChromeView.BottomAction action(int id) {
        EditorChromeView.BottomAction result = findAction(id);
        assertNotNull(view.getResources().getString(id), result);
        return result;
    }

    private EditorChromeView.BottomAction findAction(int id) {
        String label = view.getResources().getString(id);
        List<EditorChromeView.BottomAction> actions = view.editorBottomBarUi.bottomActions();
        for (EditorChromeView.BottomAction action : actions) {
            if (label.equals(action.label)) return action;
        }
        return null;
    }
}
