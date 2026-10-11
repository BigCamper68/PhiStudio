package com.xpe.mobile.shadereditor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.ShaderTimeline;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Mutable extra.json editor. Unknown fields survive edits; compiled timelines are snapshots. */
public final class ShaderDocument {
    public static final String[] PRESETS = {"chromatic", "circleBlur", "fisheye", "glitch",
        "grayscale", "noise", "pixel", "radialBlur", "shockwave", "vignette"};
    private static final Pattern UNIFORM =
        Pattern.compile("uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(float|vec2|vec4)\\s+(\\w+)\\s*;"
            + "[ \\t]*(?://[ \\t]*%([^%]+)%)?");
    private static final int HISTORY_BYTES = 8 * 1024 * 1024;
    private JSONObject root;
    private ShaderTimeline.Tempo tempo;
    private String saved, gesture, serialized;
    private long revision;
    private int gestureSelection, historyBytes;
    public int selected = -1;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, List<Parameter>> parameters = new LinkedHashMap<>();
    private static final class Snapshot {
        final String text;
        final int selected;
        Snapshot(String t, int s) {
            text = t;
            selected = s;
        }
    }
    public static final class Parameter {
        public final String name;
        public final int size;
        public final Object initial;
        Parameter(String name, int size, Object initial) {
            this.name = name;
            this.size = size;
            this.initial = initial;
        }
    }
    public ShaderDocument(String text, ChartDocument chart, ShaderTimeline.SourceLoader loader)
        throws IOException {
        try {
            if (text == null) {
                root = new JSONObject();
                JSONArray bpm = new JSONArray();
                for (BpmChange change : chart.bpmChanges)
                    bpm.put(new JSONObject()
                            .put("time", change.startTime.toJson())
                            .put("bpm", change.bpm));
                if (bpm.length() == 0)
                    bpm.put(new JSONObject()
                            .put("time", new JSONArray().put(0).put(0).put(1))
                            .put("bpm", 120));
                root.put("bpm", bpm).put("effects", new JSONArray());
            } else
                root = new JSONObject(text.startsWith("\ufeff") ? text.substring(1) : text);
            if (!root.has("effects"))
                root.put("effects", new JSONArray());
            validate();
            saved = serialized = root.toString();
            for (String preset : PRESETS) sources.put(preset, loader.load(preset));
            for (int i = 0; i < count(); i++) {
                String name = effect(i).getString("shader");
                if (!sources.containsKey(name))
                    sources.put(name, loader.load(name));
            }
        } catch (Exception ex) {
            throw new IOException("extra.json: " + ex.getMessage(), ex);
        }
    }
    public int count() {
        return root.optJSONArray("effects").length();
    }
    public JSONObject effect(int index) {
        return root.optJSONArray("effects").optJSONObject(index);
    }
    public JSONObject selected() {
        return selected < 0 || selected >= count() ? null : effect(selected);
    }
    public String json() {
        if (serialized == null)
            serialized = root.toString();
        return serialized;
    }
    public long revision() {
        return revision;
    }
    public boolean dirty() {
        return !json().equals(saved);
    }
    public void markSaved(String snapshot) {
        saved = snapshot;
    }
    public ShaderTimeline.Tempo tempo() {
        return tempo;
    }
    public Map<String, String> sourceSnapshot() {
        return new LinkedHashMap<>(sources);
    }
    public ShaderTimeline compile(String snapshot) throws IOException {
        Map<String, String> copy = sourceSnapshot();
        return ShaderTimeline.parse(snapshot, name -> {
            String source = copy.get(name);
            if (source == null)
                throw new IOException("Shader unavailable: " + name);
            return source;
        });
    }
    public List<String> shaderNames() {
        return new ArrayList<>(sources.keySet());
    }
    public void registerSource(String name, String source) {
        sources.put(name, source);
        parameters.remove(name);
    }
    public double start(int index) {
        try {
            return tempo.time(effect(index).getJSONArray("start"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
    public double end(int index) {
        try {
            return tempo.time(effect(index).getJSONArray("end"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
    public double axis(double seconds, boolean beats) {
        return beats ? tempo.beatAt(seconds) : seconds;
    }
    public double seconds(double axis, boolean beats) {
        return beats ? tempo.seconds(axis) : axis;
    }
    public JSONArray timestamp(double seconds) {
        return BeatTime.fromDouble(tempo.beatAt(seconds), 1000000).toJson();
    }
    public void edit(Consumer<JSONObject> action) {
        Snapshot before = new Snapshot(json(), selected);
        try {
            action.accept(root);
            serialized = null;
            validate();
            if (!before.text.equals(json())) {
                revision++;
                if (gesture == null) {
                    push(before);
                    redo.clear();
                }
            }
        } catch (RuntimeException ex) {
            restore(before);
            throw ex;
        }
    }
    public void beginGesture() {
        if (gesture == null) {
            gesture = json();
            gestureSelection = selected;
        }
    }
    public void endGesture() {
        if (gesture != null) {
            if (!gesture.equals(json())) {
                push(new Snapshot(gesture, gestureSelection));
                redo.clear();
            }
            gesture = null;
        }
    }
    public void cancelGesture() {
        if (gesture != null) {
            restore(new Snapshot(gesture, gestureSelection));
            gesture = null;
        }
    }
    private void push(Snapshot snapshot) {
        undo.addLast(snapshot);
        historyBytes += snapshot.text.length() * 2;
        while (undo.size() > 80 || historyBytes > HISTORY_BYTES) {
            Snapshot removed = undo.removeFirst();
            historyBytes -= removed.text.length() * 2;
        }
    }
    public boolean canUndo() {
        return !undo.isEmpty();
    }
    public boolean canRedo() {
        return !redo.isEmpty();
    }
    public void undo() {
        cancelGesture();
        if (undo.isEmpty())
            return;
        Snapshot snapshot = undo.removeLast();
        historyBytes -= snapshot.text.length() * 2;
        redo.addLast(new Snapshot(json(), selected));
        restore(snapshot);
    }
    public void redo() {
        if (redo.isEmpty())
            return;
        Snapshot snapshot = redo.removeLast();
        push(new Snapshot(json(), selected));
        restore(snapshot);
    }
    private void restore(Snapshot snapshot) {
        try {
            root = new JSONObject(snapshot.text);
            serialized = snapshot.text;
            revision++;
            selected = snapshot.selected;
            validate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
    public void add(String shader, double seconds) {
        edit(raw -> {
            try {
                double beat = tempo.beatAt(Math.max(0, seconds));
                raw.getJSONArray("effects").put(new JSONObject()
                        .put("shader", shader)
                        .put("start", BeatTime.fromDouble(beat, 1000000).toJson())
                        .put("end", BeatTime.fromDouble(beat + 4, 1000000).toJson())
                        .put("global", false)
                        .put("vars", new JSONObject()));
                selected = count() - 1;
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    public void delete() {
        if (selected() == null)
            return;
        edit(raw -> {
            try {
                raw.getJSONArray("effects").remove(selected);
                selected = Math.min(selected, count() - 1);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    public void duplicate() {
        if (selected() == null)
            return;
        edit(raw -> {
            try {
                JSONArray old = raw.getJSONArray("effects"), next = new JSONArray();
                for (int i = 0; i < old.length(); i++) {
                    next.put(old.get(i));
                    if (i == selected)
                        next.put(new JSONObject(old.getJSONObject(i).toString()));
                }
                raw.put("effects", next);
                selected++;
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    public void reorder(int delta) {
        if (selected() == null)
            return;
        int target = selected + delta;
        while (target >= 0 && target < count()
            && effect(target).optBoolean("global") != selected().optBoolean("global"))
            target += delta;
        if (target < 0 || target >= count())
            return;
        final int destination = target;
        edit(raw -> {
            try {
                JSONArray list = raw.getJSONArray("effects");
                Object a = list.get(selected), b = list.get(destination);
                list.put(selected, b).put(destination, a);
                selected = destination;
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    public void range(int index, double start, double end) {
        edit(raw -> {
            try {
                effect(index).put("start", timestamp(start)).put("end", timestamp(end));
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    public void global(boolean global) {
        edit(raw -> {
            try {
                selected().put("global", global);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    private JSONObject vars(JSONObject effect) {
        JSONObject vars = effect.optJSONObject("vars");
        if (vars == null) {
            vars = new JSONObject();
            try {
                effect.put("vars", vars);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        }
        return vars;
    }
    public Object value(Parameter param) {
        Object value = selected().optJSONObject("vars") == null
            ? null
            : selected().optJSONObject("vars").opt(param.name);
        return value == null ? param.initial : value;
    }
    public void value(String name, Object value) {
        if (value != null)
            try {
                ShaderTimeline.Animation.parse(value, tempo);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex.getMessage(), ex);
            }
        edit(raw -> {
            try {
                if (value == null)
                    vars(selected()).remove(name);
                else
                    vars(selected()).put(name, value);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    public boolean animated(Object value) {
        return value instanceof JSONArray && ((JSONArray) value).length() > 0
            && ((JSONArray) value).opt(0) instanceof JSONObject;
    }
    public List<Parameter> parameters() {
        JSONObject effect = selected();
        if (effect == null)
            return new ArrayList<>();
        String shader = effect.optString("shader");
        List<Parameter> result = new ArrayList<>();
        if (!parameters.containsKey(shader)) {
            List<Parameter> fields = new ArrayList<>();
            Matcher m = UNIFORM.matcher(sources.getOrDefault(shader, ""));
            while (m.find()) {
                String name = m.group(2);
                if (name.equals("time") || name.equals("screenSize") || name.equals("UVScale"))
                    continue;
                int size = m.group(1).equals("float") ? 1 : m.group(1).equals("vec2") ? 2 : 4;
                double[] values = new double[size];
                String[] defaults = m.group(3) == null ? new String[0] : m.group(3).split(",");
                for (int i = 0; i < Math.min(size, defaults.length); i++) try {
                        values[i] = Double.parseDouble(defaults[i].trim());
                    } catch (NumberFormatException ignored) {
                    }
                fields.add(new Parameter(name, size, pack(values, size)));
            }
            parameters.put(shader, fields);
        }
        result.addAll(parameters.get(shader));
        JSONObject vars = effect.optJSONObject("vars");
        if (vars != null) {
            java.util.Iterator<String> keys = vars.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                if (result.stream().anyMatch(p -> p.name.equals(name)))
                    continue;
                Object value = vars.opt(name);
                if (animated(value))
                    value = ((JSONArray) value).optJSONObject(0).opt("start");
                int size = value instanceof JSONArray ? ((JSONArray) value).length() : 1;
                if (size == 1 || size == 2 || size == 4)
                    result.add(new Parameter(name, size, value));
            }
        }
        return result;
    }
    private static Object pack(double[] values, int size) {
        if (size == 1)
            return values[0];
        JSONArray result = new JSONArray();
        for (double value : values) {
            Object boxed = size == 4
                ? (Object) Long.valueOf(Math.max(0, Math.min(255, Math.round(value * 255))))
                : Double.valueOf(value);
            result.put(boxed);
        }
        return result;
    }
    public double[] components(Object value, int size) {
        double[] result = new double[size];
        if (size == 1)
            result[0] = value instanceof Number ? ((Number) value).doubleValue() : 0;
        else
            for (int i = 0; i < size; i++) result[i] = ((JSONArray) value).optDouble(i, 0);
        return result;
    }
    public Object valueFrom(double[] values, int size) {
        if (values.length != size)
            throw new IllegalArgumentException("Invalid value size");
        for (double number : values)
            if (!Double.isFinite(number) || !Float.isFinite((float) number))
                throw new IllegalArgumentException("Enter a finite float value");
        if (size == 1)
            return values[0];
        JSONArray result = new JSONArray();
        for (double value : values) {
            if (size == 4) {
                if (value < 0 || value > 255 || value != Math.rint(value))
                    throw new IllegalArgumentException("RGBA: целые значения 0–255");
                result.put((int) value);
            } else
                result.put(Double.valueOf(value));
        }
        return result;
    }
    public JSONArray animate(Parameter param) {
        Object old = value(param);
        if (animated(old))
            return (JSONArray) old;
        try {
            return new JSONArray().put(new JSONObject()
                    .put("startTime", selected().getJSONArray("start"))
                    .put("endTime", selected().getJSONArray("end"))
                    .put("start", old)
                    .put("end", old)
                    .put("easingType", 1)
                    .put("easingLeft", 0)
                    .put("easingRight", 1));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
    public Object bpm() {
        return root.opt("bpm");
    }
    public void bpm(Object value) {
        edit(raw -> {
            try {
                raw.put("bpm", value);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        });
    }
    private void validate() {
        try {
            tempo = new ShaderTimeline.Tempo(root.get("bpm"));
            JSONArray list = root.getJSONArray("effects");
            for (int i = 0; i < list.length(); i++) {
                JSONObject effect = list.getJSONObject(i);
                effect.getString("shader");
                double start = tempo.time(effect.getJSONArray("start")),
                       end = tempo.time(effect.getJSONArray("end"));
                if (!Double.isFinite(start) || !Double.isFinite(end) || end <= start)
                    throw new IllegalArgumentException("Конец эффекта должен быть позже начала");
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }
}
