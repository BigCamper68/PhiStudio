package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Typed, read-only view of RPE note controls stored on a judge line. */
public final class NoteControlEvents {
    public final List<ControlEvent> position = new ArrayList<>();
    public final List<ControlEvent> size = new ArrayList<>();
    public final List<ControlEvent> alpha = new ArrayList<>();
    public final List<ControlEvent> y = new ArrayList<>();

    public static NoteControlEvents fromJson(JSONObject line) {
        NoteControlEvents result = new NoteControlEvents();
        if (line == null) return result;
        read(line.optJSONArray("posControl"), "pos", result.position);
        read(line.optJSONArray("sizeControl"), "size", result.size);
        read(line.optJSONArray("alphaControl"), "alpha", result.alpha);
        read(line.optJSONArray("yControl"), "y", result.y);
        return result;
    }

    public int count() {
        return position.size() + size.size() + alpha.size() + y.size();
    }

    public static double valueAt(List<ControlEvent> events, double height,
                                 double defaultValue) {
        if (events == null || events.isEmpty() || isIdentitySentinel(events)) {
            return defaultValue;
        }
        ControlEvent first = events.get(0);
        if (events.size() == 1 || height <= first.x) return finite(first.value, defaultValue);
        for (int index = 1; index < events.size(); index++) {
            ControlEvent next = events.get(index);
            if (height < next.x) {
                ControlEvent previous = events.get(index - 1);
                double span = next.x - previous.x;
                if (!Double.isFinite(span) || span <= 0.0) {
                    return finite(next.value, defaultValue);
                }
                double progress = (height - previous.x) / span;
                double eased = Easing.apply(Math.max(1, next.easing), progress);
                return finite(previous.value, defaultValue)
                        + (finite(next.value, defaultValue)
                        - finite(previous.value, defaultValue)) * eased;
            }
        }
        return finite(events.get(events.size() - 1).value, defaultValue);
    }

    private static void read(JSONArray source, String valueKey, List<ControlEvent> target) {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject object = source.optJSONObject(index);
            if (object == null) continue;
            ControlEvent event = new ControlEvent();
            event.x = object.optDouble("x", 0.0);
            event.easing = Math.max(1, Math.min(Easing.MAX_TYPE,
                    object.optInt("easing", 1)));
            event.value = object.optDouble(valueKey, 1.0);
            target.add(event);
        }
    }

    private static boolean isIdentitySentinel(List<ControlEvent> events) {
        ControlEvent first = events.get(0);
        return events.size() == 2 && first.easing == 1
                && Math.abs(first.value - 1.0) < 1.0e-4;
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    public static final class ControlEvent {
        public double x;
        public int easing = 1;
        public double value = 1.0;
    }
}
