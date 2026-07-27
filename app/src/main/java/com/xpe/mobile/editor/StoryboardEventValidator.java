package com.xpe.mobile.editor;

import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.StoryboardEventType;

/** Pure validation for editable RPE extended/storyboard events. */
public final class StoryboardEventValidator {
    public enum Error {
        NONE,
        MISSING_TYPE,
        WRONG_VALUE_TYPE,
        NEGATIVE_START_TIME,
        END_TIME_NOT_AFTER_START,
        NON_FINITE_NUMBER,
        EASING_OUT_OF_RANGE,
        EASING_WINDOW_INVALID,
        LINK_GROUP_NEGATIVE,
        TEXT_VALUE_MISSING,
        EVENT_OVERLAP
    }

    private StoryboardEventValidator() {
    }

    public static Error validate(ExtendedLineEvents owner, StoryboardEventType type,
                                 ExtendedLineEvents.TimedEvent event,
                                 ExtendedLineEvents.TimedEvent ignored) {
        Error fields = validateFields(type, event);
        if (fields != Error.NONE || owner == null) return fields;
        for (ExtendedLineEvents.TimedEvent other : owner.events(type)) {
            if (other == null || other == ignored || other == event
                    || other.startTime == null || other.endTime == null) continue;
            if (event.startTime.compareTo(other.endTime) < 0
                    && other.startTime.compareTo(event.endTime) < 0) {
                return Error.EVENT_OVERLAP;
            }
        }
        return Error.NONE;
    }

    public static Error validateFields(StoryboardEventType type,
                                       ExtendedLineEvents.TimedEvent event) {
        if (type == null || event == null || event.startTime == null || event.endTime == null) {
            return Error.MISSING_TYPE;
        }
        if (type == StoryboardEventType.COLOR
                && !(event instanceof ExtendedLineEvents.ColorEvent)
                || type == StoryboardEventType.TEXT
                && !(event instanceof ExtendedLineEvents.TextEvent)
                || type.isNumeric()
                && !(event instanceof ExtendedLineEvents.NumericEvent)) {
            return Error.WRONG_VALUE_TYPE;
        }
        if (event.startTime.toDouble() < 0.0) return Error.NEGATIVE_START_TIME;
        if (event.endTime.compareTo(event.startTime) <= 0) {
            return Error.END_TIME_NOT_AFTER_START;
        }
        if (!finite(event.easingLeft, event.easingRight,
                event.bezierPoints[0], event.bezierPoints[1],
                event.bezierPoints[2], event.bezierPoints[3])) {
            return Error.NON_FINITE_NUMBER;
        }
        if (event.easingType < Easing.MIN_TYPE || event.easingType > Easing.MAX_TYPE) {
            return Error.EASING_OUT_OF_RANGE;
        }
        if (event.easingLeft < 0.0 || event.easingRight > 1.0
                || event.easingLeft > event.easingRight) {
            return Error.EASING_WINDOW_INVALID;
        }
        if (event.linkGroup < 0) return Error.LINK_GROUP_NEGATIVE;
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent numeric =
                    (ExtendedLineEvents.NumericEvent) event;
            if (!finite(numeric.start, numeric.end)) return Error.NON_FINITE_NUMBER;
        } else if (event instanceof ExtendedLineEvents.TextEvent) {
            ExtendedLineEvents.TextEvent text = (ExtendedLineEvents.TextEvent) event;
            if (text.start == null || text.end == null) return Error.TEXT_VALUE_MISSING;
        }
        return Error.NONE;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }
}
