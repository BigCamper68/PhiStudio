package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.LineEvent;

/** Validation used before committing a single-event time drag. */
public final class EventDragValidator {
    public enum Error {
        NONE,
        TARGET_NOT_FOUND,
        RESERVED_LAYER,
        NEGATIVE_START_TIME,
        END_TIME_NOT_AFTER_START,
        EVENT_OVERLAP
    }

    private EventDragValidator() {
    }

    public static Error validate(EventLayer layer, int layerIndex, LineEvent target,
                                 BeatTime startTime, BeatTime endTime) {
        if (layerIndex < 0 || layerIndex > 3) return Error.RESERVED_LAYER;
        if (layer == null || target == null || target.type == null
                || startTime == null || endTime == null
                || !layer.events(target.type).contains(target)) {
            return Error.TARGET_NOT_FOUND;
        }
        if (startTime.toDouble() < 0.0) return Error.NEGATIVE_START_TIME;
        if (endTime.compareTo(startTime) <= 0) return Error.END_TIME_NOT_AFTER_START;

        LineEvent candidate = target.copy();
        candidate.startTime = startTime;
        candidate.endTime = endTime;
        if (layer.overlaps(candidate, target)) return Error.EVENT_OVERLAP;
        return Error.NONE;
    }
}
