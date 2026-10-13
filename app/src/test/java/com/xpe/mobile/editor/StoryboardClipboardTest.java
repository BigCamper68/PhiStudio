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
public final class StoryboardClipboardTest extends StoryboardTimelineTestSupport {
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

    @Test public void cancellingAResizeLeavesNoMutationOrUndoEntry() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.SCALE_X, 4, 6);
        timeline.select(StoryboardEventType.SCALE_X, event); draw();
        timeline.down(x(), y(4)); timeline.move(x(), y(4.5)); timeline.cancel();
        assertEquals(4, event.startTime.toDouble(), 0);
        view.performUndo(); assertTrue(chart.judgeLines.get(0).extended.scaleXEvents.isEmpty());
    }

    @Test public void switchingLinesCancelsSelectionAndCannotDeleteTheOldEvent() {
        ExtendedLineEvents.TimedEvent event = add(StoryboardEventType.TEXT, 4, 6);
        timeline.select(StoryboardEventType.TEXT, event);
        chart.judgeLines.add(new JudgeLine()); view.arrangeLine(1);
        assertNull(timeline.selected()); timeline.delete();
        assertTrue(chart.judgeLines.get(0).extended.textEvents.contains(event));
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
