package com.xpe.mobile.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Typed, editable and lossless view of RPE judge-line extended/storyboard events. */
public final class ExtendedLineEvents {
    public final List<NumericEvent> scaleXEvents = new ArrayList<>();
    public final List<NumericEvent> scaleYEvents = new ArrayList<>();
    public final List<NumericEvent> inclineEvents = new ArrayList<>();
    public final List<NumericEvent> paintEvents = new ArrayList<>();
    public final List<NumericEvent> gifEvents = new ArrayList<>();
    public final List<ColorEvent> colorEvents = new ArrayList<>();
    public final List<TextEvent> textEvents = new ArrayList<>();

    private JSONObject raw;
    private final EnumSet<StoryboardEventType> modified =
            EnumSet.noneOf(StoryboardEventType.class);

    public static ExtendedLineEvents fromJson(JSONObject object) throws JSONException {
        ExtendedLineEvents result = new ExtendedLineEvents();
        result.raw = object;
        if (object == null) return result;
        readNumeric(object.optJSONArray("scaleXEvents"), result.scaleXEvents);
        readNumeric(object.optJSONArray("scaleYEvents"), result.scaleYEvents);
        readNumeric(object.optJSONArray("inclineEvents"), result.inclineEvents);
        readNumeric(object.optJSONArray("paintEvents"), result.paintEvents);
        readNumeric(object.optJSONArray("gifEvents"), result.gifEvents);
        JSONArray colors = object.optJSONArray("colorEvents");
        if (colors != null) {
            for (int index = 0; index < colors.length(); index++) {
                JSONObject event = colors.optJSONObject(index);
                if (event != null) result.colorEvents.add(ColorEvent.fromJson(event));
            }
        }
        JSONArray texts = object.optJSONArray("textEvents");
        if (texts != null) {
            for (int index = 0; index < texts.length(); index++) {
                JSONObject event = texts.optJSONObject(index);
                if (event != null) result.textEvents.add(TextEvent.fromJson(event));
            }
        }
        result.sortAll();
        return result;
    }

    public JSONObject toJsonValue() throws JSONException {
        JSONObject object = RawJson.shallowCopy(raw);
        for (StoryboardEventType type : StoryboardEventType.values()) {
            if (raw != null && !modified.contains(type)) continue;
            List<? extends TimedEvent> events = events(type);
            if (events.isEmpty()) {
                JSONArray preserved = serializedArray(type, events);
                if (preserved.length() == 0) object.remove(type.jsonKey);
                else object.put(type.jsonKey, preserved);
                continue;
            }
            object.put(type.jsonKey, serializedArray(type, events));
        }
        return object;
    }

    public boolean isModified() {
        return !modified.isEmpty();
    }

    /** An explicitly empty channel still selects Paint/Text/GIF in prpr. */
    public boolean hasChannel(StoryboardEventType type) {
        return !events(type).isEmpty() || (raw != null && !modified.contains(type)
                && raw.optJSONArray(type.jsonKey) != null);
    }

    /** Playback consumes imported GIF keyframes in authored order, independently of editor sorting. */
    public List<NumericEvent> gifSourceEvents() {
        JSONArray source = raw == null || modified.contains(StoryboardEventType.GIF)
                ? null : raw.optJSONArray("gifEvents");
        List<NumericEvent> result = new ArrayList<>();
        if (source != null && source.length() == gifEvents.size()) {
            for (int i = 0; i < source.length(); i++) {
                JSONObject authored = source.optJSONObject(i);
                NumericEvent matching = null;
                for (NumericEvent event : gifEvents) {
                    if (((TimedEvent) event).raw == authored) { matching = event; break; }
                }
                if (matching == null) { result.clear(); break; }
                result.add(matching.copy());
            }
            if (result.size() == gifEvents.size()) return result;
        }
        result.clear();
        for (NumericEvent event : gifEvents) result.add(event.copy());
        return result;
    }

    public int count() {
        return scaleXEvents.size() + scaleYEvents.size() + inclineEvents.size()
                + paintEvents.size() + gifEvents.size() + colorEvents.size()
                + textEvents.size();
    }

    public List<? extends TimedEvent> events(StoryboardEventType type) {
        if (type == null) return new ArrayList<>();
        switch (type) {
            case SCALE_X: return scaleXEvents;
            case SCALE_Y: return scaleYEvents;
            case COLOR: return colorEvents;
            case PAINT: return paintEvents;
            case TEXT: return textEvents;
            case INCLINE: return inclineEvents;
            case GIF: return gifEvents;
            default: return new ArrayList<>();
        }
    }

    public boolean contains(StoryboardEventType type, TimedEvent event) {
        return event != null && events(type).contains(event);
    }

    public void add(StoryboardEventType type, TimedEvent event) {
        event.markModified();
        insert(type, event, events(type).size());
    }

    public void insert(StoryboardEventType type, TimedEvent event, int index) {
        List<TimedEvent> target = mutableEvents(type);
        requireCompatible(type, event);
        int safe = Math.max(0, Math.min(index, target.size()));
        target.add(safe, event);
        sort(type);
        modified.add(type);
    }

    public boolean remove(StoryboardEventType type, TimedEvent event) {
        boolean removed = mutableEvents(type).remove(event);
        if (removed) modified.add(type);
        return removed;
    }

    public int indexOf(StoryboardEventType type, TimedEvent event) {
        return events(type).indexOf(event);
    }

    public void copyEvent(StoryboardEventType type, TimedEvent source, TimedEvent target) {
        requireCompatible(type, source);
        requireCompatible(type, target);
        source.copyFieldsTo(target);
        target.markModified();
        sort(type);
        modified.add(type);
    }

    public void markModified(StoryboardEventType type) {
        if (type != null) modified.add(type);
    }

    public static double numericValueAt(List<NumericEvent> events, double beat,
                                        double defaultValue) {
        NumericEvent event = latest(events, beat);
        return event == null ? defaultValue : event.valueAt(beat);
    }

    public static int colorValueAt(List<ColorEvent> events, double beat, int defaultRgb) {
        ColorEvent event = latest(events, beat);
        return event == null ? defaultRgb : event.valueAt(beat);
    }

    public static String textValueAt(List<TextEvent> events, double beat, String defaultText) {
        TextEvent event = latest(events, beat);
        return event == null ? defaultText : event.valueAt(beat);
    }

    public static double firstStartBeat(List<? extends TimedEvent> events,
                                        double fallback) {
        double first = fallback;
        for (TimedEvent event : events) {
            if (event != null && event.startTime != null) {
                first = Math.min(first, event.startTime.toDouble());
            }
        }
        return first;
    }

    private static void readNumeric(JSONArray source, List<NumericEvent> target)
            throws JSONException {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject event = source.optJSONObject(index);
            if (event != null) target.add(NumericEvent.fromJson(event));
        }
    }

    private JSONArray serializedArray(StoryboardEventType type,
                                      List<? extends TimedEvent> events)
            throws JSONException {
        JSONArray result = new JSONArray();
        JSONArray original = raw == null ? null : raw.optJSONArray(type.jsonKey);
        int eventIndex = 0;
        if (original != null) {
            for (int index = 0; index < original.length(); index++) {
                Object value = original.get(index);
                if (value instanceof JSONObject) {
                    if (eventIndex < events.size()) {
                        result.put(events.get(eventIndex++).toJson());
                    }
                } else {
                    result.put(value);
                }
            }
        }
        while (eventIndex < events.size()) {
            result.put(events.get(eventIndex++).toJson());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<TimedEvent> mutableEvents(StoryboardEventType type) {
        return (List<TimedEvent>) (List<?>) events(type);
    }

    private static void requireCompatible(StoryboardEventType type, TimedEvent event) {
        if (type == null || event == null
                || type == StoryboardEventType.COLOR && !(event instanceof ColorEvent)
                || type == StoryboardEventType.TEXT && !(event instanceof TextEvent)
                || type.isNumeric() && !(event instanceof NumericEvent)) {
            throw new IllegalArgumentException("Storyboard event type does not match its value");
        }
    }

    private void sortAll() {
        for (StoryboardEventType type : StoryboardEventType.values()) sort(type);
    }

    private void sort(StoryboardEventType type) {
        mutableEvents(type).sort((first, second) -> first.startTime.compareTo(second.startTime));
    }

    private static <T extends TimedEvent> T latest(List<T> events, double beat) {
        T latest = null;
        for (T event : events) {
            if (event == null || event.startTime == null) continue;
            if (event.startTime.toDouble() <= beat
                    && (latest == null
                    || event.startTime.compareTo(latest.startTime) >= 0)) {
                latest = event;
            }
        }
        return latest;
    }

    public abstract static class TimedEvent {
        public BeatTime startTime = BeatTime.zero();
        public BeatTime endTime = new BeatTime(1, 0, 1);
        public int easingType = 1;
        public double easingLeft;
        public double easingRight = 1.0;
        public int linkGroup;
        public boolean bezier;
        public final double[] bezierPoints = new double[]{0.0, 0.0, 0.0, 0.0};
        private JSONObject raw;
        private boolean modified;

        final void readTiming(JSONObject object) throws JSONException {
            raw = object;
            startTime = BeatTime.fromJson(object.optJSONArray("startTime"));
            endTime = BeatTime.fromJson(object.optJSONArray("endTime"));
            easingType = Math.max(Easing.MIN_TYPE,
                    Math.min(Easing.MAX_TYPE, object.optInt("easingType", 1)));
            easingLeft = object.optDouble("easingLeft", 0.0);
            easingRight = object.optDouble("easingRight", 1.0);
            linkGroup = object.optInt("linkgroup", 0);
            bezier = object.optInt("bezier", 0) != 0;
            JSONArray points = object.optJSONArray("bezierPoints");
            if (points != null) {
                for (int index = 0; index < Math.min(4, points.length()); index++) {
                    bezierPoints[index] = points.optDouble(index, 0.0);
                }
            }
        }

        public final JSONObject toJson() throws JSONException {
            if (raw != null && !modified) return RawJson.shallowCopy(raw);
            JSONObject object = RawJson.shallowCopy(raw);
            object.put("startTime", startTime.toJson());
            object.put("endTime", endTime.toJson());
            object.put("easingType", easingType);
            object.put("easingLeft", easingLeft);
            object.put("easingRight", easingRight);
            object.put("linkgroup", linkGroup);
            object.put("bezier", bezier ? 1 : 0);
            object.put("bezierPoints", new JSONArray()
                    .put(bezierPoints[0]).put(bezierPoints[1])
                    .put(bezierPoints[2]).put(bezierPoints[3]));
            writeValues(object);
            return object;
        }

        public abstract TimedEvent copy();

        abstract void writeValues(JSONObject object) throws JSONException;

        abstract void copyValueFieldsTo(TimedEvent target);

        final void copyFieldsTo(TimedEvent target) {
            target.startTime = startTime;
            target.endTime = endTime;
            target.easingType = easingType;
            target.easingLeft = easingLeft;
            target.easingRight = easingRight;
            target.linkGroup = linkGroup;
            target.bezier = bezier;
            System.arraycopy(bezierPoints, 0, target.bezierPoints, 0, bezierPoints.length);
            copyValueFieldsTo(target);
        }

        final void copyBaseTo(TimedEvent target) {
            copyFieldsTo(target);
            target.raw = raw;
            target.modified = modified;
        }

        final void markModified() {
            modified = true;
        }

        public final double progressAt(double beat) {
            double startBeat = startTime.toDouble();
            double endBeat = endTime.toDouble();
            if (beat <= startBeat || endBeat <= startBeat) return 0.0;
            if (beat >= endBeat) return 1.0;
            double input = (beat - startBeat) / (endBeat - startBeat);
            return bezier
                    ? Easing.applyCubicBezierWindowed(input, easingLeft, easingRight,
                    bezierPoints[0], bezierPoints[1], bezierPoints[2], bezierPoints[3])
                    : Easing.applyWindowed(easingType, input, easingLeft, easingRight);
        }
    }

    public static final class NumericEvent extends TimedEvent {
        public double start;
        public double end;

        public static NumericEvent fromJson(JSONObject object) throws JSONException {
            NumericEvent event = new NumericEvent();
            event.readTiming(object);
            event.start = object.optDouble("start", 0.0);
            event.end = object.optDouble("end", event.start);
            return event;
        }

        @Override
        public NumericEvent copy() {
            NumericEvent copy = new NumericEvent();
            copyBaseTo(copy);
            return copy;
        }

        @Override
        void writeValues(JSONObject object) throws JSONException {
            object.put("start", start);
            object.put("end", end);
        }

        @Override
        void copyValueFieldsTo(TimedEvent target) {
            NumericEvent numeric = (NumericEvent) target;
            numeric.start = start;
            numeric.end = end;
        }

        public double valueAt(double beat) {
            if (beat <= startTime.toDouble()) return start;
            if (beat >= endTime.toDouble()) return end;
            return start + (end - start) * progressAt(beat);
        }
    }

    public static final class ColorEvent extends TimedEvent {
        public int startRgb = 0xFFFFFF;
        public int endRgb = 0xFFFFFF;

        public static ColorEvent fromJson(JSONObject object) throws JSONException {
            ColorEvent event = new ColorEvent();
            event.readTiming(object);
            event.startRgb = StoryboardColorTween.readRgb(object.optJSONArray("start"), 0xFFFFFF);
            event.endRgb = StoryboardColorTween.readRgb(object.optJSONArray("end"), event.startRgb);
            return event;
        }

        @Override
        public ColorEvent copy() {
            ColorEvent copy = new ColorEvent();
            copyBaseTo(copy);
            return copy;
        }

        @Override
        void writeValues(JSONObject object) throws JSONException {
            object.put("start", StoryboardColorTween.writeRgb(startRgb));
            object.put("end", StoryboardColorTween.writeRgb(endRgb));
        }

        @Override
        void copyValueFieldsTo(TimedEvent target) {
            ColorEvent color = (ColorEvent) target;
            color.startRgb = startRgb;
            color.endRgb = endRgb;
        }

        public int valueAt(double beat) {
            double progress = progressAt(beat);
            int red = StoryboardColorTween.interpolateChannel(startRgb >> 16, endRgb >> 16, progress);
            int green = StoryboardColorTween.interpolateChannel(startRgb >> 8, endRgb >> 8, progress);
            int blue = StoryboardColorTween.interpolateChannel(startRgb, endRgb, progress);
            return red << 16 | green << 8 | blue;
        }


    }

    public static final class TextEvent extends TimedEvent {
        public String start = "";
        public String end = "";

        public static TextEvent fromJson(JSONObject object) throws JSONException {
            TextEvent event = new TextEvent();
            event.readTiming(object);
            event.start = object.optString("start", "");
            event.end = object.optString("end", event.start);
            return event;
        }

        @Override
        public TextEvent copy() {
            TextEvent copy = new TextEvent();
            copyBaseTo(copy);
            return copy;
        }

        @Override
        void writeValues(JSONObject object) throws JSONException {
            object.put("start", start);
            object.put("end", end);
        }

        @Override
        void copyValueFieldsTo(TimedEvent target) {
            TextEvent text = (TextEvent) target;
            text.start = start;
            text.end = end;
        }

        public String valueAt(double beat) {
            if (beat >= endTime.toDouble()) return end;
            return tween(start, end, StoryboardTextTween.clamp(progressAt(beat)));
        }

        public static String tween(String start, String end, double progress) {
            return StoryboardTextTween.tween(start, end, progress);
        }

    }
}
