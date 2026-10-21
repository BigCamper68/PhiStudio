package com.xpe.mobile.noiseeditor;

import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.preview.NoiseFieldEvaluator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Copy-on-edit operations: unknown field, vector and key properties survive authoring. */
public final class NoiseFieldEdits {
    public enum Channel {
        MOVE("moveEvents", "endPosition"), SCALE("scaleEvents", "scale"), ROTATE("rotateEvents", null);
        public final String json, vector;
        Channel(String json, String vector) { this.json = json; this.vector = vector; }
        public boolean anchored() { return this != MOVE; }
    }
    public static final String[] PHASES = {"appearTime", "enableTime", "disableTime", "disappearTime"};
    public static final String LABEL = "phiStudioName";
    private NoiseFieldEdits() { }

    public static JSONObject copy(JSONObject value) throws JSONException {
        return new JSONObject(value.toString());
    }

    public static NoiseField create(double seconds, double duration, boolean subtract) throws JSONException {
        double end = seconds + Math.max(.01, duration);
        JSONObject raw = new JSONObject().put("bottomLeftPercentage", vector(.25, .25))
                .put("topRightPercentage", vector(.75, .75)).put("isSubtract", subtract)
                .put(PHASES[0], seconds).put(PHASES[1], seconds)
                .put(PHASES[2], end).put(PHASES[3], end);
        for (Channel channel : Channel.values()) raw.put(channel.json, new JSONArray());
        return NoiseField.fromJson(raw);
    }

    public static JSONObject vector(double x, double y) throws JSONException {
        return new JSONObject().put("x", x).put("y", y);
    }

    public static int keyCount(NoiseField field, Channel channel) {
        JSONArray keys = field.toJson().optJSONArray(channel.json);
        return keys == null ? 0 : keys.length();
    }

    /** Raw indices remain stable even for imported unsorted/duplicate timestamps. */
    public static List<Integer> sortedKeys(NoiseField field, Channel channel) {
        List<Integer> result = new ArrayList<>();
        JSONArray keys = field.toJson().optJSONArray(channel.json);
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) result.add(i);
            result.sort(Comparator.comparingDouble(i -> keys.optJSONObject(i).optDouble("time")));
        }
        return result;
    }

    public static JSONObject key(NoiseField field, Channel channel, int index) throws JSONException {
        return field.toJson().getJSONArray(channel.json).getJSONObject(index);
    }

    public static NoiseField withKey(NoiseField field, Channel channel, int index, JSONObject value)
            throws JSONException {
        JSONObject raw = copy(field.toJson());
        JSONArray keys = raw.optJSONArray(channel.json);
        if (keys == null) { keys = new JSONArray(); raw.put(channel.json, keys); }
        if (index < 0) keys.put(copy(value));
        else {
            if (index >= keys.length()) throw new JSONException("Noise field key no longer exists");
            keys.put(index, copy(value));
        }
        return NoiseField.fromJson(raw);
    }

    public static NoiseField withoutKey(NoiseField field, Channel channel, int index) throws JSONException {
        JSONObject raw = copy(field.toJson());
        JSONArray keys = raw.getJSONArray(channel.json);
        if (index < 0 || index >= keys.length()) throw new JSONException("Noise field key no longer exists");
        keys.remove(index);
        return NoiseField.fromJson(raw);
    }

    public static NoiseField bounds(NoiseField field, double left, double bottom, double right, double top)
            throws JSONException {
        JSONObject raw = copy(field.toJson());
        raw.getJSONObject("bottomLeftPercentage").put("x", left).put("y", bottom);
        raw.getJSONObject("topRightPercentage").put("x", right).put("y", top);
        return NoiseField.fromJson(raw);
    }

    public static NoiseField phase(NoiseField field, int phase, double seconds) throws JSONException {
        return NoiseField.fromJson(copy(field.toJson()).put(PHASES[phase], seconds));
    }

    /** Moving/copying a whole field moves all keys by the same number of native seconds. */
    public static NoiseField shifted(NoiseField field, double delta) throws JSONException {
        JSONObject raw = copy(field.toJson());
        for (String phase : PHASES) raw.put(phase, raw.getDouble(phase) + delta);
        for (Channel channel : Channel.values()) {
            JSONArray keys = raw.optJSONArray(channel.json);
            if (keys == null) continue;
            for (int i = 0; i < keys.length(); i++) {
                JSONObject key = keys.getJSONObject(i);
                key.put("time", key.getDouble("time") + delta);
            }
        }
        return NoiseField.fromJson(raw);
    }

    /** Values at the playhead use native outgoing easing, including Hold and Jump. */
    public static JSONObject sampledKey(NoiseField field, Channel channel, double seconds) throws JSONException {
        List<NoiseField.Key> keys = channel == Channel.MOVE ? field.move
                : channel == Channel.SCALE ? field.scale : field.rotate;
        float time = (float) seconds;
        int current = -1;
        for (int i = 0; i < keys.size(); i++) if ((float) keys.get(i).time <= time) current = i; else break;
        float x = channel == Channel.MOVE ? (field.left + field.right) / 2
                : channel == Channel.SCALE ? 1 : 0;
        float y = channel == Channel.MOVE ? (field.bottom + field.top) / 2 : 1;
        float ax = (field.left + field.right) / 2, ay = (field.bottom + field.top) / 2;
        int ex = 0, ey = 0;
        if (current >= 0) {
            NoiseField.Key a = keys.get(current);
            x = a.x; y = a.y; ax = a.anchorX; ay = a.anchorY; ex = a.easeX; ey = a.easeY;
            if (current + 1 < keys.size()) {
                NoiseField.Key b = keys.get(current + 1);
                float progress = (time - (float) a.time) / ((float) b.time - (float) a.time);
                x += (b.x - x) * NoiseFieldEvaluator.ease(ex, progress);
                y += (b.y - y) * NoiseFieldEvaluator.ease(ey, progress);
            }
        }
        JSONObject result = new JSONObject().put("time", seconds);
        if (channel.vector == null) result.put("rotation", x).put("easeType", ex);
        else result.put(channel.vector, vector(x, y)).put("easeTypeX", ex).put("easeTypeY", ey);
        if (channel.anchored()) result.put("anchor", vector(ax, ay));
        return result;
    }
}
