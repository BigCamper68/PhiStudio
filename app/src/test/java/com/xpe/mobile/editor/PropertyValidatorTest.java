package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PropertyValidatorTest {
    @Test
    public void validatesHoldDurationAndNoteBounds() {
        Note note = new Note();
        note.type = NoteType.HOLD;
        note.startTime = new BeatTime(2, 0, 1);
        note.endTime = new BeatTime(2, 0, 1);
        assertEquals(PropertyValidator.Error.END_TIME_NOT_AFTER_START, PropertyValidator.validate(note));

        note.endTime = new BeatTime(3, 0, 1);
        note.positionX = 676.0;
        assertEquals(PropertyValidator.Error.NOTE_X_OUT_OF_RANGE, PropertyValidator.validate(note));

        note.positionX = 675.0;
        assertEquals(PropertyValidator.Error.NONE, PropertyValidator.validate(note));
    }

    @Test
    public void validatesEventRulesAndOverlapBoundaries() {
        LineEvent event = event(1.0, 2.0);
        event.easingType = 30;
        assertEquals(PropertyValidator.Error.EVENT_EASING_OUT_OF_RANGE, PropertyValidator.validate(event));

        event.easingType = 29;
        assertEquals(PropertyValidator.Error.NONE, PropertyValidator.validate(event));

        event.easingType = 1;
        assertEquals(PropertyValidator.Error.NONE, PropertyValidator.validate(event));

        EventLayer layer = new EventLayer();
        layer.events(EventType.MOVE_X).add(event(0.0, 1.0));
        assertFalse(layer.overlaps(event(1.0, 2.0), null));
        assertTrue(layer.overlaps(event(0.5, 1.5), null));
    }

    private static LineEvent event(double start, double end) {
        LineEvent event = new LineEvent();
        event.type = EventType.MOVE_X;
        event.startTime = BeatTime.fromDouble(start, 4);
        event.endTime = BeatTime.fromDouble(end, 4);
        return event;
    }
}
