package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class EventDragValidatorTest {
    @Test
    public void acceptsTouchingBoundariesAndRejectsOverlap() {
        EventLayer layer = new EventLayer();
        LineEvent previous = event(0.0, 1.0);
        LineEvent target = event(1.0, 2.0);
        LineEvent next = event(2.0, 3.0);
        layer.events(EventType.MOVE_X).add(previous);
        layer.events(EventType.MOVE_X).add(target);
        layer.events(EventType.MOVE_X).add(next);

        assertEquals(EventDragValidator.Error.NONE,
                validate(layer, target, 1.0, 2.0));
        assertEquals(EventDragValidator.Error.EVENT_OVERLAP,
                validate(layer, target, 0.75, 2.0));
        assertEquals(EventDragValidator.Error.EVENT_OVERLAP,
                validate(layer, target, 1.0, 2.25));

        assertEquals(EventDragValidator.Error.NONE,
                validate(layer, target, 1.25, 2.0));
        assertEquals(EventDragValidator.Error.NONE,
                validate(layer, target, 1.0, 1.75));
    }

    @Test
    public void rejectsNegativeReversedAndReservedLayerTimes() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(1.0, 2.0);
        layer.events(EventType.MOVE_X).add(target);

        assertEquals(EventDragValidator.Error.NEGATIVE_START_TIME,
                validate(layer, target, -0.25, 2.0));
        assertEquals(EventDragValidator.Error.END_TIME_NOT_AFTER_START,
                validate(layer, target, 2.0, 2.0));
        assertEquals(EventDragValidator.Error.RESERVED_LAYER,
                EventDragValidator.validate(layer, 4, target,
                        beat(1.0), beat(2.0)));
    }


    @Test
    public void onlySameTypeEventsInTheSameLayerConflict() {
        EventLayer firstLayer = new EventLayer();
        EventLayer secondLayer = new EventLayer();
        LineEvent target = event(1.0, 2.0);
        LineEvent differentType = event(EventType.MOVE_Y, 1.5, 2.5);
        LineEvent otherLayerSameType = event(1.5, 2.5);
        firstLayer.events(EventType.MOVE_X).add(target);
        firstLayer.events(EventType.MOVE_Y).add(differentType);
        secondLayer.events(EventType.MOVE_X).add(otherLayerSameType);

        assertEquals(EventDragValidator.Error.NONE,
                validate(firstLayer, target, 1.5, 2.5));
        assertEquals(EventDragValidator.Error.NONE,
                EventDragValidator.validate(secondLayer, 1, otherLayerSameType,
                        beat(1.5), beat(2.5)));
    }

    @Test
    public void rejectsTargetThatNoLongerBelongsToTheLayer() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(1.0, 2.0);

        assertEquals(EventDragValidator.Error.TARGET_NOT_FOUND,
                EventDragValidator.validate(layer, 0, target,
                        target.startTime, target.endTime));
    }

    @Test
    public void rejectedValidationDoesNotRemoveImportedEvents() {
        EventLayer layer = new EventLayer();
        LineEvent importedInvalid = event(-1.0, 0.0);
        LineEvent neighbor = event(0.0, 1.0);
        layer.events(EventType.MOVE_X).add(importedInvalid);
        layer.events(EventType.MOVE_X).add(neighbor);

        assertEquals(EventDragValidator.Error.NEGATIVE_START_TIME,
                EventDragValidator.validate(layer, 0, importedInvalid,
                        importedInvalid.startTime, importedInvalid.endTime));
        assertEquals(2, layer.events(EventType.MOVE_X).size());
        assertEquals(importedInvalid, layer.events(EventType.MOVE_X).get(0));
    }

    private static EventDragValidator.Error validate(EventLayer layer, LineEvent target,
                                                      double start, double end) {
        return EventDragValidator.validate(layer, 0, target, beat(start), beat(end));
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
