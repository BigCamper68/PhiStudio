package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.StoryboardEventType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class StoryboardEditCommandTest {
    private static final double EPSILON = 1.0e-8;

    @Test
    public void addEditDeleteAreFullyReversible() {
        ExtendedLineEvents owner = new ExtendedLineEvents();
        ExtendedLineEvents.NumericEvent event = numeric(0, 2, 1.0, 2.0);
        EditHistory history = new EditHistory(10);

        history.execute(StoryboardEditCommand.add(
                owner, StoryboardEventType.SCALE_X, event));
        assertTrue(owner.contains(StoryboardEventType.SCALE_X, event));
        history.undo();
        assertFalse(owner.contains(StoryboardEventType.SCALE_X, event));
        history.redo();
        assertTrue(owner.contains(StoryboardEventType.SCALE_X, event));

        ExtendedLineEvents.NumericEvent edited = event.copy();
        edited.end = 5.0;
        history.execute(StoryboardEditCommand.edit(owner, StoryboardEventType.SCALE_X,
                event, event.copy(), edited));
        assertEquals(5.0, event.end, EPSILON);
        history.undo();
        assertEquals(2.0, event.end, EPSILON);

        history.execute(StoryboardEditCommand.delete(
                owner, StoryboardEventType.SCALE_X, event));
        assertFalse(owner.contains(StoryboardEventType.SCALE_X, event));
        history.undo();
        assertTrue(owner.contains(StoryboardEventType.SCALE_X, event));
    }

    private static ExtendedLineEvents.NumericEvent numeric(
            int startBeat, int endBeat, double start, double end) {
        ExtendedLineEvents.NumericEvent event = new ExtendedLineEvents.NumericEvent();
        event.startTime = new BeatTime(startBeat, 0, 1);
        event.endTime = new BeatTime(endBeat, 0, 1);
        event.start = start;
        event.end = end;
        return event;
    }
}
