package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class EventLayer {
    private final Map<EventType, List<LineEvent>> events = new EnumMap<>(EventType.class);
    private JSONObject raw;
    private boolean sourceNull;

    public EventLayer() {
        for (EventType type : EventType.values()) events.put(type, new MaterializingList());
    }

    public static EventLayer fromJson(JSONObject object) throws JSONException {
        EventLayer layer = new EventLayer();
        String[] typedKeys = new String[EventType.values().length];
        for (int index = 0; index < EventType.values().length; index++) {
            typedKeys[index] = EventType.values()[index].jsonKey;
        }
        layer.raw = RawJson.unknownFields(object, typedKeys);
        layer.sourceNull = object == null;
        if (object == null) return layer;
        for (EventType type : EventType.values()) {
            JSONArray array = object.optJSONArray(type.jsonKey);
            if (array == null) continue;
            List<LineEvent> target = layer.events(type);
            for (int index = 0; index < array.length(); index++) {
                JSONObject eventObject = array.optJSONObject(index);
                if (eventObject != null) target.add(LineEvent.fromJson(type, eventObject));
            }
            sort(target);
        }
        return layer;
    }

    public JSONObject toJson() throws JSONException {
        return toJson(false, false);
    }

    JSONObject toJson(boolean baseLayer) throws JSONException {
        return toJson(true, baseLayer);
    }

    private JSONObject toJson(boolean addCompatibilityPrefix,
                              boolean baseLayer) throws JSONException {
        JSONObject object = RawJson.shallowCopy(raw);
        for (EventType type : EventType.values()) {
            List<LineEvent> values = events(type);
            sort(values);
            if (values.isEmpty()) {
                // Missing and empty event arrays are equivalent in RPE. Omitting empty arrays
                // also keeps exports loadable in older Phira parsers that index every present
                // event array without first checking whether it is empty.
                object.remove(type.jsonKey);
                continue;
            }
            JSONArray array = new JSONArray();
            if (addCompatibilityPrefix) {
                appendCompatibilityPrefix(array, type, values.get(0), baseLayer);
            }
            for (LineEvent event : values) array.put(event.toJson());
            object.put(type.jsonKey, array);
        }
        return object;
    }

    public Object toJsonValue() throws JSONException {
        if (sourceNull && count() == 0) return JSONObject.NULL;
        return toJson();
    }

    Object toJsonValue(boolean baseLayer) throws JSONException {
        if (sourceNull && count() == 0) return JSONObject.NULL;
        return toJson(baseLayer);
    }

    private static void appendCompatibilityPrefix(JSONArray target, EventType type,
                                                   LineEvent first, boolean baseLayer)
            throws JSONException {
        if (first == null || first.startTime == null
                || first.startTime.compareTo(BeatTime.zero()) <= 0) return;

        // Phira/RPE Anim starts evaluating from the first keyframe even before its timestamp.
        // A delayed nonlinear first event therefore extrapolates backwards and can produce
        // thousands of degrees of rotation. Materialize the value PhiStudio already uses
        // before that event: the base-layer default, or zero for additive layers.
        LineEvent prefix = new LineEvent();
        prefix.type = type;
        prefix.startTime = BeatTime.zero();
        prefix.endTime = first.startTime;
        prefix.start = baseLayer ? LineEvent.defaultValue(type) : 0.0;
        prefix.end = prefix.start;
        prefix.easingType = 1;
        target.put(prefix.toJson());
    }

    public boolean isNullPlaceholder() {
        return sourceNull && count() == 0;
    }

    public List<LineEvent> events(EventType type) {
        return events.get(type);
    }

    public int count() {
        int total = 0;
        for (List<LineEvent> values : events.values()) total += values.size();
        return total;
    }

    public double valueAt(EventType type, double beat) {
        List<LineEvent> values = events(type);
        double value = LineEvent.defaultValue(type);
        for (LineEvent event : values) {
            if (event.startTime.toDouble() > beat) break;
            value = event.valueAt(beat);
        }
        return value;
    }

    public boolean overlaps(LineEvent candidate, LineEvent ignored) {
        BeatTime candidateStart = candidate.startTime;
        BeatTime candidateEnd = candidate.endTime;
        for (LineEvent existing : events(candidate.type)) {
            if (existing == ignored) continue;
            if (candidateStart.compareTo(existing.endTime) < 0
                    && candidateEnd.compareTo(existing.startTime) > 0) return true;
        }
        return false;
    }

    private static void sort(List<LineEvent> values) {
        values.sort(Comparator.comparing(value -> value.startTime));
    }

    private final class MaterializingList extends ArrayList<LineEvent> {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean add(LineEvent value) {
            sourceNull = false;
            return super.add(value);
        }

        @Override
        public void add(int index, LineEvent element) {
            sourceNull = false;
            super.add(index, element);
        }

        @Override
        public boolean addAll(Collection<? extends LineEvent> values) {
            if (!values.isEmpty()) sourceNull = false;
            return super.addAll(values);
        }

        @Override
        public boolean addAll(int index, Collection<? extends LineEvent> values) {
            if (!values.isEmpty()) sourceNull = false;
            return super.addAll(index, values);
        }

        @Override
        public LineEvent set(int index, LineEvent element) {
            sourceNull = false;
            return super.set(index, element);
        }

        @Override
        public LineEvent remove(int index) {
            sourceNull = false;
            return super.remove(index);
        }

        @Override
        public boolean remove(Object value) {
            boolean changed = super.remove(value);
            if (changed) sourceNull = false;
            return changed;
        }

        @Override
        public boolean removeAll(Collection<?> values) {
            boolean changed = super.removeAll(values);
            if (changed) sourceNull = false;
            return changed;
        }

        @Override
        public boolean retainAll(Collection<?> values) {
            boolean changed = super.retainAll(values);
            if (changed) sourceNull = false;
            return changed;
        }

        @Override
        public void clear() {
            if (!isEmpty()) sourceNull = false;
            super.clear();
        }
    }
}
