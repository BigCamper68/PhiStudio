package com.xpe.mobile.editor;

import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

public final class PropertyValidator {
    public enum Error {
        NONE,
        MISSING_TYPE,
        NON_FINITE_NUMBER,
        NEGATIVE_START_TIME,
        END_TIME_NOT_AFTER_START,
        NOTE_X_OUT_OF_RANGE,
        NOTE_ALPHA_OUT_OF_RANGE,
        NOTE_SIZE_NOT_POSITIVE,
        NOTE_VISIBLE_TIME_NEGATIVE,
        EVENT_ALPHA_OUT_OF_RANGE,
        EVENT_EASING_OUT_OF_RANGE,
        EVENT_EASING_WINDOW_INVALID,
        EVENT_LINK_GROUP_NEGATIVE
    }

    private PropertyValidator() {
    }

    public static Error validate(Note note) {
        if (note == null || note.type == null || note.startTime == null || note.endTime == null) {
            return Error.MISSING_TYPE;
        }
        if (note.startTime.toDouble() < 0.0) return Error.NEGATIVE_START_TIME;
        if (note.type == NoteType.HOLD && note.endTime.compareTo(note.startTime) <= 0) {
            return Error.END_TIME_NOT_AFTER_START;
        }
        if (!finite(note.positionX, note.speed, note.size, note.visibleTime, note.yOffset)) {
            return Error.NON_FINITE_NUMBER;
        }
        if (note.positionX < -675.0 || note.positionX > 675.0) return Error.NOTE_X_OUT_OF_RANGE;
        if (note.alpha < 0 || note.alpha > 255) return Error.NOTE_ALPHA_OUT_OF_RANGE;
        if (note.size <= 0.0) return Error.NOTE_SIZE_NOT_POSITIVE;
        if (note.visibleTime < 0.0) return Error.NOTE_VISIBLE_TIME_NEGATIVE;
        return Error.NONE;
    }

    public static Error validate(LineEvent event) {
        if (event == null || event.type == null || event.startTime == null || event.endTime == null) {
            return Error.MISSING_TYPE;
        }
        if (event.startTime.toDouble() < 0.0) return Error.NEGATIVE_START_TIME;
        if (event.endTime.compareTo(event.startTime) <= 0) return Error.END_TIME_NOT_AFTER_START;
        if (!finite(event.start, event.end, event.easingLeft, event.easingRight,
                event.bezierPoints[0], event.bezierPoints[1], event.bezierPoints[2], event.bezierPoints[3])) {
            return Error.NON_FINITE_NUMBER;
        }
        if (event.type == EventType.ALPHA
                && (event.start < -255.0 || event.start > 255.0 || event.end < -255.0 || event.end > 255.0)) {
            return Error.EVENT_ALPHA_OUT_OF_RANGE;
        }
        if ((event.type == EventType.SPEED && event.easingType != 1)
                || (event.type != EventType.SPEED
                && (event.easingType < Easing.MIN_TYPE || event.easingType > Easing.MAX_TYPE))) {
            return Error.EVENT_EASING_OUT_OF_RANGE;
        }
        if (event.easingLeft < 0.0 || event.easingRight > 1.0 || event.easingLeft > event.easingRight) {
            return Error.EVENT_EASING_WINDOW_INVALID;
        }
        if (event.linkGroup < 0) return Error.EVENT_LINK_GROUP_NEGATIVE;
        return Error.NONE;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }
}
