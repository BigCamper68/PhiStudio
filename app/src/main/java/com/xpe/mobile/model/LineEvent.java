package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class LineEvent {
    public EventType type = EventType.MOVE_X;
    public BeatTime startTime = BeatTime.zero();
    public BeatTime endTime = new BeatTime(1, 0, 1);
    public double start;
    public double end;
    public int easingType = 1;
    public double easingLeft;
    public double easingRight = 1.0;
    public int linkGroup;
    public boolean bezier;
    public final double[] bezierPoints = new double[]{0.0, 0.0, 0.0, 0.0};
    private JSONObject raw;

    public static LineEvent fromJson(EventType type, JSONObject object) throws JSONException {
        LineEvent event = new LineEvent();
        event.type = type;
        // Speed-event easing is source renderer metadata, so it deliberately remains raw.
        event.raw = type == EventType.SPEED
                ? RawJson.unknownFields(object,
                "startTime", "endTime", "start", "end", "linkgroup")
                : RawJson.unknownFields(object,
                "startTime", "endTime", "start", "end", "easingType",
                "easingLeft", "easingRight", "linkgroup", "bezier", "bezierPoints");
        event.startTime = BeatTime.fromJson(object.optJSONArray("startTime"));
        event.endTime = BeatTime.fromJson(object.optJSONArray("endTime"));
        event.start = object.optDouble("start", defaultValue(type));
        event.end = object.optDouble("end", event.start);
        event.easingType = type == EventType.SPEED ? 1 : Math.max(1, object.optInt("easingType", 1));
        event.easingLeft = object.optDouble("easingLeft", 0.0);
        event.easingRight = object.optDouble("easingRight", 1.0);
        event.linkGroup = object.optInt("linkgroup", 0);
        event.bezier = object.optInt("bezier", 0) != 0;
        JSONArray points = object.optJSONArray("bezierPoints");
        if (points != null) {
            for (int index = 0; index < Math.min(4, points.length()); index++) {
                event.bezierPoints[index] = points.optDouble(index, 0.0);
            }
        }
        return event;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = RawJson.shallowCopy(raw);
        object.put("startTime", startTime.toJson());
        object.put("endTime", endTime.toJson());
        object.put("start", start);
        object.put("end", end);
        object.put("linkgroup", linkGroup);
        if (type != EventType.SPEED) {
            object.put("easingType", Math.max(1, easingType));
            object.put("easingLeft", easingLeft);
            object.put("easingRight", easingRight);
            object.put("bezier", bezier ? 1 : 0);
            object.put("bezierPoints", new JSONArray()
                    .put(bezierPoints[0])
                    .put(bezierPoints[1])
                    .put(bezierPoints[2])
                    .put(bezierPoints[3]));
        }
        return object;
    }

    public LineEvent copy() {
        LineEvent copy = new LineEvent();
        copy.type = type;
        copy.startTime = startTime;
        copy.endTime = endTime;
        copy.start = start;
        copy.end = end;
        copy.easingType = easingType;
        copy.easingLeft = easingLeft;
        copy.easingRight = easingRight;
        copy.linkGroup = linkGroup;
        copy.bezier = bezier;
        System.arraycopy(bezierPoints, 0, copy.bezierPoints, 0, bezierPoints.length);
        copy.raw = raw;
        return copy;
    }

    public double valueAt(double beat) {
        double startBeat = startTime.toDouble();
        double endBeat = endTime.toDouble();
        if (beat <= startBeat || endBeat <= startBeat) return start;
        if (beat >= endBeat) return end;
        double t = (beat - startBeat) / (endBeat - startBeat);
        return start + (end - start) * easedProgressAt(t);
    }

    /**
     * Evaluates an imported speed event using the RPE 1.7 renderer rules. Editing continues
     * to expose speed events as linear; the source-only easing metadata is read from raw JSON.
     */
    public double renderSpeedValueAt(double beat, int rpeVersion) {
        return renderSpeedValueAt(beat, rpeVersion, true);
    }

    public double renderSpeedValueAt(double beat, int rpeVersion,
                                     boolean useRpe170Speed) {
        double startBeat = startTime.toDouble();
        double endBeat = endTime.toDouble();
        if (beat <= startBeat || endBeat <= startBeat) return start;
        if (beat >= endBeat) return end;
        double t = (beat - startBeat) / (endBeat - startBeat);
        return start + (end - start) * renderSpeedProgressAt(
                t, rpeVersion, useRpe170Speed);
    }

    /** Integrates speed over chart beats, for note-height evaluation. */
    public double integratedRenderSpeed(double fromBeat, double toBeat, int rpeVersion) {
        return integratedRenderSpeed(fromBeat, toBeat, rpeVersion, true);
    }

    public double integratedRenderSpeed(double fromBeat, double toBeat, int rpeVersion,
                                        boolean useRpe170Speed) {
        if (!Double.isFinite(fromBeat) || !Double.isFinite(toBeat)
                || toBeat <= fromBeat) return 0.0;
        double eventStart = startTime.toDouble();
        double eventEnd = endTime.toDouble();
        if (!Double.isFinite(eventStart) || !Double.isFinite(eventEnd)
                || eventEnd <= eventStart) {
            return start * (toBeat - fromBeat);
        }

        double result = 0.0;
        double cursor = fromBeat;
        if (cursor < eventStart) {
            double beforeEnd = Math.min(toBeat, eventStart);
            result += start * (beforeEnd - cursor);
            cursor = beforeEnd;
        }
        if (cursor < toBeat && cursor < eventEnd) {
            double insideEnd = Math.min(toBeat, eventEnd);
            double duration = eventEnd - eventStart;
            double left = Math.max(0.0, (cursor - eventStart) / duration);
            double right = Math.min(1.0, (insideEnd - eventStart) / duration);
            double progressArea = renderSpeedProgressIntegralAt(
                    right, rpeVersion, useRpe170Speed)
                    - renderSpeedProgressIntegralAt(
                    left, rpeVersion, useRpe170Speed);
            result += duration * (start * (right - left)
                    + (end - start) * progressArea);
            cursor = insideEnd;
        }
        if (cursor < toBeat) result += end * (toBeat - cursor);
        return result;
    }

    /** Returns the event's normalized easing progress, including RPE bounds and custom Bézier. */
    public double easedProgressAt(double input) {
        if (type == EventType.SPEED) return Easing.apply(1, input);
        return configuredProgressAt(input);
    }

    private double configuredProgressAt(double input) {
        if (bezier) {
            return Easing.applyCubicBezierWindowed(input, easingLeft, easingRight,
                    bezierPoints[0], bezierPoints[1], bezierPoints[2], bezierPoints[3]);
        }
        return Easing.applyWindowed(easingType, input, easingLeft, easingRight);
    }

    private double renderSpeedProgressAt(double input, int rpeVersion,
                                         boolean useRpe170Speed) {
        if (!useRpe170Speed) return input;
        int sourceEasingType = raw == null ? 1 : Math.max(0, raw.optInt("easingType", 1));
        if (rpeVersion < 170 || sourceEasingType <= 1) {
            return sourceEasingType == 0 && rpeVersion >= 170 ? 0.0 : input;
        }
        if (bezier) {
            return Easing.applyCubicBezierWindowed(input, easingLeft, easingRight,
                    bezierPoints[0], bezierPoints[1], bezierPoints[2], bezierPoints[3]);
        }
        return Easing.applyWindowed(sourceEasingType, input, easingLeft, easingRight);
    }

    private double renderSpeedProgressIntegralAt(double input, int rpeVersion,
                                                 boolean useRpe170Speed) {
        double t = Math.max(0.0, Math.min(1.0, input));
        if (!useRpe170Speed) return t * t / 2.0;
        int sourceEasingType = raw == null ? 1 : Math.max(0, raw.optInt("easingType", 1));
        if (rpeVersion < 170 || sourceEasingType == 1) return t * t / 2.0;
        if (sourceEasingType == 0) return 0.0;
        if (bezier) {
            return Easing.integralCubicBezierWindowed(t, easingLeft, easingRight,
                    bezierPoints[0], bezierPoints[1], bezierPoints[2], bezierPoints[3]);
        }
        return Easing.integralWindowed(
                sourceEasingType, t, easingLeft, easingRight);
    }

    public static double defaultValue(EventType type) {
        switch (type) {
            case ALPHA: return 255.0;
            case SPEED: return 10.0;
            default: return 0.0;
        }
    }
}
