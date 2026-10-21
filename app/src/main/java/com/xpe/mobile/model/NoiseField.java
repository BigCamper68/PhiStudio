package com.xpe.mobile.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native Phigros 4 blockAreaList. All times are seconds, independently of line BPM. */
public final class NoiseField {
    /** PhiStudio RGB extension; absent on native Phigros charts. */
    public static final String COLOR = "phiStudioColor";
    public static final int DEFAULT_COLOR = 0xffff5454;
    public final int color;
    public final float left, bottom, right, top;
    public final double appear, enable, disable, disappear;
    public final boolean subtract;
    public final List<Key> move, scale, rotate;
    private final JSONObject raw;

    public static final class Key {
        public final double time;
        public final float x, y, anchorX, anchorY;
        public final int easeX, easeY;
        Key(double time, float x, float y, float ax, float ay, int ex, int ey) {
            this.time = time; this.x = x; this.y = y;
            anchorX = ax; anchorY = ay; easeX = ex; easeY = ey;
        }
    }

    private NoiseField(JSONObject value) throws JSONException {
        raw = value;
        JSONObject bl = value.getJSONObject("bottomLeftPercentage"), tr = value.getJSONObject("topRightPercentage");
        left = number(bl, "x"); bottom = number(bl, "y");
        right = number(tr, "x"); top = number(tr, "y");
        appear = seconds(value, "appearTime"); enable = seconds(value, "enableTime");
        disable = seconds(value, "disableTime"); disappear = seconds(value, "disappearTime");
        if (appear > disappear || enable > disable) throw new JSONException("Invalid noise field lifetime");
        subtract = value.optBoolean("isSubtract", false);
        color = readColor(value.optJSONArray(COLOR));
        move = keys(value, "moveEvents", "endPosition", false);
        scale = keys(value, "scaleEvents", "scale", true);
        rotate = keys(value, "rotateEvents", null, true);
    }

    public JSONObject toJson() { return raw; }

    /** Readers use binary search on these arrays. Keep editor indices intact while saving. */
    public JSONObject toExportJson() throws JSONException {
        JSONObject result = RawJson.shallowCopy(raw);
        for (String channel : new String[]{"moveEvents", "scaleEvents", "rotateEvents"}) {
            JSONArray array = raw.optJSONArray(channel);
            if (array == null) continue;
            List<JSONObject> events = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) events.add(array.getJSONObject(i));
            // List.sort is stable: coincident keys retain their authored precedence.
            events.sort(Comparator.comparingDouble(event -> event.optDouble("time")));
            JSONArray sorted = new JSONArray();
            for (JSONObject event : events) sorted.put(event);
            result.put(channel, sorted);
        }
        return result;
    }

    private static int readColor(JSONArray rgb) {
        if (rgb == null || rgb.length() != 3) return DEFAULT_COLOR;
        int color = 0xff000000;
        for (int i = 0; i < 3; i++) {
            double channel = rgb.optDouble(i, Double.NaN);
            if (!Double.isFinite(channel) || channel < 0 || channel > 255 || channel != Math.rint(channel))
                return DEFAULT_COLOR;
            color |= (int) channel << (16 - i * 8);
        }
        return color;
    }

    /** Parse a new immutable snapshot. The caller must not mutate its JSON afterwards. */
    public static NoiseField fromJson(JSONObject value) throws JSONException {
        return new NoiseField(value);
    }

    public static List<NoiseField> fromJson(JSONArray array) throws JSONException {
        List<NoiseField> result = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            try { result.add(new NoiseField(array.getJSONObject(i))); }
            catch (JSONException ex) { throw new JSONException("blockAreaList[" + i + "]: " + ex.getMessage()); }
        }
        return result;
    }

    private static List<Key> keys(JSONObject value, String channel, String vector, boolean anchored) throws JSONException {
        List<Key> keys = new ArrayList<>();
        JSONArray array = value.optJSONArray(channel);
        if (value.has(channel) && array == null) throw new JSONException(channel + " must be an array");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject event = array.getJSONObject(i);
            JSONObject anchor = anchored ? event.getJSONObject("anchor") : null;
            JSONObject target = vector == null ? null : event.getJSONObject(vector);
            int ex = event.optInt(vector == null ? "easeType" : "easeTypeX", 0);
            int ey = vector == null ? ex : event.optInt("easeTypeY", 0);
            if (ex < 0 || ex > 14 || ey < 0 || ey > 14) throw new JSONException(channel + " easing must be 0..14");
            keys.add(new Key(seconds(event, "time"),
                    vector == null ? number(event, "rotation") : number(target, "x"),
                    vector == null ? 0 : number(target, "y"),
                    anchored ? number(anchor, "x") : .5f, anchored ? number(anchor, "y") : .5f, ex, ey));
        }
        keys.sort(Comparator.comparingDouble(key -> key.time));
        return Collections.unmodifiableList(keys);
    }

    private static float number(JSONObject object, String key) throws JSONException {
        float value = (float) object.getDouble(key);
        if (!Float.isFinite(value)) throw new JSONException(key + " must be finite");
        return value;
    }
    private static double seconds(JSONObject object, String key) throws JSONException {
        double value = object.getDouble(key);
        if (!Double.isFinite(value)) throw new JSONException(key + " must be finite");
        return value;
    }
}
