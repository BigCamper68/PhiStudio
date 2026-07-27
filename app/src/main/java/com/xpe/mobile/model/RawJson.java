package com.xpe.mobile.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;

final class RawJson {
    private RawJson() {
    }

    static JSONObject shallowCopy(JSONObject source) throws JSONException {
        JSONObject copy = new JSONObject();
        if (source == null) return copy;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            copy.put(key, source.get(key));
        }
        return copy;
    }

    /** Keeps only fields which are not represented by the typed model. */
    static JSONObject unknownFields(JSONObject source, String... knownKeys)
            throws JSONException {
        if (source == null) return null;
        Set<String> known = new HashSet<>();
        if (knownKeys != null) {
            for (String key : knownKeys) known.add(key);
        }
        JSONObject copy = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!known.contains(key)) copy.put(key, source.get(key));
        }
        return copy.length() == 0 ? null : copy;
    }
}
