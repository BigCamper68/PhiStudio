package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class EventAdvancedOperationTest {
    @Test
    public void passExtrapolatesBothEndpointsAndIsUndoable() {
        EventLayer layer = new EventLayer();
        LineEvent first = event(EventType.MOVE_X, 0, 1, 0.0, 50.0);
        LineEvent second = event(EventType.MOVE_X, 1, 2, 50.0, 100.0);
        LineEvent target = event(EventType.MOVE_X, 2, 3, -20.0, -10.0);
        layer.events(EventType.MOVE_X).add(first);
        layer.events(EventType.MOVE_X).add(second);
        layer.events(EventType.MOVE_X).add(target);

        EventAdvancedOperation.Result result = EventAdvancedOperation.pass(layer, target);
        assertEquals(EventAdvancedOperation.Error.NONE, result.error);
        assertEquals(-20.0, target.start, 0.0);
        EditHistory history = new EditHistory(4);
        history.execute(result.command);
        assertEquals(100.0, target.start, 0.0);
        assertEquals(150.0, target.end, 0.0);
        history.undo();
        assertEquals(-20.0, target.start, 0.0);
        assertEquals(-10.0, target.end, 0.0);
        history.redo();
        assertEquals(150.0, target.end, 0.0);
    }

    @Test
    public void passRequiresTwoPreviousEventsAndRejectsInvalidAlpha() {
        EventLayer layer = new EventLayer();
        LineEvent first = event(EventType.ALPHA, 0, 1, 0.0, 0.0);
        LineEvent target = event(EventType.ALPHA, 1, 2, 10.0, 10.0);
        layer.events(EventType.ALPHA).add(first);
        layer.events(EventType.ALPHA).add(target);
        assertEquals(EventAdvancedOperation.Error.NOT_ENOUGH_PREVIOUS_EVENTS,
                EventAdvancedOperation.pass(layer, target).error);

        LineEvent second = event(EventType.ALPHA, 1, 2, 200.0, 200.0);
        target.startTime = new BeatTime(2, 0, 1);
        target.endTime = new BeatTime(3, 0, 1);
        layer.events(EventType.ALPHA).add(1, second);
        assertEquals(EventAdvancedOperation.Error.INVALID_RESULT,
                EventAdvancedOperation.pass(layer, target).error);
    }

    @Test
    public void randUsesValidTypeRangesAndRedoKeepsTheSameValue() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(EventType.MOVE_Y, 0, 1, 0.0, 0.0);
        layer.events(EventType.MOVE_Y).add(target);
        EventAdvancedOperation.Result result = EventAdvancedOperation.randomize(
                layer, target, 0.75);
        assertEquals(EventAdvancedOperation.Error.NONE, result.error);
        EditHistory history = new EditHistory(4);
        history.execute(result.command);
        double generated = target.end;
        assertFalse(generated < -450.0 || generated > 450.0);
        history.undo();
        assertEquals(0.0, target.end, 0.0);
        history.redo();
        assertEquals(generated, target.end, 0.0);

        assertEquals(-675.0, EventAdvancedOperation.randomEndValue(
                EventType.MOVE_X, 0.0), 0.0);
        assertEquals(255.0, EventAdvancedOperation.randomEndValue(
                EventType.ALPHA, Math.nextDown(1.0)), 0.0);
        assertEquals(20.0, EventAdvancedOperation.randomEndValue(
                EventType.SPEED, Math.nextDown(1.0)), 0.0);
    }

    private static LineEvent event(EventType type, int startBeat, int endBeat,
                                   double startValue, double endValue) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = new BeatTime(startBeat, 0, 1);
        event.endTime = new BeatTime(endBeat, 0, 1);
        event.start = startValue;
        event.end = endValue;
        return event;
    }
}
