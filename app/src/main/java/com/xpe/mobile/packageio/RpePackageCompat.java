package com.xpe.mobile.packageio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Adds the stable RPE fields used by Re:PhiEdit package exports without changing editor JSON. */
final class RpePackageCompat {
    private static final int EVENT_LAYER_SLOTS = 5;
    private static final double CONTROL_SENTINEL_X = 9_999_999.0;

    private RpePackageCompat() {
    }

    static String normalize(String source) throws JSONException {
        JSONObject root = new JSONObject(source == null ? "{}" : source);
        JSONArray lines = root.optJSONArray("judgeLineList");
        if (lines == null) return root.toString();

        for (int index = 0; index < lines.length(); index++) {
            JSONObject line = lines.optJSONObject(index);
            if (line == null) continue;
            normalizeLine(line);
        }
        return root.toString();
    }

    private static void normalizeLine(JSONObject line) throws JSONException {
        if (!line.has("rotateWithFather")) line.put("rotateWithFather", false);
        if (!line.has("anchor")) line.put("anchor", new JSONArray().put(0.5).put(0.5));
        if (!line.has("isGif")) line.put("isGif", false);

        ensureIdentityControl(line, "alphaControl", "alpha", 1.0);
        ensureIdentityControl(line, "posControl", "pos", 1.0);
        ensureIdentityControl(line, "sizeControl", "size", 1.0);
        ensureIdentityControl(line, "yControl", "y", 1.0);
        ensureIdentityControl(line, "skewControl", "skew", 0.0);

        JSONArray sourceLayers = line.optJSONArray("eventLayers");
        JSONArray normalizedLayers = new JSONArray();
        int sourceCount = sourceLayers == null ? 0 : sourceLayers.length();
        int slots = Math.max(EVENT_LAYER_SLOTS, sourceCount);
        for (int index = 0; index < slots; index++) {
            Object value = index < sourceCount ? sourceLayers.opt(index) : null;
            if (value == null || value == JSONObject.NULL) {
                normalizedLayers.put(JSONObject.NULL);
            } else if (value instanceof JSONObject && ((JSONObject) value).length() == 0) {
                normalizedLayers.put(JSONObject.NULL);
            } else {
                normalizedLayers.put(value);
            }
        }
        line.put("eventLayers", normalizedLayers);
    }

    private static void ensureIdentityControl(JSONObject line, String arrayKey,
                                              String valueKey, double value)
            throws JSONException {
        if (line.has(arrayKey)) return;
        JSONArray controls = new JSONArray();
        controls.put(controlPoint(valueKey, value, 0.0));
        controls.put(controlPoint(valueKey, value, CONTROL_SENTINEL_X));
        line.put(arrayKey, controls);
    }

    private static JSONObject controlPoint(String valueKey, double value, double x)
            throws JSONException {
        return new JSONObject().put("easing", 1).put(valueKey, value).put("x", x);
    }
}
