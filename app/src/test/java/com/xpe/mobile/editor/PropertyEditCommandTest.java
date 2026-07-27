package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class PropertyEditCommandTest {
    @Test
    public void notePropertyEditIsUndoableAndResorted() {
        JudgeLine line = new JudgeLine();
        Note target = new Note();
        target.startTime = new BeatTime(2, 0, 1);
        target.endTime = target.startTime;
        Note earlier = new Note();
        earlier.startTime = new BeatTime(1, 0, 1);
        earlier.endTime = earlier.startTime;
        line.notes.add(earlier);
        line.notes.add(target);

        Note before = target.copy();
        Note after = target.copy();
        after.startTime = new BeatTime(0, 0, 1);
        after.endTime = after.startTime;
        after.positionX = 250.0;

        EditHistory history = new EditHistory(10);
        history.execute(PropertyEditCommand.note(line, target, before, after));
        assertEquals(250.0, target.positionX, 0.0);
        assertSame(target, line.notes.get(0));

        history.undo();
        assertEquals(0.0, target.positionX, 0.0);
        assertSame(target, line.notes.get(1));
        history.redo();
        assertEquals(250.0, target.positionX, 0.0);
    }

    @Test
    public void eventPropertyEditIsUndoableAndResorted() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(2, 3);
        LineEvent earlier = event(1, 2);
        layer.events(EventType.MOVE_X).add(earlier);
        layer.events(EventType.MOVE_X).add(target);

        LineEvent before = target.copy();
        LineEvent after = target.copy();
        after.startTime = new BeatTime(0, 0, 1);
        after.endTime = new BeatTime(1, 0, 1);
        after.end = 400.0;

        EditHistory history = new EditHistory(10);
        history.execute(PropertyEditCommand.event(layer, target, before, after));
        assertEquals(400.0, target.end, 0.0);
        assertSame(target, layer.events(EventType.MOVE_X).get(0));

        history.undo();
        assertEquals(0.0, target.end, 0.0);
        assertSame(target, layer.events(EventType.MOVE_X).get(1));
    }

    private static LineEvent event(int start, int end) {
        LineEvent event = new LineEvent();
        event.type = EventType.MOVE_X;
        event.startTime = new BeatTime(start, 0, 1);
        event.endTime = new BeatTime(end, 0, 1);
        return event;
    }
}
