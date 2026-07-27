package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Complex Move fitter based on the parametric workflow in the Re:PhiEdit manual. */
public final class ComplexMoveGenerator {
    public static final int MAX_SEGMENTS = 4096;

    public enum Error {
        NONE,
        INVALID_TIME,
        INVALID_DENSITY,
        TOO_MANY_SEGMENTS,
        INVALID_EASING,
        INVALID_EXPRESSION,
        NON_FINITE_RESULT,
        X_OUT_OF_RANGE,
        Y_OUT_OF_RANGE,
        RESERVED_LAYER,
        EVENT_OVERLAP
    }

    public static final class TimeEasing {
        public final int type;
        public final double left;
        public final double right;

        public TimeEasing(int type, double left, double right) {
            if (type < Easing.MIN_TYPE || type > Easing.MAX_TYPE) {
                throw new IllegalArgumentException("easing type must be 1 to 28");
            }
            if (!Double.isFinite(left) || !Double.isFinite(right)
                    || left < 0.0 || right > 1.0 || left > right) {
                throw new IllegalArgumentException("invalid easing window");
            }
            this.type = type;
            this.left = left;
            this.right = right;
        }

        public static TimeEasing parse(String text) {
            if (text == null) throw new IllegalArgumentException("easing is required");
            String value = text.trim();
            if (value.isEmpty()) throw new IllegalArgumentException("easing is required");
            String[] parts = value.split("[\\s,;]+", -1);
            if (parts.length != 1 && parts.length != 3) {
                throw new IllegalArgumentException("easing needs a type or type,left,right");
            }
            try {
                int type = Integer.parseInt(parts[0]);
                double left = parts.length == 3 ? Double.parseDouble(parts[1]) : 0.0;
                double right = parts.length == 3 ? Double.parseDouble(parts[2]) : 1.0;
                return new TimeEasing(type, left, right);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid easing", exception);
            }
        }

        double apply(double input) {
            return Easing.applyWindowed(type, input, left, right);
        }
    }

    public static final class Spec {
        public BeatTime startTime;
        public BeatTime endTime;
        public String xExpression;
        public String yExpression;
        public TimeEasing xTimeEasing;
        public TimeEasing yTimeEasing;
        public double density;
    }

    public static final class PathPoint {
        public final double x;
        public final double y;

        PathPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class Result {
        public final Error error;
        public final String detail;
        public final List<LineEvent> moveXEvents;
        public final List<LineEvent> moveYEvents;
        public final List<PathPoint> path;
        public final int segmentCount;

        private Result(Error error, String detail, List<LineEvent> moveXEvents,
                       List<LineEvent> moveYEvents, List<PathPoint> path, int segmentCount) {
            this.error = error;
            this.detail = detail == null ? "" : detail;
            this.moveXEvents = Collections.unmodifiableList(moveXEvents);
            this.moveYEvents = Collections.unmodifiableList(moveYEvents);
            this.path = Collections.unmodifiableList(path);
            this.segmentCount = segmentCount;
        }

        static Result error(Error error, String detail) {
            return new Result(error, detail, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), 0);
        }
    }

    private ComplexMoveGenerator() {
    }

    public static Result preview(Spec spec) {
        return build(null, -1, spec, false);
    }

    public static Result generate(EventLayer layer, int layerIndex, Spec spec) {
        return build(layer, layerIndex, spec, true);
    }

    private static Result build(EventLayer layer, int layerIndex, Spec spec,
                                boolean validatePlacement) {
        if (spec == null || spec.startTime == null || spec.endTime == null
                || spec.startTime.toDouble() < 0.0
                || spec.endTime.compareTo(spec.startTime) <= 0) {
            return Result.error(Error.INVALID_TIME, "");
        }
        if (validatePlacement && (layer == null || layerIndex < 0 || layerIndex > 3)) {
            return Result.error(Error.RESERVED_LAYER, "");
        }
        if (!Double.isFinite(spec.density) || spec.density <= 0.0) {
            return Result.error(Error.INVALID_DENSITY, "");
        }
        if (spec.xTimeEasing == null || spec.yTimeEasing == null) {
            return Result.error(Error.INVALID_EASING, "");
        }

        double duration = spec.endTime.toDouble() - spec.startTime.toDouble();
        double requestedSegments = duration * spec.density;
        if (!Double.isFinite(requestedSegments) || requestedSegments > MAX_SEGMENTS) {
            return Result.error(Error.TOO_MANY_SEGMENTS, "");
        }
        int segments = Math.max(1, (int) Math.ceil(requestedSegments - 1.0e-12));

        MathExpression.Compiled xExpression;
        MathExpression.Compiled yExpression;
        try {
            xExpression = MathExpression.compile(spec.xExpression);
            yExpression = MathExpression.compile(spec.yExpression);
        } catch (IllegalArgumentException exception) {
            return Result.error(Error.INVALID_EXPRESSION, exception.getMessage());
        }

        List<PathPoint> path = new ArrayList<>(segments + 1);
        for (int index = 0; index <= segments; index++) {
            double progress = index / (double) segments;
            double x;
            double y;
            try {
                x = xExpression.evaluate(spec.xTimeEasing.apply(progress));
                y = yExpression.evaluate(spec.yTimeEasing.apply(progress));
            } catch (IllegalArgumentException exception) {
                return Result.error(Error.NON_FINITE_RESULT, exception.getMessage());
            }
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return Result.error(Error.NON_FINITE_RESULT, "");
            }
            if (x < -675.0 || x > 675.0) return Result.error(Error.X_OUT_OF_RANGE, "");
            if (y < -450.0 || y > 450.0) return Result.error(Error.Y_OUT_OF_RANGE, "");
            path.add(new PathPoint(x, y));
        }

        List<LineEvent> moveX = new ArrayList<>(segments);
        List<LineEvent> moveY = new ArrayList<>(segments);
        try {
            for (int index = 0; index < segments; index++) {
                BeatTime start = BeatTime.interpolate(spec.startTime, spec.endTime,
                        index, segments);
                BeatTime end = BeatTime.interpolate(spec.startTime, spec.endTime,
                        index + 1, segments);
                LineEvent xEvent = event(EventType.MOVE_X, start, end,
                        path.get(index).x, path.get(index + 1).x);
                LineEvent yEvent = event(EventType.MOVE_Y, start, end,
                        path.get(index).y, path.get(index + 1).y);
                if (validatePlacement && (layer.overlaps(xEvent, null)
                        || layer.overlaps(yEvent, null))) {
                    return Result.error(Error.EVENT_OVERLAP, "");
                }
                moveX.add(xEvent);
                moveY.add(yEvent);
            }
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Result.error(Error.INVALID_TIME, exception.getMessage());
        }
        return new Result(Error.NONE, "", moveX, moveY, path, segments);
    }

    private static LineEvent event(EventType type, BeatTime startTime, BeatTime endTime,
                                   double start, double end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = startTime;
        event.endTime = endTime;
        event.start = start;
        event.end = end;
        event.easingType = 1;
        event.easingLeft = 0.0;
        event.easingRight = 1.0;
        return event;
    }
}
