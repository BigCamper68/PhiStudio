package com.xpe.mobile.editor;

import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reversible manual-derived Pass and Rand operations for one normal event. */
public final class EventAdvancedOperation {
    public enum Error {
        NONE,
        TARGET_NOT_FOUND,
        NOT_ENOUGH_PREVIOUS_EVENTS,
        INVALID_RESULT
    }

    public static final class Result {
        public final Error error;
        public final EditHistory.Command command;
        public final double endValue;

        private Result(Error error, EditHistory.Command command, double endValue) {
            this.error = error;
            this.command = command;
            this.endValue = endValue;
        }

        private static Result error(Error error) {
            return new Result(error, null, Double.NaN);
        }
    }

    private EventAdvancedOperation() {
    }

    /** Extrapolates both endpoints from the two preceding same-type events. */
    public static Result pass(EventLayer layer, LineEvent target) {
        if (!contains(layer, target)) return Result.error(Error.TARGET_NOT_FOUND);
        List<LineEvent> previous = previousEvents(layer, target);
        if (previous.size() < 2) return Result.error(Error.NOT_ENOUGH_PREVIOUS_EVENTS);

        LineEvent older = previous.get(previous.size() - 2);
        LineEvent newer = previous.get(previous.size() - 1);
        LineEvent after = target.copy();
        after.start = extrapolate(older.start, newer.start);
        after.end = extrapolate(older.end, newer.end);
        if (!valid(after)) return Result.error(Error.INVALID_RESULT);
        return command(layer, target, after);
    }

    /** Fills the event tail with a useful type-specific random value. */
    public static Result randomize(EventLayer layer, LineEvent target, double unitRandom) {
        if (!contains(layer, target)) return Result.error(Error.TARGET_NOT_FOUND);
        if (!Double.isFinite(unitRandom)) return Result.error(Error.INVALID_RESULT);
        LineEvent after = target.copy();
        after.end = randomEndValue(target.type, Math.max(0.0, Math.min(
                Math.nextDown(1.0), unitRandom)));
        if (!valid(after)) return Result.error(Error.INVALID_RESULT);
        return command(layer, target, after);
    }

    static double randomEndValue(EventType type, double unitRandom) {
        switch (type) {
            case MOVE_X: return randomInteger(-675, 675, unitRandom);
            case MOVE_Y: return randomInteger(-450, 450, unitRandom);
            case ROTATE: return randomInteger(-180, 180, unitRandom);
            case ALPHA: return randomInteger(0, 255, unitRandom);
            case SPEED:
            default:
                return (1 + (int) Math.floor(unitRandom * 200.0)) / 10.0;
        }
    }

    private static Result command(EventLayer layer, LineEvent target, LineEvent after) {
        LineEvent before = target.copy();
        return new Result(Error.NONE,
                PropertyEditCommand.event(layer, target, before, after), after.end);
    }

    private static boolean contains(EventLayer layer, LineEvent target) {
        return layer != null && target != null && target.type != null
                && target.startTime != null && target.endTime != null
                && layer.events(target.type).contains(target);
    }

    private static List<LineEvent> previousEvents(EventLayer layer, LineEvent target) {
        List<LineEvent> result = new ArrayList<>();
        for (LineEvent candidate : layer.events(target.type)) {
            if (candidate == null || candidate == target || candidate.startTime == null) continue;
            if (candidate.startTime.compareTo(target.startTime) < 0) result.add(candidate);
        }
        result.sort(Comparator.comparing(event -> event.startTime));
        return result;
    }

    private static double extrapolate(double older, double newer) {
        return newer + (newer - older);
    }

    private static boolean valid(LineEvent event) {
        return PropertyValidator.validate(event) == PropertyValidator.Error.NONE;
    }

    private static int randomInteger(int minimum, int maximum, double unitRandom) {
        int count = maximum - minimum + 1;
        return minimum + Math.min(count - 1, (int) Math.floor(unitRandom * count));
    }
}
