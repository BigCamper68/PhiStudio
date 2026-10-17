package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.StoryboardEventType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class StoryboardEventValidatorTest {
    @Test
    public void rejectsOverlapButAllowsTouchingIntervals() {
        ExtendedLineEvents owner = new ExtendedLineEvents();
        ExtendedLineEvents.NumericEvent existing = numeric(0, 2, 1.0, 2.0);
        owner.add(StoryboardEventType.SCALE_X, existing);

        assertEquals(StoryboardEventValidator.Error.NONE,
                StoryboardEventValidator.validate(owner, StoryboardEventType.SCALE_X,
                        numeric(2, 4, 2.0, 3.0), null));
        assertEquals(StoryboardEventValidator.Error.EVENT_OVERLAP,
                StoryboardEventValidator.validate(owner, StoryboardEventType.SCALE_X,
                        numeric(1, 3, 2.0, 3.0), null));
        assertEquals(StoryboardEventValidator.Error.NONE,
                StoryboardEventValidator.validate(owner, StoryboardEventType.SCALE_Y,
                        numeric(1, 3, 2.0, 3.0), null));
    }

    @Test
    public void validatesTypeTimingEasingAndLinkGroup() {
        ExtendedLineEvents.TextEvent text = new ExtendedLineEvents.TextEvent();
        text.startTime = beat(0);
        text.endTime = beat(1);
        assertEquals(StoryboardEventValidator.Error.NONE,
                StoryboardEventValidator.validateFields(StoryboardEventType.TEXT, text));

        text.easingType = 99;
        assertEquals(StoryboardEventValidator.Error.EASING_OUT_OF_RANGE,
                StoryboardEventValidator.validateFields(StoryboardEventType.TEXT, text));
        text.easingType = 1;
        text.linkGroup = -1;
        assertEquals(StoryboardEventValidator.Error.LINK_GROUP_NEGATIVE,
                StoryboardEventValidator.validateFields(StoryboardEventType.TEXT, text));
        assertEquals(StoryboardEventValidator.Error.WRONG_VALUE_TYPE,
                StoryboardEventValidator.validateFields(StoryboardEventType.COLOR, text));
    }

    private static ExtendedLineEvents.NumericEvent numeric(
            int startBeat, int endBeat, double start, double end) {
        ExtendedLineEvents.NumericEvent event = new ExtendedLineEvents.NumericEvent();
        event.startTime = beat(startBeat);
        event.endTime = beat(endBeat);
        event.start = start;
        event.end = end;
        return event;
    }

    private static BeatTime beat(int whole) {
        return new BeatTime(whole, 0, 1);
    }
}
