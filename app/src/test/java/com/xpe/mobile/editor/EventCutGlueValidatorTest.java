package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class EventCutGlueValidatorTest {
    @Test
    public void cutRequiresAnInteriorBeatAndAcceptsNeighborBoundaries() {
        EventLayer layer = new EventLayer();
        LineEvent previous = event(0.0, 1.0);
        LineEvent target = event(1.0, 3.0);
        LineEvent next = event(3.0, 4.0);
        layer.events(EventType.MOVE_X).add(previous);
        layer.events(EventType.MOVE_X).add(target);
        layer.events(EventType.MOVE_X).add(next);

        assertEquals(EventCutGlueValidator.Error.NONE,
                EventCutGlueValidator.validateCut(layer, 0, target, beat(2.0)));
        assertEquals(EventCutGlueValidator.Error.CUT_NOT_INSIDE_EVENT,
                EventCutGlueValidator.validateCut(layer, 0, target, beat(1.0)));
        assertEquals(EventCutGlueValidator.Error.CUT_NOT_INSIDE_EVENT,
                EventCutGlueValidator.validateCut(layer, 0, target, beat(3.0)));
        assertEquals(EventCutGlueValidator.Error.CUT_NOT_INSIDE_EVENT,
                EventCutGlueValidator.validateCut(layer, 0, target, beat(4.0)));
    }

    @Test
    public void cutAndGlueRejectExistingSameTypeOverlapButIgnoreOtherTypes() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(1.0, 3.0);
        LineEvent overlapping = event(2.5, 4.0);
        LineEvent otherType = event(EventType.MOVE_Y, 1.5, 3.5);
        layer.events(EventType.MOVE_X).add(target);
        layer.events(EventType.MOVE_X).add(overlapping);
        layer.events(EventType.MOVE_Y).add(otherType);

        assertEquals(EventCutGlueValidator.Error.EVENT_OVERLAP,
                EventCutGlueValidator.validateCut(layer, 0, target, beat(2.0)));
        assertEquals(EventCutGlueValidator.Error.EVENT_OVERLAP,
                EventCutGlueValidator.validateGlue(layer, 0, overlapping));

        layer.events(EventType.MOVE_X).remove(overlapping);
        assertEquals(EventCutGlueValidator.Error.NONE,
                EventCutGlueValidator.validateCut(layer, 0, target, beat(2.0)));
    }

    @Test
    public void glueUsesTheImmediatePreviousSameTypeEvent() {
        EventLayer layer = new EventLayer();
        LineEvent earliest = event(0.0, 0.5);
        LineEvent previous = event(1.0, 2.0);
        LineEvent differentType = event(EventType.ROTATE, 2.0, 3.0);
        LineEvent target = event(3.0, 4.0);
        layer.events(EventType.MOVE_X).add(target);
        layer.events(EventType.MOVE_X).add(earliest);
        layer.events(EventType.MOVE_X).add(previous);
        layer.events(EventType.ROTATE).add(differentType);

        assertEquals(EventCutGlueValidator.Error.NONE,
                EventCutGlueValidator.validateGlue(layer, 0, target));
        assertSame(previous, EventCutGlueValidator.previousSameType(layer, target));
        assertEquals(EventCutGlueValidator.Error.NO_PREVIOUS_EVENT,
                EventCutGlueValidator.validateGlue(layer, 0, earliest));
    }

    @Test
    public void reservedLayerMissingTargetAndInvalidImportedTimesAreRejectedWithoutDeletion() {
        EventLayer layer = new EventLayer();
        LineEvent invalid = event(-1.0, -1.0);
        layer.events(EventType.MOVE_X).add(invalid);

        assertEquals(EventCutGlueValidator.Error.RESERVED_LAYER,
                EventCutGlueValidator.validateCut(layer, 4, invalid, beat(-0.5)));
        assertEquals(EventCutGlueValidator.Error.INVALID_EVENT_TIME,
                EventCutGlueValidator.validateCut(layer, 0, invalid, beat(-0.5)));
        assertEquals(EventCutGlueValidator.Error.INVALID_EVENT_TIME,
                EventCutGlueValidator.validateGlue(layer, 0, invalid));
        assertEquals(1, layer.events(EventType.MOVE_X).size());
        assertSame(invalid, layer.events(EventType.MOVE_X).get(0));

        LineEvent missing = event(1.0, 2.0);
        assertEquals(EventCutGlueValidator.Error.TARGET_NOT_FOUND,
                EventCutGlueValidator.validateCut(layer, 0, missing, beat(1.5)));
    }

    private static LineEvent event(double start, double end) {
        return event(EventType.MOVE_X, start, end);
    }

    private static LineEvent event(EventType type, double start, double end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(start);
        event.endTime = beat(end);
        return event;
    }

    private static BeatTime beat(double value) {
        return BeatTime.fromDouble(value, 4);
    }
}
