package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.LineEvent;

/** Validation and lookup shared by single-event cut and glue actions. */
public final class EventCutGlueValidator {
    public enum Error {
        NONE,
        TARGET_NOT_FOUND,
        RESERVED_LAYER,
        INVALID_EVENT_TIME,
        CUT_NOT_INSIDE_EVENT,
        EVENT_OVERLAP,
        NO_PREVIOUS_EVENT
    }

    private EventCutGlueValidator() {
    }

    public static Error validateCut(EventLayer layer, int layerIndex, LineEvent target,
                                    BeatTime cutTime) {
        Error common = validateTarget(layer, layerIndex, target);
        if (common != Error.NONE) return common;
        if (cutTime == null
                || cutTime.compareTo(target.startTime) <= 0
                || cutTime.compareTo(target.endTime) >= 0) {
            return Error.CUT_NOT_INSIDE_EVENT;
        }
        return Error.NONE;
    }

    public static Error validateGlue(EventLayer layer, int layerIndex, LineEvent target) {
        Error common = validateTarget(layer, layerIndex, target);
        if (common != Error.NONE) return common;
        return previousSameType(layer, target) == null ? Error.NO_PREVIOUS_EVENT : Error.NONE;
    }

    public static LineEvent previousSameType(EventLayer layer, LineEvent target) {
        if (layer == null || target == null || target.type == null
                || target.startTime == null || !layer.events(target.type).contains(target)) {
            return null;
        }
        LineEvent previous = null;
        for (LineEvent event : layer.events(target.type)) {
            if (event == target || event.startTime == null) continue;
            if (event.startTime.compareTo(target.startTime) < 0
                    && (previous == null
                    || event.startTime.compareTo(previous.startTime) > 0)) {
                previous = event;
            }
        }
        return previous;
    }

    private static Error validateTarget(EventLayer layer, int layerIndex, LineEvent target) {
        if (layerIndex < 0 || layerIndex > 3) return Error.RESERVED_LAYER;
        if (layer == null || target == null || target.type == null
                || target.startTime == null || target.endTime == null
                || !layer.events(target.type).contains(target)) {
            return Error.TARGET_NOT_FOUND;
        }
        if (target.startTime.toDouble() < 0.0
                || target.endTime.compareTo(target.startTime) <= 0) {
            return Error.INVALID_EVENT_TIME;
        }
        if (layer.overlaps(target, target)) return Error.EVENT_OVERLAP;
        return Error.NONE;
    }
}
