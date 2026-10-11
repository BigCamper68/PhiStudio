package com.xpe.mobile.preview;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable prpr extra.json effects, evaluated in chart seconds, independently of chart BPM. */
public final class ShaderTimeline {
    public interface SourceLoader { String load(String name) throws IOException; }
    public static final ShaderTimeline EMPTY = new ShaderTimeline(Collections.emptyList());
    private static final Pattern DEFAULT = Pattern.compile(
            "uniform\\s+(\\w+)\\s+(\\w+);\\s+//\\s+%([^%]+)%");
    public final List<Effect> effects;

    private ShaderTimeline(List<Effect> effects) {
        this.effects = Collections.unmodifiableList(effects);
    }

    public boolean hasActive(double seconds) {
        for (Effect effect : effects) if (effect.active(seconds)) return true;
        return false;
    }

    public static ShaderTimeline parse(String source, SourceLoader loader) throws IOException {
        try {
            JSONObject root = new JSONObject(source);
            Tempo tempo = new Tempo(root.get("bpm"));
            JSONArray entries = root.optJSONArray("effects");
            List<Effect> effects = new ArrayList<>();
            Map<String, String> sources = new LinkedHashMap<>();
            if (entries != null) for (int i = 0; i < entries.length(); i++) {
                try {
                    JSONObject item = entries.getJSONObject(i);
                    String name = item.getString("shader");
                    String shader = sources.get(name);
                    if (shader == null) { shader = loader.load(name); sources.put(name, shader); }
                    Map<String, float[]> defaults = defaults(shader);
                    Map<String, Animation> uniforms = new LinkedHashMap<>();
                    JSONObject vars = item.optJSONObject("vars");
                    if (vars != null) {
                        Iterator<String> keys = vars.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            uniforms.put(key, Animation.parse(vars.get(key), tempo));
                        }
                    }
                    effects.add(new Effect(name, shader, tempo.time(item.getJSONArray("start")),
                            tempo.time(item.getJSONArray("end")), item.optBoolean("global", false),
                            defaults, uniforms));
                } catch (JSONException | IOException | IllegalArgumentException ex) {
                    throw new IOException("extra.json effect " + i + ": " + ex.getMessage(), ex);
                }
            }
            return effects.isEmpty() ? EMPTY : new ShaderTimeline(effects);
        } catch (JSONException | IllegalArgumentException ex) {
            throw new IOException("extra.json: " + ex.getMessage(), ex);
        }
    }

    private static Map<String, float[]> defaults(String source) throws IOException {
        Map<String, float[]> values = new LinkedHashMap<>();
        Matcher matcher = DEFAULT.matcher(source);
        while (matcher.find()) {
            int size;
            switch (matcher.group(1)) {
                case "float": size = 1; break;
                case "vec2": size = 2; break;
                case "vec4": size = 4; break;
                default: throw new IOException("Unknown uniform default type: " + matcher.group(1));
            }
            String[] parts = matcher.group(3).split(",", -1);
            if (parts.length != size) throw new IOException("Invalid default for " + matcher.group(2));
            float[] value = new float[size];
            try {
                for (int i = 0; i < size; i++) value[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ex) { throw new IOException("Invalid uniform default", ex); }
            values.put(matcher.group(2), value);
        }
        return values;
    }

    public static final class Effect {
        public final String name, source;
        public final double start, end;
        public final boolean global;
        public final Map<String, float[]> defaults;
        public final Map<String, Animation> uniforms;

        private Effect(String name, String source, double start, double end, boolean global,
                       Map<String, float[]> defaults, Map<String, Animation> uniforms) {
            this.name = name; this.source = source; this.start = start; this.end = end;
            this.global = global;
            this.defaults = Collections.unmodifiableMap(defaults);
            this.uniforms = Collections.unmodifiableMap(uniforms);
        }
        public boolean active(double seconds) { return seconds >= start && seconds < end; }
    }

    /** Keyframe pairs retain input order, steps in gaps and last-keyframe wins at shared times. */
    public static final class Animation {
        private final List<Keyframe> frames;
        public final int size;
        private Animation(List<Keyframe> frames, int size) { this.frames = frames; this.size = size; }

        public static Animation parse(Object value, Tempo tempo) throws JSONException, IOException {
            List<Keyframe> frames = new ArrayList<>();
            if (value == JSONObject.NULL) {
                frames.add(new Keyframe(0, new float[]{0}, 0, 0, 1));
            } else if (value instanceof JSONArray && ((JSONArray) value).length() > 0
                    && ((JSONArray) value).get(0) instanceof JSONObject) {
                JSONArray events = (JSONArray) value;
                for (int i = 0; i < events.length(); i++) {
                    JSONObject event = events.getJSONObject(i);
                    frames.add(new Keyframe(tempo.time(event.getJSONArray("startTime")),
                            vector(event.get("start")), Math.max(1, event.getInt("easingType")),
                            (float) event.optDouble("easingLeft", 0),
                            (float) event.optDouble("easingRight", 1)));
                    // prpr tween 0 is a step, not the RPE linear easing 1.
                    frames.add(new Keyframe(tempo.time(event.getJSONArray("endTime")),
                            vector(event.get("end")), 0, 0, 1));
                }
            } else {
                frames.add(new Keyframe(0, vector(value), 0, 0, 1));
            }
            int size = frames.get(0).value.length;
            double previous = Double.NEGATIVE_INFINITY;
            for (Keyframe frame : frames) {
                if (frame.value.length != size) throw new IOException("Uniform changes type");
                if (frame.time < previous) throw new IOException("Unordered uniform keyframes");
                previous = frame.time;
            }
            return new Animation(Collections.unmodifiableList(frames), size);
        }

        public float[] at(double seconds) {
            float[] result = new float[size];
            sample(seconds, result);
            return result;
        }

        public void sample(double seconds, float[] output) {
            int low = 0, high = frames.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (frames.get(middle).time <= seconds) low = middle + 1;
                else high = middle;
            }
            int index = Math.max(0, low - 1);
            Keyframe first = frames.get(index);
            if (index + 1 == frames.size()) {
                System.arraycopy(first.value, 0, output, 0, size);
                return;
            }
            Keyframe next = frames.get(index + 1);
            float t = (float) ((seconds - first.time) / (next.time - first.time));
            float factor = first.easing == 0 ? 0 : ShaderTween.apply(first.easing, t, first.left, first.right);
            for (int i = 0; i < size; i++) output[i] = first.value[i] + (next.value[i] - first.value[i]) * factor;
        }
    }

    private static float[] vector(Object value) throws JSONException, IOException {
        if (value instanceof Number) return new float[]{((Number) value).floatValue()};
        if (!(value instanceof JSONArray)) throw new IOException("Expected float, vec2 or RGBA color");
        JSONArray array = (JSONArray) value;
        if (array.length() != 2 && array.length() != 4) throw new IOException("Expected 2 or 4 components");
        float[] result = new float[array.length()];
        for (int i = 0; i < result.length; i++) {
            double component = array.getDouble(i);
            if (result.length == 4 && (component < 0 || component > 255 || component != Math.rint(component))) {
                throw new IOException("Color components must be integers in 0..255");
            }
            result[i] = (float) (result.length == 4 ? component / 255.0 : component);
        }
        return result;
    }

    private static final class Keyframe {
        final double time;
        final float[] value;
        final int easing;
        final float left, right;
        Keyframe(double time, float[] value, int easing, float left, float right) {
            this.time = time; this.value = value; this.easing = easing; this.left = left; this.right = right;
        }
    }

    public static final class Tempo {
        final double[] beats, times, bpms;
        public Tempo(Object value) throws JSONException, IOException {
            if (!(value instanceof Number) && !(value instanceof JSONArray)) throw new IOException("Invalid BPM map");
            int count = value instanceof Number ? 1 : ((JSONArray) value).length();
            if (count == 0) throw new IOException("Empty BPM map");
            beats = new double[count]; times = new double[count]; bpms = new double[count];
            for (int i = 0; i < count; i++) {
                if (value instanceof Number) bpms[i] = ((Number) value).doubleValue();
                else {
                    JSONObject item = ((JSONArray) value).getJSONObject(i);
                    beats[i] = beat(item.getJSONArray("time")); bpms[i] = item.getDouble("bpm");
                }
                if (!Double.isFinite(bpms[i]) || bpms[i] <= 0) throw new IOException("Invalid BPM");
                if (i > 0) {
                    if (beats[i] < beats[i - 1]) throw new IOException("Unordered BPM map");
                    times[i] = times[i - 1] + (beats[i] - beats[i - 1]) * 60 / bpms[i - 1];
                }
            }
        }
        public double time(JSONArray triple) throws JSONException, IOException {
            return seconds(beat(triple));
        }
        public double seconds(double beat) {
            int low = 0, high = beats.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (beats[middle] <= beat) low = middle + 1; else high = middle;
            }
            int i = Math.max(0, low - 1);
            return times[i] + (beat - beats[i]) * 60 / bpms[i];
        }
        public double beatAt(double seconds) {
            int low = 0, high = times.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (times[middle] <= seconds) low = middle + 1; else high = middle;
            }
            int i = Math.max(0, low - 1);
            return beats[i] + (seconds - times[i]) * bpms[i] / 60;
        }
        public static double beat(JSONArray triple) throws JSONException, IOException {
            if (triple.length() != 3 || triple.getDouble(2) == 0) throw new IOException("Invalid beat triple");
            double value = triple.getDouble(0) + triple.getDouble(1) / triple.getDouble(2);
            if (!Double.isFinite(value)) throw new IOException("Invalid beat");
            return value;
        }
    }
}
