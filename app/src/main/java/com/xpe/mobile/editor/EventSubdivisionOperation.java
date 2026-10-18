package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/** Plans a bounded, atomic subdivision on an exact beat grid without changing the source. */
public final class EventSubdivisionOperation {
    public static final int MAX_SEGMENTS = 4096;

    public enum Error {
        NONE, EMPTY_SELECTION, RESERVED_LAYER, TARGET_NOT_FOUND, INVALID_GRID,
        INVALID_TIME, EVENT_OVERLAP, XY_BINDING_INVALID, TOO_MANY_SEGMENTS,
        UNSUPPORTED_CURVE, NO_CUTS
    }

    public static final class Result {
        public final Error error;
        public final EditHistory.Command command;
        public final List<LineEvent> events;
        public final int sourceCount;
        public final BeatTime step;

        private Result(Error error, EditHistory.Command command, List<LineEvent> events,
                       int sourceCount, BeatTime step) {
            this.error = error;
            this.command = command;
            this.events = Collections.unmodifiableList(events);
            this.sourceCount = sourceCount;
            this.step = step;
        }

        private static Result error(Error error) {
            return new Result(error, null, Collections.emptyList(), 0, null);
        }
    }

    private EventSubdivisionOperation() { }

    public static Result prepare(EventLayer layer, int layerIndex, List<LineEvent> selection,
                                 BeatTime cursor, int subdivision,
                                 boolean xyBinding) {
        return prepare(layer, layerIndex, selection, cursor, subdivision, xyBinding, x -> x);
    }

    public static Result prepare(EventLayer layer, int layerIndex, List<LineEvent> selection,
                                 BeatTime cursor, int subdivision,
                                 boolean xyBinding, DoubleUnaryOperator timeline) {
        if (layerIndex < 0 || layerIndex > 3) return Result.error(Error.RESERVED_LAYER);
        if (selection == null || selection.isEmpty()) return Result.error(Error.EMPTY_SELECTION);
        if (layer == null) return Result.error(Error.TARGET_NOT_FOUND);
        BeatTime step;
        try {
            step = step(subdivision);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return Result.error(Error.INVALID_GRID);
        }
        if (cursor == null || cursor.compareTo(BeatTime.zero()) < 0) {
            return Result.error(Error.INVALID_TIME);
        }

        Set<LineEvent> targets = new LinkedHashSet<>(selection);
        for (LineEvent target : new ArrayList<>(targets)) {
            if (target == null || target.type == null || !layer.events(target.type).contains(target)) {
                return Result.error(Error.TARGET_NOT_FOUND);
            }
            if (xyBinding && XYBindingValidator.isMoveType(target.type)) {
                XYBindingValidator.PairLookup pair = XYBindingValidator.findPair(layer, target);
                if (pair.error != XYBindingValidator.Error.NONE || pair.event == null) {
                    return Result.error(Error.XY_BINDING_INVALID);
                }
                targets.add(pair.event);
            }
        }

        Map<EventType, List<LineEvent>> before = new EnumMap<>(EventType.class);
        Map<EventType, List<LineEvent>> after = new EnumMap<>(EventType.class);
        Map<LineEvent, List<LineEvent>> replacements = new java.util.IdentityHashMap<>();
        List<LineEvent> generated = new ArrayList<>();
        boolean changed = false;
        try {
            for (LineEvent target : targets) {
                if (target.startTime == null || target.endTime == null
                        || target.startTime.compareTo(BeatTime.zero()) < 0
                        || target.endTime.compareTo(target.startTime) <= 0) {
                    return Result.error(Error.INVALID_TIME);
                }
                if (layer.overlaps(target, target)) return Result.error(Error.EVENT_OVERLAP);
                // These source-only curves cannot be represented by an editor easing window.
                // Refuse atomically instead of changing imported playback while cutting it.
                if (target.start != target.end && (target.bezier
                        || target.type == EventType.SPEED && target.sourceSpeedEasing() != 1)) {
                    return Result.error(Error.UNSUPPORTED_CURVE);
                }
                double duration = target.endTime.toDouble() - target.startTime.toDouble();
                if (!Double.isFinite(duration) || duration <= 0.0) return Result.error(Error.INVALID_TIME);
                if (duration / step.toDouble() > MAX_SEGMENTS + 1.0) {
                    return Result.error(Error.TOO_MANY_SEGMENTS);
                }
                BeatTime origin = cursor.compareTo(target.startTime) >= 0
                        && cursor.compareTo(target.endTime) <= 0 ? cursor : target.startTime;
                int index = (int) Math.floor((target.startTime.toDouble() - origin.toDouble())
                        / step.toDouble());
                BeatTime cut = origin.plus(new BeatTime(0, index, subdivision));
                while (cut.compareTo(target.startTime) > 0) {
                    cut = origin.plus(new BeatTime(0, --index, subdivision));
                }
                List<BeatTime> boundaries = new ArrayList<>();
                boundaries.add(target.startTime);
                while (true) {
                    cut = origin.plus(new BeatTime(0, ++index, subdivision));
                    if (cut.compareTo(target.endTime) >= 0) break;
                    if (cut.compareTo(target.startTime) <= 0) continue;
                    boundaries.add(cut);
                    if (generated.size() + boundaries.size() > MAX_SEGMENTS) {
                        return Result.error(Error.TOO_MANY_SEGMENTS);
                    }
                }
                boundaries.add(target.endTime);
                List<LineEvent> parts = new ArrayList<>();
                for (int i = 1; i < boundaries.size(); i++) {
                    LineEvent part = boundaries.size() == 2 ? target : slice(target,
                            boundaries.get(i - 1), boundaries.get(i), timeline);
                    if (part == null) return Result.error(Error.UNSUPPORTED_CURVE);
                    parts.add(part);
                }
                if (generated.size() + parts.size() > MAX_SEGMENTS) {
                    return Result.error(Error.TOO_MANY_SEGMENTS);
                }
                changed |= parts.size() > 1;
                replacements.put(target, parts);
                generated.addAll(parts);
            }
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return Result.error(Error.INVALID_TIME);
        }
        if (!changed) return Result.error(Error.NO_CUTS);
        for (LineEvent target : targets) {
            if (before.containsKey(target.type)) continue;
            List<LineEvent> original = new ArrayList<>(layer.events(target.type));
            before.put(target.type, original);
            List<LineEvent> replaced = new ArrayList<>();
            for (LineEvent event : original) {
                List<LineEvent> parts = replacements.get(event);
                if (parts == null) replaced.add(event);
                else replaced.addAll(parts);
            }
            after.put(target.type, replaced);
        }
        EditHistory.Command command = new EditHistory.Command() {
            private void restore(Map<EventType, List<LineEvent>> values) {
                for (Map.Entry<EventType, List<LineEvent>> entry : values.entrySet()) {
                    List<LineEvent> track = layer.events(entry.getKey());
                    track.clear();
                    track.addAll(entry.getValue());
                }
            }

            @Override public void apply() { restore(after); }
            @Override public void revert() { restore(before); }
        };
        return new Result(Error.NONE, command, generated, targets.size(), step);
    }

    /** One segment per interval of the currently displayed beat grid. */
    public static BeatTime step(int subdivision) {
        if (subdivision <= 0) {
            throw new IllegalArgumentException("invalid beat grid");
        }
        return new BeatTime(0, 1, subdivision);
    }

    private static LineEvent slice(LineEvent source, BeatTime start, BeatTime end,
                                   DoubleUnaryOperator timeline) {
        LineEvent part = source.copy();
        double origin = timeline.applyAsDouble(source.startTime.toDouble());
        double duration = timeline.applyAsDouble(source.endTime.toDouble()) - origin;
        if (!Double.isFinite(duration) || duration <= 0) return null;
        double from = (timeline.applyAsDouble(start.toDouble()) - origin) / duration;
        double to = (timeline.applyAsDouble(end.toDouble()) - origin) / duration;
        part.startTime = start;
        part.endTime = end;
        part.start = source.start + (source.end - source.start) * source.easedProgressAt(from);
        part.end = source.start + (source.end - source.start) * source.easedProgressAt(to);
        if (!Double.isFinite(part.start) || !Double.isFinite(part.end)) return null;
        if (source.type != EventType.SPEED) {
            double span = source.easingRight - source.easingLeft;
            part.easingLeft = source.easingLeft + span * from;
            part.easingRight = source.easingLeft + span * to;
            // A slice spanning an equal-valued pair of extrema loses its excursion in RPE.
            if (source.start != source.end && part.start == part.end
                    && source.easedProgressAt((from + to) / 2.0) != source.easedProgressAt(from)) {
                return null;
            }
        }
        return part;
    }
}
