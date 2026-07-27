package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Structural lookup and validation for optional MoveX/MoveY time binding. */
public final class XYBindingValidator {
    public enum Error {
        NONE,
        TARGET_NOT_FOUND,
        RESERVED_LAYER,
        NOT_MOVE_EVENT,
        PAIR_NOT_FOUND,
        AMBIGUOUS_PAIR,
        NEGATIVE_START_TIME,
        END_TIME_NOT_AFTER_START,
        EVENT_OVERLAP,
        LAYER_NOT_SYNCHRONIZED
    }

    public static final class PairLookup {
        public final Error error;
        public final LineEvent event;

        private PairLookup(Error error, LineEvent event) {
            this.error = error;
            this.event = event;
        }
    }

    private XYBindingValidator() {
    }

    public static boolean isMoveType(EventType type) {
        return type == EventType.MOVE_X || type == EventType.MOVE_Y;
    }

    public static EventType pairedType(EventType type) {
        if (type == EventType.MOVE_X) return EventType.MOVE_Y;
        if (type == EventType.MOVE_Y) return EventType.MOVE_X;
        return null;
    }

    /** Finds the unique opposite-axis event with exactly equal start and end times. */
    public static PairLookup findPair(EventLayer layer, LineEvent target) {
        if (layer == null || target == null || target.type == null
                || target.startTime == null || target.endTime == null
                || !layer.events(target.type).contains(target)) {
            return new PairLookup(Error.TARGET_NOT_FOUND, null);
        }
        EventType pairType = pairedType(target.type);
        if (pairType == null) return new PairLookup(Error.NOT_MOVE_EVENT, null);

        LineEvent match = null;
        int count = 0;
        for (LineEvent candidate : layer.events(pairType)) {
            if (candidate.startTime == null || candidate.endTime == null) continue;
            if (candidate.startTime.equals(target.startTime)
                    && candidate.endTime.equals(target.endTime)) {
                match = candidate;
                count++;
            }
        }
        if (count == 0) return new PairLookup(Error.PAIR_NOT_FOUND, null);
        if (count > 1) return new PairLookup(Error.AMBIGUOUS_PAIR, null);
        return new PairLookup(Error.NONE, match);
    }

    public static Error validatePlacement(EventLayer layer, int layerIndex,
                                          LineEvent first, LineEvent second) {
        if (layerIndex < 0 || layerIndex > 3) return Error.RESERVED_LAYER;
        if (layer == null || first == null || second == null
                || first.type == null || second.type == null
                || first.startTime == null || first.endTime == null
                || second.startTime == null || second.endTime == null) {
            return Error.TARGET_NOT_FOUND;
        }
        if (!isMoveType(first.type) || pairedType(first.type) != second.type) {
            return Error.NOT_MOVE_EVENT;
        }
        if (!first.startTime.equals(second.startTime)
                || !first.endTime.equals(second.endTime)) {
            return Error.LAYER_NOT_SYNCHRONIZED;
        }
        Error time = validateTimes(first.startTime, first.endTime);
        if (time != Error.NONE) return time;
        if (layer.overlaps(first, null) || layer.overlaps(second, null)) {
            return Error.EVENT_OVERLAP;
        }
        return Error.NONE;
    }

    public static Error validatePairedTimes(EventLayer layer, int layerIndex,
                                            LineEvent target, LineEvent pair,
                                            BeatTime startTime, BeatTime endTime) {
        if (layerIndex < 0 || layerIndex > 3) return Error.RESERVED_LAYER;
        if (layer == null || target == null || pair == null
                || target.type == null || pair.type == null
                || !isMoveType(target.type) || pairedType(target.type) != pair.type
                || !layer.events(target.type).contains(target)
                || !layer.events(pair.type).contains(pair)) {
            return Error.TARGET_NOT_FOUND;
        }
        PairLookup lookup = findPair(layer, target);
        if (lookup.error != Error.NONE) return lookup.error;
        if (lookup.event != pair) return Error.AMBIGUOUS_PAIR;

        Error time = validateTimes(startTime, endTime);
        if (time != Error.NONE) return time;

        LineEvent targetCandidate = target.copy();
        targetCandidate.startTime = startTime;
        targetCandidate.endTime = endTime;
        LineEvent pairCandidate = pair.copy();
        pairCandidate.startTime = startTime;
        pairCandidate.endTime = endTime;
        if (layer.overlaps(targetCandidate, target)
                || layer.overlaps(pairCandidate, pair)) {
            return Error.EVENT_OVERLAP;
        }
        return Error.NONE;
    }

    public static boolean isChartSynchronized(ChartDocument chart) {
        if (chart == null) return false;
        for (JudgeLine line : chart.judgeLines) {
            int editableLayers = Math.min(4, line.eventLayers.size());
            for (int index = 0; index < editableLayers; index++) {
                if (!isLayerSynchronized(line.eventLayers.get(index))) return false;
            }
        }
        return true;
    }

    public static boolean isLayerSynchronized(EventLayer layer) {
        if (layer == null) return false;
        List<LineEvent> moveX = layer.events(EventType.MOVE_X);
        List<LineEvent> moveY = layer.events(EventType.MOVE_Y);
        if (hasInvalidOrOverlappingTimes(moveX) || hasInvalidOrOverlappingTimes(moveY)) {
            return false;
        }
        Map<String, Integer> xCounts = intervalCounts(moveX);
        Map<String, Integer> yCounts = intervalCounts(moveY);
        if (!xCounts.equals(yCounts)) return false;
        for (int count : xCounts.values()) {
            if (count != 1) return false;
        }
        return true;
    }

    private static Error validateTimes(BeatTime startTime, BeatTime endTime) {
        if (startTime == null || endTime == null) return Error.TARGET_NOT_FOUND;
        if (startTime.toDouble() < 0.0) return Error.NEGATIVE_START_TIME;
        if (endTime.compareTo(startTime) <= 0) return Error.END_TIME_NOT_AFTER_START;
        return Error.NONE;
    }

    private static boolean hasInvalidOrOverlappingTimes(List<LineEvent> events) {
        BeatTime previousStart = null;
        BeatTime previousEnd = null;
        for (LineEvent event : events) {
            if (event.startTime == null || event.endTime == null) return true;
            if (event.startTime.toDouble() < 0.0
                    || event.endTime.compareTo(event.startTime) <= 0) {
                return true;
            }
            if (previousStart != null && event.startTime.compareTo(previousStart) < 0) {
                return true;
            }
            if (previousEnd != null && event.startTime.compareTo(previousEnd) < 0) {
                return true;
            }
            previousStart = event.startTime;
            previousEnd = event.endTime;
        }
        return false;
    }

    private static Map<String, Integer> intervalCounts(List<LineEvent> events) {
        Map<String, Integer> counts = new HashMap<>();
        for (LineEvent event : events) {
            String key = event.startTime + "|" + event.endTime;
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }
}
